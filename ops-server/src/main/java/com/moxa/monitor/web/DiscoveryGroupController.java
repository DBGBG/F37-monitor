package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.system.DiscoveryGroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 扫描网段管理 (取代迁移前 devices.yaml 的 switch_groups).
 * 业务层写 discovery_groups 表, 采集层每 5 分钟热加载; 保存后额外下发 reload_config 让改动立即生效.
 * 读接口 VIEWER 可用, 增删改仅 ADMIN.
 */
@RestController
@RequestMapping("/api/discovery-groups")
public class DiscoveryGroupController {

    private final DiscoveryGroupService groupService;
    private final RoleGuard roleGuard;

    public DiscoveryGroupController(DiscoveryGroupService groupService, RoleGuard roleGuard) {
        this.groupService = groupService;
        this.roleGuard = roleGuard;
    }

    /** 凭据字段传回掩码值 (****) 时视为不改动 */
    public record GroupForm(String groupName, String ipRange, String model, String locationPrefix,
                            Map<String, Object> snmpConfig, Map<String, Object> sshConfig, Boolean enabled) {
    }

    /** GET /api/discovery-groups */
    @GetMapping
    public Result<Map<String, Object>> list() {
        List<Map<String, Object>> items = groupService.list();
        return Result.ok(Maps.of("total", items.size(), "items", items));
    }

    /** GET /api/discovery-groups/{id} */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable Integer id) {
        return Result.ok(groupService.get(id));
    }

    /** POST /api/discovery-groups */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody GroupForm form) {
        roleGuard.requireAdmin();
        return Result.ok(groupService.create(form.groupName(), form.ipRange(), form.model(),
                form.locationPrefix(), form.snmpConfig(), form.sshConfig(), form.enabled()));
    }

    /** PUT /api/discovery-groups/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Integer id, @RequestBody GroupForm form) {
        roleGuard.requireAdmin();
        return Result.ok(groupService.update(id, form.groupName(), form.ipRange(), form.model(),
                form.locationPrefix(), form.snmpConfig(), form.sshConfig(), form.enabled()));
    }

    /** POST /api/discovery-groups/{id}/toggle —— 启停该网段的扫描 */
    @PostMapping("/{id}/toggle")
    public Result<Map<String, Object>> toggle(@PathVariable Integer id) {
        roleGuard.requireAdmin();
        return Result.ok(groupService.toggle(id));
    }

    /** DELETE /api/discovery-groups/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Integer id) {
        roleGuard.requireAdmin();
        groupService.delete(id);
        return Result.ok(Maps.of("status", "success", "message", "扫描网段已删除"));
    }
}
