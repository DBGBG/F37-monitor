package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.alert.MaintenanceWindowService;
import com.moxa.monitor.service.alert.MaintenanceWindowService.WindowForm;
import com.moxa.monitor.service.proactive.AnomalyService;
import com.moxa.monitor.service.proactive.ProactiveEngineService;
import com.moxa.monitor.service.proactive.ProactiveQueryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 主动运维接口: 健康评分 / 异常事件 / 维护建议 / 指标基线 / 维护窗口.
 * 路径与查询参数与迁移前一致; 立即分析 (POST /analyze) 仅 ADMIN.
 */
@RestController
@RequestMapping("/api/proactive")
public class ProactiveController {

    private final ProactiveQueryService queryService;
    private final AnomalyService anomalyService;
    private final ProactiveEngineService engineService;
    private final MaintenanceWindowService windowService;
    private final RoleGuard roleGuard;

    public ProactiveController(ProactiveQueryService queryService,
                               AnomalyService anomalyService,
                               ProactiveEngineService engineService,
                               MaintenanceWindowService windowService,
                               RoleGuard roleGuard) {
        this.queryService = queryService;
        this.anomalyService = anomalyService;
        this.engineService = engineService;
        this.windowService = windowService;
        this.roleGuard = roleGuard;
    }

    /** 迁移前 ActorIn: {"by": "admin"} */
    public record ActorRequest(String by) {
    }

    /** GET /api/proactive/overview */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(queryService.overview());
    }

    /** GET /api/proactive/health —— 按分数升序, 最差的排最前 */
    @GetMapping("/health")
    public Result<PageResult<Map<String, Object>>> health(
            @RequestParam(name = "device_type", defaultValue = "") String deviceType,
            @RequestParam(defaultValue = "") String grade,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(queryService.health(deviceType, grade, keyword, page, pageSize));
    }

    /** GET /api/proactive/health/{device_id} */
    @GetMapping("/health/{device_id}")
    public Result<Map<String, Object>> healthDetail(@PathVariable("device_id") String deviceId) {
        return Result.ok(queryService.healthDetail(deviceId));
    }

    /** GET /api/proactive/anomalies?status=open */
    @GetMapping("/anomalies")
    public Result<PageResult<Map<String, Object>>> anomalies(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "") String severity,
            @RequestParam(name = "device_type", defaultValue = "") String deviceType,
            @RequestParam(name = "device_id", defaultValue = "") String deviceId,
            @RequestParam(defaultValue = "") String metric,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(anomalyService.list(status, severity, deviceType, deviceId, metric, page, pageSize));
    }

    /** POST /api/proactive/anomalies/{id}/acknowledge */
    @PostMapping("/anomalies/{id}/acknowledge")
    public Result<Map<String, Object>> acknowledgeAnomaly(@PathVariable Integer id,
                                                          @RequestBody(required = false) ActorRequest request) {
        roleGuard.requireOperator();
        return Result.ok(anomalyService.acknowledge(id, actorOf(request)));
    }

    /** POST /api/proactive/anomalies/{id}/resolve */
    @PostMapping("/anomalies/{id}/resolve")
    public Result<Map<String, Object>> resolveAnomaly(@PathVariable Integer id,
                                                      @RequestBody(required = false) ActorRequest request) {
        roleGuard.requireOperator();
        return Result.ok(anomalyService.resolve(id, actorOf(request)));
    }

    /** GET /api/proactive/recommendations */
    @GetMapping("/recommendations")
    public Result<Map<String, Object>> recommendations() {
        return Result.ok(anomalyService.recommendations());
    }

    /** GET /api/proactive/baselines —— 迁移前 page_size 上限 500 */
    @GetMapping("/baselines")
    public Result<PageResult<Map<String, Object>>> baselines(
            @RequestParam(name = "device_id", defaultValue = "") String deviceId,
            @RequestParam(defaultValue = "") String metric,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "100") int pageSize) {
        return Result.ok(queryService.baselines(deviceId, metric, page,
                Math.min(Math.max(pageSize, 1), 500)));
    }

    /** POST /api/proactive/analyze —— 在业务层同步执行一轮分析, 不再通知采集层 */
    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyze() {
        roleGuard.requireAdmin();
        return Result.ok(Maps.of("status", "success", "stats", engineService.analyze()));
    }

    // ==================== 维护窗口 ====================

    /** GET /api/proactive/maintenance/windows */
    @GetMapping("/maintenance/windows")
    public Result<Map<String, Object>> listWindows() {
        return Result.ok(windowService.list());
    }

    /** POST /api/proactive/maintenance/windows */
    @PostMapping("/maintenance/windows")
    public Result<Map<String, Object>> createWindow(@RequestBody WindowForm form) {
        roleGuard.requireOperator();
        return Result.ok(windowService.create(withCreator(form)));
    }

    /** PUT /api/proactive/maintenance/windows/{id} */
    @PutMapping("/maintenance/windows/{id}")
    public Result<Map<String, Object>> updateWindow(@PathVariable Integer id, @RequestBody WindowForm form) {
        roleGuard.requireOperator();
        return Result.ok(windowService.update(id, withCreator(form)));
    }

    /** POST /api/proactive/maintenance/windows/{id}/toggle */
    @PostMapping("/maintenance/windows/{id}/toggle")
    public Result<Map<String, Object>> toggleWindow(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(windowService.toggle(id));
    }

    /** DELETE /api/proactive/maintenance/windows/{id} */
    @DeleteMapping("/maintenance/windows/{id}")
    public Result<Map<String, Object>> deleteWindow(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(windowService.delete(id));
    }

    /** 操作人缺省取当前登录用户 */
    private String actorOf(ActorRequest request) {
        return roleGuard.usernameOr(request == null ? null : request.by());
    }

    private WindowForm withCreator(WindowForm form) {
        String creator = roleGuard.usernameOr(form.createdBy());
        return new WindowForm(form.name(), form.deviceType(),
                form.deviceIds() == null ? List.of() : form.deviceIds(),
                form.startTime(), form.endTime(), form.reason(), form.enabled(), creator);
    }
}
