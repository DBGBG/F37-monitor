package com.moxa.monitor.repo;

import com.moxa.monitor.domain.ProcessStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 进程/服务状态时序仓库 [只读] */
public interface ProcessStatusRepository extends JpaRepository<ProcessStatus, Integer> {

    /** 某服务器最新一轮的进程清单 */
    @Query("SELECT p FROM ProcessStatus p WHERE p.serverId = :serverId AND p.timestamp = "
            + "(SELECT MAX(p2.timestamp) FROM ProcessStatus p2 WHERE p2.serverId = :serverId) "
            + "ORDER BY p.isCritical DESC, p.processName ASC")
    List<ProcessStatus> findLatestOfServer(@Param("serverId") String serverId);

    /** 告警引擎: 各服务器最新一轮中挂掉的关键进程 */
    @Query("SELECT p FROM ProcessStatus p WHERE p.isCritical = TRUE AND p.isRunning = FALSE AND p.timestamp = "
            + "(SELECT MAX(p2.timestamp) FROM ProcessStatus p2 WHERE p2.serverId = p.serverId)")
    List<ProcessStatus> findLatestCriticalDown();

    /** 某服务器最后一轮采集时间 (无数据返回 null) */
    @Query("SELECT MAX(p.timestamp) FROM ProcessStatus p WHERE p.serverId = :serverId")
    LocalDateTime findLatestTimestamp(@Param("serverId") String serverId);

    @Modifying
    @Query(value = "DELETE FROM process_status WHERE timestamp < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Query("SELECT MAX(p.timestamp) FROM ProcessStatus p")
    LocalDateTime findMaxTimestamp();

    /** 删除服务器前清理时序 (外键无 CASCADE) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProcessStatus p WHERE p.serverId = :serverId")
    int deleteByServerId(@Param("serverId") String serverId);
}
