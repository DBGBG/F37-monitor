package com.moxa.monitor.service.agent;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.AgentSkill;
import com.moxa.monitor.repo.AgentSkillRepository;
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
import java.util.regex.Pattern;

/**
 * 智能体技能清单: 登记 AI 运维代理可执行的技能 (名称 / 触发场景 / 执行要点 / 风险级别) 并控制启停.
 * 本服务只维护配置, 不代智能体执行任何设备操作; 需要下发的技能在页面上以 risk_level=write 标注,
 * 实际下发仍走自动化配置的 SSH 通道并留审计记录.
 */
@Service
public class AgentSkillService {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillService.class);

    /** 技能标识: 小写字母开头, 允许小写字母/数字/下划线, 长度 2..50 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,49}$");

    private static final List<String> RISK_LEVELS = List.of("read", "suggest", "write");
    private static final List<String> CATEGORIES = List.of(
            "采集诊断", "告警分析", "主动预防", "点检辅助", "配置自动化", "报告汇总");

    private final AgentSkillRepository skillRepository;

    public AgentSkillService(AgentSkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    /** 技能入参, 字段名与前端表单一致 (Jackson 全局 snake_case) */
    public record SkillForm(String skillKey, String name, String category, String description, String triggerScene,
                            String steps, String entryPath, String riskLevel, String version, Boolean enabled) {
    }

    // ==================== 查询 ====================

    /** GET /api/agent-skills */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String keyword, String category, String enabled) {
        Boolean enabledFilter = parseEnabled(enabled);
        Specification<AgentSkill> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("skillKey")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            if (category != null && !category.isBlank() && !"all".equals(category)) {
                predicates.add(cb.equal(root.get("category"), category.trim()));
            }
            if (enabledFilter != null) {
                predicates.add(cb.equal(root.get("enabled"), enabledFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<AgentSkill> found = skillRepository.findAll(spec,
                Sort.by(Sort.Order.asc("category"), Sort.Order.asc("id")));

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        long enabledCount = 0;
        long writeCount = 0;
        for (AgentSkill skill : found) {
            items.add(view(skill));
            byCategory.merge(skill.getCategory(), 1L, Long::sum);
            if (Boolean.TRUE.equals(skill.getEnabled())) {
                enabledCount++;
            }
            if ("write".equals(skill.getRiskLevel())) {
                writeCount++;
            }
        }
        return Maps.of("total", items.size(), "enabled_count", enabledCount,
                "write_count", writeCount, "categories", byCategory, "items", items);
    }

    /** GET /api/agent-skills/options —— 分类与风险级别字典 */
    public Map<String, Object> options() {
        return Maps.of("categories", CATEGORIES, "risk_levels", RISK_LEVELS);
    }

    /** GET /api/agent-skills/{id} */
    @Transactional(readOnly = true)
    public Map<String, Object> get(Integer id) {
        return view(require(id));
    }

    // ==================== 写操作 ====================

    /** POST /api/agent-skills */
    @Transactional
    public Map<String, Object> create(SkillForm form) {
        String key = normalizeKey(form.skillKey());
        if (skillRepository.existsBySkillKey(key)) {
            throw BizException.badRequest("技能标识已存在: " + key);
        }
        if (form.name() == null || form.name().isBlank()) {
            throw BizException.badRequest("技能名称不能为空");
        }
        AgentSkill skill = new AgentSkill();
        skill.setSkillKey(key);
        applyForm(skill, form);
        AgentSkill saved = skillRepository.save(skill);
        log.info("智能体技能已登记: {} ({})", saved.getName(), saved.getSkillKey());
        return view(saved);
    }

    /** PUT /api/agent-skills/{id} —— 技能标识可改, 但必须仍然唯一 (改标识等于换调用名) */
    @Transactional
    public Map<String, Object> update(Integer id, SkillForm form) {
        AgentSkill skill = require(id);
        if (form.skillKey() != null && !form.skillKey().isBlank()) {
            String key = normalizeKey(form.skillKey());
            Optional<AgentSkill> duplicate = skillRepository.findBySkillKey(key);
            if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
                throw BizException.badRequest("技能标识已存在: " + key);
            }
            skill.setSkillKey(key);
        }
        if (form.name() == null || form.name().isBlank()) {
            throw BizException.badRequest("技能名称不能为空");
        }
        applyForm(skill, form);
        skillRepository.save(skill);
        return view(skill);
    }

    /** DELETE /api/agent-skills/{id} */
    @Transactional
    public Map<String, Object> delete(Integer id) {
        AgentSkill skill = require(id);
        skillRepository.delete(skill);
        log.info("智能体技能已删除: {}", skill.getSkillKey());
        return Maps.of("status", "success", "message", "技能已删除");
    }

    /** POST /api/agent-skills/{id}/toggle —— 停用后清单里保留, 只是标记为不可调用 */
    @Transactional
    public Map<String, Object> toggle(Integer id) {
        AgentSkill skill = require(id);
        skill.setEnabled(!Boolean.TRUE.equals(skill.getEnabled()));
        skillRepository.save(skill);
        return view(skill);
    }

    // ==================== 内部 ====================

    private AgentSkill require(Integer id) {
        return skillRepository.findById(id).orElseThrow(() -> BizException.notFound("技能不存在: " + id));
    }

    private void applyForm(AgentSkill skill, SkillForm form) {
        if (form.name() != null && !form.name().isBlank()) {
            skill.setName(form.name().trim());
        }
        skill.setCategory(form.category() == null || form.category().isBlank()
                ? CATEGORIES.get(0) : form.category().trim());
        skill.setDescription(form.description() == null ? "" : form.description().trim());
        skill.setTriggerScene(form.triggerScene() == null ? "" : form.triggerScene().trim());
        skill.setSteps(form.steps() == null ? "" : form.steps().stripTrailing());
        skill.setEntryPath(form.entryPath() == null ? "" : form.entryPath().trim());
        skill.setRiskLevel(normalizeRisk(form.riskLevel()));
        skill.setVersion(form.version() == null || form.version().isBlank() ? "1.0" : form.version().trim());
        skill.setEnabled(form.enabled() == null || form.enabled());
    }

    private static String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("技能标识不能为空 (小写字母开头, 可含数字与下划线)");
        }
        String key = raw.trim().toLowerCase();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw BizException.badRequest("技能标识格式不正确: 需以小写字母开头, 仅允许小写字母/数字/下划线, 长度 2-50");
        }
        return key;
    }

    private static String normalizeRisk(String raw) {
        String level = raw == null || raw.isBlank() ? "read" : raw.trim().toLowerCase();
        if (!RISK_LEVELS.contains(level)) {
            throw BizException.badRequest("风险级别只能是 read / suggest / write");
        }
        return level;
    }

    private Map<String, Object> view(AgentSkill skill) {
        return Maps.of(
                "id", skill.getId(),
                "skill_key", skill.getSkillKey(),
                "name", skill.getName(),
                "category", skill.getCategory(),
                "description", skill.getDescription(),
                "trigger_scene", skill.getTriggerScene(),
                "steps", skill.getSteps() == null ? "" : skill.getSteps(),
                "entry_path", skill.getEntryPath(),
                "risk_level", skill.getRiskLevel(),
                "version", skill.getVersion(),
                "enabled", Boolean.TRUE.equals(skill.getEnabled()),
                "created_at", DateTimeSerializers.fmt(skill.getCreatedAt()),
                "updated_at", DateTimeSerializers.fmt(skill.getUpdatedAt()));
    }

    /** 与其余列表接口同口径: "1"/"true" 为真, 其余非空值为假 */
    private static Boolean parseEnabled(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return List.of("1", "true").contains(raw.toLowerCase());
    }
}

