package com.moxa.monitor.service.proactive;

import com.fasterxml.jackson.databind.JsonNode;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.HardwareHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备健康评分, 移植自 legacy proactive/health.py.
 *
 * 把多维状态 (在线 / 电源冗余 / 温度 / 主动异常 / 活动告警) 综合成一个 0-100 的分数与等级,
 * 让运维人员一眼看出"全场哪些设备最需要先处理", 而不是在几十条告警里翻找.
 */
public final class HealthScoreService {

    /** 分档阈值 (与迁移前 GRADES 列表顺序一致, 从高到低匹配) */
    private static final int[][] GRADE_THRESHOLDS = {{90, 0}, {75, 1}, {60, 2}, {0, 3}};
    private static final String[] GRADES = {"优", "良", "中", "差"};

    /** 评分结果 */
    public record Result(int score, String grade, List<Map<String, Object>> factors, String summary) {
    }

    private HealthScoreService() {
    }

    public static String gradeOf(int score) {
        for (int[] entry : GRADE_THRESHOLDS) {
            if (score >= entry[0]) {
                return GRADES[entry[1]];
            }
        }
        return "差";
    }

    /**
     * @param deviceType         switch / server
     * @param hw                 交换机最新一条 HardwareHealth (可为 null)
     * @param openAnomalyCount   未闭环的主动异常数
     * @param alertCounts        该设备活动告警按级别计数 {"CRITICAL":n,"WARNING":m}
     * @param weights            sys_config.proactive_params.health_weights
     */
    public static Result compute(String deviceType, boolean isOnline, HardwareHealth hw,
                                 int openAnomalyCount, Map<String, Integer> alertCounts, JsonNode weights) {
        // online 权重在迁移前就只声明未使用 (离线直接判 0 分), 这里不再保留
        int wPower = weight(weights, "power", 15);
        int wTemp = weight(weights, "temperature", 15);
        int wAnom = weight(weights, "anomalies", 20);
        int wAlert = weight(weights, "alerts", 20);

        // 离线直接判为最差 (设备不可用是最高优先级问题)
        if (!isOnline) {
            return new Result(0, "差",
                    List.of(Maps.of("factor", "在线状态", "deduction", 100, "detail", "设备离线")),
                    "设备离线");
        }

        int score = 100;
        List<Map<String, Object>> factors = new ArrayList<>();

        // 1. 电源冗余
        if ("switch".equals(deviceType) && hw != null) {
            List<String> failed = new ArrayList<>();
            if (Integer.valueOf(2).equals(hw.getPower1Status())) {
                failed.add("电源1");
            }
            if (Integer.valueOf(2).equals(hw.getPower2Status())) {
                failed.add("电源2");
            }
            if (!failed.isEmpty()) {
                // 双电源同时故障按全额扣分, 单电源按 60% (冗余仍在)
                int deduction = failed.size() >= 2 ? wPower : pyRound(wPower * 0.6);
                score -= deduction;
                factors.add(Maps.of("factor", "电源", "deduction", deduction,
                        "detail", String.join("、", failed) + "故障"));
            }

            // 2. 温度
            double temp = hw.getTemperature() == null ? 0D : hw.getTemperature().doubleValue();
            int tempAlarm = hw.getTemperatureAlarm() == null ? 0 : hw.getTemperatureAlarm();
            if (tempAlarm == 1) {
                score -= wTemp;
                factors.add(Maps.of("factor", "温度", "deduction", wTemp,
                        "detail", String.format("温度告警 (%.0f°C)", temp)));
            } else if (temp >= 60) {
                int deduction = pyRound(wTemp * 0.7);
                score -= deduction;
                factors.add(Maps.of("factor", "温度", "deduction", deduction,
                        "detail", String.format("温度偏高 %.0f°C", temp)));
            }
        }

        // 3. 未处理主动异常
        if (openAnomalyCount > 0) {
            int deduction = Math.min(wAnom, openAnomalyCount * 5);
            score -= deduction;
            factors.add(Maps.of("factor", "异常", "deduction", deduction,
                    "detail", openAnomalyCount + " 项主动异常待处理"));
        }

        // 4. 活动告警
        int critical = alertCounts == null ? 0 : alertCounts.getOrDefault("CRITICAL", 0);
        int warning = alertCounts == null ? 0 : alertCounts.getOrDefault("WARNING", 0);
        int alertDeduction = Math.min(wAlert, critical * 10 + warning * 5);
        if (alertDeduction > 0) {
            score -= alertDeduction;
            factors.add(Maps.of("factor", "告警", "deduction", alertDeduction,
                    "detail", String.format("严重告警 %d 条, 警告 %d 条", critical, warning)));
        }

        score = Math.max(0, Math.min(100, score));
        String summary = factors.isEmpty() ? "运行正常"
                : String.join("、", factors.stream().map(f -> String.valueOf(f.get("detail"))).toList());
        return new Result(score, gradeOf(score), factors, summary);
    }

    private static int weight(JsonNode weights, String key, int defaultValue) {
        if (weights == null) {
            return defaultValue;
        }
        JsonNode node = weights.path(key);
        return node.isNumber() ? node.asInt() : defaultValue;
    }

    /** Python 的 round(x) 是"四舍六入五取偶", Java 的 Math.round 是"五入", 这里对齐前者 */
    private static int pyRound(double value) {
        return (int) Math.rint(value);
    }
}
