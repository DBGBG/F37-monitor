import http from './http';

/** 告警接口 */

export const summary = () => http.get('/alerts/summary');

export const list = (params = {}) => http.get('/alerts/list', {
    params: {
        status: params.status || 'active',
        severity: params.severity || '',
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

/** 操作人缺省由业务层取当前登录用户, 前端不再硬编码 'admin' */
export const acknowledge = (alertId, acknowledgedBy) =>
    http.post('/alerts/acknowledge', { alert_id: alertId, acknowledged_by: acknowledgedBy });

export const resolve = (alertId, resolvedBy) =>
    http.post('/alerts/resolve', { alert_id: alertId, resolved_by: resolvedBy });
