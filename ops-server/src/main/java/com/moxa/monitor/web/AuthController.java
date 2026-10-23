package com.moxa.monitor.web;

import com.moxa.monitor.common.Result;
import com.moxa.monitor.service.system.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录鉴权接口 (新增, 迁移前系统没有登录态).
 * /api/auth/login 是唯一免鉴权接口, 其余 /api/** 一律要求 Bearer token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录入参 */
    public record LoginRequest(String username, String password) {
    }

    /** 改密入参 */
    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    /** POST /api/auth/login —— 返回 {token, token_type, expires_in, user} */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody(required = false) LoginRequest request) {
        return Result.ok(authService.login(
                request == null ? null : request.username(),
                request == null ? null : request.password()));
    }

    /** GET /api/auth/me —— 当前登录用户与权限清单, 前端据此渲染菜单 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.ok(authService.me());
    }

    /** POST /api/auth/logout —— 无状态 JWT, 只记审计日志, 前端负责丢弃本地 token */
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout() {
        return Result.ok(authService.logout());
    }

    /** POST /api/auth/change-password */
    @PostMapping("/change-password")
    public Result<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest request) {
        return Result.ok(authService.changePassword(
                request == null ? null : request.oldPassword(),
                request == null ? null : request.newPassword()));
    }
}
