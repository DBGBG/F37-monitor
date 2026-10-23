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

/** 配置变更审计 [B]: 每次 SSH 下发 (含失败与超时) 都落一行 */
@Getter
@Setter
@Entity
@Table(name = "config_audit_logs")
public class ConfigAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "switch_id", nullable = false, length = 50)
    private String switchId;

    @Column(name = "switch_name", nullable = false, length = 200)
    private String switchName;

    @Column(name = "switch_ip", nullable = false, length = 50)
    private String switchIp;

    /** vlan / port / system / raw */
    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(name = "operation_desc", nullable = false, length = 500)
    private String operationDesc;

    @Column(name = "operator", nullable = false, length = 100)
    private String operator;

    @Column(name = "commands_sent", columnDefinition = "mediumtext")
    private String commandsSent;

    @Column(name = "commands_output", columnDefinition = "mediumtext")
    private String commandsOutput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "json")
    private Map<String, Object> parameters;

    /** success / failed / timeout */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;
}
