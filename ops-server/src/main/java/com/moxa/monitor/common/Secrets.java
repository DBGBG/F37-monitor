package com.moxa.monitor.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭据脱敏工具.
 * 方案约束: snmp_config.community / ssh_config.password / monitoring_config.password
 * 不得出现在任何接口响应里; 编辑表单回显时用掩码占位, 提交时掩码表示"沿用原值".
 */
public final class Secrets {

    /** 前端回显用的掩码; 提交时带掩码表示不修改该字段 */
    public static final String MASK = "********";

    private static final String[] SECRET_KEYS = {"community", "password"};

    private Secrets() {
    }

    /** 返回副本, 敏感字段替换成掩码 (值为空时保持为空, 表示设备尚未配置该凭据) */
    public static Map<String, Object> mask(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(config);
        for (String key : SECRET_KEYS) {
            Object value = copy.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                copy.put(key, MASK);
            }
        }
        return copy;
    }

    /**
     * 提交的凭据里, 掩码 / 缺失 / 空串的敏感项沿用库里已有的值 ——
     * 前端表单会把掩码原样回传, 不能因此把 community/password 清掉.
     */
    public static Map<String, Object> merge(Map<String, Object> stored, Map<String, Object> incoming) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (incoming != null) {
            result.putAll(incoming);
        }
        for (String key : SECRET_KEYS) {
            Object submitted = result.get(key);
            boolean keepExisting = submitted == null
                    || MASK.equals(String.valueOf(submitted))
                    || String.valueOf(submitted).isBlank();
            if (keepExisting) {
                Object existing = stored == null ? null : stored.get(key);
                if (existing == null) {
                    result.remove(key);
                } else {
                    result.put(key, existing);
                }
            }
        }
        return result;
    }
}
