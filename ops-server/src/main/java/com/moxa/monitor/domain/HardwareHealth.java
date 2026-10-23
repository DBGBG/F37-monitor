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

/** 交换机硬件健康时序 [C]: 采集层每 10 分钟追加, Java 只读 (温度/电源/风扇图表与告警取值) */
@Getter
@Setter
@Entity
@Table(name = "hardware_health")
public class HardwareHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "switch_id", nullable = false, length = 50)
    private String switchId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /** 0=未知 1=正常 2=故障 */
    @Column(name = "power1_status", nullable = false)
    private Integer power1Status;

    @Column(name = "power2_status", nullable = false)
    private Integer power2Status;

    @Column(name = "power1_voltage", nullable = false)
    private Float power1Voltage;

    @Column(name = "power2_voltage", nullable = false)
    private Float power2Voltage;

    @Column(name = "temperature", nullable = false)
    private Float temperature;

    /** 0=正常 1=告警 */
    @Column(name = "temperature_alarm", nullable = false)
    private Integer temperatureAlarm;

    @Column(name = "fan_status", nullable = false)
    private Integer fanStatus;

    @Column(name = "fan_speed", nullable = false)
    private Integer fanSpeed;

    @Column(name = "cpu_usage", nullable = false)
    private Float cpuUsage;

    @Column(name = "memory_usage", nullable = false)
    private Float memoryUsage;

    @Column(name = "memory_total_kb", nullable = false)
    private Integer memoryTotalKb;

    @Column(name = "memory_used_kb", nullable = false)
    private Integer memoryUsedKb;

    /** normal / warning / critical */
    @Column(name = "overall_status", nullable = false, length = 20)
    private String overallStatus;
}
