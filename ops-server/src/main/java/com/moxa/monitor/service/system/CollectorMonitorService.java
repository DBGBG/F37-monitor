package com.moxa.monitor.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.config.AppProperties;
import com.moxa.monitor.domain.CollectorCommand;
import com.moxa.monitor.domain.CollectorHeartbeat;
import com.moxa.monitor.domain.CollectorJob;
import com.moxa.monitor.repo.CollectorCommandRepository;
import com.moxa.monitor.repo.CollectorHeartbeatRepository;
import com.moxa.monitor.repo.CollectorJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集层监控与跨层指令服务.
 * Python 不暴露 HTTP, 业务层通过 collector_commands 表下发 rediscover / reload_config / collect_now,
 * 采集层每 10 秒拉取执行并回写状态; 采集层的存活状态由 collector_heartbeats 判定.
 */
@Service
public class CollectorMonitorService {

    private static final Logger log = LoggerFactory.getLogger(CollectorMonitorService.class);

    public static final String CMD_REDISCOVER = "rediscover";
    public static final String CMD_RELOAD_CONFIG = "reload_config";
    public static final String CMD_COLLECT_NOW = "collect_now";

    private final CollectorHeartbeatRepository heartbeatRepository;
    private final CollectorCommandRepository commandRepository;
    private final CollectorJobRepository jobRepository;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public CollectorMonitorService(CollectorHeartbeatRepository heartbeatRepository,
                                   CollectorCommandRepository commandRepository,
                                   CollectorJobRepository jobRepository,
                                   AppProperties props, ObjectMapper objectMapper) {
        this.heartbeatRepository = heartbeatRepository;
        this.commandRepository = commandRepository;
        this.jobRepository = jobRepository;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ---------------- 心跳 ----------------

    /** 采集层是否活着: 最近一条心跳在阈值秒内 */
    public boolean isAlive() {
        return heartbeatRepository.findFirstByOrderByLastBeatAtDesc()
                .map(beat -> beat.getDurationMs() >= 0 && !isStale(beat.getLastBeatAt()))
                .orElse(false);
    }

    public LocalDateTime lastBeatAt() {
        return heartbeatRepository.findFirstByOrderByLastBeatAtDesc()
                .map(CollectorHeartbeat::getLastBeatAt).orElse(null);
    }

    private boolean isStale(LocalDateTime beatAt) {
        if (beatAt == null) {
            return true;
        }
        long seconds = Duration.between(beatAt, LocalDateTime.now()).getSeconds();
        return seconds > props.getAlert().getCollectorDeadSeconds();
    }

    /** GET /api/system/collector/heartbeat: 总体存活 + 各阶段最新一轮统计 */
    public Map<String, Object> heartbeatReport() {
        CollectorHeartbeat latest = heartbeatRepository.findFirstByOrderByLastBeatAtDesc().orElse(null);
        List<Map<String, Object>> phases = new ArrayList<>();
        for (CollectorHeartbeat beat : heartbeatRepository.findLatestOfEachPhase()) {
            phases.add(Maps.of(
                    "component", beat.getComponent(),
                    "phase", beat.getPhase(),
                    "total", beat.getTotal(),
                    "success", beat.getSuccess(),
                    "errors", beat.getErrors(),
                    "duration_ms", beat.getDurationMs(),
                    "last_beat_at", DateTimeSerializers.fmt(beat.getLastBeatAt()),
                    "detail", beat.getDetail()));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("alive", latest != null && !isStale(latest.getLastBeatAt()));
        report.put("collector_dead_seconds", props.getAlert().getCollectorDeadSeconds());
        report.put("last_beat_at", latest == null ? null : DateTimeSerializers.fmt(latest.getLastBeatAt()));
        report.put("last_phase", latest == null ? null : latest.getPhase());
        report.put("last_duration_ms", latest == null ? null : latest.getDurationMs());
        report.put("phases", phases);
        return report;
    }

    /** 最近 N 条心跳原始记录, 供采集层历史曲线 */
    public List<Map<String, Object>> recentBeats() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollectorHeartbeat beat : heartbeatRepository.findTop50ByOrderByLastBeatAtDesc()) {
            rows.add(Maps.of(
                    "id", beat.getId(),
                    "component", beat.getComponent(),
                    "phase", beat.getPhase(),
                    "total", beat.getTotal(),
                    "success", beat.getSuccess(),
                    "errors", beat.getErrors(),
                    "duration_ms", beat.getDurationMs(),
                    "last_beat_at", DateTimeSerializers.fmt(beat.getLastBeatAt())));
        }
        return rows;
    }

