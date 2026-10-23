package com.moxa.monitor.security;

import java.io.Serializable;

/**
 * 登录态主体, 由 JwtAuthFilter 从 token 还原后放入 SecurityContext.
 * 只带展示与鉴权需要的最小信息, 不含口令散列.
 */
public record AuthUser(Integer id, String username, String displayName, String roleCode) implements Serializable {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_VIEWER = "VIEWER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(roleCode);
    }

    public boolean isOperator() {
        return ROLE_OPERATOR.equalsIgnoreCase(roleCode);
    }

    /** OPERATOR 与 ADMIN 都视为可写 */
    public boolean canWrite() {
        return isAdmin() || isOperator();
    }
}
