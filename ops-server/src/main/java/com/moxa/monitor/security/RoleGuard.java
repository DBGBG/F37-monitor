package com.moxa.monitor.security;

import com.moxa.monitor.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 角色判定入口: ADMIN > OPERATOR > VIEWER.
 * 在 Controller 内显式调用 (不使用 @PreAuthorize), 便于把权限错误映射成统一信封的 403xx.
 */
@Component
public class RoleGuard {

    public AuthUser currentOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return user;
        }
        return null;
    }

    public AuthUser current() {
        AuthUser user = currentOrNull();
        if (user == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return user;
    }

    /** 告警确认/点检执行等写入操作记录操作人, 取代迁移前端里硬编码的 'admin' */
    public String currentUsername() {
        return current().username();
    }

    /** 操作人入参为空时回落到登录用户 */
    public String usernameOr(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        return currentUsername();
    }

    public AuthUser requireOperator() {
        AuthUser user = current();
        if (!user.canWrite()) {
            throw BizException.forbidden("该操作需要运维或管理员权限");
        }
        return user;
    }

    public AuthUser requireAdmin() {
        AuthUser user = current();
        if (!user.isAdmin()) {
            throw BizException.forbidden("该操作需要管理员权限");
        }
        return user;
    }
}
