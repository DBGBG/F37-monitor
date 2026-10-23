package com.moxa.monitor.service.device;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.HardwareHealth;
import com.moxa.monitor.domain.PortStatus;
import com.moxa.monitor.domain.PortTrafficStat;
import com.moxa.monitor.domain.ProcessStatus;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.ServerMetric;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.DeviceHealthRepository;
import com.moxa.monitor.repo.HardwareHealthRepository;
import com.moxa.monitor.repo.InspectionTaskRepository;
import com.moxa.monitor.repo.PortStatusRepository;
import com.moxa.monitor.repo.PortTrafficStatRepository;
import com.moxa.monitor.repo.ProcessStatusRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.ServerMetricRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.alert.AlertView;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备查询服务: 迁移自 legacy api/switches.py 与 api/servers.py.
 * 响应的键名与键序逐字段对齐迁移前, 前端列定义与图表取数无需改动.
 * 只读服务, 统一走只读事务.
 */
@Service
public class DeviceQueryService {

    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final PortStatusRepository portRepository;
    private final PortTrafficStatRepository trafficRepository;
    private final HardwareHealthRepository hardwareRepository;
    private final ServerMetricRepository metricRepository;
    private final ProcessStatusRepository processRepository;
    private final AlertRecordRepository alertRepository;
    private final DeviceHealthRepository healthRepository;
    private final InspectionTaskRepository taskRepository;

    public DeviceQueryService(SwitchDeviceRepository switchRepository,
                              ServerDeviceRepository serverRepository,
                              PortStatusRepository portRepository,
                              PortTrafficStatRepository trafficRepository,
                              HardwareHealthRepository hardwareRepository,
                              ServerMetricRepository metricRepository,
                              ProcessStatusRepository processRepository,
                              AlertRecordRepository alertRepository,
                              DeviceHealthRepository healthRepository,
                              InspectionTaskRepository taskRepository) {
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.portRepository = portRepository;
        this.trafficRepository = trafficRepository;
        this.hardwareRepository = hardwareRepository;
        this.metricRepository = metricRepository;
        this.processRepository = processRepository;
        this.alertRepository = alertRepository;
        this.healthRepository = healthRepository;
        this.taskRepository = taskRepository;
    }

    // ==================== 交换机 ====================

    /** GET /api/switches/summary —— total 为全部设备(与迁移前一致, 不只统计 enabled) */
    @Transactional(readOnly = true)
    public Map<String, Object> switchSummary() {
        long total = switchRepository.count();
        long online = switchRepository.countByIsOnlineIsTrue();
        return Maps.of(
                "total", total,
                "online", online,
                "offline", total - online,
                "online_rate", RoundUtil.percent(online, total, 1));
    }

    /** GET /api/switches/list —— 带每台最新温度 (一次查询避免 N+1) */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> switchList(int page, int pageSize, String keyword, boolean onlineOnly) {
        int p = Math.max(1, page);
        int size = clamp(pageSize, 1, 200);
        Page<SwitchDevice> result = switchRepository.findAll(
                switchSpec(keyword, onlineOnly),
                PageRequest.of(p - 1, size, Sort.by(Sort.Order.desc("isOnline"), Sort.Order.asc("id"))));

        Map<String, Float> tempMap = latestTemperatures();
        List<Map<String, Object>> items = new ArrayList<>();
        for (SwitchDevice d : result.getContent()) {
            Float temp = tempMap.get(d.getId());
            items.add(Maps.of(
                    "id", d.getId(),
                    "name", d.getName(),
                    "ip", d.getIp(),
                    "model", d.getModel(),
                    "location", d.getLocation(),
                    "is_online", d.getIsOnline(),
                    "total_ports", d.getTotalPorts(),
                    "uptime_seconds", d.getUptimeSeconds(),
                    "firmware_version", d.getFirmwareVersion(),
                    "temperature", temp,
                    "last_poll_time", DateTimeSerializers.fmt(d.getLastPollTime())));
        }
        return PageResult.of(result.getTotalElements(), p, size, items);
    }

