package com.moxa.monitor.service.system;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.CollectorHeartbeat;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.CollectorHeartbeatRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统自监控 (迁移自 legacy api/system.py).
 * 迁移前用 SQLite 文件体积表示"数据库规模", MySQL 下改为 information_schema 的表行数与数据/索引体积;
 * 同时补充采集层心跳, 让业务层能回答"数据不新鲜是采集挂了还是业务挂了".
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    /** GET /api/system/health 里 table_rows 的表清单 (与迁移前一致) */
    private static final List<String> ROW_TABLES = List.of(
            "port_status", "port_traffic_stats", "hardware_health",
            "server_metrics", "process_status", "alert_records");

    /** 维护统计里给出时间跨度的时序表 */
    private static final List<String> SERIES_TABLES = List.of(
            "port_traffic_stats", "hardware_health", "server_metrics", "process_status");

    private final JdbcTemplate jdbc;
    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final AlertRecordRepository alertRepository;
    private final CollectorHeartbeatRepository heartbeatRepository;
    private final RetentionCleanupService retentionService;

    public SystemHealthService(JdbcTemplate jdbc,
                               SwitchDeviceRepository switchRepository,
                               ServerDeviceRepository serverRepository,
                               AlertRecordRepository alertRepository,
                               CollectorHeartbeatRepository heartbeatRepository,
                               RetentionCleanupService retentionService) {
        this.jdbc = jdbc;
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.alertRepository = alertRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.retentionService = retentionService;
    }

    /** GET /api/system/health */
    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        long swTotal = switchRepository.count();
        long swOnline = switchRepository.countByIsOnlineIsTrue();
        long srvTotal = serverRepository.count();
        long srvOnline = serverRepository.countByIsOnlineIsTrue();
        long activeAlerts = alertRepository.countByStatus("active");

        Map<String, Object> size = databaseSize();
        Map<String, Object> tableRows = tableRows();

        return Maps.of(
                "status", "ok",
                "database", size,
                "devices", Maps.of(
                        "switches", Maps.of("total", swTotal, "online", swOnline),
                        "servers", Maps.of("total", srvTotal, "online", srvOnline)),
                "alerts", Maps.of("active", activeAlerts),
                "table_rows", tableRows,
                "freshness", freshness(),
                "collector", collectorSection());
    }

    /** GET /api/system/maintenance/stats —— 各时序表行数与时间跨度 + 当前保留策略 */
    public Map<String, Object> maintenanceStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : SERIES_TABLES) {
            result.put(table, spanOf(table));
        }
        result.put("retention", retentionService.retention());
        return result;
    }

    // ---------------- 内部 ----------------

    /** information_schema 里的体积: 数据 + 索引, 单位 MB (取代迁移前的 .db 文件体积) */
    private Map<String, Object> databaseSize() {
        long dataBytes = 0L;
        long indexBytes = 0L;
        long tableCount = 0L;
        String schema = querySchema();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT TABLE_NAME, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = ?", schema);
            tableCount = rows.size();
            for (Map<String, Object> row : rows) {
                dataBytes += RoundUtil.safeLong(row.get("DATA_LENGTH"));
                indexBytes += RoundUtil.safeLong(row.get("INDEX_LENGTH"));
            }
        } catch (Exception ex) {
            log.warn("读取 information_schema 失败: {}", ex.getMessage());
        }
        double totalMb = (dataBytes + indexBytes) / (1024.0 * 1024.0);
        return Maps.of(
                "schema", schema,
                "engine", "mysql",
                "table_count", tableCount,
                "data_bytes", dataBytes,
                "index_bytes", indexBytes,
                "total_size_mb", RoundUtil.round(totalMb, 2));
    }

    private Map<String, Object> tableRows() {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String table : ROW_TABLES) {
            rows.put(table, countOf(table));
        }
        return rows;
    }

    /** 各时间列的最新写入时间, 用于判断"数据是否还在进来" */
    private Map<String, Object> freshness() {
        return Maps.of(
                "switch_last_poll", maxOf("switch_devices", "last_poll_time"),
                "server_last_poll", maxOf("server_devices", "last_poll_time"),
                "traffic_last", maxOf("port_traffic_stats", "timestamp"),
                "hardware_last", maxOf("hardware_health", "timestamp"),
                "server_metrics_last", maxOf("server_metrics", "timestamp"),
                "process_last", maxOf("process_status", "timestamp"),
                "alert_last", maxOf("alert_records", "triggered_at"));
    }

    private Map<String, Object> collectorSection() {
        CollectorHeartbeat beat = heartbeatRepository.findFirstByOrderByLastBeatAtDesc().orElse(null);
        if (beat == null) {
            return Maps.of("alive", false, "message", "尚无采集心跳, 请确认采集进程已启动");
        }
        return Maps.of(
                "alive", true,
                "component", beat.getComponent(),
                "phase", beat.getPhase(),
                "total", beat.getTotal(),
                "success", beat.getSuccess(),
                "errors", beat.getErrors(),
                "duration_ms", beat.getDurationMs(),
                "last_beat_at", DateTimeSerializers.fmt(beat.getLastBeatAt()));
    }

    private Map<String, Object> spanOf(String table) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS rows_total, MIN(timestamp) AS oldest, MAX(timestamp) AS newest FROM " + table);
        return Maps.of(
                "rows", RoundUtil.safeLong(row.get("rows_total")),
                "oldest", textOf(row.get("oldest")),
                "newest", textOf(row.get("newest")));
    }

    private long countOf(String table) {
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return count == null ? 0L : count;
        } catch (Exception ex) {
            log.warn("统计表 {} 行数失败: {}", table, ex.getMessage());
            return 0L;
        }
    }

    private String maxOf(String table, String column) {
        try {
            return textOf(jdbc.queryForObject("SELECT MAX(" + column + ") FROM " + table, LocalDateTime.class));
        } catch (Exception ex) {
            log.warn("读取 {}.{} 新鲜度失败: {}", table, column, ex.getMessage());
            return null;
        }
    }

    private String querySchema() {
        try {
            String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
            return schema == null ? "monitor" : schema;
        } catch (Exception ex) {
            return "monitor";
        }
    }

    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return DateTimeSerializers.fmt(time);
        }
        if (value instanceof java.sql.Timestamp time) {
            return DateTimeSerializers.fmt(time.toLocalDateTime());
        }
        return String.valueOf(value);
    }
}
