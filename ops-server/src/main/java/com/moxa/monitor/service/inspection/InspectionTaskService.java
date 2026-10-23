package com.moxa.monitor.service.inspection;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.PageResult;
import com.moxa.monitor.common.RoundUtil;
import com.moxa.monitor.domain.InspectionRecord;
import com.moxa.monitor.domain.InspectionTask;
import com.moxa.monitor.repo.InspectionPlanRepository;
import com.moxa.monitor.repo.InspectionRecordRepository;
import com.moxa.monitor.repo.InspectionTaskRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点检任务与履历: 迁移自 legacy api/inspection.py 的 任务 / 履历 / 统计 段.
 * 任务表是业务层独占表 ([B]), 一律走 JPA 整行写, 不使用 @Modifying 定向更新.
 */
@Service
public class InspectionTaskService {

    private static final Logger log = LoggerFactory.getLogger(InspectionTaskService.class);

    /** 履历只展示已闭环的任务 (完成 / 跳过) */
    private static final List<String> CLOSED_STATUSES = List.of("completed", "skipped");

    private final InspectionTaskRepository taskRepository;
    private final InspectionRecordRepository recordRepository;
    private final InspectionPlanRepository planRepository;

    public InspectionTaskService(InspectionTaskRepository taskRepository,
                                 InspectionRecordRepository recordRepository,
                                 InspectionPlanRepository planRepository) {
        this.taskRepository = taskRepository;
        this.recordRepository = recordRepository;
        this.planRepository = planRepository;
    }

    // ==================== 入参模型 ====================

    /** 单条检查项结果, 对应迁移前 RecordIn */
    public record RecordForm(String itemName, String checkType, String standard,
                             String value, String result, String remark) {

        public String checkTypeOrDefault() {
            return checkType == null || checkType.isBlank() ? "bool" : checkType;
        }

        public String resultOrDefault() {
            return result == null || result.isBlank() ? "normal" : result;
        }
    }

    /** POST /tasks/{id}/execute, 对应迁移前 ExecuteRequest */
    public record ExecuteForm(String operator, String remark, List<RecordForm> records) {

        public String operatorOrDefault() {
            return operator == null || operator.isBlank() ? "admin" : operator;
        }
    }

    /** POST /tasks/{id}/skip, 对应迁移前 SkipRequest */
    public record SkipForm(String operator, String reason) {

        public String operatorOrDefault() {
            return operator == null || operator.isBlank() ? "admin" : operator;
        }
    }

    // ==================== 任务查询 ====================

