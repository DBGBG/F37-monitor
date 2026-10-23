package com.moxa.monitor.repo;

import com.moxa.monitor.domain.AlertRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 告警记录仓库 [B] */
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Integer>,
        JpaSpecificationExecutor<AlertRecord> {

    Optional<AlertRecord> findByAlertId(String alertId);

    List<AlertRecord> findByStatusIn(List<String> statuses);

    List<AlertRecord> findAllByOrderByTriggeredAtDesc();

    List<AlertRecord> findByStatusOrderByTriggeredAtDesc(String status, Pageable pageable);

    long countByStatus(String status);

    /** 待发送通知: 活动且未通知 */
    List<AlertRecord> findByNotifiedIsFalseAndStatus(String status);

    /** 按级别统计活动告警 */
    @Query("SELECT a.severity, COUNT(a) FROM AlertRecord a WHERE a.status = 'active' GROUP BY a.severity")
    List<Object[]> countActiveBySeverity();

    /** 健康评分: 每台设备未闭环告警按级别分组 */
    @Query("SELECT a.deviceId, a.severity, COUNT(a) FROM AlertRecord a "
            + "WHERE a.status <> 'resolved' GROUP BY a.deviceId, a.severity")
    List<Object[]> countUnresolvedByDevice();

    /** 主动分析预载: 仅 status = active (与迁移前一致, acknowledged 不计入扣分) */
    @Query("SELECT a.deviceId, a.severity, COUNT(a) FROM AlertRecord a "
            + "WHERE a.status = 'active' GROUP BY a.deviceId, a.severity")
    List<Object[]> countActiveByDeviceAndSeverity();

    @Query("SELECT a.deviceId, COUNT(a) FROM AlertRecord a WHERE a.status = 'active' AND a.deviceId IN :ids GROUP BY a.deviceId")
    List<Object[]> countActiveByDeviceIds(@Param("ids") List<String> deviceIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord a SET a.status = :status, a.acknowledgedBy = :operator "
            + "WHERE a.alertId = :alertId AND a.status <> 'resolved'")
    int acknowledge(@Param("alertId") String alertId, @Param("status") String status,
                    @Param("operator") String operator);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord a SET a.status = 'resolved', a.resolvedBy = :operator, a.resolvedAt = :at "
            + "WHERE a.alertId = :alertId AND a.status <> 'resolved'")
    int resolve(@Param("alertId") String alertId, @Param("operator") String operator,
                @Param("at") LocalDateTime at);

    /** 指标恢复正常时由告警引擎自动闭环 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord a SET a.status = 'resolved', a.resolvedBy = 'system', a.resolvedAt = :at "
            + "WHERE a.alertId = :alertId AND a.status <> 'resolved'")
    int autoResolve(@Param("alertId") String alertId, @Param("at") LocalDateTime at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord a SET a.notified = TRUE, a.notifyChannels = :channels WHERE a.id = :id")
    int markNotified(@Param("id") Integer id, @Param("channels") String channels);

    /** 保留策略: 只删超期且已恢复的告警 */
    @Modifying
    @Query(value = "DELETE FROM alert_records WHERE status = 'resolved' AND triggered_at < :cutoff LIMIT :batchSize",
            nativeQuery = true)
    int deleteResolvedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Query("SELECT MAX(a.updatedAt) FROM AlertRecord a")
    LocalDateTime findMaxUpdatedAt();

    /** 近 N 小时新增告警数 (告警总览 recent_24h) */
    long countByTriggeredAtGreaterThanEqual(LocalDateTime since);

    /** 设备从台账中删除: 顺手闭环它的未恢复告警, 否则总览里会长期挂着一堆活动告警 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlertRecord a SET a.status = 'resolved', a.resolvedBy = 'system', a.resolvedAt = :at "
            + "WHERE a.deviceId = :deviceId AND a.status <> 'resolved'")
    int resolveAllOfDevice(@Param("deviceId") String deviceId, @Param("at") LocalDateTime at);
}
