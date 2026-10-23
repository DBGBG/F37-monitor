package com.moxa.monitor.service.realtime;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.RoundUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的设备/告警"最新态", 由 SnapshotPoller 每 2 秒从库刷新, 业务事件产生时即时改写.
 * 结构与迁移前 backend/realtime.py 的 LiveState 完全一致, 前端 realtime.js 无需调整.
 */
@Component
public class LiveStateHolder {

    /** device_id -> 展示字段 (id/type/name/ip/is_online/last_poll_time/...) */
    private final Map<String, Map<String, Object>> switches = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> servers = new ConcurrentHashMap<>();

    /** active / critical 告警数 */
    private volatile int activeAlerts;
    private volatile int criticalAlerts;

    /** 最近一次采集周期 (phase/at/total/success/errors/duration_ms) */
    private volatile Map<String, Object> lastCycle;

    private volatile LocalDateTime updatedAt;

    public void updateSwitch(String deviceId, Map<String, Object> fields) {
        switches.compute(deviceId, (key, existing) -> {
            Map<String, Object> merged = existing == null
                    ? new LinkedHashMap<>(Map.of("id", deviceId, "type", "switch"))
                    : new LinkedHashMap<>(existing);
            merged.putAll(fields);
            return merged;
        });
        touch();
    }

    public void updateServer(String deviceId, Map<String, Object> fields) {
        servers.compute(deviceId, (key, existing) -> {
            Map<String, Object> merged = existing == null
                    ? new LinkedHashMap<>(Map.of("id", deviceId, "type", "server"))
                    : new LinkedHashMap<>(existing);
            merged.putAll(fields);
            return merged;
        });
        touch();
    }

    /** 每轮快照重建: 清掉已下线/删除的设备 */
    public void replaceSwitches(Map<String, Map<String, Object>> snapshot) {
        switches.keySet().retainAll(snapshot.keySet());
        snapshot.forEach(this::updateSwitch);
    }

    public void replaceServers(Map<String, Map<String, Object>> snapshot) {
        servers.keySet().retainAll(snapshot.keySet());
        snapshot.forEach(this::updateServer);
    }

    public void setAlertCounts(int active, int critical) {
        this.activeAlerts = active;
        this.criticalAlerts = critical;
        touch();
    }

    public void markCycle(String phase, Map<String, Object> stats) {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("phase", phase);
        cycle.put("at", DateTimeSerializers.fmt(LocalDateTime.now()));
        if (stats != null) {
            cycle.putAll(stats);
        }
        this.lastCycle = cycle;
        touch();
    }

    public Map<String, Object> overview() {
        int swTotal = switches.size();
        int swOnline = countOnline(switches);
        int srvTotal = servers.size();
        int srvOnline = countOnline(servers);
        int total = swTotal + srvTotal;
        int online = swOnline + srvOnline;
        return Maps.of(
                "switches", Maps.of("total", swTotal, "online", swOnline, "offline", swTotal - swOnline),
                "servers", Maps.of("total", srvTotal, "online", srvOnline, "offline", srvTotal - srvOnline),
                "alerts", Maps.of("active", activeAlerts, "critical", criticalAlerts),
                "total_devices", total,
                "online_devices", online,
                "online_rate", total == 0 ? 0D : RoundUtil.round(online * 100.0 / total, 1),
                "updated_at", DateTimeSerializers.fmt(updatedAt));
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> devices = new ArrayList<>(switches.values());
        devices.addAll(servers.values());
        return Maps.of(
                "overview", overview(),
                "devices", devices,
                "last_cycle", lastCycle,
                "generated_at", DateTimeSerializers.fmt(LocalDateTime.now()));
    }

    public Map<String, Object> alerts() {
        return Maps.of("active", activeAlerts, "critical", criticalAlerts);
    }

    public Map<String, Object> lastCycle() {
        return lastCycle;
    }

    public int switchesTracked() {
        return switches.size();
    }

    public int serversTracked() {
        return servers.size();
    }

    public String updatedAtText() {
        return DateTimeSerializers.fmt(updatedAt);
    }

    public Map<String, Map<String, Object>> switches() {
        return switches;
    }

    public Map<String, Map<String, Object>> servers() {
        return servers;
    }

    /** 设备状态是否发生翻转: 快照轮询据此决定是否推送 device 事件 */
    public boolean switchOnlineState(String deviceId) {
        Map<String, Object> device = switches.get(deviceId);
        return device != null && Boolean.TRUE.equals(device.get("is_online"));
    }

    private static int countOnline(Map<String, Map<String, Object>> group) {
        int online = 0;
        for (Map<String, Object> device : group.values()) {
            if (Boolean.TRUE.equals(device.get("is_online"))) {
                online++;
            }
        }
        return online;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
