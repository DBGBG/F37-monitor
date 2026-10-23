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

/** 点检计划 [B]: schedule_type=daily/weekly/monthly/cron, 由 InspectionSchedulerService 生成任务 */
@Getter
@Setter
@Entity
@Table(name = "inspection_plans")
public class InspectionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /** switch / server */
    @Column(name = "device_type", nullable = false, length = 20)
    private String deviceType;

    /** all / devices */
    @Column(name = "target_scope", nullable = false, length = 20)
    private String targetScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_ids", columnDefinition = "json")
    private List<String> targetIds;

    @Column(name = "schedule_type", nullable = false, length = 20)
    private String scheduleType;

    /** {time:"09:00", weekdays:[1,3,5], days:[1,15], hourly?:..} 具体解析见 InspectionSchedulerService */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule_value", columnDefinition = "json")
    private Map<String, Object> scheduleValue;

    @Column(name = "cron_expr", nullable = false, length = 100)
    private String cronExpr;

    @Column(name = "assignee", nullable = false, length = 100)
    private String assignee;

    @Column(name = "grace_hours", nullable = false)
    private Integer graceHours;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** 任务生成游标: 只生成 (last_generated_at, now] 区间的触发点 */
    @Column(name = "last_generated_at")
    private LocalDateTime lastGeneratedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
