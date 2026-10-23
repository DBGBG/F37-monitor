package com.moxa.monitor.service.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * MOXA CLI 命令模板.
 * 迁移前用 Jinja2 渲染 config_templates/*.j2, 这里保留同样的模板文本与占位符语义,
 * 用 {name} 变量替换 + "过滤空行与 # 注释行" 还原 _render_commands 的行为, 不再引入模板引擎.
 */
@Service
public class CommandTemplateService {

    /** vlan_config.j2 :: action == "create" */
    private static final String VLAN_CREATE = """
            # 创建 VLAN {vlan_id}
            vlan {vlan_id}
            name {vlan_name}
            {description_line}
            exit""";

    /** vlan_config.j2 :: action == "delete" */
    private static final String VLAN_DELETE = """
            # 删除 VLAN {vlan_id}
            no vlan {vlan_id}""";

    /** port_config.j2 :: action == "set_access" */
    private static final String PORT_ACCESS = """
            # 将端口 {port_name} 设为 Access 模式，分配到 VLAN {vlan_id}
            interface {port_name}
            switchport mode access
            switchport access vlan {vlan_id}
            exit""";

    /** port_config.j2 :: action == "set_trunk" */
    private static final String PORT_TRUNK = """
            # 将端口 {port_name} 设为 Trunk 模式
            interface {port_name}
            switchport mode trunk
            {native_line}
            {allowed_line}
            exit""";

    /** port_config.j2 :: action == "enable" */
    private static final String PORT_ENABLE = """
            # 启用端口 {port_name}
            interface {port_name}
            no shutdown
            exit""";

    /** port_config.j2 :: action == "disable" */
    private static final String PORT_DISABLE = """
            # 禁用端口 {port_name}
            interface {port_name}
            shutdown
            exit""";

    /** 创建 VLAN: description 为空时与模板一样不产生 description 行 */
    public List<String> vlanCreate(int vlanId, String vlanName, String description) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("vlan_id", String.valueOf(vlanId));
        vars.put("vlan_name", nullToEmpty(vlanName));
        vars.put("description_line", isBlank(description) ? "" : "description " + description.trim());
        return render(VLAN_CREATE, vars);
    }

    /** 删除 VLAN */
    public List<String> vlanDelete(int vlanId) {
        return render(VLAN_DELETE, Map.of("vlan_id", String.valueOf(vlanId)));
    }

    /** 端口设为 Access 并划入指定 VLAN */
    public List<String> portAccess(String portName, int vlanId) {
        return render(PORT_ACCESS, Map.of(
                "port_name", nullToEmpty(portName),
                "vlan_id", String.valueOf(vlanId)));
    }

    /**
     * 端口设为 Trunk.
     * 迁移前模板里 native_vlan / allowed_vlans 是 "defined" 判断, 调用方始终传值,
     * 这里保留为空则整行省略的语义.
     */
    public List<String> portTrunk(String portName, List<Integer> allowedVlans, Integer nativeVlan) {
        StringBuilder allowed = new StringBuilder();
        if (allowedVlans != null) {
            for (Integer vlan : allowedVlans) {
                if (allowed.length() > 0) {
                    allowed.append(',');
                }
                allowed.append(vlan);
            }
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("port_name", nullToEmpty(portName));
        vars.put("native_line", nativeVlan == null ? "" : "switchport trunk native vlan " + nativeVlan);
        vars.put("allowed_line", allowedVlans == null || allowed.length() == 0
                ? "" : "switchport trunk allowed vlan " + allowed);
        return render(PORT_TRUNK, vars);
    }

    /** 启用 / 禁用端口 */
    public List<String> portToggle(String portName, boolean enabled) {
        return render(enabled ? PORT_ENABLE : PORT_DISABLE, Map.of("port_name", nullToEmpty(portName)));
    }

    /** 变量替换 -> 按行拆分 -> 丢弃空行与注释行 */
    private static List<String> render(String template, Map<String, String> vars) {
        String text = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        List<String> commands = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                commands.add(trimmed);
            }
        }
        return commands;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
