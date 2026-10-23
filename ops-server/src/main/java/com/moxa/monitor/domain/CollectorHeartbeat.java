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
import java.util.Map;

/** 采集层心跳 [C写/B读]: 业务层据此判定"采集层是否活着"并驱动前端实时标签 */
@Getter
@Setter
@Entity
@Table(name = "collector_heartbeats")
public class CollectorHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** collector / trap */
    @Column(name = "component", nullable = false, length = 30)
    private String component;

    /** status / traffic / hardware / server / process / discovery / startup */
    @Column(name = "phase", nullable = false, length = 30)
    private String phase;

    @Column(name = "total", nullable = false)
    private Integer total;

    @Column(name = "success", nullable = false)
    private Integer success;

    @Column(name = "errors", nullable = false)
    private Integer errors;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "last_beat_at", nullable = false)
    private LocalDateTime lastBeatAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "json")
    private Map<String, Object> detail;
}
