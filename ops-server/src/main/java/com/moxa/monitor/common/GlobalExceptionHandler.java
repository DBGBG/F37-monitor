package com.moxa.monitor.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 全局异常处理: 任何异常都转成统一信封, HTTP 状态码与 code 前三位对齐.
 * 前端 api/http.js 对非 2xx 也读取 body.message 弹窗, 因此错误语义不丢失.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Object>> handleBiz(BizException ex, HttpServletRequest req) {
        log.warn("业务异常 {} {} code={} msg={}", req.getMethod(), req.getRequestURI(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.httpStatus()).body(Result.fail(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(400).body(Result.fail(40001, msg.isEmpty() ? "参数校验失败" : msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(400).body(Result.fail(40001, "缺少必填参数: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(400)
                .body(Result.fail(40001, "参数类型不正确: " + ex.getName() + "=" + ex.getValue()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(400).body(Result.fail(40001, "请求体格式错误, 需为合法 JSON"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Result.fail(40001, ex.getMessage() == null ? "参数错误" : ex.getMessage()));
    }

    /**
     * SSE 客户端断开 (关掉页面 / 网络中断) 时 Tomcat 报这个异常: 连接已经不可写,
     * 再写信封没有意义, 降为 DEBUG 以免日志被无关的 ERROR 淹没.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex) {
        log.debug("异步/SSE 连接已由客户端关闭: {}", ex.getMessage());
    }

    /**
     * 客户端断开也可能以未包装的 IOException 抛出 (Tomcat 的 "Connection reset by peer"),
     * 与 AsyncRequestNotUsableException 同级处理: 链路已不可写, 记 ERROR 只会淹没真正的故障.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Object>> handleIo(IOException ex, HttpServletRequest req) {
        if (isClientAbort(ex)) {
            log.debug("客户端已断开 {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
            return null;
        }
        log.error("IO 异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(Result.fail(50001, "服务端错误: " + ex.getMessage()));
    }

    /**
     * 路径不存在: Spring 6 会把未命中的请求交给静态资源处理器, 找不到时抛
     * NoResourceFoundException, 若落到 handleOther 会变成 500 + "服务端错误",
     * 而正确的语义是 404 (前端才能区分"接口写错"和"后端挂了").
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex, HttpServletRequest req) {
        log.debug("接口或资源不存在: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(404)
                .body(Result.fail(40400, "接口或资源不存在: " + req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleOther(Exception ex, HttpServletRequest req) {
        if (isClientAbort(ex)) {
            log.debug("客户端已断开 {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
            return null;
        }
        // Spring MVC 自身的请求语义错误 (405 方法不支持 / 406 / 415 / 409 …) 都会带一个
        // 已经确定的 HTTP 状态码; 若一律当 500 处理, 前端就分不清"接口调用方式写错了"
        // 和"后端真的挂了" (实测 GET /api/servers 这种"路径存在但没有该方法的映射"
        // 会被判成 500), 所以这里按原始状态码回信封.
        if (ex instanceof ErrorResponse err) {
            int status = err.getStatusCode().value();
            log.debug("请求语义错误 {} {} -> {} ({})", req.getMethod(), req.getRequestURI(), status, ex.getMessage());
            return ResponseEntity.status(status).body(Result.fail(status * 100, describeHttp(status)));
        }
        log.error("未处理异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return ResponseEntity.status(500).body(Result.fail(50001, "服务端错误: " + msg));
    }

    /**
     * 接口层的权限拒绝 (Security 过滤器链上的拒绝不会走到这里, 只有控制器内抛出的才会):
     * 给 403 信封, 前端的 authExpired 判定才能生效.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Result<Object>> handleDenied(AuthorizationDeniedException ex, HttpServletRequest req) {
        log.debug("无权访问 {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(403).body(Result.fail(40300, "无权访问该接口"));
    }

    /** 4xx/5xx 的中文描述, 不往外回异常原文 (避免泄露实现细节) */
    private static String describeHttp(int status) {
        return switch (status) {
            case 400 -> "请求参数不合法";
            case 405 -> "该接口不支持此请求方法";
            case 406 -> "不支持所请求的响应格式";
            case 409 -> "请求与当前资源状态冲突";
            case 415 -> "不支持所提交的 Content-Type";
            default -> "请求不被接受: HTTP " + status;
        };
    }

    /** 异常链上任意一处是"对端已关闭连接"就按断线处理, 不当服务端故障 */
    private static boolean isClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase(Locale.ROOT);
            // Windows 下 WSAECONNABORTED 会本地化成中文, 不能只按英文关键字判
            if (lower.contains("connection reset") || lower.contains("broken pipe")
                    || lower.contains("connection abort") || lower.contains("software caused connection abort")
                    || msg.contains("中止了一个已建立的连接") || msg.contains("远程主机强迫关闭")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(FieldError error) {
        return error.getField() + " " + (error.getDefaultMessage() == null ? "格式不正确" : error.getDefaultMessage());
    }
}
