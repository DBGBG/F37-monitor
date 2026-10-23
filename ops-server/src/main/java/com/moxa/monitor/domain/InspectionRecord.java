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

/** 点检执行明细 [B]: 一次任务提交产生 N 行 */
@Getter
@Setter
@Entity
@Table(name = "inspection_records")
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "task_id", nullable = false)
    private Integer taskId;

    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    @Column(name = "check_type", nullable = false, length = 20)
    private String checkType;

    @Column(name = "standard", nullable = false, length = 200)
    private String standard;

    @Column(name = "value", nullable = false, length = 500)
    private String value;

    /** normal / abnormal / na */
    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "remark", nullable = false, length = 500)
    private String remark;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
