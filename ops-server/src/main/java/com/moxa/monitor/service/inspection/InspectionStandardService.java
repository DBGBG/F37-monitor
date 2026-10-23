package com.moxa.monitor.service.inspection;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.InspectionStandard;
import com.moxa.monitor.repo.InspectionItemRepository;
import com.moxa.monitor.repo.InspectionStandardRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 点检标准库: 把"判定标准"从计划内嵌项里抽出来独立维护, 建计划时照抄为点检项.
 * 标准与计划是松耦合 (按名称对齐), 改标准不会回写已生成的任务快照.
 */
@Service
public class InspectionStandardService {

    private static final Logger log = LoggerFactory.getLogger(InspectionStandardService.class);

    /** 与巡检实践对齐的三套取值, 页面下拉与后端校验共用 */
    private static final List<String> CATEGORIES = List.of("switch", "server", "general");
    private static final List<String> CHECK_TYPES = List.of("bool", "value", "text");
    private static final List<String> CYCLES = List.of("daily", "weekly", "monthly", "quarterly");
    private static final List<String> LEVELS = List.of("key", "normal", "optional");

    private final InspectionStandardRepository standardRepository;
    private final InspectionItemRepository itemRepository;

    public InspectionStandardService(InspectionStandardRepository standardRepository,
                                     InspectionItemRepository itemRepository) {
        this.standardRepository = standardRepository;
        this.itemRepository = itemRepository;
    }

    /** 标准入参, 字段名与前端表单一致 (Jackson 全局 snake_case) */
    public record StandardForm(String category, String name, String checkType, String standard, String unit,
                               String method, String checkCycle, String level, String handleAction,
                               Boolean enabled, Integer sortOrder) {
    }

    // ==================== 查询 ====================

