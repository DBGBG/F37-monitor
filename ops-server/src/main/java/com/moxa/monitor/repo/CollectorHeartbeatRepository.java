package com.moxa.monitor.repo;

import com.moxa.monitor.domain.CollectorHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 采集层心跳仓库 [C写/B读] */
public interface CollectorHeartbeatRepository extends JpaRepository<CollectorHeartbeat, Integer> {

    /** 最后一次心跳: 判定采集层是否活着 */
    Optional<CollectorHeartbeat> findFirstByOrderByLastBeatAtDesc();

    Optional<CollectorHeartbeat> findFirstByComponentOrderByLastBeatAtDesc(String component);

    /**
     * 每个阶段最新一条 (系统健康页展示各采集环节的耗时与成功率)
     *
     * 写成相关子查询 `h.id = (SELECT MAX(id) ... WHERE component=h.component AND phase=h.phase)` 时,
     * MySQL 对心跳表每一行都要重跑一次 MAX, 而心跳每 30s 追加一条 (一天 ~2800 行),
     * 实测该接口退到 6~15s, 撞上前端 axios 15s 超时, 采集层明明活着却被页面报"异常".
     * 改成一次性 GROUP BY 取 MAX(id) 再用 IN 过滤, 只扫一次表.
     */
    @Query("SELECT h FROM CollectorHeartbeat h WHERE h.id IN "
            + "(SELECT MAX(h2.id) FROM CollectorHeartbeat h2 GROUP BY h2.component, h2.phase)")
    List<CollectorHeartbeat> findLatestOfEachPhase();

    List<CollectorHeartbeat> findTop50ByOrderByLastBeatAtDesc();

    List<CollectorHeartbeat> findByLastBeatAtGreaterThanEqualOrderByLastBeatAtDesc(LocalDateTime since);

    long countByLastBeatAtGreaterThanEqual(LocalDateTime since);

    @Modifying
    @Query(value = "DELETE FROM collector_heartbeats WHERE last_beat_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
