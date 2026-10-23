import http from './http';

/**
 * 自动化配置下发接口.
 * CLI 下发是同步等待设备回显的长耗时操作 (业务层单条命令 30s 超时),
 * 因此这里把超时从默认 15s 放大到 180s, 避免前端先于后端断开.
 */
const SSH_TIMEOUT = 180000;

const post = (url, payload) => http.post(url, payload, { timeout: SSH_TIMEOUT });

export const vlanCreate = (payload) => post('/automation/vlan/create', payload);

export const vlanDelete = (payload) => post('/automation/vlan/delete', payload);

export const portAccess = (payload) => post('/automation/port/access', payload);

export const portTrunk = (payload) => post('/automation/port/trunk', payload);

export const portToggle = (payload) => post('/automation/port/toggle', payload);

/** use_vlan_plan=true 时按 sys_config.vlan_plan 批量下发 */
export const vlanBatch = (payload) => post('/automation/vlan/batch', payload);

export const rawCommand = (payload) => post('/automation/raw-command', payload);

export const vlanPlan = () => http.get('/automation/vlan-plan');

export const auditLogs = (params = {}) => http.get('/automation/audit-logs', {
    params: {
        switch_id: params.switchId || null,
        page: params.page || 1,
        page_size: params.pageSize || 50,
    },
});

export const auditLogDetail = (id) => http.get(`/automation/audit-logs/${id}`);
