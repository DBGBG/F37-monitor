/**
 * 实时推送客户端 (SSE - Server-Sent Events)
 * ------------------------------------------------------------
 * 单例 EventSource, 供各页面订阅后端推送, 替代 30s 轮询:
 *   Realtime.on('overview', fn)  // 设备/告警统计变化
 *   Realtime.on('device',   fn)  // 单台设备状态更新
 *   Realtime.on('alert',    fn)  // 告警触发/恢复
 *   Realtime.on('alerts',   fn)  // 活跃告警计数
 *   Realtime.on('cycle',    fn)  // 一次采集周期完成
 *   Realtime.status              // Vue.ref: 'connecting' | 'open' | 'closed'
 */
const Realtime = {
    _es: null,
    _subs: {},
    _retry: 0,
    _events: ['snapshot', 'overview', 'alerts', 'device', 'alert', 'cycle', 'trap'],
    status: Vue.ref('connecting'),
    lastEventAt: Vue.ref(null),

    connect() {
        if (this._es) return;
        try {
            this._es = new EventSource('/api/stream');
        } catch (e) {
            this._scheduleReconnect();
            return;
        }
        this._es.onopen = () => {
            this.status.value = 'open';
            this._retry = 0;
        };
        this._es.onerror = () => {
            this.status.value = 'closed';
            try { this._es.close(); } catch (e) { /* ignore */ }
            this._es = null;
            this._scheduleReconnect();
        };
        this._events.forEach((name) => {
            this._es.addEventListener(name, (ev) => {
                let data = ev.data;
                try { data = JSON.parse(ev.data); } catch (e) { /* keep raw */ }
                this.lastEventAt.value = new Date().toLocaleTimeString('zh-CN');
                this._emit(name, data);
            });
        });
    },

    _scheduleReconnect() {
        this._retry += 1;
        const delay = Math.min(30000, 2000 * this._retry);
        setTimeout(() => this.connect(), delay);
    },

    on(event, fn) {
        (this._subs[event] = this._subs[event] || []).push(fn);
    },

    _emit(event, data) {
        (this._subs[event] || []).forEach((fn) => {
            try { fn(data); } catch (e) { console.error('SSE handler error:', event, e); }
        });
    },
};
