package com.moxa.monitor.web;

import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.inspection.InspectionPlanService;
import com.moxa.monitor.service.inspection.InspectionPlanService.PlanForm;
import com.moxa.monitor.service.inspection.InspectionTaskService;
import com.moxa.monitor.service.inspection.InspectionTaskService.ExecuteForm;
import com.moxa.monitor.service.inspection.InspectionTaskService.SkipForm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备点检接口: 模板 / 计划 CRUD / 任务流转 / 履历 / 统计.
 * 路径与查询参数保持迁移前一致; 写操作需 OPERATOR 及以上.
 */
@RestController
@RequestMapping("/api/inspection")
public class InspectionController {

    private final InspectionPlanService planService;
    private final InspectionTaskService taskService;
    private final RoleGuard roleGuard;

    public InspectionController(InspectionPlanService planService, InspectionTaskService taskService,
                                RoleGuard roleGuard) {
        this.planService = planService;
        this.taskService = taskService;
        this.roleGuard = roleGuard;
    }

    /** 迁移前 OperatorRequest: {"operator": "admin"} */
    public record OperatorRequest(String operator) {
    }

    // ==================== 模板 / 设备 / 计划 ====================

    /** GET /api/inspection/templates */
    @GetMapping("/templates")
    public Result<Map<String, Object>> templates() {
        return Result.ok(planService.templates());
    }

    /** GET /api/inspection/devices?device_type */
    @GetMapping("/devices")
    public Result<Map<String, Object>> devices(@RequestParam(name = "device_type", required = false) String deviceType) {
        return Result.ok(planService.deviceOptions(deviceType));
    }

    /** GET /api/inspection/plans?keyword&enabled */
    @GetMapping("/plans")
    public Result<Map<String, Object>> plans(@RequestParam(defaultValue = "") String keyword,
                                             @RequestParam(required = false) String enabled) {
        return Result.ok(planService.list(keyword, enabled));
    }

    /** GET /api/inspection/plans/{id} */
    @GetMapping("/plans/{id}")
    public Result<Map<String, Object>> planDetail(@PathVariable Integer id) {
        return Result.ok(planService.detail(id));
    }

    /** POST /api/inspection/plans */
    @PostMapping("/plans")
    public Result<Map<String, Object>> createPlan(@RequestBody PlanForm form) {
        roleGuard.requireOperator();
        return Result.ok(planService.create(form));
    }

    /** PUT /api/inspection/plans/{id} */
    @PutMapping("/plans/{id}")
    public Result<Map<String, Object>> updatePlan(@PathVariable Integer id, @RequestBody PlanForm form) {
        roleGuard.requireOperator();
        return Result.ok(planService.update(id, form));
    }

    /** DELETE /api/inspection/plans/{id} */
    @DeleteMapping("/plans/{id}")
    public Result<Map<String, Object>> deletePlan(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(planService.delete(id));
    }

    /** POST /api/inspection/plans/{id}/toggle */
    @PostMapping("/plans/{id}/toggle")
    public Result<Map<String, Object>> togglePlan(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(planService.toggle(id));
    }

    /** POST /api/inspection/plans/{id}/run-now */
    @PostMapping("/plans/{id}/run-now")
    public Result<Map<String, Object>> runPlanNow(@PathVariable Integer id) {
        roleGuard.requireOperator();
        return Result.ok(planService.runNow(id));
    }

    // ==================== 任务 ====================

    /** GET /api/inspection/tasks */
    @GetMapping("/tasks")
    public Result<PageResult<Map<String, Object>>> tasks(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(name = "plan_id", required = false) Integer planId,
            @RequestParam(name = "device_id", defaultValue = "") String deviceId,
            @RequestParam(name = "device_type", defaultValue = "") String deviceType,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(name = "date_from", defaultValue = "") String dateFrom,
            @RequestParam(name = "date_to", defaultValue = "") String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(taskService.list(status, planId, deviceId, deviceType, result,
                dateFrom, dateTo, page, pageSize));
    }

    /** GET /api/inspection/tasks/{id} */
    @GetMapping("/tasks/{id}")
    public Result<Map<String, Object>> taskDetail(@PathVariable Integer id) {
        return Result.ok(taskService.detail(id));
    }

    /** POST /api/inspection/tasks/{id}/start */
    @PostMapping("/tasks/{id}/start")
    public Result<Map<String, Object>> startTask(@PathVariable Integer id,
                                                 @RequestBody(required = false) OperatorRequest request) {
        roleGuard.requireOperator();
        return Result.ok(taskService.start(id, request == null ? null : request.operator()));
    }

    /** POST /api/inspection/tasks/{id}/execute */
    @PostMapping("/tasks/{id}/execute")
    public Result<Map<String, Object>> executeTask(@PathVariable Integer id, @RequestBody ExecuteForm form) {
        roleGuard.requireOperator();
        return Result.ok(taskService.execute(id, form));
    }

    /** POST /api/inspection/tasks/{id}/skip */
    @PostMapping("/tasks/{id}/skip")
    public Result<Map<String, Object>> skipTask(@PathVariable Integer id, @RequestBody(required = false) SkipForm form) {
        roleGuard.requireOperator();
        return Result.ok(taskService.skip(id, form));
    }

    // ==================== 履历 / 统计 ====================

    /** GET /api/inspection/history */
    @GetMapping("/history")
    public Result<PageResult<Map<String, Object>>> history(
            @RequestParam(name = "device_id", defaultValue = "") String deviceId,
            @RequestParam(name = "plan_id", required = false) Integer planId,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(name = "date_from", defaultValue = "") String dateFrom,
            @RequestParam(name = "date_to", defaultValue = "") String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(taskService.history(deviceId, planId, result, dateFrom, dateTo, page, pageSize));
    }

    /** GET /api/inspection/stats */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(taskService.stats());
    }
}
