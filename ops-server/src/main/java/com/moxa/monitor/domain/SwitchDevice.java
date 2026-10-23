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
import java.util.Map;

/**
 * 交换机台账 [CB]: Python 写采集字段 (is_online/last_poll_time/cpu_usage/memory_usage/
 * total_ports/uptime_seconds/model/firmware_version/serial_number),
 * Java 只写 name/location/enabled/snmp_config/ssh_config —— 因此更新一律走
 * SwitchDeviceRepository 的 @Modifying 定向 UPDATE, 禁止 save(整个实体).
 */
@Getter
@Setter
@Entity
@Table(name = "switch_devices")
public class SwitchDevice {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "ip", nullable = false, length = 50)
    private String ip;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "location", nullable = false, length = 200)
    private String location;

    @Column(name = "firmware_version", nullable = false, length = 100)
    private String firmwareVersion;

    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    @Column(name = "total_ports", nullable = false)
    private Integer totalPorts;

    @Column(name = "uptime_seconds", nullable = false)
    private Integer uptimeSeconds;

    @Column(name = "cpu_usage", nullable = false)
    private Float cpuUsage;

    @Column(name = "memory_usage", nullable = false)
    private Float memoryUsage;

    @Column(name = "is_online", nullable = false)
    private Boolean isOnline;

    @Column(name = "last_poll_time")
    private LocalDateTime lastPollTime;

    /** {version, community, port} —— community 不得出现在任何接口响应 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snmp_config", columnDefinition = "json")
    private Map<String, Object> snmpConfig;

    /** {host, username, password, port} —— password 不得出现在任何接口响应 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ssh_config", columnDefinition = "json")
    private Map<String, Object> sshConfig;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
