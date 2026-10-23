package com.moxa.monitor.web;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.automation.AuditLogService;
import com.moxa.monitor.service.automation.VlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自动化配置下发接口: VLAN / 端口 / 原始 CLI, 以及配置审计日志查询.
 * 路径与请求体字段与迁移前一致; 下发是危险动作, 一律要求 OPERATOR 及以上.
 * 操作人缺省取当前登录用户 (迁移前端里硬编码的 'admin').
 */
@RestController
@RequestMapping("/api/automation")
public class AutomationController {

    private final VlanService vlanService;
    private final AuditLogService auditLogService;
    private final RoleGuard roleGuard;

    public AutomationController(VlanService vlanService, AuditLogService auditLogService, RoleGuard roleGuard) {
        this.vlanService = vlanService;
        this.auditLogService = auditLogService;
        this.roleGuard = roleGuard;
    }

    // ==================== 请求体 (与迁移前 pydantic 模型逐字段对应) ====================

    public record VlanCreateRequest(String switchId, Integer vlanId, String vlanName,
                                    String description, String operator) {
    }

    public record VlanDeleteRequest(String switchId, Integer vlanId, String operator) {
    }

    public record PortAccessRequest(String switchId, String portName, Integer vlanId, String operator) {
    }

    public record PortTrunkRequest(String switchId, String portName, List<Integer> allowedVlans,
                                   Integer nativeVlan, String operator) {
    }

    public record PortToggleRequest(String switchId, String portName, Boolean enabled, String operator) {
    }

    /** use_vlan_plan=true 时按 sys_config.vlan_plan 批量下发, 否则用请求里的 vlans */
    public record BatchVlanRequest(String switchId, String operator, Boolean useVlanPlan,
                                   List<Map<String, Object>> vlans) {
    }

    public record RawCommandRequest(String switchId, List<String> commands, String operator) {
    }

    // ==================== VLAN ====================

    /** POST /api/automation/vlan/create */
    @PostMapping("/vlan/create")
    public Result<Map<String, Object>> createVlan(@RequestBody VlanCreateRequest request) {
        roleGuard.requireOperator();
        return Result.ok(vlanService.createVlan(request.switchId(), requireVlanId(request.vlanId()),
                nullToEmpty(request.vlanName()), nullToEmpty(request.description()),
                roleGuard.usernameOr(request.operator())));
    }

    /** POST /api/automation/vlan/delete */
    @PostMapping("/vlan/delete")
    public Result<Map<String, Object>> deleteVlan(@RequestBody VlanDeleteRequest request) {
        roleGuard.requireOperator();
        return Result.ok(vlanService.deleteVlan(request.switchId(), requireVlanId(request.vlanId()),
                roleGuard.usernameOr(request.operator())));
    }

    /** POST /api/automation/vlan/batch */
    @PostMapping("/vlan/batch")
    public Result<Map<String, Object>> batchCreateVlan(@RequestBody BatchVlanRequest request) {
        roleGuard.requireOperator();
        String operator = roleGuard.usernameOr(request.operator());
        // 迁移前 use_vlan_plan 默认 True
        boolean usePlan = request.useVlanPlan() == null || request.useVlanPlan();
        if (usePlan) {
            return Result.ok(vlanService.applyVlanPlan(request.switchId(), operator));
        }
        return Result.ok(vlanService.batchCreateVlans(request.switchId(), request.vlans(), operator));
    }

    // ==================== 端口 ====================

    /** POST /api/automation/port/access */
    @PostMapping("/port/access")
    public Result<Map<String, Object>> setPortAccess(@RequestBody PortAccessRequest request) {
        roleGuard.requireOperator();
        return Result.ok(vlanService.assignPortAccess(request.switchId(), nullToEmpty(request.portName()),
                requireVlanId(request.vlanId()), roleGuard.usernameOr(request.operator())));
    }

    /** POST /api/automation/port/trunk —— native_vlan 缺省 1, 与迁移前一致 */
    @PostMapping("/port/trunk")
    public Result<Map<String, Object>> setPortTrunk(@RequestBody PortTrunkRequest request) {
        roleGuard.requireOperator();
        int nativeVlan = request.nativeVlan() == null ? 1 : request.nativeVlan();
        return Result.ok(vlanService.setPortTrunk(request.switchId(), nullToEmpty(request.portName()),
                request.allowedVlans(), nativeVlan, roleGuard.usernameOr(request.operator())));
    }

    /** POST /api/automation/port/toggle */
    @PostMapping("/port/toggle")
    public Result<Map<String, Object>> togglePort(@RequestBody PortToggleRequest request) {
        roleGuard.requireOperator();
        boolean enabled = request.enabled() == null || request.enabled();
        return Result.ok(vlanService.togglePort(request.switchId(), nullToEmpty(request.portName()),
                enabled, roleGuard.usernameOr(request.operator())));
    }

    // ==================== 原始命令 / 规划 / 审计 ====================

    /** POST /api/automation/raw-command */
    @PostMapping("/raw-command")
    public Result<Map<String, Object>> rawCommand(@RequestBody RawCommandRequest request) {
        roleGuard.requireOperator();
        return Result.ok(vlanService.rawCommand(request.switchId(), request.commands(),
                roleGuard.usernameOr(request.operator())));
    }

    /** GET /api/automation/vlan-plan */
    @GetMapping("/vlan-plan")
    public Result<Map<String, Object>> vlanPlan() {
        return Result.ok(Maps.of("vlan_plan", vlanService.vlanPlan()));
    }

    /** GET /api/automation/audit-logs?switch_id&page&page_size */
    @GetMapping("/audit-logs")
    public Result<Map<String, Object>> auditLogs(@RequestParam(name = "switch_id", required = false) String switchId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return Result.ok(auditLogService.list(switchId, Math.max(page, 1), pageSize));
    }

    /** GET /api/automation/audit-logs/{id} —— 含下发命令与回显, 新增接口 */
    @GetMapping("/audit-logs/{id}")
    public Result<Map<String, Object>> auditLogDetail(@PathVariable Integer id) {
        roleGuard.current();
        return Result.ok(auditLogService.detail(id));
    }

    // ==================== 内部 ====================

    /** 迁移前 pydantic 的必填 int 缺失会返回 422, 统一信封下按参数错误处理 */
    private static int requireVlanId(Integer vlanId) {
        if (vlanId == null) {
            throw BizException.badRequest("vlan_id 不能为空");
        }
        return vlanId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
