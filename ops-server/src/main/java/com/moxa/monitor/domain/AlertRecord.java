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

/** 告警记录 [B]: 由 AlertEngineService 写入与维护, alert_id = md5(device_id + ":" + rule_name) 前 16 位 */
@Getter
@Setter
@Entity
@Table(name = "alert_records")
public class AlertRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "alert_id", nullable = false, length = 100)
    private String alertId;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 200)
    private String deviceName;

    /** switch / server */
    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    /** INFO / WARNING / CRITICAL */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "metric_value", nullable = false, length = 100)
    private String metricValue;

    @Column(name = "threshold", nullable = false, length = 100)
    private String threshold;

    /** active / acknowledged / resolved */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "acknowledged_by", nullable = false, length = 100)
    private String acknowledgedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", nullable = false, length = 100)
    private String resolvedBy;

    @Column(name = "notified", nullable = false)
    private Boolean notified;

    @Column(name = "notify_channels", nullable = false, length = 200)
    private String notifyChannels;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
