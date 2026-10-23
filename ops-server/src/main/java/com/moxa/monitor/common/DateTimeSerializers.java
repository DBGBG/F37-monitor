package com.moxa.monitor.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 时间序列化约定: 全链路 Asia/Shanghai, 响应体统一输出 "yyyy-MM-dd HH:mm:ss"
 * (等价于 Python str(datetime) 去掉微秒, 前端展示无差异);
 * 入参同时接受 "yyyy-MM-dd HH:mm:ss"、ISO8601("2026-01-02T03:04:05")、纯日期("2026-01-02", 补 00:00:00).
 */
public final class DateTimeSerializers {

    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 入参容忍格式, 按顺序尝试 (含 HTML datetime-local 控件的不带秒写法) */
    private static final List<DateTimeFormatter> INPUT_FORMATS = List.of(
            DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private DateTimeSerializers() {
    }

    /** 注册为 Spring Bean 后由 Boot 自动挂到全局 ObjectMapper */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("ops-datetime");
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer());
        module.addSerializer(LocalDate.class, new LocalDateSerializer());
        return module;
    }

    public static String fmt(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME);
    }

    public static String fmt(LocalDate value) {
        return value == null ? null : value.format(DATE);
    }

    /** 宽松解析, 解析失败抛业务异常 */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            throw BizException.badRequest("时间参数不能为空");
        }
        String s = text.trim();
        for (DateTimeFormatter f : INPUT_FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignore) {
                // 换下一个格式
            }
        }
        try {
            return LocalDate.parse(s, DATE).atStartOfDay();
        } catch (Exception ignore) {
            // 落到统一报错
        }
        throw BizException.badRequest("时间格式无法识别: " + text + " (期望 yyyy-MM-dd HH:mm:ss)");
    }

    public static LocalDateTime parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return parse(text);
        } catch (BizException e) {
            return null;
        }
    }

    /** 把 hours 这类相对时间窗口换算为起点 */
    public static LocalDateTime startFromHours(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    static final class LocalDateTimeSerializer extends StdSerializer<LocalDateTime> {
        LocalDateTimeSerializer() {
            super(LocalDateTime.class);
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.format(DATE_TIME));
        }
    }

    static final class LocalDateSerializer extends StdSerializer<LocalDate> {
        LocalDateSerializer() {
            super(LocalDate.class);
        }

        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.format(DATE));
        }
    }

    static final class LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getValueAsString();
            return (text == null || text.isBlank()) ? null : parse(text);
        }
    }
}
