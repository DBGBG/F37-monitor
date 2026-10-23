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

/** 点检标准 [B]: 独立于计划的判定标准库, 建计划时按名称引用为点检项快照 */
@Getter
@Setter
@Entity
@Table(name = "inspection_standards")
public class InspectionStandard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** switch / server / general */
    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** bool / value / text, 与 inspection_items.check_type 同一套取值 */
    @Column(name = "check_type", nullable = false, length = 20)
    private String checkType;

    @Column(name = "standard", nullable = false, length = 200)
    private String standard;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** 点检方法: 目视 / 手感 / 仪器 / 系统查询 */
    @Column(name = "method", nullable = false, length = 100)
    private String method;

    /** 建议周期: daily / weekly / monthly / quarterly */
    @Column(name = "check_cycle", nullable = false, length = 20)
    private String checkCycle;

    /** 重要度: key / normal / optional */
    @Column(name = "level", nullable = false, length = 20)
    private String level;

    /** 异常处置措施 */
    @Column(name = "handle_action", nullable = false, length = 500)
    private String handleAction;

    /** preset(迁移脚本内置) / manual(页面新增); 内置项允许改与删, 只做来源标记 */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