    // ---------------- 采集任务 ----------------

    public List<Map<String, Object>> jobs() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollectorJob job : jobRepository.findAllByOrderByIdAsc()) {
            rows.add(Maps.of(
                    "job_id", job.getJobId(),
                    "name", job.getName(),
                    "interval_seconds", job.getIntervalSeconds(),
                    "timeout_seconds", job.getTimeoutSeconds(),
                    "max_instances", job.getMaxInstances(),
                    "enabled", job.getEnabled(),
                    "updated_at", DateTimeSerializers.fmt(job.getUpdatedAt())));
        }
        return rows;
    }

    /** PUT /api/collector-jobs/{jobId}: 改完后采集层最多 5 分钟内热加载 */
    @Transactional
    public Map<String, Object> updateJob(String jobId, Integer intervalSeconds, Integer timeoutSeconds,
                                         Integer maxInstances, Boolean enabled) {
        CollectorJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            throw com.moxa.monitor.common.BizException.notFound("采集任务不存在: " + jobId);
        }
        int interval = intervalSeconds == null ? job.getIntervalSeconds() : intervalSeconds;
        int timeout = timeoutSeconds == null ? job.getTimeoutSeconds() : timeoutSeconds;
        int instances = maxInstances == null ? job.getMaxInstances() : maxInstances;
        boolean on = enabled == null ? Boolean.TRUE.equals(job.getEnabled()) : enabled;
        if (interval < 10 || interval > 86_400) {
            throw com.moxa.monitor.common.BizException.badRequest("采集周期需在 10~86400 秒之间");
        }
        if (timeout < 5 || timeout > 3_600) {
            throw com.moxa.monitor.common.BizException.badRequest("单轮超时需在 5~3600 秒之间");
        }
        jobRepository.updateSchedule(jobId, interval, timeout, Math.max(1, instances), on);
        submit(CMD_RELOAD_CONFIG, Map.of(), "配置已更新, 通知采集层重载");
        return Maps.of("job_id", jobId, "interval_seconds", interval, "timeout_seconds", timeout,
                "max_instances", Math.max(1, instances), "enabled", on);
    }

    // ---------------- 指令 ----------------

    /** 写入一条待执行指令 (Python 每 10 秒拉取) */
    @Transactional
    public Integer submit(String cmd, Map<String, Object> payload, String createdBy) {
        CollectorCommand command = new CollectorCommand();
        command.setCmd(cmd);
        command.setPayload(payload == null ? Map.of() : payload);
        command.setStatus("pending");
        command.setResult("");
        command.setCreatedBy(createdBy == null || createdBy.isBlank() ? "system" : createdBy);
        commandRepository.save(command);
        log.info("下发采集指令 cmd={} payload={} by={}", cmd, payload, createdBy);
        return command.getId();
    }

    public List<Map<String, Object>> commands() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CollectorCommand command : commandRepository.findTop50ByOrderByIdDesc()) {
            rows.add(Maps.of(
                    "id", command.getId(),
                    "cmd", command.getCmd(),
                    "payload", command.getPayload(),
                    "status", command.getStatus(),
                    "result", command.getResult(),
                    "created_by", command.getCreatedBy(),
                    "created_at", DateTimeSerializers.fmt(command.getCreatedAt()),
                    "executed_at", DateTimeSerializers.fmt(command.getExecutedAt())));
        }
        return rows;
    }

    /** 指令是否仍在排队/执行, 前端据此显示"下发中" */
    public boolean hasPending() {
        return commandRepository.countByStatusIn(List.of("pending", "running")) > 0;
    }

    /** 把指令 payload 序列化为 JSON 文本 (原生 SQL 写 JSON 列用) */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw com.moxa.monitor.common.BizException.badRequest("配置内容无法序列化: " + ex.getMessage());
        }
    }
}
