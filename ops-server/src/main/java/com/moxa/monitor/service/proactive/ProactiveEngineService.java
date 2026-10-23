package com.moxa.monitor.service.proactive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.AnomalyEvent;
import com.moxa.monitor.domain.DeviceHealth;
import com.moxa.monitor.domain.HardwareHealth;
import com.moxa.monitor.domain.MetricBaseline;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.ServerMetric;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.AnomalyEventRepository;
import com.moxa.monitor.repo.AlertRecordRepository;
import com.moxa.monitor.repo.DeviceHealthRepository;
import com.moxa.monitor.repo.HardwareHealthRepository;
import com.moxa.monitor.repo.MetricBaselineRepository;
import com.moxa.monitor.repo.PortTrafficStatRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.ServerMetricRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.alert.AlertEngineService;
import com.moxa.monitor.service.alert.MaintenanceWindowService;
import com.moxa.monitor.service.system.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 主动运维分析引擎: 移植自 legacy proactive/engine.py.
 * 一轮 analyze 完成: 取各设备最新样本 -> EWMA 基线判定 -> 异常事件生命周期 (开启/持续合并/自动恢复)
 * -> 高危异常升级为告警 -> 刷新设备健康评分. 维护窗口内静默, 设备离线时不再产生子指标噪音.
 *
 * 与迁移前必须一致的几个语义:
 * 1. 采集频率高于分析频率, 同一采集样本 (sample_time <= baseline.updated_at) 不重复喂给基线;
 * 2. 基线自适应: 正常样本按 alpha 快速跟随, 异常样本按 alpha*outlier_damping 几乎不更新,
 *    避免故障值把基线带偏导致持续故障停报;
 * 3. 严重度判 CRITICAL 的条件是 z >= k*1.5 或 绝对偏离 >= 2*min_deviation;
 * 4. 自动恢复只看 "本轮有该指标样本且未判异常", 因此设备离线时其历史异常也会被关闭;
 * 5. 健康分的活动告警计数取本轮开始前预载的快照 (本轮新建的告警不影响本轮健康分).
 */
