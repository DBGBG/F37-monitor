package com.moxa.monitor.service.proactive;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.AnomalyEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预防性维护建议, 移植自 legacy proactive/engine.py 的 build_recommendations.
 * 依据未闭环异常给出"下一步该做什么"的可执行动作, 按 priority 再按严重度排序.
 */
public final class RecommendationService {

    /** metric -> {动作, 说明, 优先级}; 与迁移前 advice 字典逐字一致 */
    private static final Map<String, String[]> ADVICE = new LinkedHashMap<>();

    static {
        ADVICE.put("temperature", new String[]{"检查散热与环境", "确认机房/机柜通风、风扇运转, 清理积尘", "high"});
        ADVICE.put("cpu", new String[]{"排查 CPU 高负载", "检查是否有广播风暴/环路或异常流量, 查看高负载进程", "high"});
        ADVICE.put("memory", new String[]{"排查内存占用", "确认无内存泄漏, 评估是否需要重启或扩容", "medium"});
        ADVICE.put("disk", new String[]{"清理磁盘空间", "清理日志/临时文件, 规划扩容, 避免写满导致服务中断", "high"});
        ADVICE.put("port_error", new String[]{"检查物理链路", "端口误码持续增长, 检查网线/光模块/水晶头与接口接触", "high"});
    }

    private static final String[] UNKNOWN = {"关注该指标", "持续观察该指标变化趋势", "low"};

    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("high", 0, "medium", 1, "low", 2);

    private RecommendationService() {
    }

    public static List<Map<String, Object>> build(List<AnomalyEvent> anomalies) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AnomalyEvent event : anomalies) {
            String[] advice = ADVICE.getOrDefault(event.getMetric(), UNKNOWN);
            items.add(Maps.of(
                    "device_id", event.getDeviceId(),
                    "device_name", event.getDeviceName(),
                    "device_type", event.getDeviceType(),
                    "metric", event.getMetric(),
                    "severity", event.getSeverity(),
                    "priority", advice[2],
                    "action", advice[0],
                    "detail", advice[1],
                    "value", event.getValue(),
                    "baseline", event.getBaseline(),
                    "unit", event.getUnit(),
                    "occurred_at", DateTimeSerializers.fmt(event.getOccurredAt())));
        }
        // 迁移前的排序键 (priority 升序, 严重度取负即降序); List.sort 与 Python sorted 同为稳定排序
        items.sort(Comparator
                .comparingInt((Map<String, Object> item) ->
                        PRIORITY_ORDER.getOrDefault(String.valueOf(item.get("priority")), 3))
                .thenComparing(item -> -severityRankOf(String.valueOf(item.get("severity")))));
        return items;
    }

    /** 迁移前的 SEVERITY_RANK: 未知级别按最低严重度 (get(severity, 0)) */
    static int severityRankOf(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 2;
            case "WARNING" -> 1;
            case "INFO" -> 0;
            default -> 0;
        };
    }
}
