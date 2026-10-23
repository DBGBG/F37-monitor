package com.moxa.monitor.repo;

import com.moxa.monitor.domain.DeviceHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 设备健康评分仓库 [B] */
public interface DeviceHealthRepository extends JpaRepository<DeviceHealth, String>,
        JpaSpecificationExecutor<DeviceHealth> {

    List<DeviceHealth> findAllByOrderByScoreAsc();

    /** 总览用: 健康分最低的 8 台 = 最需关注设备 */
    List<DeviceHealth> findTop8ByOrderByScoreAsc();

    List<DeviceHealth> findByDeviceType(String deviceType);

    /** 总览用: 各等级 (优/良/中/差) 台数 */
    @Query("SELECT h.grade, COUNT(h) FROM DeviceHealth h GROUP BY h.grade")
    List<Object[]> countByGrade();

    @Query("SELECT AVG(h.score) FROM DeviceHealth h")
    Double averageScore();

    @Query("SELECT MIN(h.score) FROM DeviceHealth h")
    Integer minScore();

    /** 健康分布: 按等级 + 类型 */
    @Query("SELECT h.deviceType, h.grade, COUNT(h) FROM DeviceHealth h GROUP BY h.deviceType, h.grade")
    List<Object[]> countByTypeAndGrade();
}