    /** GET /api/inspection/standards */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String keyword, String category, String enabled) {
        Boolean enabledFilter = parseEnabled(enabled);
        Specification<InspectionStandard> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("standard")), like),
                        cb.like(cb.lower(root.get("handleAction")), like)));
            }
            if (category != null && !category.isBlank() && !"all".equals(category)) {
                predicates.add(cb.equal(root.get("category"), category.trim()));
            }
            if (enabledFilter != null) {
                predicates.add(cb.equal(root.get("enabled"), enabledFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<InspectionStandard> found = standardRepository.findAll(spec,
                Sort.by(Sort.Order.asc("category"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id")));

        Map<String, Long> refs = referenceCounts();
        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectionStandard standard : found) {
            items.add(view(standard, refs.getOrDefault(standard.getName(), 0L)));
        }
        long enabledCount = items.stream().filter(i -> Boolean.TRUE.equals(i.get("enabled"))).count();
        return Maps.of("total", items.size(), "enabled_count", enabledCount, "items", items);
    }

    /** GET /api/inspection/standards/options —— 下拉字典, 前端不必硬编码 */
    public Map<String, Object> options() {
        return Maps.of("categories", CATEGORIES, "check_types", CHECK_TYPES,
                "cycles", CYCLES, "levels", LEVELS);
    }

    /** GET /api/inspection/standards/{id} */
    @Transactional(readOnly = true)
    public Map<String, Object> get(Integer id) {
        InspectionStandard standard = require(id);
        return view(standard, referenceCounts().getOrDefault(standard.getName(), 0L));
    }

    // ==================== 写操作 ====================

    /** POST /api/inspection/standards */
    @Transactional
    public Map<String, Object> create(StandardForm form) {
        String category = normalizeCategory(form.category());
        String name = requireName(form.name());
        if (standardRepository.findByCategoryAndName(category, name).isPresent()) {
            throw BizException.badRequest("同一适用对象下已存在同名标准: " + name);
        }
        InspectionStandard standard = new InspectionStandard();
        standard.setCategory(category);
        standard.setName(name);
        applyForm(standard, form);
        standard.setSource("manual");
        InspectionStandard saved = standardRepository.save(standard);
        log.info("点检标准已新增: {} ({})", saved.getName(), saved.getCategory());
        return view(saved, 0L);
    }

    /** PUT /api/inspection/standards/{id} —— 改名会断开与历史计划项的引用关系, 由前端提示 */
    @Transactional
    public Map<String, Object> update(Integer id, StandardForm form) {
        InspectionStandard standard = require(id);
        String category = normalizeCategory(form.category() == null ? standard.getCategory() : form.category());
        String name = form.name() == null || form.name().isBlank() ? standard.getName() : form.name().trim();
        Optional<InspectionStandard> duplicate = standardRepository.findByCategoryAndName(category, name);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            throw BizException.badRequest("同一适用对象下已存在同名标准: " + name);
        }
        standard.setCategory(category);
        standard.setName(name);
        applyForm(standard, form);
        standardRepository.save(standard);
        return view(standard, referenceCounts().getOrDefault(standard.getName(), 0L));
    }

    /** DELETE /api/inspection/standards/{id} —— 只删标准, 计划内已快照的点检项不受影响 */
    @Transactional
    public Map<String, Object> delete(Integer id) {
        InspectionStandard standard = require(id);
        standardRepository.delete(standard);
        log.info("点检标准已删除: {} ({})", standard.getName(), standard.getCategory());
        return Maps.of("status", "success", "message", "标准已删除, 已生成的点检计划不受影响");
    }

    /** POST /api/inspection/standards/{id}/toggle */
    @Transactional
    public Map<String, Object> toggle(Integer id) {
        InspectionStandard standard = require(id);
        standard.setEnabled(!Boolean.TRUE.equals(standard.getEnabled()));
        standardRepository.save(standard);
        return view(standard, referenceCounts().getOrDefault(standard.getName(), 0L));
    }

    // ==================== 内部 ====================

    private InspectionStandard require(Integer id) {
        return standardRepository.findById(id).orElseThrow(() -> BizException.notFound("点检标准不存在: " + id));
    }

    private void applyForm(InspectionStandard standard, StandardForm form) {
        standard.setCheckType(normalize(form.checkType(), CHECK_TYPES, "bool", "检查类型"));
        standard.setStandard(form.standard() == null ? "" : form.standard().trim());
        standard.setUnit(form.unit() == null ? "" : form.unit().trim());
        standard.setMethod(form.method() == null ? "" : form.method().trim());
        standard.setCheckCycle(normalize(form.checkCycle(), CYCLES, "daily", "建议周期"));
        standard.setLevel(normalize(form.level(), LEVELS, "normal", "重要度"));
        standard.setHandleAction(form.handleAction() == null ? "" : form.handleAction().trim());
        standard.setSortOrder(form.sortOrder() == null ? 0 : form.sortOrder());
        standard.setEnabled(form.enabled() == null || form.enabled());
        if (standard.getSource() == null || standard.getSource().isBlank()) {
            standard.setSource("manual");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("标准名称不能为空");
        }
        return name.trim();
    }

    private static String normalizeCategory(String raw) {
        String category = raw == null || raw.isBlank() ? "general" : raw.trim();
        if (!CATEGORIES.contains(category)) {
            throw BizException.badRequest("适用对象只能是 switch / server / general");
        }
        return category;
    }

    private static String normalize(String raw, List<String> allowed, String fallback, String label) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if (!allowed.contains(value)) {
            throw BizException.badRequest(label + "取值不正确: " + value);
        }
        return value;
    }

    /**
     * 引用次数: inspection_items 里没有 category 列, 只能按计划项名称匹配,
     * 同名标准在多个计划里出现时累加.
     */
    private Map<String, Long> referenceCounts() {
        Map<String, Long> byName = new LinkedHashMap<>();
        for (Object[] row : itemRepository.countGroupByName()) {
            byName.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return byName;
    }

    private Map<String, Object> view(InspectionStandard standard, Long refCount) {
        return Maps.of(
                "id", standard.getId(),
                "category", standard.getCategory(),
                "name", standard.getName(),
                "check_type", standard.getCheckType(),
                "standard", standard.getStandard(),
                "unit", standard.getUnit(),
                "method", standard.getMethod(),
                "check_cycle", standard.getCheckCycle(),
                "level", standard.getLevel(),
                "handle_action", standard.getHandleAction(),
                "source", standard.getSource(),
                "enabled", Boolean.TRUE.equals(standard.getEnabled()),
                "sort_order", standard.getSortOrder(),
                "ref_count", refCount,
                "created_at", DateTimeSerializers.fmt(standard.getCreatedAt()),
                "updated_at", DateTimeSerializers.fmt(standard.getUpdatedAt()));
    }

    /** 与点检计划列表同口径: "1"/"true" 为真, 其余非空值为假 */
    private static Boolean parseEnabled(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return List.of("1", "true").contains(raw.toLowerCase());
    }
}

