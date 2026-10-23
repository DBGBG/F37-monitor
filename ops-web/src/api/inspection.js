import http from './http';

/** 设备点检接口: 模板 / 计划 / 任务 / 履历 / 统计 */

export const templates = () => http.get('/inspection/templates');

export const devices = (deviceType) =>
    http.get('/inspection/devices', { params: deviceType ? { device_type: deviceType } : {} });

export const plans = (params = {}) => http.get('/inspection/plans', {
    params: { keyword: params.keyword || '', enabled: params.enabled == null ? '' : params.enabled },
});

export const planDetail = (id) => http.get(`/inspection/plans/${id}`);

export const createPlan = (payload) => http.post('/inspection/plans', payload);

export const updatePlan = (id, payload) => http.put(`/inspection/plans/${id}`, payload);

export const deletePlan = (id) => http.delete(`/inspection/plans/${id}`);

export const togglePlan = (id) => http.post(`/inspection/plans/${id}/toggle`);

export const runPlanNow = (id) => http.post(`/inspection/plans/${id}/run-now`);

export const tasks = (params = {}) => http.get('/inspection/tasks', {
    params: {
        status: params.status || '',
        // Integer 型入参不能传空串 (会 400), 用 null 让 axios 直接省略该参数
        plan_id: params.planId || null,
        device_id: params.deviceId || '',
        device_type: params.deviceType || '',
        result: params.result || '',
        date_from: params.dateFrom || '',
        date_to: params.dateTo || '',
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

export const taskDetail = (id) => http.get(`/inspection/tasks/${id}`);

export const startTask = (id, operator) => http.post(`/inspection/tasks/${id}/start`, { operator });

export const executeTask = (id, payload) => http.post(`/inspection/tasks/${id}/execute`, payload);

export const skipTask = (id, payload) => http.post(`/inspection/tasks/${id}/skip`, payload);

export const history = (params = {}) => http.get('/inspection/history', {
    params: {
        device_id: params.deviceId || '',
        plan_id: params.planId || null,
        result: params.result || '',
        date_from: params.dateFrom || '',
        date_to: params.dateTo || '',
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

export const stats = () => http.get('/inspection/stats');
