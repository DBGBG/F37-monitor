package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 设备健康评分 [B]: 主键即 device_id, 每设备一行覆盖更新 */
@Getter
@Setter
@Entity
@Table(name = "device_health")
public class DeviceHealth {

    @Id
    @Column(name = "device_id", length = 50)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 200)
    private String deviceName;

    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    @Column(name = "score", nullable = false)
    private Integer score;

    /** 优 / 良 / 中 / 差 */
    @Column(name = "grade", nullable = false, length = 10)
    private String grade;

    /** [{factor, deduction, detail}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors", columnDefinition = "json")
    private List<Map<String, Object>> factors;

    @Column(name = "summary", nullable = false, length = 300)
    private String summary;

    /** 分析轮次时间, 由 ProactiveEngineService 显式写入 (健康分未变化时也要前移, 供列表展示更新于) */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
