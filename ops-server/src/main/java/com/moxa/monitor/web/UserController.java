package com.moxa.monitor.web;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.system.UserService;
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
 * 用户与角色管理 (新增接口, ADMIN 专属).
 * 响应里绝不包含 password_hash —— UserService.row() 已按白名单出字段.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RoleGuard roleGuard;

    public UserController(UserService userService, RoleGuard roleGuard) {
        this.userService = userService;
        this.roleGuard = roleGuard;
    }

    public record UserCreateRequest(String username, String password, String displayName,
                                    String roleCode, Boolean enabled) {
    }

    /** password 留空表示不改动 */
    public record UserUpdateRequest(String displayName, String roleCode, Boolean enabled, String password) {
    }

    /** GET /api/users */
    @GetMapping
    public Result<Map<String, Object>> list() {
        roleGuard.requireAdmin();
        List<Map<String, Object>> items = userService.list();
        return Result.ok(Maps.of("total", items.size(), "items", items));
    }

    /** GET /api/users/roles —— 角色下拉 */
    @GetMapping("/roles")
    public Result<Map<String, Object>> roles() {
        roleGuard.requireAdmin();
        List<Map<String, Object>> items = userService.roles();
        return Result.ok(Maps.of("total", items.size(), "items", items));
    }

    /** POST /api/users */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody UserCreateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(userService.create(request.username(), request.password(),
                request.displayName(), request.roleCode(), request.enabled()));
    }

    /** PUT /api/users/{id} */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Integer id, @RequestBody UserUpdateRequest request) {
        roleGuard.requireAdmin();
        return Result.ok(userService.update(id, request.displayName(), request.roleCode(),
                request.enabled(), request.password()));
    }

    /** DELETE /api/users/{id} */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Integer id) {
        roleGuard.requireAdmin();
        userService.delete(id);
        return Result.ok(Maps.of("status", "success"));
    }
}
