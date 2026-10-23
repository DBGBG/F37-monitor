package com.moxa.monitor.repo;

import com.moxa.monitor.domain.MaintenanceWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 计划维护窗口仓库 [B] */
public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Integer> {

    List<MaintenanceWindow> findAllByOrderByStartTimeDesc();

    List<MaintenanceWindow> findByEnabledIsTrueOrderByStartTimeDesc();

    /** 当前生效的窗口: 告警引擎据此抑制 */
    @Query("SELECT w FROM MaintenanceWindow w WHERE w.enabled = TRUE AND w.startTime <= :now AND w.endTime >= :now")
    List<MaintenanceWindow> findActiveAt(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MaintenanceWindow w SET w.enabled = :enabled WHERE w.id = :id")
    int toggle(@Param("id") Integer id, @Param("enabled") Boolean enabled);
}
