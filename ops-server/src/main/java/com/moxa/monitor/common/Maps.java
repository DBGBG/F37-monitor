package com.moxa.monitor.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有序 Map 构造器.
 * 聚合类响应 (overview / stats / snapshot) 用 LinkedHashMap 逐字段拼装,
 * 既保持与迁移前 Python dict 相同的键顺序, 也避免把 JPA 实体直接序列化出去.
 */
public final class Maps {

    private Maps() {
    }

    /** Maps.of("total", 10, "online", 8) —— 参数必须成对出现 */
    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Maps.of 需要成对的 key/value, 收到 " + keyValues.length + " 个参数");
        }
        Map<String, Object> map = new LinkedHashMap<>(keyValues.length / 2 * 2 + 4);
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 链式构造, 便于条件放值 */
    public static final class Builder {
        private final Map<String, Object> map = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        /** value 为 null 时写 null 键, 与 Python 响应保留 null 字段的行为一致 */
        public Builder putIfAbsentNull(String key) {
            map.putIfAbsent(key, null);
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }
}
