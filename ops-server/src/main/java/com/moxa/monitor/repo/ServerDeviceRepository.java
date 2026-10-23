package com.moxa.monitor.repo;

import com.moxa.monitor.domain.ServerDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 服务器台账仓库, 同样只允许定向 UPDATE (采集列由 Python 维护) */
public interface ServerDeviceRepository extends JpaRepository<ServerDevice, String>,
        JpaSpecificationExecutor<ServerDevice> {

    List<ServerDevice> findByEnabledIsTrueOrderByIpAsc();

    /** 告警引擎: 迁移前遍历的是 is_online=True 的服务器(不按 enabled 过滤), 此处保持一致 */
    List<ServerDevice> findByIsOnlineIsTrue();

    long countByEnabledIsTrue();

    long countByIsOnlineIsTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ServerDevice s SET s.name = :name, s.location = :location, s.enabled = :enabled WHERE s.id = :id")
    int updateLedger(@Param("id") String id, @Param("name") String name,
                     @Param("location") String location, @Param("enabled") Boolean enabled);

    /** monitoring_config: {method, port, username, password} */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE server_devices SET monitoring_config = CAST(:json AS JSON) WHERE id = :id", nativeQuery = true)
    int updateMonitoringConfig(@Param("id") String id, @Param("json") String json);

    /** processes_config: [{name, port, critical}] */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE server_devices SET processes_config = CAST(:json AS JSON) WHERE id = :id", nativeQuery = true)
    int updateProcessesConfig(@Param("id") String id, @Param("json") String json);

    @Query("SELECT s.id, s.name, s.ip, s.osType, s.isOnline, s.lastPollTime FROM ServerDevice s ORDER BY s.id")
    List<Object[]> findLedgerSummary();
}
