package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.device.DeviceManageService;
import com.moxa.monitor.service.device.DeviceQueryService;
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
 * 交换机接口: 路径与查询参数保持迁移前一致, 只是外面套了统一信封.
 * 读接口 VIEWER 即可; 台账改动 (名称/位置/凭据/启停/删除) 需 ADMIN; 定向刷新需 OPERATOR.
 */
@RestController
@RequestMapping("/api/switches")
public class SwitchController {

    private final DeviceQueryService queryService;
    private final DeviceManageService manageService;
    private final RoleGuard roleGuard;

    public SwitchController(DeviceQueryService queryService, DeviceManageService manageService, RoleGuard roleGuard) {
        this.queryService = queryService;
        this.manageService = manageService;
        this.roleGuard = roleGuard;
    }

    /** 台账编辑入参: 凭据传回掩码值时视为不改动 (Secrets.merge 负责识别) */
    public record SwitchUpdateRequest(String name, String location, Boolean enabled,
                                     Map<String, Object> snmpConfig, Map<String, Object> sshConfig) {
    }

    /** GET /api/switches/summary */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(queryService.switchSummary());
    }

    /** GET /api/switches/list */
    @GetMapping("/list")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(name = "online_only", defaultValue = "false") boolean onlineOnly) {
        return Result.ok(queryService.switchList(page, pageSize, keyword, onlineOnly));
    }

    /** GET /api/switches/{id}/detail */
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable("id") String id) {
        return Result.ok(queryService.switchDetail(id));
    }

    /** GET /api/switches/{id}/ports */
    @GetMapping("/{id}/ports")
    public Result<Map<String, Object>> ports(@PathVariable("id") String id) {
        return Result.ok(queryService.switchPorts(id));
    }

    /** GET /api/switches/{id}/traffic?port_index&hours */
    @GetMapping("/{id}/traffic")
    public Result<Map<String, Object>> traffic(@PathVariable("id") String id,
                                               @RequestParam(name = "port_index") int portIndex,
                                               @RequestParam(defaultValue = "1") int hours) {
        return Result.ok(queryService.switchTraffic(id, portIndex, clampHours(hours)));
    }

    /** GET /api/switches/{id}/hardware?hours */
    @GetMapping("/{id}/hardware")
    public Result<Map<String, Object>> hardware(@PathVariable("id") String id,
                                                @RequestParam(defaultValue = "1") int hours) {
        return Result.ok(queryService.switchHardware(id, clampHours(hours)));
    }

    /** GET /api/switches/{id} —— 编辑表单初始值 (凭据掩码), 新增接口 */
    @GetMapping("/{id}/edit")
    public Result<Map<String, Object>> forEdit(@PathVariable("id") String id) {
        roleGuard.current();
        return Result.ok(manageService.switchForEdit(id));
    }

    /** PUT /api/switches/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") String id,
                                              @RequestBody SwitchUpdateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(manageService.updateSwitch(id, request.name(), request.location(), request.enabled(),
                request.snmpConfig(), request.sshConfig()));
    }

    /** DELETE /api/switches/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable("id") String id) {
        roleGuard.requireAdmin();
        manageService.deleteSwitch(id);
        return Result.ok(Maps.of("status", "success", "message", "设备已删除"));
    }

    /** POST /api/switches/{id}/refresh —— 通知采集层定向重采一次 */
    @PostMapping("/{id}/refresh")
    public Result<Map<String, Object>> refresh(@PathVariable("id") String id) {
        roleGuard.requireOperator();
        return Result.ok(manageService.requestRefresh(id));
    }

    /** 迁移前 hours 限定 1~24 */
    private static int clampHours(int hours) {
        return Math.min(Math.max(hours, 1), 24);
    }
}
