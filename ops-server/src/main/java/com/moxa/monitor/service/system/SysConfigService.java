package com.moxa.monitor.service.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.SysConfig;
import com.moxa.monitor.repo.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务参数配置服务: sys_config 表的唯一出入口.
 * 承载从 settings.yaml 迁出的键 (alert_rules / alert_settings / proactive_params /
 * notification_config / retention_policy / inspection_defaults / ssh_defaults / vlan_plan),
 * 前端可在线修改, 各服务每次读取走 15 秒 TTL 缓存, 修改后立即失效.
 */
@Service
public class SysConfigService {

    private static final Logger log = LoggerFactory.getLogger(SysConfigService.class);

    public static final String ALERT_RULES = "alert_rules";
    public static final String ALERT_SETTINGS = "alert_settings";
    public static final String PROACTIVE_PARAMS = "proactive_params";
    public static final String NOTIFICATION_CONFIG = "notification_config";
    public static final String RETENTION_POLICY = "retention_policy";
    public static final String INSPECTION_DEFAULTS = "inspection_defaults";
    public static final String SSH_DEFAULTS = "ssh_defaults";
    public static final String VLAN_PLAN = "vlan_plan";

    private static final long TTL_MS = 15_000L;

    private final SysConfigRepository repository;
    private final ObjectMapper objectMapper;

    /** key -> 解析后的 JSON 树; 整体替换以保证读端无锁安全 */
    private volatile Map<String, JsonNode> cache = Map.of();
    private volatile long cacheLoadedAt = 0L;

    public SysConfigService(SysConfigRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // ---------------- 读取 ----------------

    /** 整键取值, 缺失返回 NullNode (调用方按 .path(...) 继续下钻) */
    public JsonNode node(String key) {
        JsonNode value = cache().get(key);
        return value == null ? NullNode.getInstance() : value;
    }

    /**
     * 点号路径取值, 例如 "proactive_params.anomaly_k" / "notification_config.email.enabled".
     * 第一段是 config_key, 其余是 JSON 内部路径.
     */
    public JsonNode path(String dotted) {
        int dot = dotted.indexOf('.');
        if (dot < 0) {
            return node(dotted);
        }
        JsonNode current = node(dotted.substring(0, dot));
        for (String segment : dotted.substring(dot + 1).split("\\.")) {
            current = current.path(segment);
        }
        return current;
    }

    public double number(String dotted, double defaultValue) {
        JsonNode n = path(dotted);
        return n.isNumber() ? n.asDouble() : defaultValue;
    }

    public int integer(String dotted, int defaultValue) {
        JsonNode n = path(dotted);
        return n.isNumber() ? n.asInt() : defaultValue;
    }

    public boolean bool(String dotted, boolean defaultValue) {
        JsonNode n = path(dotted);
        return n.isBoolean() ? n.asBoolean() : defaultValue;
    }

    public String text(String dotted, String defaultValue) {
        JsonNode n = path(dotted);
        return n.isTextual() ? n.asText() : defaultValue;
    }

    /** 对象键 -> Map (值仍是 Jackson 树, 由调用方按类型取) */
    public Map<String, JsonNode> children(String key) {
        JsonNode root = node(key);
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (root.isObject()) {
            root.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public List<Map<String, Object>> objectList(String key) {
        JsonNode root = node(key);
        List<Map<String, Object>> list = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(item -> list.add(toMap(item)));
        }
        return list;
    }

    public List<String> stringList(String dotted) {
        JsonNode n = path(dotted);
        List<String> list = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(item -> list.add(item.asText()));
        }
        return list;
    }

    /** 全量配置键值, 给前端系统设置页 */
    public Map<String, Object> values() {
        Map<String, Object> result = new LinkedHashMap<>();
        cache().forEach((key, node) -> result.put(key, node));
        return result;
    }

    /** 全量配置条目 [{config_key, value, description, updated_at}] */
    public List<Map<String, Object>> allItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (SysConfig config : repository.findAllByOrderByIdAsc()) {
            items.add(Maps.of(
                    "config_key", config.getConfigKey(),
                    "value", parse(config.getConfigValue()),
                    "description", config.getDescription(),
                    "updated_at", DateTimeSerializers.fmt(config.getUpdatedAt())));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    // ---------------- 写入 ----------------

    /** 覆盖写某个键 (value 可以是 Map / List / POJO) */
    @Transactional
    public void put(String key, Object value, String description) {
        try {
            String json = objectMapper.writeValueAsString(value);
            SysConfig existing = repository.findByConfigKey(key).orElse(null);
            if (existing == null) {
                SysConfig config = new SysConfig();
                config.setConfigKey(key);
                config.setConfigValue(json);
                config.setDescription(description == null ? "" : description);
                repository.save(config);
            } else {
                repository.updateValue(key, json, description == null ? existing.getDescription() : description);
            }
            evict();
        } catch (Exception ex) {
            throw BizException.badRequest("配置值无法序列化为 JSON: " + ex.getMessage());
        }
    }

    /** 修改单个键后立即失效缓存, 让告警/分析引擎下一轮就生效 */
    public void evict() {
        cacheLoadedAt = 0L;
        cache = Map.of();
    }

    // ---------------- 内部 ----------------

    private Map<String, JsonNode> cache() {
        refreshIfStale();
        return cache;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - cacheLoadedAt < TTL_MS) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - cacheLoadedAt < TTL_MS) {
                return;
            }
            try {
                Map<String, JsonNode> loaded = new LinkedHashMap<>();
                for (SysConfig config : repository.findAllByOrderByIdAsc()) {
                    loaded.put(config.getConfigKey(), parse(config.getConfigValue()));
                }
                cache = Map.copyOf(loaded);
            } catch (Exception ex) {
                // 配置表读取失败时保持旧缓存, 不能因为一次抖动就停掉告警引擎
                log.warn("读取 sys_config 失败, 沿用上一份配置: {}", ex.getMessage());
            }
            cacheLoadedAt = System.currentTimeMillis();
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("sys_config JSON 解析失败: {}", ex.getMessage());
            return NullNode.getInstance();
        }
    }
}
