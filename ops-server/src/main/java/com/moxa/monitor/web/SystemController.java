package com.moxa.monitor.web;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.system.CollectorMonitorService;
import com.moxa.monitor.service.system.RetentionCleanupService;
import com.moxa.monitor.service.system.SysConfigService;
import com.moxa.monitor.service.system.SystemHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 系统自监控与运行参数接口.
 * 迁移前的 /api/system/status, /sync-devices, /rediscover 由业务层直接执行,
 * 改造后跨层动作一律写 collector_commands 表通知采集层 (Python 不再暴露 HTTP).
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /** 允许在线修改的配置键, 其它键不接受写入, 避免前端误建垃圾配置 */
    private static final List<String> EDITABLE_KEYS = List.of(
            SysConfigService.ALERT_RULES, SysConfigService.ALERT_SETTINGS, SysConfigService.PROACTIVE_PARAMS,
            SysConfigService.NOTIFICATION_CONFIG, SysConfigService.RETENTION_POLICY,
            SysConfigService.INSPECTION_DEFAULTS, SysConfigService.SSH_DEFAULTS, SysConfigService.VLAN_PLAN);

    private final SystemHealthService healthService;
    private final RetentionCleanupService cleanupService;
    private final CollectorMonitorService collectorService;
    private final SysConfigService configService;
    private final RoleGuard roleGuard;

    public SystemController(SystemHealthService healthService, RetentionCleanupService cleanupService,
                            CollectorMonitorService collectorService, SysConfigService configService,
                            RoleGuard roleGuard) {
        this.healthService = healthService;
        this.cleanupService = cleanupService;
        this.collectorService = collectorService;
        this.configService = configService;
        this.roleGuard = roleGuard;
    }

    /** 迁移前 CleanupRequest; checkpoint 在 MySQL 下无对应动作 (清理后改为 ANALYZE TABLE) */
    public record CleanupRequest(Integer metricsDays, Integer alertsDays, Boolean checkpoint) {
    }

    public record ConfigUpdateRequest(Object value, String description) {
    }

    /** GET /api/system/health —— 库规模 / 数据新鲜度 / 设备在线 / 采集心跳 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(healthService.health());
    }

    /** GET /api/system/status —— 业务层自身状态 + 采集任务清单 (迁移前的 scheduler_jobs) */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        LocalDateTime beatAt = collectorService.lastBeatAt();
        return Result.ok(Maps.of(
                "system_name", "主动运维系统",
                "layer", "business",
                "status", "running",
                "collector_alive", collectorService.isAlive(),
                "last_beat_at", beatAt == null ? null : DateTimeSerializers.fmt(beatAt),
                "scheduler_jobs", collectorService.jobs()));
    }

    /** POST /api/system/maintenance/cleanup —— 手动触发一轮保留策略清理 */
    @PostMapping("/maintenance/cleanup")
    public Result<Map<String, Object>> cleanup(@RequestBody(required = false) CleanupRequest request) {
        roleGuard.requireAdmin();
        CleanupRequest body = request == null ? new CleanupRequest(null, null, null) : request;
        return Result.ok(Maps.of("status", "success",
                "summary", cleanupService.runCleanup(body.metricsDays(), body.alertsDays())));
    }

    /** GET /api/system/maintenance/stats —— 各时序表跨度与当前保留策略 */
    @GetMapping("/maintenance/stats")
    public Result<Map<String, Object>> maintenanceStats() {
        return Result.ok(healthService.maintenanceStats());
    }

    /** POST /api/system/sync-devices —— 通知采集层重载配置并同步台账 */
    @PostMapping("/sync-devices")
    public Result<Map<String, Object>> syncDevices() {
        roleGuard.requireOperator();
        Integer id = collectorService.submit(CollectorMonitorService.CMD_RELOAD_CONFIG, Map.of(),
                roleGuard.currentUsername());
        return Result.ok(Maps.of("status", "success", "message", "设备配置已重新加载", "command_id", id));
    }

    /** POST /api/system/rediscover —— 通知采集层立即执行一次设备发现 */
    @PostMapping("/rediscover")
    public Result<Map<String, Object>> rediscover() {
        roleGuard.requireOperator();
        Integer id = collectorService.submit(CollectorMonitorService.CMD_REDISCOVER, Map.of(),
                roleGuard.currentUsername());
        return Result.ok(Maps.of("status", "success", "message", "设备发现已下发", "command_id", id));
    }

    // ==================== 采集层监控 ====================

    /** GET /api/system/collector/heartbeat —— 各阶段最新一轮采集统计 */
    @GetMapping("/collector/heartbeat")
    public Result<Map<String, Object>> collectorHeartbeat() {
        return Result.ok(collectorService.heartbeatReport());
    }

    /** GET /api/system/collector/heartbeat/recent —— 最近 50 条心跳, 画采集耗时曲线 */
    @GetMapping("/collector/heartbeat/recent")
    public Result<Map<String, Object>> recentBeats() {
        return Result.ok(Maps.of("items", collectorService.recentBeats()));
    }

    /** GET /api/system/collector/commands —— 最近 50 条跨层指令及其执行状态 */
    @GetMapping("/collector/commands")
    public Result<Map<String, Object>> collectorCommands() {
        return Result.ok(Maps.of("pending", collectorService.hasPending(),
                "items", collectorService.commands()));
    }

    // ==================== 运行参数 ====================

    /** GET /api/system/configs —— 全量业务参数 (告警阈值/通知/保留策略/SSH/VLAN 规划) */
    @GetMapping("/configs")
    public Result<Map<String, Object>> configs() {
        roleGuard.current();
        return Result.ok(Maps.of("items", configService.allItems()));
    }

    /** GET /api/system/configs/values —— 键 -> JSON 值, 前端表单直接绑定 */
    @GetMapping("/configs/values")
    public Result<Map<String, Object>> configValues() {
        roleGuard.current();
        return Result.ok(configService.values());
    }

    /** PUT /api/system/configs/{key} —— 仅 ADMIN, 保存后配置缓存立即失效 */
    @PutMapping("/configs/{key}")
    public Result<Map<String, Object>> updateConfig(@PathVariable String key,
                                                    @RequestBody ConfigUpdateRequest request) {
        roleGuard.requireAdmin();
        if (!EDITABLE_KEYS.contains(key)) {
            throw BizException.badRequest("不支持修改的配置项: " + key);
        }
        if (request.value() == null) {
            throw BizException.badRequest("配置值不能为空");
        }
        configService.put(key, request.value(), request.description());
        return Result.ok(Maps.of("status", "success", "config_key", key, "value", request.value()));
    }

    /** GET /api/system/configs/keys —— 可修改的配置键清单 */
    @GetMapping("/configs/keys")
    public Result<Map<String, Object>> configKeys() {
        roleGuard.current();
        return Result.ok(Maps.of("keys", EDITABLE_KEYS));
    }
}
