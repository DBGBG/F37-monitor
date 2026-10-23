package com.moxa.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 端口最新状态 [C]: 每 (switch_id, port_index) 一行, 由采集层覆盖更新, Java 只读 */
@Getter
@Setter
@Entity
@Table(name = "port_status")
public class PortStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "switch_id", nullable = false, length = 50)
    private String switchId;

    @Column(name = "port_index", nullable = false)
    private Integer portIndex;

    @Column(name = "port_name", nullable = false, length = 100)
    private String portName;

    /** 1=up 2=down */
    @Column(name = "admin_status", nullable = false)
    private Integer adminStatus;

    /** 1=up 2=down */
    @Column(name = "oper_status", nullable = false)
    private Integer operStatus;

    @Column(name = "speed_bps", nullable = false)
    private Long speedBps;

    @Column(name = "mac_address", nullable = false, length = 50)
    private String macAddress;

    @Column(name = "vlan_id", nullable = false)
    private Integer vlanId;

    /** access / trunk / hybrid */
    @Column(name = "port_mode", nullable = false, length = 20)
    private String portMode;

    @Column(name = "last_change")
    private LocalDateTime lastChange;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
