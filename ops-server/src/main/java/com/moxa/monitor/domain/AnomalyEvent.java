package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 主动运维异常事件 [B]: 生命周期 open / acknowledged / resolved (含自动恢复) */
@Getter
@Setter
@Entity
@Table(name = "anomaly_events")
public class AnomalyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 200)
    private String deviceName;

    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    @Column(name = "metric", nullable = false, length = 30)
    private String metric;

    /** WARNING / CRITICAL */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "value", nullable = false)
    private Float value;

    @Column(name = "baseline", nullable = false)
    private Float baseline;

    @Column(name = "deviation", nullable = false)
    private Float deviation;

    /** z 分数 */
    @Column(name = "score", nullable = false)
    private Float score;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "acknowledged_by", nullable = false, length = 100)
    private String acknowledgedBy;

    /** 是否已升级为 alert_records (rule = anomaly_{metric}) */
    @Column(name = "escalated", nullable = false)
    private Boolean escalated;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
