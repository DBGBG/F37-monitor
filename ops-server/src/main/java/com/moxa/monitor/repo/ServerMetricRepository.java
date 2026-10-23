package com.moxa.monitor.repo;

import com.moxa.monitor.domain.ServerMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 服务器资源时序仓库 [只读] */
public interface ServerMetricRepository extends JpaRepository<ServerMetric, Integer> {

    ServerMetric findFirstByServerIdOrderByTimestampDesc(String serverId);

    List<ServerMetric> findByServerIdAndTimestampGreaterThanEqualOrderByTimestampAsc(String serverId, LocalDateTime since);

    /** 每台服务器最新一条 (总览 / 实时快照 / 告警引擎 / 健康评分) */
    @Query("SELECT m FROM ServerMetric m WHERE m.timestamp = "
            + "(SELECT MAX(m2.timestamp) FROM ServerMetric m2 WHERE m2.serverId = m.serverId)")
    List<ServerMetric> findLatestOfEachServer();

    /** 主动分析样本 */
    List<ServerMetric> findByTimestampGreaterThanEqualOrderByTimestampAsc(LocalDateTime since);

    @Modifying
    @Query(value = "DELETE FROM server_metrics WHERE timestamp < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Query("SELECT MAX(m.timestamp) FROM ServerMetric m")
    LocalDateTime findMaxTimestamp();

    /** 删除服务器前清理时序 (外键无 CASCADE) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ServerMetric m WHERE m.serverId = :serverId")
    int deleteByServerId(@Param("serverId") String serverId);
}
