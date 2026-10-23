package com.moxa.monitor.service.system;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.AnomalyEventRepository;
import com.moxa.monitor.repo.CollectorHeartbeatRepository;
import com.moxa.monitor.repo.ConfigAuditLogRepository;
import com.moxa.monitor.repo.HardwareHealthRepository;
import com.moxa.monitor.repo.InspectionTaskRepository;
import com.moxa.monitor.repo.PortTrafficStatRepository;
import com.moxa.monitor.repo.ProcessStatusRepository;
import com.moxa.monitor.repo.ServerMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 数据保留策略 (迁移自 legacy maintenance/cleanup.py).
 *
 * MySQL 上的两点差异:
 * 1. 没有 WAL / vacuum 可回收, 清理后改为 ANALYZE TABLE 更新统计信息;
 * 2. 单次 DELETE 百万行会长时间持锁, 因此一律 LIMIT 分批提交, 每批一个独立事务
 *    (用 TransactionTemplate 而不是 @Transactional, 避免同类内部调用绕过代理).
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    /** 每批删除行数 */
    private static final int BATCH_SIZE = 5_000;

    /** 单表单轮最多批次数, 防止极端数据量把一次清理拖成几小时 */
    private static final int MAX_BATCHES = 200;

    private final PortTrafficStatRepository trafficRepository;
    private final HardwareHealthRepository hardwareRepository;
    private final ServerMetricRepository metricRepository;
    private final ProcessStatusRepository processRepository;
    private final AlertRecordRepository alertRepository;
    private final AnomalyEventRepository anomalyRepository;
    private final ConfigAuditLogRepository auditRepository;
    private final InspectionTaskRepository taskRepository;
    private final CollectorHeartbeatRepository heartbeatRepository;
    private final SysConfigService configService;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    public RetentionCleanupService(PortTrafficStatRepository trafficRepository,
                                   HardwareHealthRepository hardwareRepository,
                                   ServerMetricRepository metricRepository,
                                   ProcessStatusRepository processRepository,
                                   AlertRecordRepository alertRepository,
                                   AnomalyEventRepository anomalyRepository,
                                   ConfigAuditLogRepository auditRepository,
                                   InspectionTaskRepository taskRepository,
                                   CollectorHeartbeatRepository heartbeatRepository,
                                   SysConfigService configService,
                                   TransactionTemplate txTemplate,
                                   JdbcTemplate jdbc) {
        this.trafficRepository = trafficRepository;
        this.hardwareRepository = hardwareRepository;
        this.metricRepository = metricRepository;
        this.processRepository = processRepository;
        this.alertRepository = alertRepository;
        this.anomalyRepository = anomalyRepository;
        this.auditRepository = auditRepository;
        this.taskRepository = taskRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.configService = configService;
        this.txTemplate = txTemplate;
        this.jdbc = jdbc;
    }

    /** 每天 03:30 执行一轮 (迁移前是 APScheduler 的 data_cleanup 任务) */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledCleanup() {
        try {
            Map<String, Object> summary = runCleanup(null, null);
            log.info("定时数据清理完成: {}", summary);
        } catch (Exception ex) {
            log.error("定时数据清理失败: {}", ex.getMessage(), ex);
        }
    }

    /**
     * 一轮完整清理, 供定时任务与 POST /api/system/maintenance/cleanup 共用.
     * 入参为 null 时取 sys_config.retention_policy 的配置.
     */
    public Map<String, Object> runCleanup(Integer metricsDaysOverride, Integer alertsDaysOverride) {
        int metricsDays = positive(metricsDaysOverride,
                configService.integer(SysConfigService.RETENTION_POLICY + ".metrics_days", 30));
        int alertsDays = positive(alertsDaysOverride,
                configService.integer(SysConfigService.RETENTION_POLICY + ".alerts_days", 90));
        int anomaliesDays = configService.integer(SysConfigService.RETENTION_POLICY + ".anomalies_days", 180);
        int auditDays = configService.integer(SysConfigService.RETENTION_POLICY + ".audit_days", 365);
        int inspectionDays = configService.integer(SysConfigService.RETENTION_POLICY + ".inspection_days", 0);

        Map<String, Object> deletedMetrics = new LinkedHashMap<>();
        deletedMetrics.put("port_traffic_stats",
                deleteInBatches(trafficRepository::deleteOlderThan, daysAgo(metricsDays)));
        deletedMetrics.put("server_metrics",
                deleteInBatches(metricRepository::deleteOlderThan, daysAgo(metricsDays)));
        deletedMetrics.put("hardware_health",
                deleteInBatches(hardwareRepository::deleteOlderThan, daysAgo(metricsDays)));
        deletedMetrics.put("process_status",
                deleteInBatches(processRepository::deleteOlderThan, daysAgo(metricsDays)));
        // 心跳表迁移前不存在, 保留 7 天足够排查采集层问题
        deletedMetrics.put("collector_heartbeats",
                deleteInBatches(heartbeatRepository::deleteOlderThan, daysAgo(7)));

        long deletedAlerts = deleteInBatches(alertRepository::deleteResolvedOlderThan, daysAgo(alertsDays));
        // days<=0 表示永久保留 (迁移前同样是这个语义)
        long deletedAnomalies = anomaliesDays <= 0 ? 0
                : deleteInBatches(anomalyRepository::deleteResolvedOlderThan, daysAgo(anomaliesDays));
        long deletedAudit = deleteInBatches(auditRepository::deleteOlderThan, daysAgo(auditDays));
        // inspection_tasks -> inspection_records 的外键是 ON DELETE CASCADE, 只需删任务
        long deletedInspection = inspectionDays <= 0 ? 0
                : deleteInBatches(taskRepository::deleteFinishedOlderThan, daysAgo(inspectionDays));

        analyzeAll();

        Map<String, Object> summary = Maps.of(
                "metrics_days", metricsDays,
                "alerts_days", alertsDays,
                "anomalies_days", anomaliesDays,
                "audit_days", auditDays,
                "inspection_days", inspectionDays,
                "deleted_metrics", deletedMetrics,
                "deleted_alerts", deletedAlerts,
                "deleted_anomalies", deletedAnomalies,
                "deleted_audit", deletedAudit,
                "deleted_inspection", deletedInspection);
        return summary;
    }

    /** 系统健康页要展示的保留策略原文 */
    public Map<String, Object> retention() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics_days", configService.integer(SysConfigService.RETENTION_POLICY + ".metrics_days", 30));
        result.put("alerts_days", configService.integer(SysConfigService.RETENTION_POLICY + ".alerts_days", 90));
        result.put("anomalies_days", configService.integer(SysConfigService.RETENTION_POLICY + ".anomalies_days", 180));
        result.put("audit_days", configService.integer(SysConfigService.RETENTION_POLICY + ".audit_days", 365));
        result.put("inspection_days", configService.integer(SysConfigService.RETENTION_POLICY + ".inspection_days", 0));
        return result;
    }

    // ---------------- 内部 ----------------

    /**
     * 分批删除: 每批单独提交, 返回累计删除行数.
     * 删除方法返回本批实际行数, 0 表示已无超期数据.
     */
    private long deleteInBatches(BiFunction<LocalDateTime, Integer, Integer> deleter, LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0L;
        }
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Integer deleted = txTemplate.execute(status -> deleter.apply(cutoff, BATCH_SIZE));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /** ANALYZE TABLE 更新索引统计, 对应迁移前的 PRAGMA optimize */
    private void analyzeAll() {
        List<String> tables = List.of("port_traffic_stats", "server_metrics", "hardware_health",
                "process_status", "alert_records", "anomaly_events", "config_audit_logs", "inspection_tasks");
        for (String table : tables) {
            try {
                // ANALYZE TABLE 会返回结果集, 走 JdbcTemplate.execute (Statement.execute) 而不是更新计数
                jdbc.execute("ANALYZE TABLE " + table);
            } catch (DataAccessException ex) {
                // 统计信息更新失败不影响清理结果
                log.warn("ANALYZE TABLE {} 失败: {}", table, ex.getMessage());
            }
        }
    }

    private LocalDateTime daysAgo(int days) {
        return days <= 0 ? null : LocalDateTime.now().minusDays(days);
    }

    private int positive(Integer override, int fallback) {
        return override == null || override <= 0 ? fallback : override;
    }
}

