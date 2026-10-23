package com.moxa.monitor.service.device;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Secrets;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.DeviceHealthRepository;
import com.moxa.monitor.repo.HardwareHealthRepository;
import com.moxa.monitor.repo.PortStatusRepository;
import com.moxa.monitor.repo.PortTrafficStatRepository;
import com.moxa.monitor.repo.ProcessStatusRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.ServerMetricRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.realtime.LiveStateHolder;
import com.moxa.monitor.service.system.CollectorMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备台账维护服务: 名称 / 位置 / 启停 / 采集凭据.
 *
 * 分层约束的落地点 —— switch_devices 与 server_devices 由采集层写采集字段,
 * 这里一律通过 SwitchDeviceRepository / ServerDeviceRepository 的定向 UPDATE 只碰台账列,
 * 绝不 save(整个实体), 否则会用读取时的旧值冲掉 Python 刚写入的 is_online / last_poll_time.
 */
@Service
public class DeviceManageService {

    private static final Logger log = LoggerFactory.getLogger(DeviceManageService.class);

    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final PortStatusRepository portRepository;
    private final PortTrafficStatRepository trafficRepository;
    private final HardwareHealthRepository hardwareRepository;
    private final ServerMetricRepository metricRepository;
    private final ProcessStatusRepository processRepository;
    private final AlertRecordRepository alertRepository;
    private final DeviceHealthRepository healthRepository;
    private final CollectorMonitorService collectorService;
    private final LiveStateHolder live;

    public DeviceManageService(SwitchDeviceRepository switchRepository,
                               ServerDeviceRepository serverRepository,
                               PortStatusRepository portRepository,
                               PortTrafficStatRepository trafficRepository,
                               HardwareHealthRepository hardwareRepository,
                               ServerMetricRepository metricRepository,
                               ProcessStatusRepository processRepository,
                               AlertRecordRepository alertRepository,
                               DeviceHealthRepository healthRepository,
                               CollectorMonitorService collectorService,
                               LiveStateHolder live) {
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.portRepository = portRepository;
        this.trafficRepository = trafficRepository;
        this.hardwareRepository = hardwareRepository;
        this.metricRepository = metricRepository;
        this.processRepository = processRepository;
        this.alertRepository = alertRepository;
        this.healthRepository = healthRepository;
        this.collectorService = collectorService;
        this.live = live;
    }

    // ==================== 读 (带凭据, 供编辑) ====================

    /**
     * PUT 表单的初始值: 与 detail 接口不同, 这里要返回凭据, 但敏感项一律掩码,
     * 前端只在用户真的改动时才提交新值.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> switchForEdit(String switchId) {
        SwitchDevice d = requireSwitch(switchId);
        return Maps.of(
                "id", d.getId(),
                "name", d.getName(),
                "ip", d.getIp(),
                "model", d.getModel(),
                "location", d.getLocation(),
                "enabled", d.getEnabled(),
                "is_online", d.getIsOnline(),
                "snmp_config", Secrets.mask(d.getSnmpConfig()),
                "ssh_config", Secrets.mask(d.getSshConfig()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> serverForEdit(String serverId) {
        ServerDevice s = requireServer(serverId);
        return Maps.of(
                "id", s.getId(),
                "name", s.getName(),
                "ip", s.getIp(),
                "os_type", s.getOsType(),
                "location", s.getLocation(),
                "enabled", s.getEnabled(),
                "is_online", s.getIsOnline(),
                "monitoring_config", Secrets.mask(s.getMonitoringConfig()),
                "processes_config", s.getProcessesConfig() == null ? List.of() : s.getProcessesConfig());
    }

    // ==================== 交换机 ====================

    /** PUT /api/switches/{id} —— 只改传入的列; 凭据传掩码视为不变 */
    @Transactional
    public Map<String, Object> updateSwitch(String switchId, String name, String location, Boolean enabled,
                                            Map<String, Object> snmpConfig, Map<String, Object> sshConfig) {
        SwitchDevice d = requireSwitch(switchId);

        String newName = name == null || name.isBlank() ? d.getName() : name.trim();
        String newLocation = location == null ? d.getLocation() : location.trim();
        boolean newEnabled = enabled == null ? Boolean.TRUE.equals(d.getEnabled()) : enabled;
        if (newName.length() > 200 || newLocation.length() > 200) {
            throw BizException.badRequest("名称与位置长度不能超过 200 字符");
        }
        switchRepository.updateLedger(switchId, newName, newLocation, newEnabled);

        if (snmpConfig != null) {
            switchRepository.updateSnmpConfig(switchId,
                    collectorService.toJson(Secrets.merge(d.getSnmpConfig(), snmpConfig)));
        }
        if (sshConfig != null) {
            switchRepository.updateSshConfig(switchId,
                    collectorService.toJson(Secrets.merge(d.getSshConfig(), sshConfig)));
        }

        // 停用的设备立刻从实时内存态摘掉, 不必等下一轮轮询
        if (!newEnabled) {
            live.updateSwitch(switchId, Maps.of("is_online", false));
        }
        log.info("交换机台账更新: id={} name={} enabled={}", switchId, newName, newEnabled);
        return switchForEdit(switchId);
    }

