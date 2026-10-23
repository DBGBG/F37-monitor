package com.moxa.monitor.repo;

import com.moxa.monitor.domain.MetricBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 指标 EWMA 基线仓库 [B] */
public interface MetricBaselineRepository extends JpaRepository<MetricBaseline, Integer>,
        JpaSpecificationExecutor<MetricBaseline> {

    Optional<MetricBaseline> findByDeviceIdAndMetric(String deviceId, String metric);

    List<MetricBaseline> findByDeviceId(String deviceId);

    /** 主动分析预载: 一次取回全部基线, 内存里按 device|metric 建索引 */
    List<MetricBaseline> findAllByOrderByDeviceIdAscMetricAsc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MetricBaseline b SET b.mean = :mean, b.variance = :variance, b.sampleCount = :samples, "
            + "b.lastValue = :lastValue, b.deviceType = :deviceType, b.updatedAt = :updatedAt WHERE b.id = :id")
    int updateSnapshot(@Param("id") Integer id, @Param("mean") Float mean, @Param("variance") Float variance,
                       @Param("samples") Integer samples, @Param("lastValue") Float lastValue,
                       @Param("deviceType") String deviceType, @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying
    @Query("DELETE FROM MetricBaseline b WHERE b.deviceId = :deviceId")
    int deleteByDeviceId(@Param("deviceId") String deviceId);
}
