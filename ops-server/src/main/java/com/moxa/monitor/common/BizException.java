package com.moxa.monitor.common;

/**
 * 业务异常. code 约定与 Result 信封一致:
 * 400xx 参数/业务错误, 401xx 未认证, 403xx 无权限, 404xx 资源不存在, 500xx 服务端错误.
 * 迁移前 Python 返回 {"error":"设备不存在"} + HTTP 404, 现在统一为 code=40401 + message.
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** code 前三位即 HTTP 状态码 (40401 -> 404) */
    public int httpStatus() {
        int status = code / 100;
        return (status >= 400 && status <= 599) ? status : 500;
    }

    public static BizException badRequest(String message) {
        return new BizException(40001, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(40101, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(40301, message);
    }

    public static BizException notFound(String message) {
        return new BizException(40401, message);
    }

    public static BizException internal(String message) {
        return new BizException(50001, message);
    }
}
