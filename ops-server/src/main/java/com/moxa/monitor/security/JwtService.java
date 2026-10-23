package com.moxa.monitor.security;

import com.moxa.monitor.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验 (HS256).
 * 无状态: 业务层不落 session, 前端把 token 存 localStorage 并注入 Authorization 头.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final AppProperties props;
    private final SecretKey key;

    public JwtService(AppProperties props) {
        this.props = props;
        byte[] secret = props.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("ops.security.jwt-secret 长度不足 32 字节, 无法用于 HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issue(AuthUser user) {
        long expireMs = props.getSecurity().getJwtExpireMinutes() * 60_000L;
        Date now = new Date();
        return Jwts.builder()
                .subject(user.username())
                .issuer(props.getSecurity().getIssuer())
                .claim("uid", user.id())
                .claim("role", user.roleCode())
                .claim("name", user.displayName())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(key)
                .compact();
    }

    /** token 有效期(分钟), 供登录响应回给前端做临期提醒 */
    public int expireMinutes() {
        return props.getSecurity().getJwtExpireMinutes();
    }

    /** 校验失败返回 null, 由过滤器按未认证处理 */
    public AuthUser parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.getSecurity().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Integer uid = claims.get("uid", Integer.class);
            String role = claims.get("role", String.class);
            String name = claims.get("name", String.class);
            return new AuthUser(uid, claims.getSubject(), name == null ? claims.getSubject() : name,
                    role == null ? AuthUser.ROLE_VIEWER : role);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token 校验失败: {}", ex.getMessage());
            return null;
        }
    }
}
