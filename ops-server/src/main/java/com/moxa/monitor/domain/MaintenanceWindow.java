package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/** 计划维护窗口 [B]: 窗口内的告警一律抑制不落库 */
@Getter
@Setter
@Entity
@Table(name = "maintenance_windows")
public class MaintenanceWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** all / switch / server */
    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    /** 指定设备ID列表, 空表示该类型全部设备 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_ids", columnDefinition = "json")
    private List<String> deviceIds;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
