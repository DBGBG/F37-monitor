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

/** 进程/服务状态时序 [C]: 采集层每 60 秒追加, Java 只读 */
@Getter
@Setter
@Entity
@Table(name = "process_status")
public class ProcessStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "server_id", nullable = false, length = 50)
    private String serverId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "process_name", nullable = false, length = 200)
    private String processName;

    @Column(name = "pid", nullable = false)
    private Integer pid;

    @Column(name = "is_running", nullable = false)
    private Boolean isRunning;

    @Column(name = "cpu_percent", nullable = false)
    private Float cpuPercent;

    @Column(name = "memory_mb", nullable = false)
    private Float memoryMb;

    @Column(name = "memory_percent", nullable = false)
    private Float memoryPercent;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "port_open", nullable = false)
    private Boolean portOpen;

    @Column(name = "is_critical", nullable = false)
    private Boolean isCritical;

    @Column(name = "status_detail", nullable = false, length = 500)
    private String statusDetail;
}
