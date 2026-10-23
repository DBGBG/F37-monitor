package com.moxa.monitor.repo;

import com.moxa.monitor.domain.SwitchDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 交换机台账仓库.
 * 该表由采集层与业务层共写不同列, 因此业务层更新只允许走下面的 @Modifying 定向 UPDATE,
 * 严禁 save(整个实体) —— 否则会用读取时的旧值覆盖掉采集层刚写入的 is_online / last_poll_time 等字段.
 */
public interface SwitchDeviceRepository extends JpaRepository<SwitchDevice, String>,
        JpaSpecificationExecutor<SwitchDevice> {

    List<SwitchDevice> findByEnabledIsTrueOrderByIpAsc();

    List<SwitchDevice> findByEnabledIsTrueAndIsOnlineIsTrue();

    long countByIsOnlineIsTrue();

    SwitchDevice findFirstByIp(String ip);

    /** 台账字段 (业务层唯一可写列) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SwitchDevice s SET s.name = :name, s.location = :location, s.enabled = :enabled WHERE s.id = :id")
    int updateLedger(@Param("id") String id, @Param("name") String name,
                     @Param("location") String location, @Param("enabled") Boolean enabled);

    /** SNMP 凭据 (JSON 列走原生 CAST, 参数为已序列化的 JSON 字符串) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE switch_devices SET snmp_config = CAST(:json AS JSON) WHERE id = :id", nativeQuery = true)
    int updateSnmpConfig(@Param("id") String id, @Param("json") String json);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE switch_devices SET ssh_config = CAST(:json AS JSON) WHERE id = :id", nativeQuery = true)
    int updateSshConfig(@Param("id") String id, @Param("json") String json);

    /**
     * 实时快照与采集监控用的轻量清单. 不按 enabled 过滤 —— 迁移前的内存态统计全部设备,
     * 保持与 /api/switches/summary 和 /api/dashboard/overview 的口径一致, 避免 SSE 推送把
     * 卡片数字改成另一套统计.
     */
    @Query("SELECT s.id, s.name, s.ip, s.isOnline, s.lastPollTime FROM SwitchDevice s ORDER BY s.id")
    List<Object[]> findLedgerSummary();
}