    /** GET /api/switches/{id}/detail */
    @Transactional(readOnly = true)
    public Map<String, Object> switchDetail(String switchId) {
        SwitchDevice d = switchRepository.findById(switchId)
                .orElseThrow(() -> BizException.notFound("设备不存在"));
        return Maps.of(
                "id", d.getId(),
                "name", d.getName(),
                "ip", d.getIp(),
                "model", d.getModel(),
                "location", d.getLocation(),
                "firmware_version", d.getFirmwareVersion(),
                "serial_number", d.getSerialNumber(),
                "is_online", d.getIsOnline(),
                "total_ports", d.getTotalPorts(),
                "uptime_seconds", d.getUptimeSeconds(),
                "cpu_usage", d.getCpuUsage(),
                "memory_usage", d.getMemoryUsage(),
                "last_poll_time", DateTimeSerializers.fmt(d.getLastPollTime()));
    }

    /** GET /api/switches/{id}/ports */
    @Transactional(readOnly = true)
    public Map<String, Object> switchPorts(String switchId) {
        List<Map<String, Object>> ports = new ArrayList<>();
        for (PortStatus port : portRepository.findBySwitchIdOrderByPortIndexAsc(switchId)) {
            ports.add(Maps.of(
                    "port_index", port.getPortIndex(),
                    "port_name", port.getPortName(),
                    "admin_status", port.getAdminStatus(),
                    "oper_status", port.getOperStatus(),
                    "speed_bps", port.getSpeedBps(),
                    "vlan_id", port.getVlanId(),
                    "port_mode", port.getPortMode(),
                    "is_up", port.getOperStatus() != null && port.getOperStatus() == 1,
                    "updated_at", DateTimeSerializers.fmt(port.getUpdatedAt())));
        }
        return Maps.of("switch_id", switchId, "ports", ports);
    }

