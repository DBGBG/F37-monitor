package com.moxa.monitor.repo;

import com.moxa.monitor.domain.InspectionTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 点检任务仓库 [B] */
public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Integer>,
        JpaSpecificationExecutor<InspectionTask> {

    /** 幂等判定: (plan_id, device_id, planned_time) 唯一 */
    boolean existsByPlanIdAndDeviceIdAndPlannedTime(Integer planId, String deviceId, LocalDateTime plannedTime);

    List<InspectionTask> findByStatusInOrderByPlannedTimeAsc(List<String> statuses);

    List<InspectionTask> findByStatusInOrderByPlannedTimeDesc(List<String> statuses, Pageable pageable);

    long countByStatusIn(List<String> statuses);

    /** 点检统计: 各状态台数与今日完成/异常数 */
    long countByStatus(String status);

    long countByStatusAndFinishedAtGreaterThanEqual(String status, LocalDateTime from);

    long countByStatusAndResultAndFinishedAtGreaterThanEqual(String status, String result, LocalDateTime from);

    List<InspectionTask> findAllByOrderByPlannedTimeDesc();

    /** 逾期: pending / in_progress 且已过截止时间 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionTask t SET t.status = 'overdue' "
            + "WHERE t.status IN ('pending', 'in_progress') AND t.dueTime < :now")
    int markOverdue(@Param("now") LocalDateTime now);

    /** 点检统计: 区间内按状态分组 */
    @Query("SELECT t.status, COUNT(t) FROM InspectionTask t WHERE t.plannedTime >= :from AND t.plannedTime <= :to GROUP BY t.status")
    List<Object[]> countByStatusInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT t.deviceType, t.result, COUNT(t) FROM InspectionTask t "
            + "WHERE t.status = 'completed' AND t.plannedTime >= :from AND t.plannedTime <= :to "
            + "GROUP BY t.deviceType, t.result")
    List<Object[]> countResultByTypeInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 保留策略: 只清已完成/已跳过 */
    @Modifying
    @Query(value = "DELETE FROM inspection_tasks WHERE status IN ('completed','skipped') AND planned_time < :cutoff LIMIT :batchSize",
            nativeQuery = true)
    int deleteFinishedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionTask t SET t.status = :status, t.operator = :operator, t.startedAt = :at "
            + "WHERE t.id = :id")
    int startTask(@Param("id") Integer id, @Param("status") String status,
                  @Param("operator") String operator, @Param("at") LocalDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InspectionTask t SET t.status = :status, t.result = :result, t.abnormalCount = :abnormal, "
            + "t.operator = :operator, t.remark = :remark, t.finishedAt = :at WHERE t.id = :id")
    int finishTask(@Param("id") Integer id, @Param("status") String status, @Param("result") String result,
                   @Param("abnormal") Integer abnormal, @Param("operator") String operator,
                   @Param("remark") String remark, @Param("at") LocalDateTime at);
}
