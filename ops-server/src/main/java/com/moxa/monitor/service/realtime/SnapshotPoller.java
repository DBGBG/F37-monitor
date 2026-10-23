package com.moxa.monitor.service.realtime;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.config.AppProperties;
import com.moxa.monitor.domain.CollectorHeartbeat;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.CollectorHeartbeatRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.alert.AlertEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实时快照轮询器.
 * 迁移前采集任务与 SSE 处于同一进程, 采集完直接写内存态; 改造后采集层是独立 Python 进程,
 * 业务层唯一的感知途径是读库, 因此这里每 2 秒把库里的最新态刷进 LiveStateHolder.
 *
 * 差量推送策略 (单条聚合查询 * 3, 2 秒一次的开销可接受):
 * - 设备在线态翻转 -> device 事件 (只带翻转的那台)
 * - 活跃/严重告警数变化 -> overview 事件
 * - 采集心跳 id 前进或存活状态变化 -> cycle 事件
 * - 每 8 个 tick (约 16 秒) 发一次 SSE 注释保活, 与迁移前的 15 秒一致
 */
@Service
public class SnapshotPoller {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPoller.class);

    /** 每 N 轮 ping 一次 (2s * 8 = 16s) */
    private static final int PING_TICKS = 8;

    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final AlertRecordRepository alertRepository;
    private final CollectorHeartbeatRepository heartbeatRepository;
    private final AppProperties props;
    private final LiveStateHolder live;
    private final StreamService stream;
    /** 告警引擎反过来依赖本包的 StreamService/LiveStateHolder, 用 ObjectProvider 打断构造期循环 */
    private final ObjectProvider<AlertEngineService> alertEngine;

    /** 上一轮的在线态, 用于识别翻转 */
    private final Map<String, Boolean> previousOnline = new LinkedHashMap<>();
    private int tick;
    private Integer lastHeartbeatId;
    private volatile boolean lastCollectorAlive;

    public SnapshotPoller(SwitchDeviceRepository switchRepository,
                          ServerDeviceRepository serverRepository,
                          AlertRecordRepository alertRepository,
                          CollectorHeartbeatRepository heartbeatRepository,
                          AppProperties props,
                          LiveStateHolder live,
                          StreamService stream,
                          ObjectProvider<AlertEngineService> alertEngine) {
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.alertRepository = alertRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.props = props;
        this.live = live;
        this.stream = stream;
        this.alertEngine = alertEngine;
    }

    /** 首轮延后几秒, 避开 Flyway 与用户初始化; fixedDelay 保证不会并发重入 */
    @Scheduled(fixedDelay = 2_000L, initialDelay = 6_000L)
    public void poll() {
        try {
            tick++;
            refreshDevices();
            refreshAlerts();
            refreshCycle();
            if (tick % PING_TICKS == 0) {
                stream.ping();
            }
        } catch (Exception ex) {
            // 轮询失败绝不能终止调度线程
            log.error("实时快照轮询失败: {}", ex.getMessage(), ex);
        }
    }

    // ---------------- 设备 ----------------

    private void refreshDevices() {
        Map<String, Map<String, Object>> switches = new LinkedHashMap<>();
        for (Object[] row : switchRepository.findLedgerSummary()) {
            String id = String.valueOf(row[0]);
            switches.put(id, Maps.of(
                    "id", id,
                    "type", "switch",
                    "name", row[1],
                    "ip", row[2],
                    "is_online", Boolean.TRUE.equals(row[3]),
                    "last_poll_time", row[4] == null ? null : DateTimeSerializers.fmt((LocalDateTime) row[4])));
        }

        Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
        for (Object[] row : serverRepository.findLedgerSummary()) {
            String id = String.valueOf(row[0]);
            servers.put(id, Maps.of(
                    "id", id,
                    "type", "server",
                    "name", row[1],
                    "ip", row[2],
                    "os_type", row[3],
                    "is_online", Boolean.TRUE.equals(row[4]),
                    "last_poll_time", row[5] == null ? null : DateTimeSerializers.fmt((LocalDateTime) row[5])));
        }

        live.replaceSwitches(switches);
        live.replaceServers(servers);

        Set<String> seen = new HashSet<>();
        switches.forEach((id, fields) -> publishIfFlipped(id, fields, seen));
        servers.forEach((id, fields) -> publishIfFlipped(id, fields, seen));
        previousOnline.keySet().retainAll(seen);
    }

    private void publishIfFlipped(String deviceId, Map<String, Object> fields, Set<String> seen) {
        boolean online = Boolean.TRUE.equals(fields.get("is_online"));
        seen.add(deviceId);
        Boolean before = previousOnline.put(deviceId, online);
        if (before != null && before != online) {
            stream.publish("device", Maps.of(
                    "id", deviceId,
                    "type", fields.get("type"),
                    "name", fields.get("name"),
                    "ip", fields.get("ip"),
                    "is_online", online,
                    "changed_at", DateTimeSerializers.fmt(LocalDateTime.now())));
            log.info("设备在线态翻转: {} {} -> {}", deviceId, fields.get("name"), online ? "在线" : "离线");
        }
    }

    // ---------------- 告警 ----------------

    private void refreshAlerts() {
        int active = (int) alertRepository.countByStatus("active");
        int critical = 0;
        for (Object[] row : alertRepository.countActiveBySeverity()) {
            if ("CRITICAL".equals(String.valueOf(row[0]))) {
                critical = RoundUtil.safeInt(row[1]);
            }
        }
        Map<String, Object> before = live.alerts();
        if (RoundUtil.safeInt(before.get("active")) != active
                || RoundUtil.safeInt(before.get("critical")) != critical) {
            live.setAlertCounts(active, critical);
            stream.publish("overview", live.overview());
        }
    }

    // ---------------- 采集周期 ----------------

    private void refreshCycle() {
        CollectorHeartbeat beat = heartbeatRepository.findFirstByOrderByLastBeatAtDesc().orElse(null);
        if (beat == null) {
            return;
        }
        boolean alive = !isStale(beat.getLastBeatAt());
        if (Objects.equals(beat.getId(), lastHeartbeatId) && alive == lastCollectorAlive) {
            return;
        }
        lastHeartbeatId = beat.getId();
        lastCollectorAlive = alive;
        if (alive) {
            // 迁移前采集完成后会在同进程直接跑一次告警检查, 改造后改为心跳前进时异步唤起
            alertEngine.ifAvailable(AlertEngineService::triggerCheck);
        }
        live.markCycle(beat.getPhase(), Maps.of(
                "component", beat.getComponent(),
                "total", beat.getTotal(),
                "success", beat.getSuccess(),
                "errors", beat.getErrors(),
                "duration_ms", beat.getDurationMs(),
                "beat_at", DateTimeSerializers.fmt(beat.getLastBeatAt())));
        if (stream.subscribers() > 0) {
            stream.publish("cycle", live.lastCycle());
        }
    }

    private boolean isStale(LocalDateTime beatAt) {
        return beatAt == null
                || Duration.between(beatAt, LocalDateTime.now()).getSeconds()
                > props.getAlert().getCollectorDeadSeconds();
    }

    /** 采集层是否存活, 供 /api/realtime/status 与前端"实时"标签 */
    public boolean collectorAlive() {
        return lastCollectorAlive;
    }
}
