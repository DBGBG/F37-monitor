package com.moxa.monitor.service.proactive;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.AnomalyEvent;

import java.util.Map;

/**
 * 异常事件响应视图, 键顺序与 legacy api/proactive.py 的 _event_dict 完全一致.
 * 前端 proactive.js 按这些键渲染异常中心表格与详情弹窗.
 */
public final class AnomalyView {

    private AnomalyView() {
    }

    public static Map<String, Object> of(AnomalyEvent event) {
        return Maps.of(
                "id", event.getId(),
                "device_id", event.getDeviceId(),
                "device_name", event.getDeviceName(),
                "device_type", event.getDeviceType(),
                "metric", event.getMetric(),
                "severity", event.getSeverity(),
                "title", event.getTitle(),
                "description", event.getDescription(),
                "value", event.getValue(),
                "baseline", event.getBaseline(),
                "deviation", event.getDeviation(),
                // z 分数保留 2 位, 与迁移前 round(e.score, 2) 一致
                "score", RoundUtil.round(event.getScore(), 2),
                "unit", event.getUnit(),
                "status", event.getStatus(),
                "escalated", Boolean.TRUE.equals(event.getEscalated()),
                "occurred_at", DateTimeSerializers.fmt(event.getOccurredAt()),
                "last_seen_at", DateTimeSerializers.fmt(event.getLastSeenAt()),
                "resolved_at", DateTimeSerializers.fmt(event.getResolvedAt()),
                "acknowledged_by", event.getAcknowledgedBy());
    }
}
