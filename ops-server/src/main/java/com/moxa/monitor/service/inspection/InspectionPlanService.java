package com.moxa.monitor.service.inspection;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.InspectionItem;
import com.moxa.monitor.domain.InspectionPlan;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.InspectionItemRepository;
import com.moxa.monitor.repo.InspectionPlanRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点检计划管理: 迁移自 legacy api/inspection.py 的模板 / 设备选项 / 计划 CRUD 段.
 * 计划周期一律在写库时折算成 5 段 cron 存进 cron_expr, 任务生成只看 cron_expr.
 */
@Service
public class InspectionPlanService {

    private static final Logger log = LoggerFactory.getLogger(InspectionPlanService.class);

    private final InspectionPlanRepository planRepository;
    private final InspectionItemRepository itemRepository;
    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final InspectionSchedulerService scheduler;

    public InspectionPlanService(InspectionPlanRepository planRepository,
                                 InspectionItemRepository itemRepository,
                                 SwitchDeviceRepository switchRepository,
                                 ServerDeviceRepository serverRepository,
                                 InspectionSchedulerService scheduler) {
        this.planRepository = planRepository;
        this.itemRepository = itemRepository;
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.scheduler = scheduler;
    }

    /** 点检项入参, 与迁移前 PlanItemModel 对应 */
    public record ItemForm(String name, String checkType, String standard, String unit,
                           Boolean required, Integer sortOrder) {

        public String checkTypeOrDefault() {
            return checkType == null || checkType.isBlank() ? "bool" : checkType;
        }

        public boolean requiredOrDefault() {
            return required == null || required;
        }
    }

    /** 计划入参, 与迁移前 PlanModel 对应 */
    public record PlanForm(String name, String description, String deviceType, String targetScope,
                           List<String> targetIds, String scheduleType, Map<String, Object> scheduleValue,
                           String cronExpr, String assignee, Integer graceHours, Boolean enabled,
                           List<ItemForm> items) {

        public String deviceTypeOrDefault() {
            return deviceType == null || deviceType.isBlank() ? "switch" : deviceType;
        }

        public String targetScopeOrDefault() {
            return targetScope == null || targetScope.isBlank() ? "all" : targetScope;
        }

        public String scheduleTypeOrDefault() {
            return scheduleType == null || scheduleType.isBlank() ? "daily" : scheduleType;
        }

        public int graceHoursOrDefault() {
            return graceHours == null ? 12 : graceHours;
        }

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    // ==================== 模板与设备选项 ====================

    /** GET /api/inspection/templates */
    public Map<String, Object> templates() {
        return Maps.of("templates", InspectionSchedulerService.presetTemplates());
    }

    /** GET /api/inspection/devices —— 供"指定设备"下拉, 按名称排序 */
    @Transactional(readOnly = true)
    public Map<String, Object> deviceOptions(String deviceType) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean all = deviceType == null || deviceType.isBlank();
        if (all || "switch".equals(deviceType)) {
            List<Map<String, Object>> switches = new ArrayList<>();
            for (SwitchDevice device : switchRepository.findAll()) {
                switches.add(deviceOption(device.getId(), device.getName(), device.getIp(),
                        "switch", Boolean.TRUE.equals(device.getIsOnline())));
            }
            switches.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
            result.addAll(switches);
        }
        if (all || "server".equals(deviceType)) {
            List<Map<String, Object>> servers = new ArrayList<>();
            for (ServerDevice device : serverRepository.findAll()) {
                servers.add(deviceOption(device.getId(), device.getName(), device.getIp(),
                        "server", Boolean.TRUE.equals(device.getIsOnline())));
            }
            servers.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
            result.addAll(servers);
        }
        return Maps.of("devices", result);
    }

    private static Map<String, Object> deviceOption(String id, String name, String ip,
                                                    String type, boolean online) {
        return Maps.of("id", id, "name", name, "ip", ip, "type", type, "online", online);
    }

