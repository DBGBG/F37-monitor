package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 点检计划项 [B]: 属于某个计划, 生成任务时会被快照进 inspection_tasks.items */
@Getter
@Setter
@Entity
@Table(name = "inspection_items")
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "plan_id", nullable = false)
    private Integer planId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** bool / value / text */
    @Column(name = "check_type", nullable = false, length = 20)
    private String checkType;

    @Column(name = "standard", nullable = false, length = 200)
    private String standard;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "required", nullable = false)
    private Boolean required;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
