package com.moxa.monitor.service.alert;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.AlertRecord;

import java.util.Map;

/**
 * 告警记录的响应视图.
 * 绝不直接序列化 AlertRecord 实体: 表里有 id / resolved_by / notify_channels / updated_at
 * 等迁移前响应中没有的列, 一律在此显式挑字段.
 */
public final class AlertView {

    private AlertView() {
    }

    /** GET /api/alerts/list 的行结构, 与迁移前逐键对齐 */
    public static Map<String, Object> of(AlertRecord a) {
        return Maps.of(
                "alert_id", a.getAlertId(),
                "device_id", a.getDeviceId(),
                "device_name", a.getDeviceName(),
                "device_type", a.getDeviceType(),
                "rule_name", a.getRuleName(),
                "severity", a.getSeverity(),
                "title", a.getTitle(),
                "description", a.getDescription(),
                "metric_value", a.getMetricValue(),
                "threshold", a.getThreshold(),
                "status", a.getStatus(),
                "acknowledged_by", a.getAcknowledgedBy(),
                "notified", a.getNotified(),
                "triggered_at", DateTimeSerializers.fmt(a.getTriggeredAt()),
                "resolved_at", DateTimeSerializers.fmt(a.getResolvedAt()));
    }

    /** 总览卡片的精简结构 (只取展示必需的 6 个字段) */
    public static Map<String, Object> brief(AlertRecord a) {
        return Maps.of(
                "alert_id", a.getAlertId(),
                "device_id", a.getDeviceId(),
                "device_name", a.getDeviceName(),
                "severity", a.getSeverity(),
                "title", a.getTitle(),
                "triggered_at", DateTimeSerializers.fmt(a.getTriggeredAt()));
    }
}
