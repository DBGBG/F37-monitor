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

/** 业务层下发给采集层的指令 [B写/C读]: Python 每 10 秒拉取 pending 执行并回写 */
@Getter
@Setter
@Entity
@Table(name = "collector_commands")
public class CollectorCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** rediscover / reload_config / collect_now */
    @Column(name = "cmd", nullable = false, length = 50)
    private String cmd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "json")
    private Map<String, Object> payload;

    /** pending / running / done / failed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "result", nullable = false, length = 500)
    private String result;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
