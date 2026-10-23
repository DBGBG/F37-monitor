package com.moxa.monitor.service.proactive;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.AnomalyEvent;
import com.moxa.monitor.domain.DeviceHealth;
import com.moxa.monitor.domain.MetricBaseline;
import com.moxa.monitor.repo.AnomalyEventRepository;
import com.moxa.monitor.repo.DeviceHealthRepository;
import com.moxa.monitor.repo.MetricBaselineRepository;
import com.moxa.monitor.service.alert.MaintenanceWindowService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动运维查询服务: 迁移自 legacy api/proactive.py 的总览 / 健康 / 基线三段只读接口.
 * 响应形状 (键顺序、精度、null 语义) 与迁移前逐字段对齐, 前端 proactive.js 无需改动取数逻辑.
 */
@Service
public class ProactiveQueryService {

    private final DeviceHealthRepository healthRepository;
    private final MetricBaselineRepository baselineRepository;
    private final AnomalyEventRepository anomalyRepository;
    private final MaintenanceWindowService maintenanceService;

    public ProactiveQueryService(DeviceHealthRepository healthRepository,
                                 MetricBaselineRepository baselineRepository,
                                 AnomalyEventRepository anomalyRepository,
                                 MaintenanceWindowService maintenanceService) {
        this.healthRepository = healthRepository;
        this.baselineRepository = baselineRepository;
        this.anomalyRepository = anomalyRepository;
        this.maintenanceService = maintenanceService;
    }

    /** GET /api/proactive/overview */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Long> gradeDist = new LinkedHashMap<>();
        long analyzed = 0L;
        for (Object[] row : healthRepository.countByGrade()) {
            long count = RoundUtil.safeLong(row[1]);
            gradeDist.merge(String.valueOf(row[0]), count, Long::sum);
            analyzed += count;
        }
        Double avgScore = healthRepository.averageScore();

        long openTotal = anomalyRepository.countByStatusIn(AnomalyService.OPEN_STATUSES);
        Map<String, Long> sevDist = new LinkedHashMap<>();
        for (Object[] row : anomalyRepository.countOpenBySeverity(AnomalyService.OPEN_STATUSES)) {
            sevDist.merge(String.valueOf(row[0]), RoundUtil.safeLong(row[1]), Long::sum);
        }

