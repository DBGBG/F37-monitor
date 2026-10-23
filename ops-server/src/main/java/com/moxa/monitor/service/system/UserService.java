package com.moxa.monitor.service.system;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.SysRole;
import com.moxa.monitor.domain.SysUser;
import com.moxa.monitor.repo.SysRoleRepository;
import com.moxa.monitor.repo.SysUserRepository;
import com.moxa.monitor.security.AuthUser;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.security.UserBootstrapInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户与角色管理 (仅 ADMIN).
 * role_code 存在 sys_user 上, 同时同步一条 sys_user_role 关联, 保持两张表一致.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final List<String> ROLES = List.of(
            AuthUser.ROLE_ADMIN, AuthUser.ROLE_OPERATOR, AuthUser.ROLE_VIEWER);

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleGuard roleGuard;
    private final UserBootstrapInitializer bootstrap;

    public UserService(SysUserRepository userRepository, SysRoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, RoleGuard roleGuard,
                       UserBootstrapInitializer bootstrap) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.roleGuard = roleGuard;
        this.bootstrap = bootstrap;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysUser user : userRepository.findAllByOrderByIdAsc()) {
            items.add(row(user));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysRole role : roleRepository.findAllByOrderByIdAsc()) {
            items.add(Maps.of(
                    "id", role.getId(),
                    "code", role.getCode(),
                    "name", role.getName(),
                    "permissions", role.getPermissionsJson() == null ? List.of() : role.getPermissionsJson(),
                    "user_count", userRepository.findByRoleCode(role.getCode()).size()));
        }
        return items;
    }

    @Transactional
    public Map<String, Object> create(String username, String password, String displayName,
                                      String roleCode, Boolean enabled) {
        if (username == null || username.isBlank()) {
            throw BizException.badRequest("用户名不能为空");
        }
        String name = username.trim();
        if (userRepository.existsByUsername(name)) {
            throw BizException.badRequest("用户名已存在: " + name);
        }
        if (password == null || password.length() < 6) {
            throw BizException.badRequest("口令长度不能少于 6 位");
        }
        String role = normalizeRole(roleCode);

        SysUser user = new SysUser();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        user.setRoleCode(role);
        user.setEnabled(enabled == null || enabled);
        userRepository.save(user);
        bootstrap.syncRoles(user);
        log.info("新建用户: {} ({})", name, role);
        return row(user);
    }

    /** password 传空表示不改口令; 不允许把自己降级或停用, 避免把系统锁死 */
    @Transactional
    public Map<String, Object> update(Integer id, String displayName, String roleCode,
                                      Boolean enabled, String password) {
        SysUser user = require(id);
        String self = roleGuard.current().username();
        String role = roleCode == null ? user.getRoleCode() : normalizeRole(roleCode);
        boolean nextEnabled = enabled == null ? Boolean.TRUE.equals(user.getEnabled()) : enabled;

        if (user.getUsername().equals(self) && (!AuthUser.ROLE_ADMIN.equals(role) || !nextEnabled)) {
            throw BizException.badRequest("不能停用或降级当前登录账号");
        }
        if (AuthUser.ROLE_ADMIN.equals(user.getRoleCode()) && !AuthUser.ROLE_ADMIN.equals(role)
                && userRepository.findByRoleCode(AuthUser.ROLE_ADMIN).size() <= 1) {
            throw BizException.badRequest("系统必须保留至少一个管理员账号");
        }

        userRepository.updateProfile(id,
                displayName == null || displayName.isBlank() ? user.getDisplayName() : displayName.trim(),
                role, nextEnabled);
        if (password != null && !password.isEmpty()) {
            if (password.length() < 6) {
                throw BizException.badRequest("口令长度不能少于 6 位");
            }
            userRepository.updatePassword(id, passwordEncoder.encode(password));
        }
        SysUser updated = require(id);
        bootstrap.syncRoles(updated);
        log.info("更新用户: {} role={} enabled={}", updated.getUsername(), role, nextEnabled);
        return row(updated);
    }

    @Transactional
    public void delete(Integer id) {
        SysUser user = require(id);
        if (user.getUsername().equals(roleGuard.current().username())) {
            throw BizException.badRequest("不能删除当前登录账号");
        }
        if (AuthUser.ROLE_ADMIN.equals(user.getRoleCode())
                && userRepository.findByRoleCode(AuthUser.ROLE_ADMIN).size() <= 1) {
            throw BizException.badRequest("系统必须保留至少一个管理员账号");
        }
        // sys_user_role.user_id 的外键是 ON DELETE CASCADE, 关联行会随之删除
        userRepository.delete(user);
        log.info("删除用户: {}", user.getUsername());
    }

    // ---------------- 内部 ----------------

    private SysUser require(Integer id) {
        return userRepository.findById(id).orElseThrow(() -> BizException.notFound("用户不存在: " + id));
    }

    private String normalizeRole(String roleCode) {
        String role = roleCode == null ? AuthUser.ROLE_VIEWER : roleCode.trim().toUpperCase();
        if (!ROLES.contains(role)) {
            throw BizException.badRequest("角色只能是 " + String.join(" / ", ROLES));
        }
        return role;
    }

    /** 行结构 = 展示字段 + 权限清单, 绝不含 password_hash */
    private Map<String, Object> row(SysUser user) {
        return Maps.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "display_name", user.getDisplayName(),
                "role_code", user.getRoleCode(),
                "enabled", user.getEnabled(),
                "permissions", bootstrap.permissionsOf(user.getRoleCode()),
                "last_login_at", DateTimeSerializers.fmt(user.getLastLoginAt()),
                "created_at", DateTimeSerializers.fmt(user.getCreatedAt()));
    }
}
