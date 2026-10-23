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

/**
 * 服务器台账 [CB]: Python 写采集字段 (hostname/os_version/cpu_cores/total_memory_mb/
 * is_online/uptime_seconds/last_poll_time), Java 写 name/location/enabled/monitoring_config/processes_config.
 * 同样禁止整行 save, 更新走 ServerDeviceRepository 的定向 UPDATE.
 */
@Getter
@Setter
@Entity
@Table(name = "server_devices")
public class ServerDevice {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "ip", nullable = false, length = 50)
    private String ip;

    /** windows / linux */
    @Column(name = "os_type", nullable = false, length = 20)
    private String osType;

    @Column(name = "location", nullable = false, length = 200)
    private String location;

    @Column(name = "hostname", nullable = false, length = 200)
    private String hostname;

    @Column(name = "os_version", nullable = false, length = 200)
    private String osVersion;

    @Column(name = "cpu_cores", nullable = false)
    private Integer cpuCores;

    @Column(name = "total_memory_mb", nullable = false)
    private Integer totalMemoryMb;

    @Column(name = "is_online", nullable = false)
    private Boolean isOnline;

    @Column(name = "uptime_seconds", nullable = false)
    private Integer uptimeSeconds;

    @Column(name = "last_poll_time")
    private LocalDateTime lastPollTime;

    /** {method: agent|ssh|wmi, port, username, password} —— 凭据不得外泄 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monitoring_config", columnDefinition = "json")
    private Map<String, Object> monitoringConfig;

    /** [{name, port, critical}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "processes_config", columnDefinition = "json")
    private List<Map<String, Object>> processesConfig;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
