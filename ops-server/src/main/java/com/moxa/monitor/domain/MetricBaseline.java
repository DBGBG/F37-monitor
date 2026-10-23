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

/** 指标 EWMA 动态基线 [B]: (device_id, metric) 唯一 */
@Getter
@Setter
@Entity
@Table(name = "metric_baselines")
public class MetricBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    @Column(name = "metric", nullable = false, length = 30)
    private String metric;

    /** EWMA 均值 */
    @Column(name = "mean", nullable = false)
    private Float mean;

    /** EWMA 方差 */
    @Column(name = "variance", nullable = false)
    private Float variance;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    /** 物理列名为 latest_value: LAST_VALUE 是 MySQL 8 保留字, 未加反引号的 SQL 会直接语法报错 */
    @Column(name = "latest_value", nullable = false)
    private Float lastValue;

    /**
     * 分析轮次时间, 由 ProactiveEngineService 显式写入.
     * 采集样本去重依赖 "sample_time <= updated_at 则跳过", 必须每轮都前移,
     * 因此不能交给 MySQL 的 ON UPDATE CURRENT_TIMESTAMP (值未变化时不会触发).
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
