package com.moxa.monitor.service.system;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.SysUser;
import com.moxa.monitor.repo.SysUserRepository;
import com.moxa.monitor.security.AuthUser;
import com.moxa.monitor.security.JwtService;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.security.UserBootstrapInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录与当前用户信息.
 * 无状态 JWT: logout 只回成功, 由前端清 localStorage; token 到期自然失效.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RoleGuard roleGuard;
    private final UserBootstrapInitializer bootstrap;

    public AuthService(SysUserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, RoleGuard roleGuard, UserBootstrapInitializer bootstrap) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.roleGuard = roleGuard;
        this.bootstrap = bootstrap;
    }

    /** POST /api/auth/login —— 用户名或口令错误都用同一句提示, 不暴露账号是否存在 */
    @Transactional
    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw BizException.badRequest("用户名与口令不能为空");
        }
        SysUser user = userRepository.findByUsername(username.trim()).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("登录失败: username={}", username);
            throw BizException.unauthorized("用户名或口令错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw BizException.forbidden("该账号已被停用");
        }
        userRepository.touchLogin(user.getId(), LocalDateTime.now());

        AuthUser principal = new AuthUser(user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRoleCode());
        String token = jwtService.issue(principal);
        log.info("用户登录: {} ({})", user.getUsername(), user.getRoleCode());
        return Maps.of(
                "token", token,
                "token_type", "Bearer",
                "expires_in", jwtService.expireMinutes() * 60,
                "user", profileWithPermissions(user));
    }

    /** GET /api/auth/me */
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        AuthUser current = roleGuard.current();
        SysUser user = userRepository.findByUsername(current.username())
                .orElseThrow(() -> BizException.unauthorized("账号不存在或已被删除"));
        return profileWithPermissions(user);
    }

    /** POST /api/auth/logout —— 同时改密后前端也应调用它换取新 token */
    public Map<String, Object> logout() {
        AuthUser current = roleGuard.currentOrNull();
        if (current != null) {
            log.info("用户登出: {}", current.username());
        }
        return Maps.of("message", "已退出登录");
    }

    /** 修改自己的口令 */
    @Transactional
    public Map<String, Object> changePassword(String oldPassword, String newPassword) {
        AuthUser current = roleGuard.current();
        SysUser user = userRepository.findByUsername(current.username())
                .orElseThrow(() -> BizException.unauthorized("账号不存在"));
        if (!passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash())) {
            throw BizException.badRequest("原口令不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.badRequest("新口令长度不能少于 6 位");
        }
        userRepository.updatePassword(user.getId(), passwordEncoder.encode(newPassword));
        return Maps.of("message", "口令已修改, 请重新登录");
    }

    /** 用户展示信息 (绝不含 password_hash) */
    public static Map<String, Object> profileOf(SysUser user) {
        return Maps.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "display_name", user.getDisplayName(),
                "role_code", user.getRoleCode(),
                "enabled", user.getEnabled(),
                "last_login_at", DateTimeSerializers.fmt(user.getLastLoginAt()),
                "created_at", DateTimeSerializers.fmt(user.getCreatedAt()));
    }

    /** 登录态响应额外带权限清单, 前端 stores/auth.js 据此控制菜单与按钮 */
    private Map<String, Object> profileWithPermissions(SysUser user) {
        Map<String, Object> profile = new LinkedHashMap<>(profileOf(user));
        profile.put("permissions", bootstrap.permissionsOf(user.getRoleCode()));
        return profile;
    }

    /** 角色权限清单, 登录与 me 接口都带上, 前端据此控制按钮显隐 */
    public List<String> permissionsOf(String roleCode) {
        return bootstrap.permissionsOf(roleCode);
    }
}
