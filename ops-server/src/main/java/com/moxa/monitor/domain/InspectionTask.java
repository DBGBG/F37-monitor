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
import java.util.List;
import java.util.Map;

/** 点检任务 [B]: (plan_id, device_id, planned_time) 唯一, 生成幂等依赖该约束 */
@Getter
@Setter
@Entity
@Table(name = "inspection_tasks")
public class InspectionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "plan_id", nullable = false)
    private Integer planId;

    @Column(name = "plan_name", nullable = false, length = 200)
    private String planName;

    @Column(name = "device_id", nullable = false, length = 50)
    private String deviceId;

    @Column(name = "device_name", nullable = false, length = 200)
    private String deviceName;

    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    @Column(name = "planned_time", nullable = false)
    private LocalDateTime plannedTime;

    @Column(name = "due_time", nullable = false)
    private LocalDateTime dueTime;

    /** 点检项快照 [{name, check_type, standard, unit, required, sort_order}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", columnDefinition = "json")
    private List<Map<String, Object>> items;

    /** pending / in_progress / completed / skipped / overdue */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** normal / abnormal / 空串表示未提交 */
    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "abnormal_count", nullable = false)
    private Integer abnormalCount;

    @Column(name = "assignee", nullable = false, length = 100)
    private String assignee;

    @Column(name = "operator", nullable = false, length = 100)
    private String operator;

    @Column(name = "remark", nullable = false, length = 500)
    private String remark;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
