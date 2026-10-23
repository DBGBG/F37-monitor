package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.alert.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 告警接口: 迁移自 legacy api/alerts.py.
 * 确认与恢复属于闭环动作, 需 OPERATOR 及以上; 操作人缺省取当前登录用户 (迁移前端硬编码 'admin').
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final RoleGuard roleGuard;

    public AlertController(AlertService alertService, RoleGuard roleGuard) {
        this.alertService = alertService;
        this.roleGuard = roleGuard;
    }

    public record AcknowledgeRequest(String alertId, String acknowledgedBy) {
    }

    public record ResolveRequest(String alertId, String resolvedBy) {
    }

    /** GET /api/alerts/summary */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(alertService.summary());
    }

    /** GET /api/alerts/list?status&severity&page&page_size */
    @GetMapping("/list")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(alertService.list(status, severity, page, pageSize));
    }

    /** POST /api/alerts/acknowledge */
    @PostMapping("/acknowledge")
    public Result<Map<String, Object>> acknowledge(@RequestBody AcknowledgeRequest request) {
        roleGuard.requireOperator();
        alertService.acknowledge(request.alertId(),
                roleGuard.usernameOr(request.acknowledgedBy()));
        return Result.ok(Maps.of("status", "success"));
    }

    /** POST /api/alerts/resolve */
    @PostMapping("/resolve")
    public Result<Map<String, Object>> resolve(@RequestBody ResolveRequest request) {
        roleGuard.requireOperator();
        alertService.resolve(request.alertId(), roleGuard.usernameOr(request.resolvedBy()));
        return Result.ok(Maps.of("status", "success"));
    }
}
