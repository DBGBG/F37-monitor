package com.moxa.monitor.service.automation;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.ConfigAuditLog;
import com.moxa.monitor.repo.ConfigAuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置变更审计: 迁移自 legacy ssh_executor._save_audit_log 与 api/automation.get_audit_logs.
 * 下发失败也要留痕, 因此记录审计用一个独立事务, 且自身异常只记日志不外抛.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final ConfigAuditLogRepository repository;

    public AuditLogService(ConfigAuditLogRepository repository) {
        this.repository = repository;
    }

    /** 每次 CLI 下发落一行; commands 用换行拼接, 与迁移前 "\n".join(commands) 一致 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String switchId, String switchName, String switchIp, String operationType,
                       String operationDesc, String operator, List<String> commands, String output,
                       Map<String, Object> parameters, String status, String errorMessage, int durationMs) {
        try {
            ConfigAuditLog entry = new ConfigAuditLog();
            entry.setSwitchId(switchId == null ? "" : switchId);
            entry.setSwitchName(switchName == null ? "" : switchName);
            entry.setSwitchIp(switchIp == null ? "" : switchIp);
            entry.setOperationType(operationType == null ? "general" : operationType);
            entry.setOperationDesc(operationDesc == null ? "" : operationDesc);
            entry.setOperator(operator == null ? "system" : operator);
            entry.setCommandsSent(String.join("\n", commands == null ? List.of() : commands));
            entry.setCommandsOutput(output == null ? "" : output);
            entry.setParameters(parameters == null ? new LinkedHashMap<>() : parameters);
            entry.setStatus(status);
            entry.setErrorMessage(errorMessage == null ? "" : errorMessage);
            entry.setDurationMs(durationMs);
            entry.setExecutedAt(LocalDateTime.now());
            repository.save(entry);
        } catch (Exception ex) {
            log.error("保存审计日志失败: {}", ex.getMessage(), ex);
        }
    }

    /** GET /api/automation/audit-logs —— 响应形状保持不变: 只有 total 与 items */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String switchId, int page, int pageSize) {
        Specification<ConfigAuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (switchId != null && !switchId.isBlank()) {
                predicates.add(cb.equal(root.get("switchId"), switchId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        int size = Math.min(Math.max(pageSize, 1), 200);
        Page<ConfigAuditLog> found = repository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, size, Sort.by(Sort.Direction.DESC, "executedAt")));
        List<Map<String, Object>> items = new ArrayList<>();
        found.getContent().forEach(entry -> items.add(view(entry)));
        return Maps.of("total", found.getTotalElements(), "items", items);
    }

    /** 审计明细 (含命令与回显), 供单条查看 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Integer id) {
        ConfigAuditLog entry = repository.findById(id).orElse(null);
        if (entry == null) {
            return Maps.of();
        }
        Map<String, Object> data = view(entry);
        data.put("commands_sent", entry.getCommandsSent());
        data.put("commands_output", entry.getCommandsOutput());
        data.put("parameters", entry.getParameters() == null ? Map.of() : entry.getParameters());
        return data;
    }

    private static Map<String, Object> view(ConfigAuditLog entry) {
        return Maps.of(
                "id", entry.getId(),
                "switch_id", entry.getSwitchId(),
                "switch_name", entry.getSwitchName(),
                "switch_ip", entry.getSwitchIp(),
                "operation_type", entry.getOperationType(),
                "operation_desc", entry.getOperationDesc(),
                "operator", entry.getOperator(),
                "status", entry.getStatus(),
                "duration_ms", entry.getDurationMs(),
                "error_message", entry.getErrorMessage(),
                "executed_at", DateTimeSerializers.fmt(entry.getExecutedAt()));
    }
}
