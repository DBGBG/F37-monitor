package com.moxa.monitor.service.system;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Secrets;
import com.moxa.monitor.domain.DiscoveryGroup;
import com.moxa.monitor.repo.DiscoveryGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 扫描网段 (discovery_groups) 管理: 取代迁移前 devices.yaml 的 switch_groups.
 * 业务层增删改, 采集层每 5 分钟热加载一次; 保存后立即下发 reload_config 指令, 让改动马上生效.
 */
@Service
public class DiscoveryGroupService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryGroupService.class);

    private final DiscoveryGroupRepository repository;
    private final CollectorMonitorService collectorService;

    public DiscoveryGroupService(DiscoveryGroupRepository repository, CollectorMonitorService collectorService) {
        this.repository = repository;
        this.collectorService = collectorService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DiscoveryGroup group : repository.findAllByOrderByIdAsc()) {
            rows.add(view(group));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Integer id) {
        return view(require(id));
    }

    @Transactional
    public Map<String, Object> create(String groupName, String ipRange, String model, String locationPrefix,
                                      Map<String, Object> snmpConfig, Map<String, Object> sshConfig,
                                      Boolean enabled) {
        validate(groupName, ipRange);
        DiscoveryGroup group = new DiscoveryGroup();
        group.setGroupName(groupName.trim());
        group.setIpRange(ipRange.trim());
        group.setModel(model == null ? "" : model.trim());
        group.setLocationPrefix(locationPrefix == null ? "" : locationPrefix.trim());
        group.setSnmpConfig(Secrets.merge(null, snmpConfig));
        group.setSshConfig(Secrets.merge(null, sshConfig));
        group.setEnabled(enabled == null || enabled);
        repository.save(group);
        notifyCollector("新增扫描网段 " + group.getIpRange());
        log.info("新增扫描网段: {} {}", group.getGroupName(), group.getIpRange());
        return view(group);
    }

    @Transactional
    public Map<String, Object> update(Integer id, String groupName, String ipRange, String model,
                                      String locationPrefix, Map<String, Object> snmpConfig,
                                      Map<String, Object> sshConfig, Boolean enabled) {
        DiscoveryGroup group = require(id);
        String newName = groupName == null || groupName.isBlank() ? group.getGroupName() : groupName.trim();
        String newRange = ipRange == null || ipRange.isBlank() ? group.getIpRange() : ipRange.trim();
        validate(newName, newRange);

        group.setGroupName(newName);
        group.setIpRange(newRange);
        if (model != null) {
            group.setModel(model.trim());
        }
        if (locationPrefix != null) {
            group.setLocationPrefix(locationPrefix.trim());
        }
        if (snmpConfig != null) {
            group.setSnmpConfig(Secrets.merge(group.getSnmpConfig(), snmpConfig));
        }
        if (sshConfig != null) {
            group.setSshConfig(Secrets.merge(group.getSshConfig(), sshConfig));
        }
        if (enabled != null) {
            group.setEnabled(enabled);
        }
        repository.save(group);
        notifyCollector("更新扫描网段 " + group.getIpRange());
        return view(group);
    }

    /** 启停切换: 采集层下一轮就会跳过该网段 */
    @Transactional
    public Map<String, Object> toggle(Integer id) {
        DiscoveryGroup group = require(id);
        group.setEnabled(!Boolean.TRUE.equals(group.getEnabled()));
        repository.save(group);
        notifyCollector("切换扫描网段启停: " + group.getIpRange());
        return view(group);
    }

    @Transactional
    public void delete(Integer id) {
        DiscoveryGroup group = require(id);
        repository.delete(group);
        notifyCollector("删除扫描网段 " + group.getIpRange());
        log.info("删除扫描网段: {}", group.getIpRange());
    }

    // ---------------- 内部 ----------------

    private DiscoveryGroup require(Integer id) {
        return repository.findById(id).orElseThrow(() -> BizException.notFound("扫描网段不存在: " + id));
    }

    private void validate(String groupName, String ipRange) {
        if (groupName == null || groupName.isBlank()) {
            throw BizException.badRequest("分组名称不能为空");
        }
        if (ipRange == null || ipRange.isBlank()) {
            throw BizException.badRequest("IP 范围不能为空");
        }
        String range = ipRange.trim();
        // 四种写法与采集层 config.expand_ip_range 一致:
        //   10.51.200.5 / 10.51.200.1-200 / 10.51.200.1-10.51.200.200 / 10.51.200.0/24
        String ip = "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})";
        boolean ok = range.matches(ip)
                || range.matches(ip + "-" + ip)
                || range.matches(ip + "-\\d{1,3}")
                || range.matches(ip + "/\\d{1,2}");
        if (!ok) {
            throw BizException.badRequest(
                    "IP 范围格式不正确, 支持 192.168.1.5 / 192.168.1.1-200 / 192.168.1.1-192.168.1.200 / 192.168.1.0/24");
        }
    }

    private void notifyCollector(String reason) {
        collectorService.submit(CollectorMonitorService.CMD_RELOAD_CONFIG, Map.of(), reason);
    }

    private Map<String, Object> view(DiscoveryGroup group) {
        return Maps.of(
                "id", group.getId(),
                "group_name", group.getGroupName(),
                "ip_range", group.getIpRange(),
                "model", group.getModel(),
                "location_prefix", group.getLocationPrefix(),
                "snmp_config", Secrets.mask(group.getSnmpConfig()),
                "ssh_config", Secrets.mask(group.getSshConfig()),
                "enabled", group.getEnabled(),
                "created_at", DateTimeSerializers.fmt(group.getCreatedAt()),
                "updated_at", DateTimeSerializers.fmt(group.getUpdatedAt()));
    }
}
