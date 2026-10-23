package com.moxa.monitor.repo;

import com.moxa.monitor.domain.InspectionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 点检计划仓库 [B] */
public interface InspectionPlanRepository extends JpaRepository<InspectionPlan, Integer>,
        JpaSpecificationExecutor<InspectionPlan> {

    List<InspectionPlan> findAllByOrderByIdDesc();

    List<InspectionPlan> findByEnabledIsTrueOrderByIdAsc();

    long countByEnabledIsTrue();

    List<InspectionPlan> findByDeviceType(String deviceType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionPlan p SET p.enabled = :enabled WHERE p.id = :id")
    int toggle(@Param("id") Integer id, @Param("enabled") Boolean enabled);

    /** 任务生成游标前移 (仅生成 (last_generated_at, now] 的触发点) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionPlan p SET p.lastGeneratedAt = :at WHERE p.id = :id")
    int touchGeneratedAt(@Param("id") Integer id, @Param("at") LocalDateTime at);
}