    // ==================== 计划 CRUD ====================

    /** GET /api/inspection/plans */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String keyword, String enabled) {
        Boolean enabledFilter = parseEnabled(enabled);
        Specification<InspectionPlan> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            if (enabledFilter != null) {
                predicates.add(cb.equal(root.get("enabled"), enabledFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<InspectionPlan> plans = planRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));

        // 一次取回全部计划的点检项再分组计数, 替代迁移前的逐计划 COUNT
        Map<Integer, Long> counts = new LinkedHashMap<>();
        if (!plans.isEmpty()) {
            List<Integer> ids = plans.stream().map(InspectionPlan::getId).toList();
            for (InspectionItem item : itemRepository.findByPlanIdInOrderBySortOrderAscIdAsc(ids)) {
                counts.merge(item.getPlanId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectionPlan plan : plans) {
            items.add(summary(plan, counts.getOrDefault(plan.getId(), 0L)));
        }
        return Maps.of("total", items.size(), "items", items);
    }

    /** GET /api/inspection/plans/{id} */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Integer id) {
        InspectionPlan plan = require(id);
        List<InspectionItem> planItems = itemRepository.findByPlanIdOrderBySortOrderAscIdAsc(id);
        Map<String, Object> data = summary(plan, planItems.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectionItem item : planItems) {
            items.add(Maps.of(
                    "id", item.getId(),
                    "name", item.getName(),
                    "check_type", item.getCheckType(),
                    "standard", item.getStandard(),
                    "unit", item.getUnit(),
                    "required", Boolean.TRUE.equals(item.getRequired()),
                    "sort_order", item.getSortOrder()));
        }
        data.put("items", items);
        return data;
    }

    /** POST /api/inspection/plans */
    @Transactional
    public Map<String, Object> create(PlanForm form) {
        String cronExpr = resolveCron(form);
        InspectionPlan plan = new InspectionPlan();
        applyForm(plan, form, cronExpr);
        plan.setLastGeneratedAt(LocalDateTime.now());
        InspectionPlan saved = planRepository.save(plan);
        replaceItems(saved.getId(), form.items());
        log.info("点检计划已创建: {} (#{})", saved.getName(), saved.getId());
        return Maps.of("status", "success", "id", saved.getId());
    }

    /** PUT /api/inspection/plans/{id} —— 迁移前不动 last_generated_at, 此处保持一致 */
    @Transactional
    public Map<String, Object> update(Integer id, PlanForm form) {
        InspectionPlan plan = require(id);
        applyForm(plan, form, resolveCron(form));
        planRepository.save(plan);
        replaceItems(id, form.items());
        return Maps.of("status", "success");
    }

    /** DELETE /api/inspection/plans/{id} —— 点检项随外键 CASCADE 删除, 历史任务保留 */
    @Transactional
    public Map<String, Object> delete(Integer id) {
        itemRepository.deleteByPlanId(id);
        planRepository.deleteById(id);
        return Maps.of("status", "success");
    }

    /** POST /api/inspection/plans/{id}/toggle */
    @Transactional
    public Map<String, Object> toggle(Integer id) {
        InspectionPlan plan = require(id);
        boolean enabled = !Boolean.TRUE.equals(plan.getEnabled());
        plan.setEnabled(enabled);
        planRepository.save(plan);
        return Maps.of("status", "success", "enabled", enabled);
    }

    /** POST /api/inspection/plans/{id}/run-now */
    @Transactional
    public Map<String, Object> runNow(Integer id) {
        InspectionPlan plan = require(id);
        int created = scheduler.runNow(plan, LocalDateTime.now());
        return Maps.of("status", "success", "created", created);
    }

    // ==================== 内部 ====================

    private InspectionPlan require(Integer id) {
        return planRepository.findById(id).orElseThrow(() -> BizException.notFound("计划不存在"));
    }

    /** 迁移前: schedule_type=cron 时取 cron_expr 或 schedule_value.expr, 其余由周期折算 */
    private static String resolveCron(PlanForm form) {
        String type = form.scheduleTypeOrDefault();
        if ("cron".equals(type)) {
            String expr = InspectionSchedulerService.planToCron(type, form.scheduleValue(), form.cronExpr());
            if (expr.isBlank()) {
                throw BizException.badRequest("请提供 cron 表达式");
            }
            return expr;
        }
        return InspectionSchedulerService.planToCron(type, form.scheduleValue(), "");
    }

    private static void applyForm(InspectionPlan plan, PlanForm form, String cronExpr) {
        plan.setName(form.name() == null ? "" : form.name());
        plan.setDescription(form.description() == null ? "" : form.description());
        plan.setDeviceType(form.deviceTypeOrDefault());
        plan.setTargetScope(form.targetScopeOrDefault());
        plan.setTargetIds(form.targetIds() == null ? List.of() : form.targetIds());
        plan.setScheduleType(form.scheduleTypeOrDefault());
        plan.setScheduleValue(form.scheduleValue() == null ? Map.of() : form.scheduleValue());
        plan.setCronExpr(cronExpr);
        plan.setAssignee(form.assignee() == null ? "" : form.assignee());
        plan.setGraceHours(form.graceHoursOrDefault());
        plan.setEnabled(form.enabledOrDefault());
    }

    /** 计划更新采用"先清空再写入", 未显式给 sort_order 时按提交顺序补号 */
    private void replaceItems(Integer planId, List<ItemForm> forms) {
        itemRepository.deleteByPlanId(planId);
        if (forms == null) {
            return;
        }
        int index = 0;
        for (ItemForm form : forms) {
            InspectionItem item = new InspectionItem();
            item.setPlanId(planId);
            item.setName(form.name() == null ? "" : form.name());
            item.setCheckType(form.checkTypeOrDefault());
            item.setStandard(form.standard() == null ? "" : form.standard());
            item.setUnit(form.unit() == null ? "" : form.unit());
            item.setRequired(form.requiredOrDefault());
            item.setSortOrder(form.sortOrder() == null || form.sortOrder() == 0 ? index : form.sortOrder());
            itemRepository.save(item);
            index++;
        }
    }

    private Map<String, Object> summary(InspectionPlan plan, long itemCount) {
        String cronExpr = plan.getCronExpr() == null || plan.getCronExpr().isBlank()
                ? InspectionSchedulerService.planToCron(plan.getScheduleType(), plan.getScheduleValue(), "")
                : plan.getCronExpr();
        return Maps.of(
                "id", plan.getId(),
                "name", plan.getName(),
                "description", plan.getDescription(),
                "device_type", plan.getDeviceType(),
                "target_scope", plan.getTargetScope(),
                "target_ids", plan.getTargetIds() == null ? List.of() : plan.getTargetIds(),
                "schedule_type", plan.getScheduleType(),
                "schedule_value", plan.getScheduleValue() == null ? Map.of() : plan.getScheduleValue(),
                "cron_expr", plan.getCronExpr(),
                "assignee", plan.getAssignee(),
                "grace_hours", plan.getGraceHours(),
                "enabled", Boolean.TRUE.equals(plan.getEnabled()),
                "item_count", itemCount,
                "last_generated_at", DateTimeSerializers.fmt(plan.getLastGeneratedAt()),
                "next_run", InspectionSchedulerService.nextRunText(cronExpr),
                "created_at", DateTimeSerializers.fmt(plan.getCreatedAt()));
    }

    /** 迁移前 enabled 查询参数是字符串: "1"/"true" 为真, 其余非空值为假 */
    private static Boolean parseEnabled(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> truthy = List.of("1", "true");
        return truthy.contains(raw.toLowerCase());
    }

    /** 供 Controller 层复用的批量计数入口 (仪表盘统计用) */
    @Transactional(readOnly = true)
    public long enabledPlanCount() {
        return planRepository.countByEnabledIsTrue();
    }
}
