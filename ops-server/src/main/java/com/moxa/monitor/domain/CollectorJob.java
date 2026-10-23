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

/** 采集任务周期与开关 [B写/C读]: Python 每 5 分钟热加载一次 */
@Getter
@Setter
@Entity
@Table(name = "collector_jobs")
public class CollectorJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** snmp_poll_status / snmp_poll_traffic / snmp_poll_hardware / server_poll_all / process_monitor_all / device_rediscovery */
    @Column(name = "job_id", nullable = false, length = 50)
    private String jobId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "interval_seconds", nullable = false)
    private Integer intervalSeconds;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    @Column(name = "max_instances", nullable = false)
    private Integer maxInstances;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
