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
 * 智能体技能 [B]: AI 运维代理可调用的技能清单 (对应 ops-collector/AGENT.md 的能力矩阵).
 * 只做登记与启停配置, 业务层不会据此自动执行任何设备操作.
 */
@Getter
@Setter
@Entity
@Table(name = "agent_skills")
public class AgentSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** 技能标识, 小写字母/数字/下划线, 唯一 */
    @Column(name = "skill_key", nullable = false, length = 50)
    private String skillKey;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 采集诊断 / 告警分析 / 主动预防 / 点检辅助 / 配置自动化 / 报告汇总 */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /** 触发场景: 什么情况下调用该技能 */
    @Column(name = "trigger_scene", nullable = false, length = 300)
    private String triggerScene;

    /** 执行要点, 每行一条 */
    @Column(name = "steps", columnDefinition = "text")
    private String steps;

    /** 关联页面路径, 如 /inspection */
    @Column(name = "entry_path", nullable = false, length = 100)
    private String entryPath;

    /** read(只读) / suggest(给结论) / write(需人工确认后下发) */
    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
