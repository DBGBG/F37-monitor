package com.moxa.monitor.service.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.moxa.monitor.common.HashUtil;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.AlertRecord;
import com.moxa.monitor.domain.HardwareHealth;
import com.moxa.monitor.domain.ProcessStatus;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.ServerMetric;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.HardwareHealthRepository;
import com.moxa.monitor.repo.PortStatusRepository;
import com.moxa.monitor.repo.ProcessStatusRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.ServerMetricRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.realtime.LiveStateHolder;
import com.moxa.monitor.service.realtime.StreamService;
import com.moxa.monitor.service.system.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 告警引擎: 迁移自 legacy alerts/alert_engine.py, 8 条规则逐条对齐.
 * 与迁移前相同的几个关键语义必须保留, 否则前端告警行为会变:
 * 1. alert_id = md5(device_id + ":" + rule_name) 前 16 位, 同设备同规则只留一条记录;
 * 2. 已恢复/已确认的历史告警再次满足条件时复用原行并重置为 active (含重新通知);
 * 3. 维护窗口内静默 —— 不落库也不推送;
 * 4. 设备离线时吸收其端口 Down 子告警 (根因优先于症状), 并清理跳变检测状态;
 * 5. 端口 Down 只在 "上一轮 oper=1 且本轮 admin=1,oper=2" 的跳变时产生, 未接线端口不报;
 * 6. 每轮结束扫描 notified=0 且 status=active 的记录补发通知并回填渠道.
 * 防重入: 定时检查与"采集心跳变化后的即时检查"可能并发, 上一轮未结束则跳过本轮.
 */
