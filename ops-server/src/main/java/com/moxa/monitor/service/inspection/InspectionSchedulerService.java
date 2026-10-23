package com.moxa.monitor.service.inspection;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.InspectionItem;
import com.moxa.monitor.domain.InspectionPlan;
import com.moxa.monitor.domain.InspectionTask;
import com.moxa.monitor.domain.ServerDevice;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.InspectionItemRepository;
import com.moxa.monitor.repo.InspectionPlanRepository;
import com.moxa.monitor.repo.InspectionTaskRepository;
import com.moxa.monitor.repo.ServerDeviceRepository;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.support.CronExpression;

/**
 * 点检调度: 周期 -> cron 转换, 按周期为每台目标设备生成任务, 标记逾期.
 * 移植自 legacy inspection/service.py.
 *
 * cron 约定: 与迁移前一样按 crontab 的 5 段格式落库 (分 时 日 月 周),
 * 交给 Spring CronExpression 解析时前置一位秒字段 ("0 " + expr).
 */
@Service
public class InspectionSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(InspectionSchedulerService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 迁移前 _WEEKDAY_NAMES, 索引 (d-1)%7: 1=周一 ... 7=周日 */
    private static final String[] WEEKDAY_NAMES = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    /** 单次生成最多回看的触发点数量, 防止异常 cron 死循环 */
    private static final int GUARD = 2000;

    /** 预置点检模板 (新建计划时一键载入), 文案与迁移前 PRESET_TEMPLATES 逐字一致 */
    private static final Map<String, Map<String, Object>> PRESET_TEMPLATES = buildTemplates();

    private final InspectionPlanRepository planRepository;
    private final InspectionItemRepository itemRepository;
    private final InspectionTaskRepository taskRepository;
    private final SwitchDeviceRepository switchRepository;
    private final ServerDeviceRepository serverRepository;
    private final TransactionTemplate txTemplate;

    private final ReentrantLock lock = new ReentrantLock();

    public InspectionSchedulerService(InspectionPlanRepository planRepository,
                                      InspectionItemRepository itemRepository,
                                      InspectionTaskRepository taskRepository,
                                      SwitchDeviceRepository switchRepository,
                                      ServerDeviceRepository serverRepository,
                                      TransactionTemplate txTemplate) {
        this.planRepository = planRepository;
        this.itemRepository = itemRepository;
        this.taskRepository = taskRepository;
        this.switchRepository = switchRepository;
        this.serverRepository = serverRepository;
        this.txTemplate = txTemplate;
    }

    // ==================== 调度入口 ====================

    /** 迁移前由 task_scheduler 以 60 秒周期注册 inspection_generate */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 25_000L)
    public void scheduledRun() {
        run();
    }

    /** 调度器入口: 标记逾期 + 生成任务 */
    public void run() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            int overdue = txTemplate.execute(status -> markOverdue(LocalDateTime.now()));
            if (overdue > 0) {
                log.info("点检任务逾期标记: {} 条", overdue);
            }
            int created = txTemplate.execute(status -> generateTasks(LocalDateTime.now()));
            if (created > 0) {
                log.info("点检任务生成: 新增 {} 条", created);
            }
        } catch (Exception ex) {
            log.error("点检任务调度异常: {}", ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /** 将已过截止时间的未完成任务标记为逾期 */
    public int markOverdue(LocalDateTime now) {
        return taskRepository.markOverdue(now);
    }

    /** 为一轮周期生成点检任务, 返回新建任务数 */
    public int generateTasks(LocalDateTime now) {
        int created = 0;
        for (InspectionPlan plan : planRepository.findByEnabledIsTrueOrderByIdAsc()) {
            String cronExpr = notBlank(plan.getCronExpr())
                    ? plan.getCronExpr()
                    : planToCron(plan.getScheduleType(), plan.getScheduleValue(), "");

            // 从上次生成时间开始, 最多回看 24 小时, 避免重启后大量补单
            LocalDateTime start = plan.getLastGeneratedAt() == null ? now : plan.getLastGeneratedAt();
            LocalDateTime floor = now.minusHours(24);
            if (start.isBefore(floor)) {
                start = floor;
            }

            List<LocalDateTime> occurrences = iterOccurrences(cronExpr, start, now);
            if (occurrences.isEmpty()) {
                planRepository.touchGeneratedAt(plan.getId(), now);
                continue;
            }

            List<Map<String, Object>> snapshot = itemsSnapshot(plan.getId());
            List<Map<String, String>> targets = resolveTargets(plan);
            int grace = plan.getGraceHours() == null || plan.getGraceHours() <= 0
                    ? 12 : Math.max(plan.getGraceHours(), 1);

            for (LocalDateTime occurrence : occurrences) {
                LocalDateTime due = occurrence.plusHours(grace);
                for (Map<String, String> target : targets) {
                    // 幂等: 同一 计划+设备+计划时间 已存在则跳过 (依赖唯一约束 uq_inspection_task)
                    if (taskRepository.existsByPlanIdAndDeviceIdAndPlannedTime(
                            plan.getId(), target.get("id"), occurrence)) {
                        continue;
                    }
                    taskRepository.save(newTask(plan, target, occurrence, due, snapshot));
                    created++;
                }
            }
            planRepository.touchGeneratedAt(plan.getId(), now);
        }
        return created;
    }

    /** 立即为计划的全部目标设备生成一条点检任务 (POST /plans/{id}/run-now) */
    public int runNow(InspectionPlan plan, LocalDateTime now) {
        List<Map<String, Object>> snapshot = itemsSnapshot(plan.getId());
        List<Map<String, String>> targets = resolveTargets(plan);
        int grace = plan.getGraceHours() == null || plan.getGraceHours() <= 0
                ? 12 : Math.max(plan.getGraceHours(), 1);
        LocalDateTime due = now.plusHours(grace);
        int created = 0;
        for (Map<String, String> target : targets) {
            taskRepository.save(newTask(plan, target, now, due, snapshot));
            created++;
        }
        return created;
    }

    private static InspectionTask newTask(InspectionPlan plan, Map<String, String> target,
                                          LocalDateTime plannedTime, LocalDateTime dueTime,
                                          List<Map<String, Object>> snapshot) {
        InspectionTask task = new InspectionTask();
        task.setPlanId(plan.getId());
        task.setPlanName(plan.getName());
        task.setDeviceId(target.get("id"));
        task.setDeviceName(target.get("name"));
        task.setDeviceType(target.get("type"));
        task.setPlannedTime(plannedTime);
        task.setDueTime(dueTime);
        task.setItems(snapshot);
        task.setStatus("pending");
        task.setResult("");
        task.setAbnormalCount(0);
        task.setAssignee(plan.getAssignee() == null ? "" : plan.getAssignee());
        task.setOperator("");
        task.setRemark("");
        return task;
    }

    /** 解析计划的目标设备, 返回 [{id, name, type}] */
    public List<Map<String, String>> resolveTargets(InspectionPlan plan) {
        boolean isSwitch = "switch".equals(plan.getDeviceType());
        List<String> wanted = plan.getTargetIds();
        boolean scoped = "devices".equals(plan.getTargetScope()) && wanted != null && !wanted.isEmpty();
        List<Map<String, String>> targets = new ArrayList<>();
        if (isSwitch) {
            for (SwitchDevice device : switchRepository.findByEnabledIsTrueOrderByIpAsc()) {
                if (!scoped || wanted.contains(device.getId())) {
                    targets.add(Map.of("id", device.getId(), "name", nullToEmpty(device.getName()),
                            "type", "switch"));
                }
            }
        } else {
            for (ServerDevice device : serverRepository.findByEnabledIsTrueOrderByIpAsc()) {
                if (!scoped || wanted.contains(device.getId())) {
                    targets.add(Map.of("id", device.getId(), "name", nullToEmpty(device.getName()),
                            "type", "server"));
                }
            }
        }
        return targets;
    }

    /** 点检项快照, 键顺序与迁移前 items JSON 一致 */
    public List<Map<String, Object>> itemsSnapshot(Integer planId) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (InspectionItem item : itemRepository.findByPlanIdOrderBySortOrderAscIdAsc(planId)) {
            snapshot.add(Maps.of(
                    "name", item.getName(),
                    "check_type", item.getCheckType(),
                    "standard", nullToEmpty(item.getStandard()),
                    "unit", nullToEmpty(item.getUnit()),
                    "required", Boolean.TRUE.equals(item.getRequired())));
        }
        return snapshot;
    }

    // ==================== cron 转换 ====================

    /** 把计划周期转换为 5 段 cron 表达式 (与迁移前 plan_to_cron 逐字对齐) */
    public static String planToCron(String scheduleType, Map<String, Object> scheduleValue, String explicit) {
        Map<String, Object> value = scheduleValue == null ? Map.of() : scheduleValue;
        if ("cron".equals(scheduleType)) {
            Object rawExpr = value.get("expr");
            String expr = notBlank(explicit) ? explicit : (rawExpr == null ? "" : String.valueOf(rawExpr));
            return expr.trim();
        }

        Object rawTime = value.get("time");
        String timeStr = rawTime == null ? "08:00" : String.valueOf(rawTime);
        String[] parts = timeStr.split(":");
        int hour = parts.length > 0 && isDigits(parts[0]) ? Integer.parseInt(parts[0]) : 8;
        int minute = parts.length > 1 && isDigits(parts[1]) ? Integer.parseInt(parts[1]) : 0;

        if ("weekly".equals(scheduleType)) {
            List<Integer> weekdays = toIntList(value.get("weekdays"));
            if (weekdays.isEmpty()) {
                weekdays = List.of(1);
            }
            StringBuilder days = new StringBuilder();
            for (Integer day : weekdays) {
                if (days.length() > 0) {
                    days.append(',');
                }
                days.append(WEEKDAY_NAMES[Math.floorMod(day - 1, 7)]);
            }
            String joined = days.length() == 0 ? "mon" : days.toString();
            return minute + " " + hour + " * * " + joined;
        }

        if ("monthly".equals(scheduleType)) {
            List<Integer> days = toIntList(value.get("days"));
            if (days.isEmpty()) {
                days = List.of(1);
            }
            StringBuilder joined = new StringBuilder();
            for (Integer day : days) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(day);
            }
            String result = joined.length() == 0 ? "1" : joined.toString();
            return minute + " " + hour + " " + result + " * *";
        }

        // daily (默认)
        return minute + " " + hour + " * * *";
    }

    /** 返回 (start, end] 区间内该 cron 的所有触发时间 (朴素本地时间) */
    public static List<LocalDateTime> iterOccurrences(String cron5, LocalDateTime start, LocalDateTime end) {
        CronExpression expression = parseCron(cron5);
        if (expression == null) {
            return List.of();
        }
        List<LocalDateTime> occurrences = new ArrayList<>();
        ZonedDateTime limit = end.atZone(ZONE);
        ZonedDateTime cursor = start.atZone(ZONE);
        int guard = 0;
        while (guard < GUARD) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null || next.isAfter(limit)) {
                break;
            }
            occurrences.add(next.toLocalDateTime());
            cursor = next;
            guard++;
        }
        return occurrences;
    }

    /** 下一次执行时间, 供计划列表的 next_run 展示 */
    public static String nextRunText(String cron5) {
        CronExpression expression = parseCron(cron5);
        if (expression == null) {
            return null;
        }
        ZonedDateTime next = expression.next(ZonedDateTime.now(ZONE));
        return next == null ? null : DateTimeSerializers.fmt(next.toLocalDateTime());
    }

    /** 5 段 crontab -> Spring 的 6 段; 已是 6 段则原样使用 */
    private static CronExpression parseCron(String cron5) {
        if (cron5 == null || cron5.isBlank()) {
            return null;
        }
        String normalized = cron5.trim();
        if (normalized.split("\\s+").length == 5) {
            normalized = "0 " + normalized;
        }
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            log.warn("无效的 cron 表达式 [{}]: {}", cron5, ex.getMessage());
            return null;
        }
    }

    // ==================== 模板 ====================

    public static Map<String, Map<String, Object>> presetTemplates() {
        return PRESET_TEMPLATES;
    }

    private static Map<String, Map<String, Object>> buildTemplates() {
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        templates.put("switch", template("交换机常规点检", List.of(
                item("电源指示灯", "bool", "绿灯常亮", "", true),
                item("系统/告警指示灯", "bool", "无红灯告警", "", true),
                item("风扇运转", "bool", "运转正常无异响", "", true),
                item("设备温度", "value", "< 60", "°C", true),
                item("端口指示灯", "bool", "与业务一致", "", true),
                item("网线/光纤连接", "bool", "连接牢固无松动", "", true),
                item("设备清洁除尘", "bool", "无积尘", "", false),
                item("接地与安装固定", "bool", "接地良好、固定牢靠", "", false))));
        templates.put("server", template("服务器常规点检", List.of(
                item("电源/运行指示灯", "bool", "正常", "", true),
                item("风扇/散热", "bool", "运转正常", "", true),
                item("机房环境温度", "value", "18~27", "°C", true),
                item("硬盘告警指示灯", "bool", "无红灯", "", true),
                item("线缆连接", "bool", "连接正常", "", true),
                item("设备清洁除尘", "bool", "无积尘", "", false))));
        return templates;
    }

    private static Map<String, Object> template(String name, List<Map<String, Object>> items) {
        return Maps.of("name", name, "items", items);
    }

    private static Map<String, Object> item(String name, String checkType, String standard,
                                             String unit, boolean required) {
        return Maps.of("name", name, "check_type", checkType, "standard", standard,
                "unit", unit, "required", required);
    }

    // ==================== 小工具 ====================

    /** JSON 里的数字可能是 Integer / Long / String, 统一按 int 取 */
    private static List<Integer> toIntList(Object raw) {
        List<Integer> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Number number) {
                    result.add(number.intValue());
                } else if (element != null && isDigits(String.valueOf(element))) {
                    result.add(Integer.parseInt(String.valueOf(element)));
                }
            }
        }
        return result;
    }

    private static boolean isDigits(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
