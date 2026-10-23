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

import java.util.List;
import java.util.Map;

/**
 * 服务器接口.
 * 迁移前服务器清单来自 devices.yaml, 改造后新增/编辑/删除都直接落 server_devices,
 * 采集层下一轮热加载 (最多 5 分钟) 开始采集新登记的机器.
 */
@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final DeviceQueryService queryService;
    private final DeviceManageService manageService;
    private final RoleGuard roleGuard;

    public ServerController(DeviceQueryService queryService, DeviceManageService manageService, RoleGuard roleGuard) {
        this.queryService = queryService;
        this.manageService = manageService;
        this.roleGuard = roleGuard;
    }

    /** 新增服务器 */
    public record ServerCreateRequest(String id, String name, String ip, String osType, String location,
                                      Map<String, Object> monitoringConfig,
                                      List<Map<String, Object>> processesConfig) {
    }

    /** 编辑服务器: 凭据传回掩码值时视为不改动 */
    public record ServerUpdateRequest(String name, String location, Boolean enabled,
                                      Map<String, Object> monitoringConfig,
                                      List<Map<String, Object>> processesConfig) {
    }

    /** GET /api/servers/summary */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(queryService.serverSummary());
    }

    /** GET /api/servers/list */
    @GetMapping("/list")
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String keyword) {
        return Result.ok(queryService.serverList(page, pageSize, keyword));
    }

    /** GET /api/servers/{id}/detail */
    @GetMapping("/{id}/detail")
    public Result<Map<String, Object>> detail(@PathVariable("id") String id) {
        return Result.ok(queryService.serverDetail(id));
    }

    /** GET /api/servers/{id}/metrics?hours */
    @GetMapping("/{id}/metrics")
    public Result<Map<String, Object>> metrics(@PathVariable("id") String id,
                                               @RequestParam(defaultValue = "1") int hours) {
        return Result.ok(queryService.serverMetrics(id, Math.min(Math.max(hours, 1), 24)));
    }

    /** GET /api/servers/{id}/processes */
    @GetMapping("/{id}/processes")
    public Result<Map<String, Object>> processes(@PathVariable("id") String id) {
        return Result.ok(queryService.serverProcesses(id));
    }

    /** GET /api/servers/{id}/edit —— 编辑表单初始值 (凭据掩码), 新增接口 */
    @GetMapping("/{id}/edit")
    public Result<Map<String, Object>> forEdit(@PathVariable("id") String id) {
        roleGuard.current();
        return Result.ok(manageService.serverForEdit(id));
    }

    /** POST /api/servers */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody ServerCreateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(manageService.createServer(request.id(), request.name(), request.ip(),
                request.osType(), request.location(), request.monitoringConfig(), request.processesConfig()));
    }

    /** PUT /api/servers/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable("id") String id,
                                              @RequestBody ServerUpdateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(manageService.updateServer(id, request.name(), request.location(), request.enabled(),
                request.monitoringConfig(), request.processesConfig()));
    }

    /** DELETE /api/servers/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable("id") String id) {
        roleGuard.requireAdmin();
        manageService.deleteServer(id);
        return Result.ok(Maps.of("status", "success", "message", "服务器已删除"));
    }
}
