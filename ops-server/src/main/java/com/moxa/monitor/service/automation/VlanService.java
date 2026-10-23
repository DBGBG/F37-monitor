package com.moxa.monitor.service.automation;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.service.automation.MoxaSshClient.SwitchTarget;
import com.moxa.monitor.service.system.SysConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VLAN / 端口配置下发: 迁移自 legacy automation/vlan_manager.py.
 * 命令由 CommandTemplateService 生成, 执行与审计由 MoxaSshClient 负责.
 *
 * 设备不存在的返回值保持迁移前的 {"status":"error","message":"交换机不存在"} (HTTP 仍是 200),
 * 前端 automation 页面按 status 判断, 不改语义。
 */
@Service
public class VlanService {

    private static final String NOT_FOUND_MESSAGE = "交换机不存在";

    private final MoxaSshClient sshClient;
    private final CommandTemplateService templates;
    private final SysConfigService configService;

    public VlanService(MoxaSshClient sshClient, CommandTemplateService templates, SysConfigService configService) {
        this.sshClient = sshClient;
        this.templates = templates;
        this.configService = configService;
    }

    /** POST /api/automation/vlan/create */
    public Map<String, Object> createVlan(String switchId, int vlanId, String vlanName,
                                          String description, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        List<String> commands = templates.vlanCreate(vlanId, vlanName, description);
        return sshClient.execute(target, commands, "vlan",
                "创建 VLAN " + vlanId + " (" + nullToEmpty(vlanName) + ")", operator,
                Maps.of("vlan_id", vlanId, "vlan_name", nullToEmpty(vlanName),
                        "description", nullToEmpty(description)));
    }

    /** POST /api/automation/vlan/delete */
    public Map<String, Object> deleteVlan(String switchId, int vlanId, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        return sshClient.execute(target, templates.vlanDelete(vlanId), "vlan",
                "删除 VLAN " + vlanId, operator, Maps.of("vlan_id", vlanId));
    }

    /** POST /api/automation/port/access */
    public Map<String, Object> assignPortAccess(String switchId, String portName, int vlanId, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        return sshClient.execute(target, templates.portAccess(portName, vlanId), "port",
                "端口 " + portName + " 设为 Access VLAN " + vlanId, operator,
                Maps.of("port_name", nullToEmpty(portName), "vlan_id", vlanId, "mode", "access"));
    }

    /** POST /api/automation/port/trunk */
    public Map<String, Object> setPortTrunk(String switchId, String portName, List<Integer> allowedVlans,
                                            int nativeVlan, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        List<Integer> vlans = allowedVlans == null ? List.of() : allowedVlans;
        return sshClient.execute(target, templates.portTrunk(portName, vlans, nativeVlan), "port",
                "端口 " + portName + " 设为 Trunk (VLANs: " + vlans + ")", operator,
                Maps.of("port_name", nullToEmpty(portName), "mode", "trunk",
                        "allowed_vlans", vlans, "native_vlan", nativeVlan));
    }

    /** POST /api/automation/port/toggle */
    public Map<String, Object> togglePort(String switchId, String portName, boolean enabled, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        return sshClient.execute(target, templates.portToggle(portName, enabled), "port",
                (enabled ? "启用" : "禁用") + "端口 " + portName, operator,
                Maps.of("port_name", nullToEmpty(portName), "enabled", enabled));
    }

    /**
     * POST /api/automation/vlan/batch
     * vlans: [{vlan_id, name, description}]; name 缺省为 VLAN{vlan_id}, 与迁移前一致.
     */
    public Map<String, Object> batchCreateVlans(String switchId, List<Map<String, Object>> vlans, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        List<Map<String, Object>> wanted = vlans == null ? List.of() : vlans;
        List<String> commands = new ArrayList<>();
        for (Map<String, Object> vlan : wanted) {
            int vlanId = intValue(vlan.get("vlan_id"), 0);
            Object name = vlan.get("name");
            String vlanName = name == null || String.valueOf(name).isBlank() ? "VLAN" + vlanId : String.valueOf(name);
            commands.addAll(templates.vlanCreate(vlanId, vlanName, string(vlan.get("description"))));
        }
        return sshClient.execute(target, commands, "vlan",
                "批量创建 " + wanted.size() + " 个 VLAN", operator, Maps.of("vlans", wanted));
    }

    /** POST /api/automation/vlan/batch (use_vlan_plan=true) —— 规划为空时保持迁移前的失败文案 */
    public Map<String, Object> applyVlanPlan(String switchId, String operator) {
        List<Map<String, Object>> plan = vlanPlan();
        if (plan.isEmpty()) {
            return Maps.of("status", "failed", "error", "未配置 VLAN 规划");
        }
        return batchCreateVlans(switchId, plan, operator);
    }

    /** GET /api/automation/vlan-plan —— sys_config.vlan_plan (原 devices.yaml) */
    public List<Map<String, Object>> vlanPlan() {
        return configService.objectList(SysConfigService.VLAN_PLAN);
    }

    /** 手动 CLI 下发: POST /api/automation/raw-command */
    public Map<String, Object> rawCommand(String switchId, List<String> commands, String operator) {
        SwitchTarget target = sshClient.findTarget(switchId);
        if (target == null) {
            return switchNotFound();
        }
        return sshClient.execute(target, commands == null ? List.of() : commands, "raw",
                "手动执行原始命令", operator, Map.of());
    }

    private static Map<String, Object> switchNotFound() {
        return Maps.of("status", "error", "message", NOT_FOUND_MESSAGE);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
