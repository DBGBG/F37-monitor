package com.moxa.monitor.repo;

import com.moxa.monitor.domain.PortStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 端口状态仓库 [只读]: 写入方是采集层 */
public interface PortStatusRepository extends JpaRepository<PortStatus, Integer> {

    List<PortStatus> findBySwitchIdOrderByPortIndexAsc(String switchId);

    List<PortStatus> findBySwitchIdAndOperStatus(String switchId, Integer operStatus);

    long countBySwitchIdAndOperStatus(String switchId, Integer operStatus);

    /** 告警引擎一次取回全部端口的 (switch_id, port_index, admin_status, oper_status) */
    @Query("SELECT p.switchId, p.portIndex, p.adminStatus, p.operStatus FROM PortStatus p")
    List<Object[]> findAllOperStates();

    /** 全部交换机的 up 端口数 (实时快照用, 一次查完避免 N+1) */
    @Query("SELECT p.switchId, COUNT(p) FROM PortStatus p WHERE p.operStatus = 1 GROUP BY p.switchId")
    List<Object[]> countUpPortsGrouped();

    /** 单台交换机的 up 端口数 */
    @Query("SELECT COUNT(p) FROM PortStatus p WHERE p.switchId = :switchId AND p.operStatus = 1")
    long countUpPorts(@Param("switchId") String switchId);

    /** 删除设备前清理子表: port_status 的外键没有 ON DELETE CASCADE */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PortStatus p WHERE p.switchId = :switchId")
    int deleteBySwitchId(@Param("switchId") String switchId);
}
