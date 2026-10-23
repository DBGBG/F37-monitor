package com.moxa.monitor.service.proactive;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.domain.AnomalyEvent;
import com.moxa.monitor.repo.AnomalyEventRepository;
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
 * 异常中心查询与人工闭环服务: 迁移自 legacy api/proactive.py 的 anomalies / recommendations 段.
 */
@Service
public class AnomalyService {

    /** 未闭环状态, 迁移前多处复用同一列表 */
    public static final List<String> OPEN_STATUSES = List.of("open", "acknowledged");

    private final AnomalyEventRepository repository;

    public AnomalyService(AnomalyEventRepository repository) {
        this.repository = repository;
    }

    /** GET /api/proactive/anomalies —— status=all 不过滤, 默认按前端传的 open 过滤 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String status, String severity, String deviceType,
                                               String deviceId, String metric, int page, int pageSize) {
        Specification<AnomalyEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (notBlank(status) && !"all".equals(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (notBlank(severity)) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (notBlank(deviceType)) {
                predicates.add(cb.equal(root.get("deviceType"), deviceType));
            }
            if (notBlank(deviceId)) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (notBlank(metric)) {
                predicates.add(cb.equal(root.get("metric"), metric));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<AnomalyEvent> result = repository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, pageSize, Sort.by(Sort.Direction.DESC, "lastSeenAt")));
        List<Map<String, Object>> items = new ArrayList<>();
        result.getContent().forEach(event -> items.add(AnomalyView.of(event)));
        return PageResult.of(result.getTotalElements(), page, pageSize, items);
    }

    /**
     * POST /api/proactive/anomalies/{id}/acknowledge
     * 迁移前只有 open 能被确认 (已确认/已恢复的重复点击静默成功), 这里保持一致.
     */
    @Transactional
    public Map<String, Object> acknowledge(Integer id, String operator) {
        AnomalyEvent event = require(id);
        if ("open".equals(event.getStatus())) {
            event.setStatus("acknowledged");
            event.setAcknowledgedBy(operator == null ? "" : operator);
            repository.save(event);
        }
        return Maps.of("status", "success");
    }

    /**
     * POST /api/proactive/anomalies/{id}/resolve
     * 迁移前无条件置 resolved, 且 acknowledged_by 为空时补填操作人 (便于追溯谁手工闭环).
     */
    @Transactional
    public Map<String, Object> resolve(Integer id, String operator) {
        AnomalyEvent event = require(id);
        event.setStatus("resolved");
        event.setResolvedAt(LocalDateTime.now());
        if (event.getAcknowledgedBy() == null || event.getAcknowledgedBy().isBlank()) {
            event.setAcknowledgedBy(operator == null ? "" : operator);
        }
        repository.save(event);
        return Maps.of("status", "success");
    }

    /** GET /api/proactive/recommendations —— 未闭环异常按最近出现时间倒序喂给建议生成器 */
    @Transactional(readOnly = true)
    public Map<String, Object> recommendations() {
        List<AnomalyEvent> events = repository.findByStatusInOrderByLastSeenAtDesc(OPEN_STATUSES);
        List<Map<String, Object>> items = RecommendationService.build(events);
        return Maps.of("total", items.size(), "items", items);
    }

    private AnomalyEvent require(Integer id) {
        return repository.findById(id).orElseThrow(() -> BizException.notFound("异常不存在"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