    /** GET /api/switches/{id}/traffic?port_index&hours */
    @Transactional(readOnly = true)
    public Map<String, Object> switchTraffic(String switchId, int portIndex, int hours) {
        int h = clamp(hours, 1, 24);
        List<Map<String, Object>> data = new ArrayList<>();
        for (PortTrafficStat s : trafficRepository
                .findBySwitchIdAndPortIndexAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        switchId, portIndex, DateTimeSerializers.startFromHours(h))) {
            data.add(Maps.of(
                    "timestamp", DateTimeSerializers.fmt(s.getTimestamp()),
                    "in_rate_bps", s.getInRateBps(),
                    "out_rate_bps", s.getOutRateBps(),
                    "in_errors", s.getInErrors(),
                    "out_errors", s.getOutErrors(),
                    "in_error_rate", s.getInErrorRate(),
                    "out_error_rate", s.getOutErrorRate()));
        }
        return Maps.of("switch_id", switchId, "port_index", portIndex, "data", data);
    }

    /** GET /api/switches/{id}/hardware?hours */
    @Transactional(readOnly = true)
    public Map<String, Object> switchHardware(String switchId, int hours) {
        int h = clamp(hours, 1, 24);
        List<Map<String, Object>> data = new ArrayList<>();
        for (HardwareHealth r : hardwareRepository
                .findBySwitchIdAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        switchId, DateTimeSerializers.startFromHours(h))) {
            data.add(Maps.of(
                    "timestamp", DateTimeSerializers.fmt(r.getTimestamp()),
                    "power1_status", r.getPower1Status(),
                    "power2_status", r.getPower2Status(),
                    "temperature", r.getTemperature(),
                    "temperature_alarm", r.getTemperatureAlarm(),
                    "cpu_usage", r.getCpuUsage(),
                    "memory_usage", r.getMemoryUsage(),
                    "overall_status", r.getOverallStatus()));
        }
        return Maps.of("switch_id", switchId, "data", data);
    }

    // ==================== 服务器 ====================

    /**
     * GET /api/servers/summary
     * 迁移前 total 取自 devices.yaml 的服务器条数, 与库里的实际台账可能不一致;
     * 改造后一律按 server_devices.enabled 统计. abnormal_processes 同样改为
     * "各服务器最新一轮里挂掉的关键进程数" (迁移前统计的是历史累计行数).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> serverSummary() {
        long total = serverRepository.countByEnabledIsTrue();
        long online = serverRepository.countByIsOnlineIsTrue();
        long abnormal = processRepository.findLatestCriticalDown().size();
        return Maps.of(
                "total", total,
                "online", online,
                "offline", Math.max(0, total - online),
                "online_rate", RoundUtil.percent(online, total, 1),
                "abnormal_processes", abnormal);
    }

    /** GET /api/servers/list */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> serverList(int page, int pageSize, String keyword) {
        int p = Math.max(1, page);
        int size = clamp(pageSize, 1, 100);
        Page<ServerDevice> result = serverRepository.findAll(
                serverSpec(keyword),
                PageRequest.of(p - 1, size, Sort.by(Sort.Order.desc("isOnline"), Sort.Order.asc("id"))));

        Map<String, ServerMetric> latestMetrics = latestServerMetrics();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ServerDevice s : result.getContent()) {
            ServerMetric m = latestMetrics.get(s.getId());
            items.add(Maps.of(
                    "id", s.getId(),
                    "name", s.getName(),
                    "ip", s.getIp(),
                    "os_type", s.getOsType(),
                    "location", s.getLocation(),
                    "hostname", s.getHostname(),
                    "is_online", s.getIsOnline(),
                    "uptime_seconds", s.getUptimeSeconds(),
                    "last_poll_time", DateTimeSerializers.fmt(s.getLastPollTime()),
                    "metrics", m == null ? null : Maps.of(
                            "cpu_usage", m.getCpuUsagePercent(),
                            "memory_usage", m.getMemoryUsagePercent(),
                            "memory_total_mb", m.getMemoryTotalMb(),
                            "disk_usage", m.getDiskUsagePercent(),
                            "disk_total_gb", m.getDiskTotalGb(),
                            "net_send_rate_bps", m.getNetSendRateBps(),
                            "net_recv_rate_bps", m.getNetRecvRateBps())));
        }
        return PageResult.of(result.getTotalElements(), p, size, items);
    }

    /** GET /api/servers/{id}/detail */
    @Transactional(readOnly = true)
    public Map<String, Object> serverDetail(String serverId) {
        ServerDevice s = serverRepository.findById(serverId)
                .orElseThrow(() -> BizException.notFound("服务器不存在"));
        return Maps.of(
                "id", s.getId(),
                "name", s.getName(),
                "ip", s.getIp(),
                "os_type", s.getOsType(),
                "location", s.getLocation(),
                "hostname", s.getHostname(),
                "os_version", s.getOsVersion(),
                "cpu_cores", s.getCpuCores(),
                "total_memory_mb", s.getTotalMemoryMb(),
                "is_online", s.getIsOnline(),
                "uptime_seconds", s.getUptimeSeconds(),
                "last_poll_time", DateTimeSerializers.fmt(s.getLastPollTime()));
    }

    /** GET /api/servers/{id}/metrics?hours —— 注意 trend 里是 net_send_rate / net_recv_rate (无 _bps 后缀) */
    @Transactional(readOnly = true)
    public Map<String, Object> serverMetrics(String serverId, int hours) {
        int h = clamp(hours, 1, 24);
        if (!serverRepository.existsById(serverId)) {
            throw BizException.notFound("服务器不存在");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (ServerMetric r : metricRepository
                .findByServerIdAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        serverId, DateTimeSerializers.startFromHours(h))) {
            data.add(Maps.of(
                    "timestamp", DateTimeSerializers.fmt(r.getTimestamp()),
                    "cpu_usage", r.getCpuUsagePercent(),
                    "memory_usage", r.getMemoryUsagePercent(),
                    "memory_used_mb", r.getMemoryUsedMb(),
                    "disk_usage", r.getDiskUsagePercent(),
                    "disk_read_bps", r.getDiskReadBps(),
                    "disk_write_bps", r.getDiskWriteBps(),
                    "net_send_rate", r.getNetSendRateBps(),
                    "net_recv_rate", r.getNetRecvRateBps()));
        }
        return Maps.of("server_id", serverId, "data", data);
    }

    /** GET /api/servers/{id}/processes —— 取最新一轮, 按进程名去重保留首条 */
    @Transactional(readOnly = true)
    public Map<String, Object> serverProcesses(String serverId) {
        List<Map<String, Object>> processes = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ProcessStatus p : processRepository.findLatestOfServer(serverId)) {
            if (seen.containsKey(p.getProcessName())) {
                continue;
            }
            seen.put(p.getProcessName(), Boolean.TRUE);
            processes.add(Maps.of(
                    "process_name", p.getProcessName(),
                    "pid", p.getPid(),
                    "is_running", p.getIsRunning(),
                    "cpu_percent", p.getCpuPercent(),
                    "memory_mb", RoundUtil.round(p.getMemoryMb(), 1),
                    "memory_percent", p.getMemoryPercent(),
                    "port", p.getPort(),
                    "port_open", p.getPortOpen(),
                    "is_critical", p.getIsCritical(),
                    "status_detail", p.getStatusDetail(),
                    "timestamp", DateTimeSerializers.fmt(p.getTimestamp())));
        }
        return Maps.of("server_id", serverId, "processes", processes);
    }

    // ==================== 总览 ====================

    /**
     * GET /api/dashboard/overview
     * 迁移前只有设备/告警计数, 改造后按方案扩展点检待办、健康分布与最近 10 条告警,
     * 原有键名与结构保持不变 (前端 dashboard.js 只读旧键).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboardOverview() {
        long swTotal = switchRepository.count();
        long swOnline = switchRepository.countByIsOnlineIsTrue();
        long srvTotal = serverRepository.count();
        long srvOnline = serverRepository.countByIsOnlineIsTrue();
        long active = alertRepository.countByStatus("active");
        long critical = 0;
        for (Object[] row : alertRepository.countActiveBySeverity()) {
            if ("CRITICAL".equals(row[0])) {
                critical = RoundUtil.safeLong(row[1]);
            }
        }

        Map<String, Long> healthDistribution = new LinkedHashMap<>();
        for (Object[] row : healthRepository.countByGrade()) {
            healthDistribution.put(String.valueOf(row[0]), RoundUtil.safeLong(row[1]));
        }

        return Maps.of(
                "switches", Maps.of("total", swTotal, "online", swOnline, "offline", swTotal - swOnline),
                "servers", Maps.of("total", srvTotal, "online", srvOnline, "offline", srvTotal - srvOnline),
                "alerts", Maps.of("active", active, "critical", critical),
                "total_devices", swTotal + srvTotal,
                "online_devices", swOnline + srvOnline,
                "online_rate", RoundUtil.percent(swOnline + srvOnline, swTotal + srvTotal, 1),
                "inspection", Maps.of(
                        "pending", taskRepository.countByStatusIn(List.of("pending", "in_progress")),
                        "overdue", taskRepository.countByStatusIn(List.of("overdue"))),
                "health", Maps.of(
                        "distribution", healthDistribution,
                        "avg_score", RoundUtil.round(healthRepository.averageScore(), 1)),
                "recent_alerts", alertRepository
                        .findByStatusOrderByTriggeredAtDesc("active", PageRequest.of(0, 10))
                        .stream().map(AlertView::brief).toList());
    }

    // ==================== 内部工具 ====================

    /** switch_devices 查询条件: 名称/IP/位置模糊匹配 + 仅在线 */
    private Specification<SwitchDevice> switchSpec(String keyword, boolean onlineOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("ip")), like),
                        cb.like(cb.lower(root.get("location")), like)));
            }
            if (onlineOnly) {
                predicates.add(cb.isTrue(root.get("isOnline")));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<ServerDevice> serverSpec(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return cb.conjunction();
            }
            String like = "%" + keyword.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("ip")), like));
        };
    }

    /** 每台交换机最新一条硬件记录的温度 (hardware_health 行数极少, 一次取回即可) */
    private Map<String, Float> latestTemperatures() {
        Map<String, Float> map = new LinkedHashMap<>();
        for (HardwareHealth h : hardwareRepository.findLatestOfEachSwitch()) {
            if (h.getTemperature() != null) {
                map.put(h.getSwitchId(), h.getTemperature());
            }
        }
        return map;
    }

    private Map<String, ServerMetric> latestServerMetrics() {
        Map<String, ServerMetric> map = new LinkedHashMap<>();
        for (ServerMetric m : metricRepository.findLatestOfEachServer()) {
            map.put(m.getServerId(), m);
        }
        return map;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