    /** DELETE /api/switches/{id} —— 台账与时序一并清理 (子表外键没有 CASCADE) */
    @Transactional
    public void deleteSwitch(String switchId) {
        requireSwitch(switchId);
        portRepository.deleteBySwitchId(switchId);
        trafficRepository.deleteBySwitchId(switchId);
        hardwareRepository.deleteBySwitchId(switchId);
        alertRepository.resolveAllOfDevice(switchId, LocalDateTime.now());
        healthRepository.deleteById(switchId);
        switchRepository.deleteById(switchId);
        live.switches().remove(switchId);
        log.info("交换机已删除并清理时序: {}", switchId);
    }

    // ==================== 服务器 ====================

    /** PUT /api/servers/{id} */
    @Transactional
    public Map<String, Object> updateServer(String serverId, String name, String location, Boolean enabled,
                                            Map<String, Object> monitoringConfig,
                                            List<Map<String, Object>> processesConfig) {
        ServerDevice s = requireServer(serverId);

        String newName = name == null || name.isBlank() ? s.getName() : name.trim();
        String newLocation = location == null ? s.getLocation() : location.trim();
        boolean newEnabled = enabled == null ? Boolean.TRUE.equals(s.getEnabled()) : enabled;
        if (newName.length() > 200 || newLocation.length() > 200) {
            throw BizException.badRequest("名称与位置长度不能超过 200 字符");
        }
        serverRepository.updateLedger(serverId, newName, newLocation, newEnabled);

        if (monitoringConfig != null) {
            serverRepository.updateMonitoringConfig(serverId,
                    collectorService.toJson(Secrets.merge(s.getMonitoringConfig(), monitoringConfig)));
        }
        if (processesConfig != null) {
            serverRepository.updateProcessesConfig(serverId, collectorService.toJson(processesConfig));
        }
        if (!newEnabled) {
            live.updateServer(serverId, Maps.of("is_online", false));
        }
        log.info("服务器台账更新: id={} name={} enabled={}", serverId, newName, newEnabled);
        return serverForEdit(serverId);
    }

    /** DELETE /api/servers/{id} */
    @Transactional
    public void deleteServer(String serverId) {
        requireServer(serverId);
        processRepository.deleteByServerId(serverId);
        metricRepository.deleteByServerId(serverId);
        alertRepository.resolveAllOfDevice(serverId, LocalDateTime.now());
        healthRepository.deleteById(serverId);
        serverRepository.deleteById(serverId);
        live.servers().remove(serverId);
        log.info("服务器已删除并清理时序: {}", serverId);
    }

    /**
     * 新增服务器: 自动发现只产出交换机, 服务器必须手工登记 (迁移前写在 devices.yaml).
     * 插入后由采集层下一轮 (最多 5 分钟热加载) 开始采集.
     */
    @Transactional
    public Map<String, Object> createServer(String id, String name, String ip, String osType, String location,
                                           Map<String, Object> monitoringConfig,
                                           List<Map<String, Object>> processesConfig) {
        String deviceId = id == null || id.isBlank() ? "srv-" + (ip == null ? "" : ip.replace('.', '-')) : id.trim();
        if (ip == null || ip.isBlank()) {
            throw BizException.badRequest("服务器 IP 不能为空");
        }
        if (serverRepository.existsById(deviceId)) {
            throw BizException.badRequest("服务器已存在: " + deviceId);
        }
        ServerDevice s = new ServerDevice();
        s.setId(deviceId);
        s.setName(name == null || name.isBlank() ? deviceId : name.trim());
        s.setIp(ip.trim());
        s.setOsType("windows".equalsIgnoreCase(osType) ? "windows" : "linux");
        s.setLocation(location == null ? "" : location.trim());
        s.setHostname("");
        s.setOsVersion("");
        s.setCpuCores(0);
        s.setTotalMemoryMb(0);
        s.setIsOnline(false);
        s.setUptimeSeconds(0);
        s.setMonitoringConfig(monitoringConfig == null ? new LinkedHashMap<>()
                : Secrets.merge(null, monitoringConfig));
        s.setProcessesConfig(processesConfig == null ? List.of() : processesConfig);
        s.setEnabled(true);
        serverRepository.save(s);
        collectorService.submit(CollectorMonitorService.CMD_RELOAD_CONFIG, Map.of(), "新增服务器 " + deviceId);
        log.info("新增服务器: {} {}", deviceId, ip);
        return serverForEdit(deviceId);
    }

    // ==================== 指令转发 ====================

    /** POST /api/switches/{id}/refresh -> 让采集层立刻定向重采一次 */
    public Map<String, Object> requestRefresh(String switchId) {
        requireSwitch(switchId);
        Integer commandId = collectorService.submit(CollectorMonitorService.CMD_COLLECT_NOW,
                Map.of("device_id", switchId), "定向刷新");
        return Maps.of("command_id", commandId, "status", "pending",
                "message", "已通知采集层刷新, 数秒内生效");
    }

    // ==================== 内部 ====================

    private SwitchDevice requireSwitch(String switchId) {
        return switchRepository.findById(switchId).orElseThrow(() -> BizException.notFound("设备不存在"));
    }

    private ServerDevice requireServer(String serverId) {
        return serverRepository.findById(serverId).orElseThrow(() -> BizException.notFound("服务器不存在"));
    }
}
