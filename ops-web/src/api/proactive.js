import http from './http';

/** 主动运维接口: 健康评分 / 异常事件 / 建议 / 基线 / 维护窗口 */

export const overview = () => http.get('/proactive/overview');

export const health = (params = {}) => http.get('/proactive/health', {
    params: {
        device_type: params.deviceType || '',
        grade: params.grade || '',
        keyword: params.keyword || '',
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

export const healthDetail = (deviceId) => http.get(`/proactive/health/${deviceId}`);

export const anomalies = (params = {}) => http.get('/proactive/anomalies', {
    params: {
        status: params.status || 'open',
        severity: params.severity || '',
        device_type: params.deviceType || '',
        device_id: params.deviceId || '',
        metric: params.metric || '',
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

export const acknowledgeAnomaly = (id, by) => http.post(`/proactive/anomalies/${id}/acknowledge`, { by });

export const resolveAnomaly = (id, by) => http.post(`/proactive/anomalies/${id}/resolve`, { by });

export const recommendations = () => http.get('/proactive/recommendations');

export const baselines = (params = {}) => http.get('/proactive/baselines', {
    params: {
        device_id: params.deviceId || '',
        metric: params.metric || '',
        page: params.page || 1,
        page_size: params.pageSize || 100,
    },
});

/** 同步执行一轮 EWMA 分析 (仅 ADMIN, 耗时随设备数增长) */
export const analyze = () => http.post('/proactive/analyze');

// ---------------- 维护窗口 ----------------

export const windows = () => http.get('/proactive/maintenance/windows');

export const createWindow = (payload) => http.post('/proactive/maintenance/windows', payload);

export const updateWindow = (id, payload) => http.put(`/proactive/maintenance/windows/${id}`, payload);

export const toggleWindow = (id) => http.post(`/proactive/maintenance/windows/${id}/toggle`);

export const deleteWindow = (id) => http.delete(`/proactive/maintenance/windows/${id}`);
