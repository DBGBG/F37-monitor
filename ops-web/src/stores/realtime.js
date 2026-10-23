import { defineStore } from 'pinia';
import { getToken } from '@/api/http';

/**
 * SSE 实时通道 (业务层 /api/stream).
 * 事件与迁移前一致: snapshot / overview / alerts / device / alert / cycle.
 * EventSource 无法携带请求头, token 走查询参数 (业务层 JwtAuthFilter 已支持).
 */

const EVENTS = ['snapshot', 'overview', 'alerts', 'device', 'alert', 'cycle', 'trap'];

/** 订阅回调放在 store 外部, 避免把函数塞进 pinia 的响应式 state */
const handlers = new Map();

let source = null;
let retry = 0;
let retryTimer = null;

export const useRealtimeStore = defineStore('realtime', {
    state: () => ({
        status: 'connecting',
        lastEventAt: null,
        overview: {},
        alerts: { active: 0, critical: 0 },
        devices: [],
        lastCycle: null,
        collectorAlive: false,
    }),
    actions: {
        connect() {
            if (source || !getToken()) {
                return;
            }
            this.status = 'connecting';
            try {
                source = new EventSource(`/api/stream?token=${encodeURIComponent(getToken())}`);
            } catch (e) {
                this._scheduleReconnect();
                return;
            }
            source.onopen = () => {
                this.status = 'open';
                retry = 0;
            };
            source.onerror = () => {
                this.status = 'closed';
                try {
                    source.close();
                } catch (e) {
                    /* 已关闭 */
                }
                source = null;
                this._scheduleReconnect();
            };
            EVENTS.forEach((name) => {
                source.addEventListener(name, (ev) => {
                    let data = ev.data;
                    try {
                        data = JSON.parse(ev.data);
                    } catch (e) {
                        /* 保留原始文本 */
                    }
                    this.lastEventAt = new Date().toLocaleTimeString('zh-CN');
                    this._apply(name, data);
                    this._emit(name, data);
                });
            });
        },
        close() {
            if (retryTimer) {
                clearTimeout(retryTimer);
                retryTimer = null;
            }
            if (source) {
                try {
                    source.close();
                } catch (e) {
                    /* 已关闭 */
                }
                source = null;
            }
            this.status = 'closed';
        },
        /** 页面级订阅, 返回退订函数 */
        on(event, fn) {
            if (!handlers.has(event)) {
                handlers.set(event, []);
            }
            handlers.get(event).push(fn);
            return () => this.off(event, fn);
        },
        off(event, fn) {
            const list = handlers.get(event);
            if (list) {
                const idx = list.indexOf(fn);
                if (idx >= 0) {
                    list.splice(idx, 1);
                }
            }
        },
        _apply(event, data) {
            if (event === 'snapshot' && data) {
                this.overview = data.overview || {};
                this.devices = data.devices || [];
                this.lastCycle = data.last_cycle || null;
            } else if (event === 'overview' && data) {
                this.overview = data;
                if (data.alerts) {
                    this.alerts = data.alerts;
                }
            } else if (event === 'alerts' && data) {
                this.alerts = data;
            } else if (event === 'cycle' && data) {
                this.lastCycle = data;
                this.collectorAlive = true;
            }
        },
        _emit(event, data) {
            (handlers.get(event) || []).forEach((fn) => {
                try {
                    fn(data);
                } catch (e) {
                    console.error('SSE handler error:', event, e);
                }
            });
        },
        _scheduleReconnect() {
            retry += 1;
            const delay = Math.min(30000, 2000 * retry);
            if (retryTimer) {
                clearTimeout(retryTimer);
            }
            retryTimer = setTimeout(() => {
                retryTimer = null;
                this.connect();
            }, delay);
        },
    },
});
