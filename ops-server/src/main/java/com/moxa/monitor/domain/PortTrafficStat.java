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

/** 端口流量时序 [C]: 采集层按 collector_jobs.snmp_poll_traffic 周期追加 (当前 15 分钟), Java 只读 (图表 + 错包增量基线) */
@Getter
@Setter
@Entity
@Table(name = "port_traffic_stats")
public class PortTrafficStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "switch_id", nullable = false, length = 50)
    private String switchId;

    @Column(name = "port_index", nullable = false)
    private Integer portIndex;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "in_octets", nullable = false)
    private Long inOctets;

    @Column(name = "out_octets", nullable = false)
    private Long outOctets;

    @Column(name = "in_unicast_pkts", nullable = false)
    private Long inUnicastPkts;

    @Column(name = "out_unicast_pkts", nullable = false)
    private Long outUnicastPkts;

    @Column(name = "in_errors", nullable = false)
    private Long inErrors;

    @Column(name = "out_errors", nullable = false)
    private Long outErrors;

    @Column(name = "in_discards", nullable = false)
    private Long inDiscards;

    @Column(name = "out_discards", nullable = false)
    private Long outDiscards;

    @Column(name = "in_rate_bps", nullable = false)
    private Float inRateBps;

    @Column(name = "out_rate_bps", nullable = false)
    private Float outRateBps;

    @Column(name = "in_error_rate", nullable = false)
    private Float inErrorRate;

    @Column(name = "out_error_rate", nullable = false)
    private Float outErrorRate;
}
