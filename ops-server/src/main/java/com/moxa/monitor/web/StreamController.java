package com.moxa.monitor.web;

import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.common.Maps;
import com.moxa.monitor.common.Result;
import com.moxa.monitor.security.RoleGuard;
import com.moxa.monitor.service.realtime.LiveStateHolder;
import com.moxa.monitor.service.realtime.SnapshotPoller;
import com.moxa.monitor.service.realtime.StreamService;
import com.moxa.monitor.service.system.CollectorMonitorService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实时推送接口 (SSE 归属业务层).
 * 事件类型与迁移前一致: snapshot / overview / device / alert / cycle.
 * EventSource 无法携带请求头, 前端用 ?token= 建立连接 (JwtAuthFilter 已支持).
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final StreamService streamService;
    private final LiveStateHolder live;
    private final SnapshotPoller poller;
    private final CollectorMonitorService collectorService;
    private final RoleGuard roleGuard;

    public StreamController(StreamService streamService, LiveStateHolder live, SnapshotPoller poller,
                            CollectorMonitorService collectorService, RoleGuard roleGuard) {
        this.streamService = streamService;
        this.live = live;
        this.poller = poller;
        this.collectorService = collectorService;
        this.roleGuard = roleGuard;
    }

    /** GET /api/stream —— 长连接, 首帧为全量快照 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        roleGuard.current();
        SseEmitter emitter = streamService.subscribe();
        streamService.sendSnapshot(emitter, live.snapshot());
        return emitter;
    }

    /** GET /api/realtime/status —— 实时子系统自监控 */
    @GetMapping("/realtime/status")
    public Result<Map<String, Object>> realtimeStatus() {
        LocalDateTime beatAt = collectorService.lastBeatAt();
        return Result.ok(Maps.of(
                "subscribers", streamService.subscribers(),
                "published", streamService.publishedCount(),
                "switches_tracked", live.switchesTracked(),
                "servers_tracked", live.serversTracked(),
                "alerts", live.alerts(),
                "last_cycle", live.lastCycle(),
                "updated_at", live.updatedAtText(),
                "collector_alive", poller.collectorAlive(),
                "last_beat_at", beatAt == null ? null : DateTimeSerializers.fmt(beatAt),
                "server_time", DateTimeSerializers.fmt(LocalDateTime.now())));
    }
}
