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

/**
 * 业务参数配置 [B]: 承载从 settings.yaml 迁出的键
 * (alert_rules / alert_settings / proactive_params / notification_config /
 *  retention_policy / inspection_defaults / ssh_defaults / vlan_plan).
 *
 * config_value 映射为原始 JSON 字符串而非对象: 各键的形状不一 (有的是对象, 有的是数组),
 * 统一交给 SysConfigService 用 Jackson 解析成 JsonNode 后再取用.
 */
@Getter
@Setter
@Entity
@Table(name = "sys_config")
public class SysConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "config_key", nullable = false, length = 50)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "json")
    private String configValue;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
