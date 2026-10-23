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

/** 服务器资源时序 [C]: 采集层每 30 秒追加, Java 只读 */
@Getter
@Setter
@Entity
@Table(name = "server_metrics")
public class ServerMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "server_id", nullable = false, length = 50)
    private String serverId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "cpu_usage_percent", nullable = false)
    private Float cpuUsagePercent;

    /** 每核使用率 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cpu_per_core", columnDefinition = "json")
    private List<Double> cpuPerCore;

    @Column(name = "cpu_freq_mhz", nullable = false)
    private Float cpuFreqMhz;

    @Column(name = "memory_total_mb", nullable = false)
    private Long memoryTotalMb;

    @Column(name = "memory_used_mb", nullable = false)
    private Long memoryUsedMb;

    @Column(name = "memory_usage_percent", nullable = false)
    private Float memoryUsagePercent;

    @Column(name = "memory_available_mb", nullable = false)
    private Long memoryAvailableMb;

    @Column(name = "disk_total_gb", nullable = false)
    private Float diskTotalGb;

    @Column(name = "disk_used_gb", nullable = false)
    private Float diskUsedGb;

    @Column(name = "disk_usage_percent", nullable = false)
    private Float diskUsagePercent;

    @Column(name = "disk_read_bps", nullable = false)
    private Float diskReadBps;

    @Column(name = "disk_write_bps", nullable = false)
    private Float diskWriteBps;

    @Column(name = "net_bytes_sent", nullable = false)
    private Long netBytesSent;

    @Column(name = "net_bytes_recv", nullable = false)
    private Long netBytesRecv;

    @Column(name = "net_send_rate_bps", nullable = false)
    private Float netSendRateBps;

    @Column(name = "net_recv_rate_bps", nullable = false)
    private Float netRecvRateBps;

    @Column(name = "uptime_seconds", nullable = false)
    private Long uptimeSeconds;

    /** [1m, 5m, 15m] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "load_avg", columnDefinition = "json")
    private List<Double> loadAvg;

    @Column(name = "process_count", nullable = false)
    private Integer processCount;
}
