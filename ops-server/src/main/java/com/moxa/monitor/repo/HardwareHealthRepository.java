package com.moxa.monitor.repo;

import com.moxa.monitor.domain.HardwareHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 硬件健康时序仓库 [只读] */
public interface HardwareHealthRepository extends JpaRepository<HardwareHealth, Integer> {

    HardwareHealth findFirstBySwitchIdOrderByTimestampDesc(String switchId);

    List<HardwareHealth> findBySwitchIdAndTimestampGreaterThanEqualOrderByTimestampAsc(String switchId, LocalDateTime since);

    /**
     * 每台交换机的最新一条硬件记录 (交换机列表温度列 / 告警引擎 / 健康评分共用).
     * hardware_health 行数很少 (10 分钟一条), 用 MAX(timestamp) 关联子查询足够.
     */
    @Query("SELECT h FROM HardwareHealth h WHERE h.timestamp = "
            + "(SELECT MAX(h2.timestamp) FROM HardwareHealth h2 WHERE h2.switchId = h.switchId)")
    List<HardwareHealth> findLatestOfEachSwitch();

    /** 主动分析样本: 窗口内全部记录 (按设备取序列) */
    List<HardwareHealth> findByTimestampGreaterThanEqualOrderByTimestampAsc(LocalDateTime since);

    @Modifying
    @Query(value = "DELETE FROM hardware_health WHERE timestamp < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Query("SELECT MAX(h.timestamp) FROM HardwareHealth h")
    LocalDateTime findMaxTimestamp();

    /** 删除设备前清理时序 (外键无 CASCADE) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM HardwareHealth h WHERE h.switchId = :switchId")
    int deleteBySwitchId(@Param("switchId") String switchId);
}
