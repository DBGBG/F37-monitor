package com.moxa.monitor.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应信封: {"code":0,"message":"success","data":{...},"timestamp":"yyyy-MM-dd HH:mm:ss"}
 *
 * code 约定: 0 成功; 400xx 参数/业务错误; 401xx 未认证; 403xx 无权限; 404xx 资源不存在; 500xx 服务端错误.
 * 前端在 api/http.js 拦截器里统一按 code 弹提示, 业务代码只拿 data.
 */
public record Result<T>(int code, String message, T data, String timestamp) {

    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data, LocalDateTime.now().format(TS));
    }

    /** 无 data 的成功响应 (Python 侧部分接口返回 {"status":"success", ...}, 由调用方放进 data) */
    public static Result<Object> ok() {
        return ok(new java.util.LinkedHashMap<>());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, LocalDateTime.now().format(TS));
    }

    @JsonProperty("success")
    public boolean success() {
        return code == 0;
    }
}