@Service
public class AlertEngineService {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineService.class);

    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final PortStatusRepository portRepository;
    private final HardwareHealthRepository hardwareRepository;
    private final ServerMetricRepository metricRepository;
    private final ProcessStatusRepository processRepository;
    private final AlertRecordRepository alertRepository;
    private final SysConfigService configService;
    private final MaintenanceWindowService maintenanceService;
    private final NotificationService notificationService;
    private final StreamService stream;
    private final LiveStateHolder live;
    private final TransactionTemplate txTemplate;

    /** switch_id -> (port_index -> 上一轮 oper_status) */
    private final Map<String, Map<Integer, Integer>> prevOper = new ConcurrentHashMap<>();
    /** switch_id -> 已判定为链路中断的端口集合 */
    private final Map<String, Set<Integer>> alertedDown = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public AlertEngineService(SwitchDeviceRepository switchRepository,
                              ServerDeviceRepository serverRepository,
                              PortStatusRepository portRepository,
                              HardwareHealthRepository hardwareRepository,
                              ServerMetricRepository metricRepository,
                              ProcessStatusRepository processRepository,
                              AlertRecordRepository alertRepository,
                              SysConfigService configService,
                              MaintenanceWindowService maintenanceService,
                              NotificationService notificationService,
                              StreamService stream,
                              LiveStateHolder live,
                              TransactionTemplate txTemplate) {
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.portRepository = portRepository;
        this.hardwareRepository = hardwareRepository;
        this.metricRepository = metricRepository;
        this.processRepository = processRepository;
        this.alertRepository = alertRepository;
        this.configService = configService;
        this.maintenanceService = maintenanceService;
        this.notificationService = notificationService;
        this.stream = stream;
        this.live = live;
        this.txTemplate = txTemplate;
    }

    /** 一条待创建的告警, 与迁移前 _create_alert 的关键字参数一一对应 */
    public record AlertCreate(String deviceId, String deviceName, String deviceType, String ruleName,
                              String severity, String title, String description,
                              String metricValue, String threshold) {

        public static AlertCreate of(String deviceId, String deviceName, String deviceType, String ruleName,
                                     String severity, String title, String description) {
            return new AlertCreate(deviceId, deviceName, deviceType, ruleName, severity, title, description, "", "");
        }
    }

    // ==================== 调度入口 ====================

    /** 周期检查, 间隔取 sys_config.alert_settings.check_interval (默认 30s), 这里固定 30s 与迁移前一致 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void scheduledCheck() {
        checkAll();
    }

    /**
     * 采集层刚写完一轮心跳时的即时检查 (迁移前是采集完成后直接调用 check_all).
     * 走独立单线程池, 绝不占用 2 秒一次的快照轮询线程; 队列满则丢弃, 由 tryLock 兜底.
     */
    @Async("alertCheckExecutor")
    public void triggerCheck() {
        checkAll();
    }

    /** 采集心跳变化时可由 SnapshotPoller / CollectorMonitorService 立即唤起一次 */
    public void checkAll() {
        if (!configService.bool("alert_settings.enabled", true)) {
            return;
        }
        if (!lock.tryLock()) {
            log.debug("告警检查进行中, 跳过本轮");
            return;
        }
        try {
            JsonNode rules = configService.node(SysConfigService.ALERT_RULES);
            txTemplate.executeWithoutResult(status -> {
                maintenanceService.refresh();
                checkSwitchAlerts(rules);
                checkServerAlerts(rules);
            });
            notifyPendingAlerts();
            refreshLiveAlertCounts();
        } catch (Exception ex) {
            log.error("告警检查异常: {}", ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 交换机规则 ====================

    private void checkSwitchAlerts(JsonNode rules) {
        List<SwitchDevice> switches = switchRepository.findByEnabledIsTrueOrderByIpAsc();
        if (switches.isEmpty()) {
            return;
        }
        Map<String, HardwareHealth> hardware = indexLatestHardware();
        Map<String, Map<Integer, int[]>> portStates = loadPortStates();

        for (SwitchDevice sw : switches) {
            JsonNode offlineRule = rules.path("switch_offline");

            // 0. 离线: 触发离线告警并吸收端口级子告警
            if (!Boolean.TRUE.equals(sw.getIsOnline())) {
                if (hasRule(offlineRule)) {
                    createAlert(new AlertCreate(sw.getId(), sw.getName(), "switch", "switch_offline",
                            offlineRule.path("severity").asText("CRITICAL"),
                            "[" + sw.getName() + "] 设备离线",
                            String.format("交换机 %s(%s) 无法通过 SNMP 采集, 可能设备断电或网络中断, 请优先排查",
                                    sw.getName(), sw.getIp()),
                            "", ""));
                }
                resolveRule(sw.getId(), "switch_port_down");
                prevOper.remove(sw.getId());
                alertedDown.remove(sw.getId());
                continue;
            }
            if (hasRule(offlineRule)) {
                resolveRule(sw.getId(), "switch_offline");
            }

            // 1. 端口 Down (仅 up -> down 跳变)
            JsonNode portDownRule = rules.path("switch_port_down");
            if (hasRule(portDownRule)) {
                Map<Integer, int[]> states = portStates.getOrDefault(sw.getId(), Map.of());
                Map<Integer, Integer> previous = prevOper.getOrDefault(sw.getId(), Map.of());
                Set<Integer> alerted = alertedDown.computeIfAbsent(sw.getId(), key -> new LinkedHashSet<>());
                Map<Integer, Integer> currentOper = new LinkedHashMap<>();
                for (Map.Entry<Integer, int[]> entry : states.entrySet()) {
                    int portIndex = entry.getKey();
                    int admin = entry.getValue()[0];
                    int oper = entry.getValue()[1];
                    currentOper.put(portIndex, oper);
                    if (admin == 1 && oper == 2) {
                        Integer prev = previous.get(portIndex);
                        if (prev != null && prev == 1) {
                            alerted.add(portIndex);
                        }
                    } else {
                        alerted.remove(portIndex);
                    }
                }
                prevOper.put(sw.getId(), currentOper);

                if (!alerted.isEmpty()) {
                    List<String> portNames = new ArrayList<>();
                    for (Integer index : new TreeSet<>(alerted)) {
                        portNames.add("Port " + index);
                    }
                    createAlert(new AlertCreate(sw.getId(), sw.getName(), "switch", "switch_port_down",
                            portDownRule.path("severity").asText("WARNING"),
                            "[" + sw.getName() + "] 端口 Down: " + String.join(", ", portNames),
                            String.format("交换机 %s(%s) 有 %d 个端口链路中断", sw.getName(), sw.getIp(), alerted.size()),
                            String.valueOf(alerted.size()), "0"));
                } else {
                    resolveRule(sw.getId(), "switch_port_down");
                }
            }

            // 2. 电源故障
            JsonNode powerRule = rules.path("switch_power_failure");
            HardwareHealth hw = hardware.get(sw.getId());
            if (hasRule(powerRule) && hw != null) {
                boolean p1 = Integer.valueOf(2).equals(hw.getPower1Status());
                boolean p2 = Integer.valueOf(2).equals(hw.getPower2Status());
                if (p1 || p2) {
                    List<String> failed = new ArrayList<>();
                    if (p1) {
                        failed.add("电源1");
                    }
                    if (p2) {
                        failed.add("电源2");
                    }
                    String joined = String.join(", ", failed);
                    createAlert(new AlertCreate(sw.getId(), sw.getName(), "switch", "switch_power_failure",
                            powerRule.path("severity").asText("CRITICAL"),
                            "[" + sw.getName() + "] " + joined + " 故障",
                            String.format("交换机 %s(%s) %s 发生故障，请立即检查", sw.getName(), sw.getIp(), joined),
                            "", ""));
                } else {
                    resolveRule(sw.getId(), "switch_power_failure");
                }
            }

            // 3. CPU 过高
            JsonNode cpuRule = rules.path("switch_cpu_high");
            if (hasRule(cpuRule)) {
                double threshold = cpuRule.path("threshold").asDouble(90);
                double cpu = sw.getCpuUsage() == null ? 0D : sw.getCpuUsage();
                if (cpu > threshold) {
                    String value = percent(cpu);
                    createAlert(new AlertCreate(sw.getId(), sw.getName(), "switch", "switch_cpu_high",
                            cpuRule.path("severity").asText("WARNING"),
                            "[" + sw.getName() + "] CPU 使用率过高: " + value,
                            String.format("交换机 %s CPU 使用率 %s", sw.getName(), value),
                            value, percentThreshold(threshold)));
                } else {
                    resolveRule(sw.getId(), "switch_cpu_high");
                }
            }
        }
    }

    // ==================== 服务器规则 ====================

    private void checkServerAlerts(JsonNode rules) {
        List<ServerDevice> servers = serverRepository.findByIsOnlineIsTrue();
        if (servers.isEmpty()) {
            return;
        }
        Map<String, ServerMetric> metrics = new LinkedHashMap<>();
        for (ServerMetric metric : metricRepository.findLatestOfEachServer()) {
            metrics.putIfAbsent(metric.getServerId(), metric);
        }
        // 与迁移前的注释意图一致: 只看最新一轮采集里挂掉的关键进程 (原实现误查了历史全部行)
        Map<String, List<String>> downCritical = new LinkedHashMap<>();
        for (ProcessStatus process : processRepository.findLatestCriticalDown()) {
            downCritical.computeIfAbsent(process.getServerId(), key -> new ArrayList<>())
                    .add(process.getProcessName());
        }

        for (ServerDevice server : servers) {
            ServerMetric metric = metrics.get(server.getId());
            if (metric == null) {
                continue;
            }
            checkServerThreshold(rules, server, "server_cpu_high", "CPU 使用率过高",
                    metric.getCpuUsagePercent());
            checkServerThreshold(rules, server, "server_memory_high", "内存使用率过高",
                    metric.getMemoryUsagePercent());
            checkServerThreshold(rules, server, "server_disk_high", "磁盘空间不足",
                    metric.getDiskUsagePercent());

            JsonNode procRule = rules.path("server_process_down");
            if (hasRule(procRule)) {
                List<String> procNames = downCritical.get(server.getId());
                if (procNames != null && !procNames.isEmpty()) {
                    String joined = String.join(", ", procNames);
                    createAlert(new AlertCreate(server.getId(), server.getName(), "server", "server_process_down",
                            procRule.path("severity").asText("CRITICAL"),
                            "[" + server.getName() + "] 关键进程异常: " + joined,
                            String.format("服务器 %s 关键进程 %s 未运行", server.getName(), joined),
                            "", ""));
                } else {
                    resolveRule(server.getId(), "server_process_down");
                }
            }
        }
    }

    private void checkServerThreshold(JsonNode rules, ServerDevice server, String ruleName,
                                      String label, Float rawValue) {
        JsonNode rule = rules.path(ruleName);
        if (!hasRule(rule)) {
            return;
        }
        double threshold = rule.path("threshold").asDouble(90);
        double value = rawValue == null ? 0D : rawValue;
        if (value > threshold) {
            String text = percent(value);
            createAlert(new AlertCreate(server.getId(), server.getName(), "server", ruleName,
                    rule.path("severity").asText("WARNING"),
                    "[" + server.getName() + "] " + label + ": " + text,
                    "", text, percentThreshold(threshold)));
        } else {
            resolveRule(server.getId(), ruleName);
        }
    }

    // ==================== 告警生命周期 ====================

    /**
     * 创建或更新告警 (迁移前 _create_alert).
     * 公开给主动分析引擎复用 —— 高危异常升级为告警走同一套去重/抑制/推送逻辑.
     */
    public AlertRecord createAlert(AlertCreate cmd) {
        String alertId = HashUtil.alertId(cmd.deviceId(), cmd.ruleName());

        String window = maintenanceService.suppressedWindowName(cmd.deviceId(), cmd.deviceType());
        if (window != null) {
            log.debug("维护窗口[{}]内抑制告警: {}", window, cmd.title());
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        AlertRecord existing = alertRepository.findByAlertId(alertId).orElse(null);
        if (existing != null) {
            if ("active".equals(existing.getStatus())) {
                existing.setSeverity(cmd.severity());
                existing.setTitle(cmd.title());
                existing.setDescription(cmd.description());
                existing.setMetricValue(nullToEmpty(cmd.metricValue()));
                existing.setThreshold(nullToEmpty(cmd.threshold()));
                return alertRepository.save(existing);
            }
            // 历史告警重新触发: 复用同一行并重置为 active, 让通知能再发一次
            existing.setStatus("active");
            existing.setSeverity(cmd.severity());
            existing.setTitle(cmd.title());
            existing.setDescription(cmd.description());
            existing.setMetricValue(nullToEmpty(cmd.metricValue()));
            existing.setThreshold(nullToEmpty(cmd.threshold()));
            existing.setAcknowledgedBy("");
            existing.setResolvedAt(null);
            existing.setResolvedBy("");
            existing.setNotified(false);
            existing.setNotifyChannels("");
            existing.setTriggeredAt(now);
            log.warn("告警重新触发: [{}] {}", cmd.severity(), cmd.title());
            publishAlertEvent("triggered", cmd);
            return alertRepository.save(existing);
        }

        AlertRecord alert = new AlertRecord();
        alert.setAlertId(alertId);
        alert.setDeviceId(cmd.deviceId());
        alert.setDeviceName(cmd.deviceName());
        alert.setDeviceType(cmd.deviceType());
        alert.setRuleName(cmd.ruleName());
        alert.setSeverity(cmd.severity());
        alert.setTitle(cmd.title());
        alert.setDescription(cmd.description());
        alert.setMetricValue(nullToEmpty(cmd.metricValue()));
        alert.setThreshold(nullToEmpty(cmd.threshold()));
        alert.setStatus("active");
        alert.setAcknowledgedBy("");
        alert.setResolvedBy("");
        alert.setNotified(false);
        alert.setNotifyChannels("");
        alert.setTriggeredAt(now);
        AlertRecord saved = alertRepository.save(alert);
        log.warn("告警触发: [{}] {}", cmd.severity(), cmd.title());
        publishAlertEvent("triggered", cmd);
        return saved;
    }

    /** 条件已消失: 自动恢复该设备对应规则的活跃告警 (迁移前 _resolve_rule) */
    public void resolveRule(String deviceId, String ruleName) {
        AlertRecord alert = alertRepository
                .findByAlertId(HashUtil.alertId(deviceId, ruleName))
                .orElse(null);
        if (alert == null) {
            return;
        }
        if ("active".equals(alert.getStatus()) || "acknowledged".equals(alert.getStatus())) {
            alert.setStatus("resolved");
            alert.setResolvedAt(LocalDateTime.now());
            alert.setResolvedBy("system");
            alertRepository.save(alert);
            log.info("告警恢复: {} - {}", deviceId, ruleName);
            stream.publish("alert", Maps.of(
                    "action", "resolved",
                    "device_id", deviceId,
                    "rule", ruleName,
                    "severity", alert.getSeverity(),
                    "title", alert.getTitle(),
                    "status", "resolved"));
        }
    }

    // ==================== 通知与实时态 ====================

    /**
     * 每轮结束扫描未通知的活动告警补发通知.
     * 放在事务外执行: SMTP / Webhook 的等待不能占着数据库连接.
     */
    private void notifyPendingAlerts() {
        if (!notificationService.anyChannelEnabled()) {
            return;
        }
        List<AlertRecord> pending = alertRepository.findByNotifiedIsFalseAndStatus("active");
        if (pending.isEmpty()) {
            return;
        }
        String channels = notificationService.activeChannels();
        for (AlertRecord alert : pending) {
            if (notificationService.sendAlert(alert)) {
                alertRepository.markNotified(alert.getId(), channels);
            }
        }
    }

    private void refreshLiveAlertCounts() {
        long active = 0L;
        long critical = 0L;
        for (Object[] row : alertRepository.countActiveBySeverity()) {
            long count = ((Number) row[1]).longValue();
            active += count;
            if ("CRITICAL".equals(String.valueOf(row[0]))) {
                critical += count;
            }
        }
        live.setAlertCounts((int) active, (int) critical);
        stream.publish("overview", live.overview());
        stream.publish("alerts", live.alerts());
    }

    private void publishAlertEvent(String action, AlertCreate cmd) {
        stream.publish("alert", Maps.of(
                "action", action,
                "device_id", cmd.deviceId(),
                "device_name", cmd.deviceName(),
                "device_type", cmd.deviceType(),
                "rule", cmd.ruleName(),
                "severity", cmd.severity(),
                "title", cmd.title()));
    }

    // ==================== 数据装载 ====================

    private Map<String, HardwareHealth> indexLatestHardware() {
        Map<String, HardwareHealth> map = new LinkedHashMap<>();
        for (HardwareHealth hw : hardwareRepository.findLatestOfEachSwitch()) {
            map.putIfAbsent(hw.getSwitchId(), hw);
        }
        return map;
    }

    /** switch_id -> (port_index -> {admin_status, oper_status}), 一次查完全部端口避免 N+1 */
    private Map<String, Map<Integer, int[]>> loadPortStates() {
        Map<String, Map<Integer, int[]>> map = new LinkedHashMap<>();
        for (Object[] row : portRepository.findAllOperStates()) {
            String switchId = String.valueOf(row[0]);
            Integer portIndex = ((Number) row[1]).intValue();
            int admin = row[2] == null ? 0 : ((Number) row[2]).intValue();
            int oper = row[3] == null ? 0 : ((Number) row[3]).intValue();
            map.computeIfAbsent(switchId, key -> new LinkedHashMap<>()).put(portIndex, new int[]{admin, oper});
        }
        return map;
    }

    // ==================== 小工具 ====================

    /** 规则未配置 (缺键或空对象) 时跳过该规则, 与迁移前 `if rule:` 的假值语义一致 */
    private static boolean hasRule(JsonNode rule) {
        return rule.isObject() && rule.size() > 0;
    }

    /** 迁移前统一用 f"{value:.1f}%" —— Locale.ROOT 保证小数点不受系统语言影响 */
    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /** 阈值展示: 整数阈值不带小数 (90 -> "90%"), 与迁移前 f"{threshold}%" 一致 */
    private static String percentThreshold(double threshold) {
        if (threshold == Math.rint(threshold) && !Double.isInfinite(threshold)) {
            return String.valueOf((long) threshold) + "%";
        }
        return String.valueOf(threshold) + "%";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
