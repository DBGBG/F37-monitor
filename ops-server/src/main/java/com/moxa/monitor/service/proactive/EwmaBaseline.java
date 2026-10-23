package com.moxa.monitor.service.proactive;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * EWMA 动态基线, 移植自 legacy proactive/baseline.py.
 *
 * 为什么用 EWMA:
 * - 工业设备指标 (温度/CPU/误码) 具有慢漂移特性, 需要一条随时间平滑跟随的"正常基线";
 * - 无需保存全部历史, 常数内存增量更新, 天然适应设备随季节/负载的缓慢变化;
 * - 配合方差可得到 z 分数: |x - mean| / std, 用于与固定阈值互补的异常判定.
 */
public final class EwmaBaseline {

    /** 与迁移前 math.sqrt 一致的 double 精度状态 (落库时再降为 FLOAT) */
    public record State(double mean, double variance, int count) {

        /** 方差可能因浮点累积出现极小负值, 下限兜到 eps 避免除零 */
        public double std() {
            return Math.max(Math.sqrt(Math.max(variance, 0D)), 1e-9);
        }
    }

    /** 判定结果: 是否异常 / 绝对偏离 / z 分数 */
    public record Verdict(boolean hit, double absDeviation, double zScore) {

        public static final Verdict MISS = new Verdict(false, 0D, 0D);
    }

    private EwmaBaseline() {
    }

    /**
     * 用新样本 x 更新 EWMA 均值与方差 (RiskMetrics 形式, 数值稳定).
     * count<=0 时首个样本直接作为基线起点, 方差 0.
     */
    public static State update(State state, double x, double alpha) {
        if (state.count() <= 0) {
            return new State(x, 0D, 1);
        }
        double delta = x - state.mean();
        double mean = state.mean() + alpha * delta;
        double variance = (1 - alpha) * (state.variance() + alpha * delta * delta);
        return new State(mean, variance, state.count() + 1);
    }

    /**
     * 判定样本 x 是否偏离基线, 需同时满足: 样本数足够 + 方向匹配 + 绝对偏离达标 + z 分数达标.
     * 四者叠加是为了抑制"小抖动被高灵敏度放大"的误报.
     *
     * @param metricCfg sys_config.proactive_params.metrics.{metric} 那一段
     */
    public static Verdict evaluate(JsonNode metricCfg, State state, double x, double k, int minSamples) {
        if (state.count() < minSamples) {
            return Verdict.MISS;
        }
        // 迁移前 direction 只识别 up/down, 其他值等同于"双向都算"; 这里保持同一语义
        String direction = metricCfg.path("direction").asText("up");
        double dev = x - state.mean();
        if ("up".equals(direction) && dev <= 0) {
            return Verdict.MISS;
        }
        if ("down".equals(direction) && dev >= 0) {
            return Verdict.MISS;
        }
        double absDev = Math.abs(dev);
        if (absDev < minDeviation(metricCfg)) {
            return Verdict.MISS;
        }
        double z = absDev / state.std();
        if (z < k) {
            return Verdict.MISS;
        }
        // z 分数截断到 1000: 方差趋近 0 时 z 会爆炸, 前端展示与排序都不需要真实值
        return new Verdict(true, absDev, Math.min(z, 1000D));
    }

    /** min_deviation 缺失或为 null 时按 0 处理 (对齐 Python 的 `or 0`) */
    public static double minDeviation(JsonNode metricCfg) {
        JsonNode node = metricCfg == null ? null : metricCfg.path("min_deviation");
        return node == null || !node.isNumber() ? 0D : node.asDouble();
    }
}
