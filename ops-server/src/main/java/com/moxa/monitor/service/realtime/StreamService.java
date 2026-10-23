package com.moxa.monitor.service.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 推送出口 (迁移前 backend/realtime.py 的 EventBus).
 * 事件类型与迁移前一致: snapshot / overview / device / alert / cycle.
 * 每个订阅者一个 SseEmitter, 发送失败即摘除, 不影响其它订阅者.
 */
@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    /** 不超时: 由前端 EventSource 自行重连 */
    private static final long NO_TIMEOUT = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong publishedCount = new AtomicLong();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    /** 连接建立时的首屏全量快照 */
    public void sendSnapshot(SseEmitter emitter, Map<String, Object> snapshot) {
        send(emitter, "snapshot", snapshot);
    }

    public void publish(String type, Object data) {
        if (emitters.isEmpty()) {
            return;
        }
        publishedCount.incrementAndGet();
        for (SseEmitter emitter : emitters) {
            send(emitter, type, data);
        }
    }

    /** 空闲保活: SSE 注释行, 浏览器不会当作事件处理 */
    public void ping() {
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
            }
        }
    }

    public int subscribers() {
        return emitters.size();
    }

    public long publishedCount() {
        return publishedCount.get();
    }

    private void send(SseEmitter emitter, String type, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(type).data(data == null ? Map.of() : data));
            }
        } catch (IOException | IllegalStateException ex) {
            // 客户端断开属正常情况, 摘除即可
            emitters.remove(emitter);
            log.debug("SSE 推送失败, 摘除订阅者: {}", ex.getMessage());
        }
    }
}
