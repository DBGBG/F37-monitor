package com.moxa.monitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxa.monitor.security.JwtAuthFilter;
import com.moxa.monitor.security.SecurityErrorResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置: 无状态 JWT + 角色白名单.
 * 除 /api/auth/login 与静态资源外, /api/** 一律要求登录;
 * 更细的 ADMIN/OPERATOR 权限在 Controller 内用 RoleGuard 显式判定.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // 其余为前端 SPA 静态产物 (index.html / assets / favicon 等), 由浏览器直接获取
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, resp, authException) ->
                                SecurityErrorResponses.write(objectMapper, req, resp, 401, 40101, "未登录或登录已过期"))
                        .accessDeniedHandler((req, resp, deniedException) ->
                                SecurityErrorResponses.write(objectMapper, req, resp, 403, 40301, "当前账号无该操作权限")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
