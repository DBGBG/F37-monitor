package com.moxa.monitor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 Authorization: Bearer <token> 还原登录态.
 * SSE 场景 (EventSource 无法自定义请求头) 额外允许 GET 请求用 ?token= 传递.
 * token 缺失或失效时不在此处报错, 交由 SecurityConfig 的 entryPoint 统一返回 401 信封.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** 无需携带 token 的路径 */
    private static final List<RequestMatcher> ANONYMOUS = List.of(
            new AntPathRequestMatcher("/api/auth/login"),
            new AntPathRequestMatcher("/api/health"));

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE (SseEmitter) 完成时会触发一次 ASYNC 派发, 默认实现会跳过本过滤器,
        // 导致该次派发没有登录态而被 AuthorizationFilter 判为 AccessDenied;
        // 此时响应流已提交, 异常无法转成 401/403 信封, 只能落到容器里打 500 堆栈.
        // 本过滤器只在上下文为空时写入认证信息, 重复执行是幂等的.
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null
                && !CorsUtils.isPreFlightRequest(request)
                && !isAnonymousPath(request)) {
            AuthUser user = jwtService.parse(resolveToken(request));
            if (user != null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.roleCode())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAnonymousPath(HttpServletRequest request) {
        return ANONYMOUS.stream().anyMatch(m -> m.matches(request));
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        // 只在 GET 上读查询参数, 避免表单类 POST 的请求体被提前消费
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String token = request.getParameter("token");
            if (token != null && !token.isBlank()) {
                return token.trim();
            }
        }
        return null;
    }
}