        List<Map<String, Object>> worst = new ArrayList<>();
        for (DeviceHealth health : healthRepository.findTop8ByOrderByScoreAsc()) {
            worst.add(Maps.of(
                    "device_id", health.getDeviceId(),
                    "device_name", health.getDeviceName(),
                    "device_type", health.getDeviceType(),
                    "score", health.getScore(),
                    "grade", health.getGrade(),
                    "summary", health.getSummary()));
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        // 迁移前不区分状态, 取全表最近 10 条
        anomalyRepository.findAll((Specification<AnomalyEvent>) null,
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "lastSeenAt")))
                .forEach(event -> recent.add(AnomalyView.of(event)));

        List<Map<String, Object>> windows = maintenanceService.currentWindows();
        return Maps.of(
                "generated_at", DateTimeSerializers.fmt(LocalDateTime.now()),
                "health", Maps.of(
                        "analyzed", analyzed,
                        "avg_score", avgScore == null ? null : RoundUtil.round(avgScore, 1),
                        "distribution", Maps.of(
                                "优", gradeDist.getOrDefault("优", 0L),
                                "良", gradeDist.getOrDefault("良", 0L),
                                "中", gradeDist.getOrDefault("中", 0L),
                                "差", gradeDist.getOrDefault("差", 0L))),
                "anomalies", Maps.of("open", openTotal, "by_severity", sevDist),
                "maintenance", Maps.of("active_windows", windows.size(), "windows", windows),
                "worst_devices", worst,
                "recent_anomalies", recent);
    }

    /** GET /api/proactive/health —— 分数升序, 最差的排最前 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> health(String deviceType, String grade, String keyword,
                                                  int page, int pageSize) {
        Specification<DeviceHealth> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (notBlank(deviceType)) {
                predicates.add(cb.equal(root.get("deviceType"), deviceType));
            }
            if (notBlank(grade)) {
                predicates.add(cb.equal(root.get("grade"), grade));
            }
            if (notBlank(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("deviceName")), like.toLowerCase()),
                        cb.like(cb.lower(root.get("deviceId")), like.toLowerCase())));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<DeviceHealth> result = healthRepository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, pageSize, Sort.by(Sort.Direction.ASC, "score")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceHealth health : result.getContent()) {
            items.add(Maps.of(
                    "device_id", health.getDeviceId(),
                    "device_name", health.getDeviceName(),
                    "device_type", health.getDeviceType(),
                    "score", health.getScore(),
                    "grade", health.getGrade(),
                    "factors", health.getFactors() == null ? List.of() : health.getFactors(),
                    "summary", health.getSummary(),
                    "updated_at", DateTimeSerializers.fmt(health.getUpdatedAt())));
        }
        return PageResult.of(result.getTotalElements(), page, pageSize, items);
    }

    /** GET /api/proactive/health/{deviceId} —— 评分 + 扣分因子 + 基线 + 未处理异常 */
    @Transactional(readOnly = true)
    public Map<String, Object> healthDetail(String deviceId) {
        DeviceHealth health = healthRepository.findById(deviceId).orElse(null);
        List<Map<String, Object>> baselines = new ArrayList<>();
        for (MetricBaseline baseline : baselineRepository.findByDeviceId(deviceId)) {
            baselines.add(baselineItem(baseline));
        }
        List<Map<String, Object>> anomalies = new ArrayList<>();
        anomalyRepository.findByDeviceIdAndStatusInOrderByLastSeenAtDesc(
                        deviceId, AnomalyService.OPEN_STATUSES)
                .forEach(event -> anomalies.add(AnomalyView.of(event)));

        return Maps.of(
                "device_id", deviceId,
                "health", health == null ? null : Maps.of(
                        "score", health.getScore(),
                        "grade", health.getGrade(),
                        "factors", health.getFactors() == null ? List.of() : health.getFactors(),
                        "summary", health.getSummary(),
                        "device_name", health.getDeviceName(),
                        "device_type", health.getDeviceType()),
                "baselines", baselines,
                "anomalies", anomalies);
    }

    /** GET /api/proactive/baselines */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> baselines(String deviceId, String metric, int page, int pageSize) {
        Specification<MetricBaseline> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (notBlank(deviceId)) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (notBlank(metric)) {
                predicates.add(cb.equal(root.get("metric"), metric));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<MetricBaseline> result = baselineRepository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, pageSize,
                        Sort.by(Sort.Order.asc("deviceId"), Sort.Order.asc("metric"))));
        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(baseline -> items.add(baselineItem(baseline)));
        return PageResult.of(result.getTotalElements(), page, pageSize, items);
    }

    /** 基线条目: std 由方差开方得到, 精度 3 位与迁移前一致 */
    private static Map<String, Object> baselineItem(MetricBaseline baseline) {
        double variance = baseline.getVariance() == null ? 0D : baseline.getVariance();
        return Maps.of(
                "device_id", baseline.getDeviceId(),
                "device_type", baseline.getDeviceType(),
                "metric", baseline.getMetric(),
                "mean", RoundUtil.round(baseline.getMean(), 3),
                "std", RoundUtil.round(Math.sqrt(Math.max(variance, 0D)), 3),
                "last_value", RoundUtil.round(baseline.getLastValue(), 3),
                "sample_count", baseline.getSampleCount(),
                "updated_at", DateTimeSerializers.fmt(baseline.getUpdatedAt()));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 供总览与仪表盘复用: 未闭环异常条数 */
    @Transactional(readOnly = true)
    public long openAnomalyCount() {
        return anomalyRepository.countByStatusIn(AnomalyService.OPEN_STATUSES);
    }

    /** 未闭环异常全量 (建议表与仪表盘共用), 按最近出现时间倒序 */
    @Transactional(readOnly = true)
    public List<AnomalyEvent> openAnomalies() {
        return anomalyRepository.findByStatusInOrderByLastSeenAtDesc(AnomalyService.OPEN_STATUSES);
    }
}
