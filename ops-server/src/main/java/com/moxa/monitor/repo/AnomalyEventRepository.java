package com.moxa.monitor.repo;

import com.moxa.monitor.domain.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 主动运维异常事件仓库 [B] */
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Integer>,
        JpaSpecificationExecutor<AnomalyEvent> {

    /** 未闭环事件 (open + acknowledged), 主动分析用来做合并与自动恢复 */
    List<AnomalyEvent> findByStatusNot(String status);

    Optional<AnomalyEvent> findFirstByDeviceIdAndMetricAndStatusNotOrderByIdDesc(
            String deviceId, String metric, String status);

    long countByStatus(String status);

    long countByStatusIn(List<String> statuses);

    /** 总览用: 未闭环异常按级别分布 */
    @Query("SELECT a.severity, COUNT(a) FROM AnomalyEvent a WHERE a.status IN :statuses GROUP BY a.severity")
    List<Object[]> countOpenBySeverity(@Param("statuses") List<String> statuses);

    List<AnomalyEvent> findByStatusInOrderByLastSeenAtDesc(List<String> statuses);

    List<AnomalyEvent> findByDeviceIdAndStatusInOrderByLastSeenAtDesc(String deviceId, List<String> statuses);

    List<AnomalyEvent> findAllByOrderByLastSeenAtDesc();

    /** 健康评分: 每台设备未处理异常数 */
    @Query("SELECT a.deviceId, COUNT(a) FROM AnomalyEvent a WHERE a.status = 'open' GROUP BY a.deviceId")
    List<Object[]> countOpenByDevice();

    /** 高危异常是否已升级为告警 */
    List<AnomalyEvent> findBySeverityAndStatusNotAndEscalatedIsFalse(String severity, String status);

    /** 同设备同指标的未闭环事件合并更新 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnomalyEvent a SET a.lastSeenAt = :seenAt, a.value = :value, a.baseline = :baseline, "
            + "a.deviation = :deviation, a.score = :score, a.severity = :severity, a.title = :title, "
            + "a.description = :description, "
            + "a.status = CASE WHEN a.status = 'resolved' THEN 'open' ELSE a.status END, "
            + "a.resolvedAt = NULL "
            + "WHERE a.id = :id")
    int mergeOccurrence(@Param("id") Integer id, @Param("seenAt") LocalDateTime seenAt,
                        @Param("value") Float value, @Param("baseline") Float baseline,
                        @Param("deviation") Float deviation, @Param("score") Float score,
                        @Param("severity") String severity, @Param("title") String title,
                        @Param("description") String description);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnomalyEvent a SET a.status = :status, a.acknowledgedBy = :operator WHERE a.id = :id "
            + "AND a.status <> 'resolved'")
    int acknowledge(@Param("id") Integer id, @Param("status") String status, @Param("operator") String operator);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnomalyEvent a SET a.status = 'resolved', a.resolvedAt = :at WHERE a.id = :id AND a.status <> 'resolved'")
    int resolve(@Param("id") Integer id, @Param("at") LocalDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnomalyEvent a SET a.escalated = TRUE WHERE a.id = :id")
    int markEscalated(@Param("id") Integer id);

    /** 本轮未再出现的未闭环事件视为自动恢复 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AnomalyEvent a SET a.status = 'resolved', a.resolvedAt = :at "
            + "WHERE a.status <> 'resolved' AND a.lastSeenAt < :before")
    int autoResolveStale(@Param("at") LocalDateTime at, @Param("before") LocalDateTime before);

    @Modifying
    @Query(value = "DELETE FROM anomaly_events WHERE status = 'resolved' AND occurred_at < :cutoff LIMIT :batchSize",
            nativeQuery = true)
    int deleteResolvedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
