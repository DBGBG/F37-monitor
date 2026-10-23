package com.moxa.monitor.repo;

import com.moxa.monitor.domain.PortTrafficStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 端口流量时序仓库 [只读] */
public interface PortTrafficStatRepository extends JpaRepository<PortTrafficStat, Integer> {

    /** 端口流量趋势图 */
    List<PortTrafficStat> findBySwitchIdAndPortIndexAndTimestampGreaterThanEqualOrderByTimestampAsc(
            String switchId, Integer portIndex, LocalDateTime since);

    /** 保留策略清理用: 分批删除, 返回本批实际删除行数 (0 表示删完) */
    @Modifying
    @Query(value = "DELETE FROM port_traffic_stats WHERE timestamp < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    /**
     * 每台交换机在回看窗口内的错包+丢弃增量总量 (主动分析样本), 语义与采集层逐字对齐:
     * 每个端口只取窗口内最近两行 (rn = 1 那一行的 LAG 即上一行), 计数器回绕产生的负值按 0 处理.
     * 只有一个样本的端口仍参不汇总 (增量 0), 保证设备有样本就能喂给基线.
     * 返回 [switch_id, delta, 最新样本时间]
     */
    @Query(value = """
            SELECT t.switch_id,
                   SUM(GREATEST(t.in_errors - COALESCE(t.prev_in, t.in_errors), 0)
                     + GREATEST(t.out_errors - COALESCE(t.prev_out, t.out_errors), 0)
                     + GREATEST(t.in_discards - COALESCE(t.prev_in_d, t.in_discards), 0)
                     + GREATEST(t.out_discards - COALESCE(t.prev_out_d, t.out_discards), 0)),
                   MAX(t.timestamp)
            FROM (SELECT switch_id, port_index, timestamp, in_errors, out_errors, in_discards, out_discards,
                         LAG(in_errors) OVER (PARTITION BY switch_id, port_index ORDER BY timestamp) AS prev_in,
                         LAG(out_errors) OVER (PARTITION BY switch_id, port_index ORDER BY timestamp) AS prev_out,
                         LAG(in_discards) OVER (PARTITION BY switch_id, port_index ORDER BY timestamp) AS prev_in_d,
                         LAG(out_discards) OVER (PARTITION BY switch_id, port_index ORDER BY timestamp) AS prev_out_d,
                         ROW_NUMBER() OVER (PARTITION BY switch_id, port_index ORDER BY timestamp DESC) AS rn
                  FROM port_traffic_stats
                  WHERE timestamp >= :since) t
            WHERE t.rn = 1
            GROUP BY t.switch_id
            """, nativeQuery = true)
    List<Object[]> sumErrorDeltaBySwitch(@Param("since") LocalDateTime since);

    /**
     * 每台交换机每个端口的最新一行流量 (取最近一条 + 前一条用于增量).
     * 返回 [switch_id, port_index, timestamp, in_errors, out_errors]
     */
    @Query(value = """
            SELECT c.switch_id, c.port_index, c.timestamp, c.in_errors, c.out_errors
            FROM (SELECT switch_id, port_index, timestamp, in_errors, out_errors,
                         ROW_NUMBER() OVER (PARTITION BY switch_id, port_index ORDER BY timestamp DESC) AS rn
                  FROM port_traffic_stats
                  WHERE switch_id = :switchId AND timestamp >= :since) c
            WHERE c.rn <= 2
            """, nativeQuery = true)
    List<Object[]> findTwoLatestPerPort(@Param("switchId") String switchId, @Param("since") LocalDateTime since);

    /** 删除设备前清理时序 (外键无 CASCADE) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PortTrafficStat t WHERE t.switchId = :switchId")
    int deleteBySwitchId(@Param("switchId") String switchId);
}
