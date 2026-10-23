package com.moxa.monitor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxa.monitor.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证 / 无权限时输出统一信封, 而不是 Spring Security 默认的空体 401,
 * 前端 api/http.js 才能像处理业务错误一样弹出 message.
 */
public final class SecurityErrorResponses {

    private SecurityErrorResponses() {
    }

    public static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse resp,
                            int httpStatus, int code, String message) throws IOException {
        resp.setStatus(httpStatus);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // /api/stream 是 SSE, 出错时也要退回 JSON
        resp.getWriter().write(mapper.writeValueAsString(Result.fail(code, message)));
    }
}
