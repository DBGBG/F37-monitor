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

/** 设备发现范围 [B写/C读]: 取代原 devices.yaml 的 switch_groups, Java 增删改, Python 读取执行扫描 */
@Getter
@Setter
@Entity
@Table(name = "discovery_groups")
public class DiscoveryGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    /** a.b.c.1-200 / a.b.c.0/24 */
    @Column(name = "ip_range", nullable = false, length = 100)
    private String ipRange;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "location_prefix", nullable = false, length = 100)
    private String locationPrefix;

    /** SNMP 凭据模板; 响应里 community 需要脱敏 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snmp_config", columnDefinition = "json")
    private Map<String, Object> snmpConfig;

    /** SSH 凭据模板; 响应里 password 需要脱敏 */
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
