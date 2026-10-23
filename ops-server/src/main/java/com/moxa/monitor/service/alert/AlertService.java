package com.moxa.monitor.service.alert;

import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.domain.AlertRecord;
import com.moxa.monitor.repo.AlertRecordRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 告警查询与人工闭环服务: 迁移自 legacy api/alerts.py.
 * 响应形状逐字段对齐 (summary 的 total = 三态之和, list 的 items 见 AlertView.of).
 */
@Service
public class AlertService {

    private final AlertRecordRepository repository;

    public AlertService(AlertRecordRepository repository) {
        this.repository = repository;
    }

    /** GET /api/alerts/summary */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        long active = repository.countByStatus("active");
        long acknowledged = repository.countByStatus("acknowledged");
        long resolved = repository.countByStatus("resolved");
        long critical = 0L;
        long warning = 0L;
        for (Object[] row : repository.countActiveBySeverity()) {
            String severity = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            if ("CRITICAL".equals(severity)) {
                critical += count;
            } else if ("WARNING".equals(severity)) {
                warning += count;
            }
        }
        long recent = repository.countByTriggeredAtGreaterThanEqual(LocalDateTime.now().minusHours(24));
        return Maps.of(
                "active", active,
                "acknowledged", acknowledged,
                "resolved", resolved,
                "total", active + acknowledged + resolved,
                "critical", critical,
                "warning", warning,
                "recent_24h", recent);
    }

    /** GET /api/alerts/list —— status=all 时不过滤, 固定按触发时间倒序 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String status, String severity, int page, int pageSize) {
        Specification<AlertRecord> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank() && !"all".equals(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null && !severity.isBlank()) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<AlertRecord> result = repository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, pageSize, Sort.by(Sort.Direction.DESC, "triggeredAt")));
        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(alert -> items.add(AlertView.of(alert)));
        return PageResult.of(result.getTotalElements(), page, pageSize, items);
    }

    /** POST /api/alerts/acknowledge —— 与迁移前一致, 仅活动告警可被确认 */
    @Transactional
    public void acknowledge(String alertId, String operator) {
        repository.findByAlertId(alertId)
                .filter(alert -> "active".equals(alert.getStatus()))
                .ifPresent(alert -> {
                    alert.setStatus("acknowledged");
                    alert.setAcknowledgedBy(operator == null ? "" : operator);
                    repository.save(alert);
                });
    }

    /** POST /api/alerts/resolve —— 仅活动告警可被人工恢复 */
    @Transactional
    public void resolve(String alertId, String operator) {
        repository.findByAlertId(alertId)
                .filter(alert -> "active".equals(alert.getStatus()))
                .ifPresent(alert -> {
                    alert.setStatus("resolved");
                    alert.setResolvedAt(LocalDateTime.now());
                    alert.setResolvedBy(operator == null ? "system" : operator);
                    repository.save(alert);
                });
    }
}
