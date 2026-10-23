package com.moxa.monitor.service.alert;

import com.moxa.monitor.common.BizException;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.domain.MaintenanceWindow;
import com.moxa.monitor.repo.MaintenanceWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 维护窗口服务: 迁移自 legacy proactive/windows.py.
 * 两层职责 ——
 * 1. 告警/异常创建前的抑制判定 (进程内短 TTL 缓存, 避免每条告警都查库);
 * 2. 窗口的增删改查 (前端系统页维护, 写后立即刷新缓存, 与迁移前的 _maintenance.refresh() 一致).
 */
@Service
public class MaintenanceWindowService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowService.class);

    /** 与迁移前 API 侧 MaintenanceService(ttl_seconds=5) 保持一致 */
    private static final long TTL_SECONDS = 5L;

    private final MaintenanceWindowRepository repository;

    /** 当前生效窗口快照, 整体替换避免读端加锁 */
    private volatile List<MaintenanceWindow> cache = List.of();
    private volatile LocalDateTime cacheLoadedAt;

    public MaintenanceWindowService(MaintenanceWindowRepository repository) {
        this.repository = repository;
    }

    /** 创建窗口表单: 与迁移前 WindowIn 字段一一对应 */
    public record WindowForm(String name, String deviceType, List<String> deviceIds,
                             LocalDateTime startTime, LocalDateTime endTime, String reason,
                             Boolean enabled, String createdBy) {
    }

    // ==================== 抑制判定 ====================

    /** 每轮告警检查开始时调用, 保证窗口生效/失效在一轮内被感知 */
    @Transactional(readOnly = true)
    public List<MaintenanceWindow> refresh() {
        LocalDateTime now = LocalDateTime.now();
        cache = repository.findActiveAt(now);
        cacheLoadedAt = now;
        return cache;
    }

    /** 供告警引擎在只读事务外调用: 超过 TTL 才回库 */
    public void ensureFresh() {
        LocalDateTime now = LocalDateTime.now();
        if (cacheLoadedAt == null || cacheLoadedAt.plusSeconds(TTL_SECONDS).isBefore(now)) {
            refresh();
        }
    }

    /** 设备当前是否处于维护窗口内 (迁移前 is_suppressed) */
    public boolean isSuppressed(String deviceId, String deviceType) {
        return suppressedWindowName(deviceId, deviceType) != null;
    }

    /** 命中的窗口名, 未命中返回 null; 日志与迁移前 "维护窗口[x]内抑制告警" 一致 */
    public String suppressedWindowName(String deviceId, String deviceType) {
        LocalDateTime now = LocalDateTime.now();
        for (MaintenanceWindow window : cache) {
            if (matches(window, deviceId, deviceType, now)) {
                return window.getName();
            }
        }
        return null;
    }

    /** 语义照搬 windows.py 的 _match: 缓存里已是 enabled + 时间命中的窗口, 这里再判一次做双保险 */
    private static boolean matches(MaintenanceWindow window, String deviceId, String deviceType, LocalDateTime now) {
        if (!Boolean.TRUE.equals(window.getEnabled())) {
            return false;
        }
        if (window.getStartTime() != null && now.isBefore(window.getStartTime())) {
            return false;
        }
        if (window.getEndTime() != null && now.isAfter(window.getEndTime())) {
            return false;
        }
        String scope = (window.getDeviceType() == null || window.getDeviceType().isBlank())
                ? "all" : window.getDeviceType();
        if (!scope.equals("all") && !scope.equals(deviceType)) {
            return false;
        }
        List<String> ids = window.getDeviceIds();
        return ids == null || ids.isEmpty() || ids.contains(deviceId);
    }

    /** 主动运维总览用: 当前生效窗口 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> currentWindows() {
        ensureFresh();
        List<Map<String, Object>> items = new ArrayList<>();
        for (MaintenanceWindow w : cache) {
            items.add(Maps.of(
                    "id", w.getId(),
                    "name", w.getName(),
                    "device_type", w.getDeviceType(),
                    "device_ids", w.getDeviceIds() == null ? List.of() : w.getDeviceIds(),
                    "start_time", DateTimeSerializers.fmt(w.getStartTime()),
                    "end_time", DateTimeSerializers.fmt(w.getEndTime()),
                    "reason", w.getReason(),
                    "active", true));
        }
        return items;
    }

    // ==================== CRUD ====================

    /** GET /api/proactive/maintenance/windows —— 全量列表, active 为即时计算 */
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (MaintenanceWindow w : repository.findAllByOrderByStartTimeDesc()) {
            items.add(Maps.of(
                    "id", w.getId(),
                    "name", w.getName(),
                    "device_type", w.getDeviceType(),
                    "device_ids", w.getDeviceIds() == null ? List.of() : w.getDeviceIds(),
                    "start_time", DateTimeSerializers.fmt(w.getStartTime()),
                    "end_time", DateTimeSerializers.fmt(w.getEndTime()),
                    "reason", w.getReason(),
                    "enabled", w.getEnabled(),
                    "created_by", w.getCreatedBy(),
                    "created_at", DateTimeSerializers.fmt(w.getCreatedAt()),
                    "active", Boolean.TRUE.equals(w.getEnabled())
                            && !now.isBefore(w.getStartTime()) && !now.isAfter(w.getEndTime())));
        }
        return Maps.of("items", items);
    }

    @Transactional
    public Map<String, Object> create(WindowForm form) {
        validate(form);
        MaintenanceWindow window = new MaintenanceWindow();
        apply(window, form);
        repository.save(window);
        refresh();
        log.info("维护窗口已创建: {} (#{})", window.getName(), window.getId());
        return Maps.of("status", "success", "id", window.getId());
    }

    @Transactional
    public Map<String, Object> update(Integer id, WindowForm form) {
        validate(form);
        MaintenanceWindow window = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("窗口不存在"));
        apply(window, form);
        repository.save(window);
        refresh();
        return Maps.of("status", "success");
    }

    @Transactional
    public Map<String, Object> toggle(Integer id) {
        MaintenanceWindow window = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("窗口不存在"));
        boolean enabled = !Boolean.TRUE.equals(window.getEnabled());
        repository.toggle(id, enabled);
        refresh();
        return Maps.of("status", "success", "enabled", enabled);
    }

    @Transactional
    public Map<String, Object> delete(Integer id) {
        MaintenanceWindow window = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("窗口不存在"));
        repository.delete(window);
        refresh();
        return Maps.of("status", "success");
    }

    // ==================== 内部 ====================

    private void validate(WindowForm form) {
        if (form.name() == null || form.name().isBlank()) {
            throw BizException.badRequest("窗口名称不能为空");
        }
        if (form.startTime() == null || form.endTime() == null) {
            throw BizException.badRequest("开始时间与结束时间不能为空");
        }
        if (!form.endTime().isAfter(form.startTime())) {
            throw BizException.badRequest("结束时间必须晚于开始时间");
        }
    }

    private void apply(MaintenanceWindow window, WindowForm form) {
        window.setName(form.name().trim());
        window.setDeviceType(form.deviceType() == null || form.deviceType().isBlank() ? "all" : form.deviceType());
        window.setDeviceIds(form.deviceIds() == null ? List.of() : form.deviceIds());
        window.setStartTime(form.startTime());
        window.setEndTime(form.endTime());
        window.setReason(form.reason() == null ? "" : form.reason());
        window.setEnabled(form.enabled() == null ? Boolean.TRUE : form.enabled());
        if (window.getCreatedBy() == null) {
            window.setCreatedBy(form.createdBy() == null || form.createdBy().isBlank()
                    ? "admin" : form.createdBy());
        }
    }
}
