package com.moxa.monitor.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数值取舍工具, 对齐迁移前 Python 的 round(value, n) 输出精度
 * (前端图表与表格按 2 位小数展示 bps / 百分比 / 温度).
 */
public final class RoundUtil {

    private RoundUtil() {
    }

    public static Double round(Double value, int scale) {
        return value == null ? null : round(value.doubleValue(), scale);
    }

    public static Double round(Float value, int scale) {
        return value == null ? null : round(value.doubleValue(), scale);
    }

    public static double round(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0D;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** 保留 2 位小数, 最常用的展示精度 */
    public static Double r2(Double value) {
        return round(value, 2);
    }

    public static double r2(double value) {
        return round(value, 2);
    }

    /** part / total * 100, total<=0 返回 0 */
    public static double percent(long part, long total) {
        return percent(part, total, 2);
    }

    /** 迁移前的在线率一律 round(x, 1), 这里保留精度可选项 */
    public static double percent(long part, long total, int scale) {
        if (total <= 0) {
            return 0D;
        }
        return round(part * 100.0 / total, scale);
    }

    /** 安全除法, 分母<=0 返回 0 */
    public static double divide(double numerator, double denominator, int scale) {
        if (denominator == 0D) {
            return 0D;
        }
        return round(numerator / denominator, scale);
    }

    /** 聚合查询 (COUNT / SUM) 返回的列类型不确定 (Long / BigInteger / Double), 统一按 Number 取 */
    public static long safeLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public static int safeInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static double safeDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }
}
