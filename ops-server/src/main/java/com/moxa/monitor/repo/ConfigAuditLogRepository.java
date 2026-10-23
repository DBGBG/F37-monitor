package com.moxa.monitor.repo;

import com.moxa.monitor.domain.ConfigAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 配置变更审计仓库 [B] */
public interface ConfigAuditLogRepository extends JpaRepository<ConfigAuditLog, Integer>,
        JpaSpecificationExecutor<ConfigAuditLog> {

    List<ConfigAuditLog> findAllByOrderByExecutedAtDesc();

    List<ConfigAuditLog> findBySwitchIdOrderByExecutedAtDesc(String switchId);

    @Query("SELECT COUNT(a) FROM ConfigAuditLog a WHERE a.status = 'success' AND a.executedAt >= :from")
    long countSuccessSince(@Param("from") LocalDateTime from);

    @Query("SELECT COUNT(a) FROM ConfigAuditLog a WHERE a.executedAt >= :from")
    long countSince(@Param("from") LocalDateTime from);

    @Modifying
    @Query(value = "DELETE FROM config_audit_logs WHERE executed_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