    /** GET /api/inspection/tasks —— status=all 不过滤, 日期区间按 planned_time, date_to 补一天 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String status, Integer planId, String deviceId,
                                                String deviceType, String result,
                                                String dateFrom, String dateTo,
                                                int page, int pageSize) {
        Specification<InspectionTask> spec = taskSpec(statusFilter(status), planId, deviceId, deviceType, result,
                dateFrom, dateTo, "plannedTime");
        Page<InspectionTask> found = taskRepository.findAll(spec, pageRequest(page, pageSize,
                Sort.Order.desc("plannedTime"), Sort.Order.desc("id")));
        List<Map<String, Object>> items = new ArrayList<>();
        found.getContent().forEach(task -> items.add(summary(task)));
        return PageResult.of(found.getTotalElements(), page, pageSize, items);
    }

    /** GET /api/inspection/tasks/{id} */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Integer id) {
        InspectionTask task = require(id);
        Map<String, Object> data = summary(task);
        data.put("items", task.getItems() == null ? List.of() : task.getItems());
        data.put("records", recordViews(recordRepository.findByTaskIdOrderByIdAsc(id)));
        return data;
    }

    // ==================== 任务流转 ====================

    /** POST /api/inspection/tasks/{id}/start —— 仅 pending / overdue 会进入执行中 */
    @Transactional
    public Map<String, Object> start(Integer id, String operator) {
        InspectionTask task = require(id);
        if ("pending".equals(task.getStatus()) || "overdue".equals(task.getStatus())) {
            task.setStatus("in_progress");
            task.setStartedAt(LocalDateTime.now());
            if (operator != null && !operator.isBlank()) {
                task.setOperator(operator);
            }
            taskRepository.save(task);
        }
        return Maps.of("status", "success");
    }

    /** POST /api/inspection/tasks/{id}/execute —— 提交结果并生成执行明细 */
    @Transactional
    public Map<String, Object> execute(Integer id, ExecuteForm form) {
        List<RecordForm> records = form == null ? null : form.records();
        if (records == null || records.isEmpty()) {
            throw BizException.badRequest("请至少填写一个检查项结果");
        }
        InspectionTask task = require(id);
        if ("completed".equals(task.getStatus())) {
            throw BizException.badRequest("任务已完成");
        }

        int abnormal = 0;
        for (RecordForm record : records) {
            if ("abnormal".equals(record.resultOrDefault())) {
                abnormal++;
            }
            InspectionRecord row = new InspectionRecord();
            row.setTaskId(id);
            row.setItemName(record.itemName() == null ? "" : record.itemName());
            row.setCheckType(record.checkTypeOrDefault());
            row.setStandard(nullToEmpty(record.standard()));
            row.setValue(nullToEmpty(record.value()));
            row.setResult(record.resultOrDefault());
            row.setRemark(nullToEmpty(record.remark()));
            recordRepository.save(row);
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        task.setStatus("completed");
        task.setResult(abnormal > 0 ? "abnormal" : "normal");
        task.setAbnormalCount(abnormal);
        task.setOperator(form.operatorOrDefault());
        task.setRemark(nullToEmpty(form.remark()));
        task.setFinishedAt(finishedAt);
        if (task.getStartedAt() == null) {
            task.setStartedAt(finishedAt);
        }
        taskRepository.save(task);
        log.info("点检完成: 任务#{} 结果={} 异常{}项", id, task.getResult(), abnormal);
        return Maps.of("status", "success", "result", task.getResult(), "abnormal_count", abnormal);
    }

    /** POST /api/inspection/tasks/{id}/skip */
    @Transactional
    public Map<String, Object> skip(Integer id, SkipForm form) {
        InspectionTask task = require(id);
        task.setStatus("skipped");
        task.setOperator(form == null ? "admin" : form.operatorOrDefault());
        task.setRemark(form == null ? "" : nullToEmpty(form.reason()));
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
        return Maps.of("status", "success");
    }

    // ==================== 履历 / 统计 ====================

    /** GET /api/inspection/history —— 已完成/已跳过任务及其明细, 按完成时间倒序 */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> history(String deviceId, Integer planId, String result,
                                                   String dateFrom, String dateTo,
                                                   int page, int pageSize) {
        Specification<InspectionTask> spec = taskSpec(CLOSED_STATUSES, planId, deviceId, null, result,
                dateFrom, dateTo, "finishedAt");
        Page<InspectionTask> found = taskRepository.findAll(spec, pageRequest(page, pageSize,
                Sort.Order.desc("finishedAt")));

        // 一次取回本页任务的全部明细再分组, 替代迁移前的逐任务查询
        List<Integer> taskIds = found.getContent().stream().map(InspectionTask::getId).toList();
        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            for (InspectionRecord record : recordRepository.findByTaskIdInOrderByIdAsc(taskIds)) {
                grouped.computeIfAbsent(record.getTaskId(), key -> new ArrayList<>())
                        .add(recordView(record));
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (InspectionTask task : found.getContent()) {
            Map<String, Object> row = summary(task);
            row.put("records", grouped.getOrDefault(task.getId(), List.of()));
            items.add(row);
        }
        return PageResult.of(found.getTotalElements(), page, pageSize, items);
    }

    /** GET /api/inspection/stats —— 迁移前的 completion_rate 只算 completed 与 overdue */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long pending = taskRepository.countByStatus("pending");
        long inProgress = taskRepository.countByStatus("in_progress");
        long overdue = taskRepository.countByStatus("overdue");
        long completed = taskRepository.countByStatus("completed");
        long skipped = taskRepository.countByStatus("skipped");
        long completedToday = taskRepository.countByStatusAndFinishedAtGreaterThanEqual("completed", todayStart);
        long abnormalToday = taskRepository.countByStatusAndResultAndFinishedAtGreaterThanEqual(
                "completed", "abnormal", todayStart);
        double completionRate = (completed + overdue) > 0
                ? RoundUtil.round(completed * 100.0 / (completed + overdue), 1)
                : 100.0D;
        return Maps.of(
                "plans_total", planRepository.count(),
                "plans_enabled", planRepository.countByEnabledIsTrue(),
                "tasks", Maps.of(
                        "pending", pending,
                        "in_progress", inProgress,
                        "overdue", overdue,
                        "completed", completed,
                        "skipped", skipped,
                        "todo", pending + inProgress + overdue),
                "completed_today", completedToday,
                "abnormal_today", abnormalToday,
                "completion_rate", completionRate);
    }

    // ==================== 内部 ====================

    private InspectionTask require(Integer id) {
        return taskRepository.findById(id).orElseThrow(() -> BizException.notFound("任务不存在"));
    }

    /** 任务与履历共用的条件构造, 时间区间字段名两处不同 (planned_time / finished_at) */
    private Specification<InspectionTask> taskSpec(List<String> statuses, Integer planId, String deviceId,
                                                   String deviceType, String result,
                                                   String dateFrom, String dateTo, String timeField) {
        LocalDateTime from = parseDate(dateFrom);
        LocalDateTime to = parseDate(dateTo);
        if (to != null) {
            to = to.plusDays(1);
        }
        final LocalDateTime toBound = to;
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (statuses != null) {
                predicates.add(root.get("status").in(statuses));
            }
            if (planId != null && planId > 0) {
                predicates.add(cb.equal(root.get("planId"), planId));
            }
            if (isNotBlank(deviceId)) {
                predicates.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (isNotBlank(deviceType)) {
                predicates.add(cb.equal(root.get("deviceType"), deviceType));
            }
            if (isNotBlank(result)) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get(timeField), from));
            }
            if (toBound != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get(timeField), toBound));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 任务列表: status 为空或 all 时不过滤; 履历: 固定只看已闭环 */
    private static List<String> statusFilter(String status) {
        if (isNotBlank(status) && !"all".equals(status)) {
            return List.of(status);
        }
        return null;
    }

    private static PageRequest pageRequest(int page, int pageSize, Sort.Order... orders) {
        int size = Math.min(Math.max(pageSize, 1), 200);
        return PageRequest.of(Math.max(page, 1) - 1, size, Sort.by(orders));
    }

    /** 迁移前用 datetime.fromisoformat, 无法解析直接报错; 这里沿用宽松解析并抛 400 */
    private static LocalDateTime parseDate(String raw) {
        return isNotBlank(raw) ? DateTimeSerializers.parse(raw) : null;
    }

    /** 任务摘要, 键顺序与迁移前 _task_summary 完全一致 */
    static Map<String, Object> summary(InspectionTask task) {
        return Maps.of(
                "id", task.getId(),
                "plan_id", task.getPlanId(),
                "plan_name", task.getPlanName(),
                "device_id", task.getDeviceId(),
                "device_name", task.getDeviceName(),
                "device_type", task.getDeviceType(),
                "planned_time", DateTimeSerializers.fmt(task.getPlannedTime()),
                "due_time", DateTimeSerializers.fmt(task.getDueTime()),
                "status", task.getStatus(),
                "result", task.getResult(),
                "abnormal_count", task.getAbnormalCount(),
                "assignee", task.getAssignee(),
                "operator", task.getOperator(),
                "remark", task.getRemark(),
                "started_at", DateTimeSerializers.fmt(task.getStartedAt()),
                "finished_at", DateTimeSerializers.fmt(task.getFinishedAt()),
                "item_count", task.getItems() == null ? 0 : task.getItems().size());
    }

    private static Map<String, Object> recordView(InspectionRecord record) {
        return Maps.of(
                "item_name", record.getItemName(),
                "check_type", record.getCheckType(),
                "standard", record.getStandard(),
                "value", record.getValue(),
                "result", record.getResult(),
                "remark", record.getRemark());
    }

    private static List<Map<String, Object>> recordViews(List<InspectionRecord> records) {
        List<Map<String, Object>> views = new ArrayList<>();
        records.forEach(record -> views.add(recordView(record)));
        return views;
    }

    /** 仪表盘与 SSE 快照复用: 未闭环任务数 */
    @Transactional(readOnly = true)
    public long todoCount() {
        return taskRepository.countByStatusIn(List.of("pending", "in_progress", "overdue"));
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