@Service
public class ProactiveEngineService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngineService.class);

    /** metric -> {指标中文名, 单位}, 用于异常标题与描述 */
    private static final Map<String, String[]> METRIC_META = new LinkedHashMap<>();

    static {
        METRIC_META.put("temperature", new String[]{"设备温度", "°C"});
        METRIC_META.put("cpu", new String[]{"CPU 使用率", "%"});
        METRIC_META.put("memory", new String[]{"内存使用率", "%"});
        METRIC_META.put("disk", new String[]{"磁盘使用率", "%"});
        METRIC_META.put("port_error", new String[]{"端口误码增量", "个"});
    }

    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final HardwareHealthRepository hardwareRepository;
    private final ServerMetricRepository metricRepository;
    private final PortTrafficStatRepository trafficRepository;
    private final MetricBaselineRepository baselineRepository;
    private final AnomalyEventRepository anomalyRepository;
    private final DeviceHealthRepository healthRepository;
    private final AlertRecordRepository alertRepository;
    private final SysConfigService configService;
    private final MaintenanceWindowService maintenanceService;
    private final AlertEngineService alertEngine;
    private final TransactionTemplate txTemplate;

    private final ReentrantLock lock = new ReentrantLock();

    /** 本轮参数 (analyze 入口赋值, 与迁移前 self._xxx 一致) */
    private LocalDateTime now;
    private double alpha;
    private double k;
    private int minSamples;
    private double outlierDamping;
    private int escalateRank;
    private JsonNode metricCfg = NullNode.getInstance();
    private JsonNode weights = NullNode.getInstance();

    public ProactiveEngineService(SwitchDeviceRepository switchRepository,
                                  ServerDeviceRepository serverRepository,
                                  HardwareHealthRepository hardwareRepository,
                                  ServerMetricRepository metricRepository,
                                  PortTrafficStatRepository trafficRepository,
                                  MetricBaselineRepository baselineRepository,
                                  AnomalyEventRepository anomalyRepository,
                                  DeviceHealthRepository healthRepository,
                                  AlertRecordRepository alertRepository,
                                  SysConfigService configService,
                                  MaintenanceWindowService maintenanceService,
                                  AlertEngineService alertEngine,
                                  TransactionTemplate txTemplate) {
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.hardwareRepository = hardwareRepository;
        this.metricRepository = metricRepository;
        this.trafficRepository = trafficRepository;
        this.baselineRepository = baselineRepository;
        this.anomalyRepository = anomalyRepository;
        this.healthRepository = healthRepository;
        this.alertRepository = alertRepository;
        this.configService = configService;
        this.maintenanceService = maintenanceService;
        this.alertEngine = alertEngine;
        this.txTemplate = txTemplate;
    }

    /** 一个指标样本 */
    private record Sample(double value, LocalDateTime sampleTime, String unit) {
    }

    /** 一次异常判定结果 */
    private record Hit(double value, double baseline, double absDeviation, double z, String unit, String severity) {
    }

    /** 事件键: 设备 + 指标 (与迁移前的 (device_id, metric) 元组一致) */
    private record EventKey(String deviceId, String metric) {
    }

    // ==================== 调度入口 ====================

    /** 周期分析, 间隔取 sys_config.proactive_params.analyze_interval (默认 300s), 此处固定 300s */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void scheduledAnalyze() {
        analyze();
    }

    /** POST /api/proactive/analyze —— 手动立即分析, 同步返回统计 */
    public Map<String, Object> analyze() {
        JsonNode cfg = configService.node(SysConfigService.PROACTIVE_PARAMS);
        if (!cfg.path("enabled").asBoolean(true)) {
            return Maps.of("skipped", "proactive disabled");
        }
        if (!lock.tryLock()) {
            return Maps.of("skipped", "analysis already running");
        }
        try {
            Map<String, Object> stats = txTemplate.execute(status -> doAnalyze(cfg));
            logStats(stats);
            return stats == null ? Maps.of("devices", 0, "anomalies", 0, "resolved", 0, "escalated", 0) : stats;
        } catch (Exception ex) {
            log.error("主动分析异常: {}", ex.getMessage(), ex);
            return Maps.of("error", String.valueOf(ex.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> doAnalyze(JsonNode cfg) {
        this.now = LocalDateTime.now();
        this.alpha = cfg.path("ewma_alpha").asDouble(0.15);
        this.k = cfg.path("anomaly_k").asDouble(4.0);
        this.minSamples = cfg.path("min_samples").asInt(20);
        this.outlierDamping = cfg.path("outlier_damping").asDouble(0.1);
        this.escalateRank = escalateRankOf(cfg.path("escalate_severity").asText("CRITICAL"));
        this.metricCfg = cfg.path("metrics");
        this.weights = cfg.path("health_weights");

        maintenanceService.refresh();

        Map<String, Object> stats = Maps.of("devices", 0, "anomalies", 0, "resolved", 0, "escalated", 0);

        List<SwitchDevice> switches = switchRepository.findByEnabledIsTrueOrderByIpAsc();
        List<ServerDevice> servers = serverRepository.findByEnabledIsTrueOrderByIpAsc();

        // 预加载: 基线 / 未闭环异常 / 活动告警计数 / 健康快照 (一次取全, 避免逐设备 N+1)
        Map<EventKey, MetricBaseline> baselines = new LinkedHashMap<>();
        for (MetricBaseline baseline : baselineRepository.findAllByOrderByDeviceIdAscMetricAsc()) {
            baselines.put(new EventKey(baseline.getDeviceId(), baseline.getMetric()), baseline);
        }
        Map<EventKey, AnomalyEvent> openEvents = new LinkedHashMap<>();
        for (AnomalyEvent event : anomalyRepository.findByStatusInOrderByLastSeenAtDesc(
                AnomalyService.OPEN_STATUSES)) {
            openEvents.put(new EventKey(event.getDeviceId(), event.getMetric()), event);
        }
        Map<String, Map<String, Integer>> alertsByDevice = new LinkedHashMap<>();
        for (Object[] row : alertRepository.countActiveByDeviceAndSeverity()) {
            alertsByDevice.computeIfAbsent(String.valueOf(row[0]), key -> new LinkedHashMap<>())
                    .put(String.valueOf(row[1]), RoundUtil.safeInt(row[2]));
        }
        Map<String, DeviceHealth> healthMap = new LinkedHashMap<>();
        for (DeviceHealth health : healthRepository.findAll()) {
            healthMap.put(health.getDeviceId(), health);
        }

        Map<String, HardwareHealth> hwMap = latestHardware();
        Map<String, ServerMetric> smMap = latestServerMetrics();
        Map<String, Object[]> portErrors = portErrorDeltas(cfg);

        for (SwitchDevice sw : switches) {
            Map<String, Sample> samples = switchSamples(sw, hwMap.get(sw.getId()), portErrors.get(sw.getId()));
            processDevice(sw.getId(), sw.getName(), "switch", Boolean.TRUE.equals(sw.getIsOnline()),
                    hwMap.get(sw.getId()), samples, baselines, openEvents, alertsByDevice, healthMap, stats);
            bump(stats, "devices");
        }
        for (ServerDevice server : servers) {
            Map<String, Sample> samples = serverSamples(smMap.get(server.getId()));
            processDevice(server.getId(), server.getName(), "server", Boolean.TRUE.equals(server.getIsOnline()),
                    null, samples, baselines, openEvents, alertsByDevice, healthMap, stats);
            bump(stats, "devices");
        }
        return stats;
    }

    // ==================== 样本构造 ====================

    /** 交换机样本: 温度取硬件表, CPU/内存取台账, 误码取流量表增量 */
    private Map<String, Sample> switchSamples(SwitchDevice sw, HardwareHealth hw, Object[] portError) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        if (hw != null) {
            double temp = hw.getTemperature() == null ? 0D : hw.getTemperature().doubleValue();
            // 温度为 0 说明 SNMP 未取到该 OID, 不能当成"很冷"喂给基线
            if (temp > 0) {
                samples.put("temperature", new Sample(temp, hw.getTimestamp(), "°C"));
            }
        }
        if (sw.getCpuUsage() != null && sw.getCpuUsage() > 0) {
            samples.put("cpu", new Sample(sw.getCpuUsage(), sw.getLastPollTime(), "%"));
        }
        if (sw.getMemoryUsage() != null && sw.getMemoryUsage() > 0) {
            samples.put("memory", new Sample(sw.getMemoryUsage(), sw.getLastPollTime(), "%"));
        }
        if (portError != null) {
            samples.put("port_error", new Sample(RoundUtil.safeLong(portError[1]),
                    asDateTime(portError[2]), "个"));
        }
        return samples;
    }

    private Map<String, Sample> serverSamples(ServerMetric metric) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        if (metric == null) {
            return samples;
        }
        putIfNotNull(samples, "cpu", metric.getCpuUsagePercent(), metric.getTimestamp());
        putIfNotNull(samples, "memory", metric.getMemoryUsagePercent(), metric.getTimestamp());
        putIfNotNull(samples, "disk", metric.getDiskUsagePercent(), metric.getTimestamp());
        return samples;
    }

    private static void putIfNotNull(Map<String, Sample> samples, String metric, Float value, LocalDateTime ts) {
        if (value != null) {
            samples.put(metric, new Sample(value, ts, "%"));
        }
    }

    // ==================== 单设备处理 ====================

    private void processDevice(String deviceId, String deviceName, String deviceType, boolean isOnline,
                               HardwareHealth hw, Map<String, Sample> samples,
                               Map<EventKey, MetricBaseline> baselines,
                               Map<EventKey, AnomalyEvent> openEvents,
                               Map<String, Map<String, Integer>> alertsByDevice,
                               Map<String, DeviceHealth> healthMap,
                               Map<String, Object> stats) {
        Map<String, Hit> deviceAnomalies = new LinkedHashMap<>();

        if (isOnline) {
            for (Map.Entry<String, Sample> entry : samples.entrySet()) {
                String metric = entry.getKey();
                Sample sample = entry.getValue();
                JsonNode cfg = metricCfg.path(metric);
                // 未配置该指标的判定参数 -> 直接跳过 (迁移前 `if not mcfg: continue`)
                if (cfg.isMissingNode() || cfg.isNull() || cfg.size() == 0) {
                    continue;
                }
                EventKey key = new EventKey(deviceId, metric);
                MetricBaseline baseline = baselines.get(key);
                if (baseline == null) {
                    baseline = newBaseline(deviceId, deviceType, metric, sample.value());
                    baseline = baselineRepository.save(baseline);
                    baselines.put(key, baseline);
                }

                // 去重: 同一采集样本不重复喂给基线 (采集频率可能高于分析频率)
                if (baseline.getSampleCount() != null && baseline.getSampleCount() > 0
                        && sample.sampleTime() != null && baseline.getUpdatedAt() != null
                        && !sample.sampleTime().isAfter(baseline.getUpdatedAt())) {
                    continue;
                }

                EwmaBaseline.State state = new EwmaBaseline.State(
                        baseline.getMean(), baseline.getVariance(), baseline.getSampleCount());
                EwmaBaseline.Verdict verdict = EwmaBaseline.evaluate(cfg, state, sample.value(), k, minSamples);

                // 基线自适应: 正常样本快速跟随(适应季节/负载漂移); 异常样本几乎不更新
                double adaptiveAlpha = verdict.hit() ? alpha * outlierDamping : alpha;
                EwmaBaseline.State next = EwmaBaseline.update(state, sample.value(), adaptiveAlpha);
                baseline.setMean((float) next.mean());
                baseline.setVariance((float) next.variance());
                baseline.setSampleCount(next.count());
                baseline.setLastValue((float) sample.value());
                baseline.setDeviceType(deviceType);
                baseline.setUpdatedAt(now);
                baselineRepository.save(baseline);

                if (verdict.hit()) {
                    boolean critical = verdict.zScore() >= k * 1.5
                            || verdict.absDeviation() >= 2 * EwmaBaseline.minDeviation(cfg);
                    deviceAnomalies.put(metric, new Hit(sample.value(), state.mean(),
                            verdict.absDeviation(), verdict.zScore(), sample.unit(),
                            critical ? "CRITICAL" : "WARNING"));
                }
            }
        }

        // 异常事件生命周期: 同设备同指标只保留一条未闭环事件, 重复出现则合并刷新
        for (Map.Entry<String, Hit> entry : deviceAnomalies.entrySet()) {
            String metric = entry.getKey();
            Hit hit = entry.getValue();
            EventKey key = new EventKey(deviceId, metric);
            String[] described = describe(deviceName, metric, hit);
            boolean suppressed = maintenanceService.isSuppressed(deviceId, deviceType);

            AnomalyEvent event = openEvents.get(key);
            if (event == null) {
                event = new AnomalyEvent();
                event.setDeviceId(deviceId);
                event.setDeviceName(deviceName);
                event.setDeviceType(deviceType);
                event.setMetric(metric);
                event.setSeverity(hit.severity());
                event.setTitle(described[0]);
                event.setDescription(described[1]);
                event.setValue((float) hit.value());
                event.setBaseline((float) hit.baseline());
                event.setDeviation((float) hit.absDeviation());
                event.setScore((float) hit.z());
                event.setUnit(hit.unit());
                event.setStatus("open");
                event.setOccurredAt(now);
                event.setLastSeenAt(now);
                event.setAcknowledgedBy("");
                event.setEscalated(false);
                anomalyRepository.save(event);
                openEvents.put(key, event);
                bump(stats, "anomalies");
            } else {
                event.setValue((float) hit.value());
                event.setBaseline((float) hit.baseline());
                event.setDeviation((float) hit.absDeviation());
                event.setScore((float) hit.z());
                event.setSeverity(hit.severity());
                event.setTitle(described[0]);
                event.setDescription(described[1]);
                event.setLastSeenAt(now);
                anomalyRepository.save(event);
            }

            // 高危异常升级为告警 (维护窗口内不打扰)
            if (RecommendationService.severityRankOf(hit.severity()) >= escalateRank
                    && !suppressed) {
                alertEngine.createAlert(new AlertEngineService.AlertCreate(
                        deviceId, deviceName, deviceType, "anomaly_" + metric,
                        hit.severity(), described[0], described[1],
                        String.format(Locale.ROOT, "%.1f%s", hit.value(), hit.unit()),
                        String.format(Locale.ROOT, "基线 %.1f%s", hit.baseline(), hit.unit())));
                if (!Boolean.TRUE.equals(event.getEscalated())) {
                    event.setEscalated(true);
                    anomalyRepository.save(event);
                }
                bump(stats, "escalated");
            }
        }

        // 自动恢复: 本轮有样本且不再异常 -> 关闭事件并闭环对应告警
        Set<String> present = samples.keySet();
        for (Map.Entry<EventKey, AnomalyEvent> entry : new ArrayList<>(openEvents.entrySet())) {
            EventKey key = entry.getKey();
            if (!deviceId.equals(key.deviceId())) {
                continue;
            }
            if (present.contains(key.metric()) && !deviceAnomalies.containsKey(key.metric())) {
                AnomalyEvent event = entry.getValue();
                event.setStatus("resolved");
                event.setResolvedAt(now);
                anomalyRepository.save(event);
                openEvents.remove(key);
                bump(stats, "resolved");
                alertEngine.resolveRule(deviceId, "anomaly_" + key.metric());
            }
        }

        // 健康评分
        int openCount = 0;
        for (Map.Entry<EventKey, AnomalyEvent> entry : openEvents.entrySet()) {
            if (deviceId.equals(entry.getKey().deviceId())
                    && AnomalyService.OPEN_STATUSES.contains(entry.getValue().getStatus())) {
                openCount++;
            }
        }
        HealthScoreService.Result result = HealthScoreService.compute(deviceType, isOnline, hw, openCount,
                alertsByDevice.getOrDefault(deviceId, Map.of()), weights);

        DeviceHealth health = healthMap.get(deviceId);
        if (health == null) {
            health = new DeviceHealth();
            health.setDeviceId(deviceId);
            healthMap.put(deviceId, health);
        }
        health.setDeviceName(deviceName);
        health.setDeviceType(deviceType);
        health.setScore(result.score());
        health.setGrade(result.grade());
        health.setFactors(result.factors());
        health.setSummary(result.summary());
        health.setUpdatedAt(now);
        healthRepository.save(health);
    }

    /** 迁移前 _describe: 标题与描述里的数值一律 1 位小数 */
    private String[] describe(String deviceName, String metric, Hit hit) {
        String[] meta = METRIC_META.getOrDefault(metric, new String[]{metric, ""});
        String name = meta[0];
        String unit = hit.unit() == null || hit.unit().isEmpty() ? meta[1] : hit.unit();
        String arrow = hit.value() >= hit.baseline() ? "高于" : "低于";
        String title = String.format(Locale.ROOT, "[%s] %s异常: %.1f%s", deviceName, name, hit.value(), unit);
        String description = String.format(Locale.ROOT,
                "设备 %s 的%s为 %.1f%s, %s基线 %.1f%s (偏离 %.1f%s)",
                deviceName, name, hit.value(), unit, arrow, hit.baseline(), unit,
                Math.abs(hit.value() - hit.baseline()), unit);
        return new String[]{title, description};
    }

    // ==================== 数据装载 ====================

    /** 每台交换机最新一条硬件记录 (行数极少, 关联子查询足够) */
    private Map<String, HardwareHealth> latestHardware() {
        Map<String, HardwareHealth> map = new LinkedHashMap<>();
        for (HardwareHealth health : hardwareRepository.findLatestOfEachSwitch()) {
            map.putIfAbsent(health.getSwitchId(), health);
        }
        return map;
    }

    private Map<String, ServerMetric> latestServerMetrics() {
        Map<String, ServerMetric> map = new LinkedHashMap<>();
        for (ServerMetric metric : metricRepository.findLatestOfEachServer()) {
            map.putIfAbsent(metric.getServerId(), metric);
        }
        return map;
    }

    /** 每台交换机 "本窗口内新增误码+丢弃" 总量, 返回 [switch_id, delta, 最新样本时间] */
    private Map<String, Object[]> portErrorDeltas(JsonNode cfg) {
        double hours = cfg.path("port_error_window_hours").asDouble(2);
        LocalDateTime since = now.minus(Duration.ofMillis((long) (hours * 3600_000L)));
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Object[] row : trafficRepository.sumErrorDeltaBySwitch(since)) {
            map.put(String.valueOf(row[0]), new Object[]{row[0], row[1], row[2]});
        }
        return map;
    }

    private MetricBaseline newBaseline(String deviceId, String deviceType, String metric, double value) {
        MetricBaseline baseline = new MetricBaseline();
        baseline.setDeviceId(deviceId);
        baseline.setDeviceType(deviceType);
        baseline.setMetric(metric);
        baseline.setMean((float) value);
        baseline.setVariance(0F);
        baseline.setSampleCount(0);
        baseline.setLastValue((float) value);
        // 迁移前新基线的 updated_at 交给数据库默认值; 这里显式写本轮时间, 语义等价且便于下一轮去重
        baseline.setUpdatedAt(now);
        return baseline;
    }

    // ==================== 小工具 ====================

    /** escalate_severity 配错时按"永不升级"处理 (迁移前 SEVERITY_RANK.get(..., 99)) */
    private static int escalateRankOf(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 2;
            case "WARNING" -> 1;
            case "INFO" -> 0;
            default -> 99;
        };
    }

    private static void bump(Map<String, Object> stats, String key) {
        stats.put(key, RoundUtil.safeInt(stats.get(key)) + 1);
    }

    private void logStats(Map<String, Object> stats) {
        if (stats == null || stats.containsKey("skipped") || stats.containsKey("error")) {
            return;
        }
        int anomalies = RoundUtil.safeInt(stats.get("anomalies"));
        int resolved = RoundUtil.safeInt(stats.get("resolved"));
        int escalated = RoundUtil.safeInt(stats.get("escalated"));
        if (anomalies > 0 || resolved > 0 || escalated > 0) {
            log.info("主动分析: 设备 {} 台, 新增/持续异常 {}, 恢复 {}, 升级告警 {}",
                    RoundUtil.safeInt(stats.get("devices")), anomalies, resolved, escalated);
        } else {
            log.debug("主动分析完成: {} 台, 无异常", RoundUtil.safeInt(stats.get("devices")));
        }
    }

    /** 原生查询的 DATETIME 列在不同驱动下可能是 Timestamp / LocalDateTime, 统一兜底 */
    private static LocalDateTime asDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value == null) {
            return null;
        }
        return DateTimeSerializers.parseOrNull(String.valueOf(value));
    }
}
