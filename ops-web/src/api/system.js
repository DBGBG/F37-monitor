import http from './http';

/** 总览 / 系统自监控 / 采集层管理 / 运行参数 (sys_config) 接口 */

// ---------------- 总览 ----------------

export const dashboardOverview = () => http.get('/dashboard/overview');

// ---------------- 系统自监控 ----------------

export const health = () => http.get('/system/health');

export const status = () => http.get('/system/status');

export const maintenanceStats = () => http.get('/system/maintenance/stats');

/** 手动清理: metrics_days / alerts_days 留空则按 sys_config.retention_policy */
export const cleanup = (payload = {}) => http.post('/system/maintenance/cleanup', payload, { timeout: 120000 });

// ---------------- 跨层指令 (业务层写库, 采集层拉取执行) ----------------

export const syncDevices = () => http.post('/system/sync-devices');

export const rediscover = () => http.post('/system/rediscover');

export const collectorHeartbeat = () => http.get('/system/collector/heartbeat');

export const collectorBeats = () => http.get('/system/collector/heartbeat/recent');

export const collectorCommands = () => http.get('/system/collector/commands');

// ---------------- 采集任务周期 ----------------

export const collectorJobs = () => http.get('/collector-jobs');

export const updateCollectorJob = (jobId, payload) => http.put(`/collector-jobs/${jobId}`, payload);

// ---------------- 扫描网段 ----------------

export const discoveryGroups = () => http.get('/discovery-groups');

export const createDiscoveryGroup = (payload) => http.post('/discovery-groups', payload);

export const updateDiscoveryGroup = (id, payload) => http.put(`/discovery-groups/${id}`, payload);

export const toggleDiscoveryGroup = (id) => http.post(`/discovery-groups/${id}/toggle`);

export const deleteDiscoveryGroup = (id) => http.delete(`/discovery-groups/${id}`);

// ---------------- 运行参数 ----------------

export const configs = () => http.get('/system/configs');

export const configValues = () => http.get('/system/configs/values');

export const configKeys = () => http.get('/system/configs/keys');

export const updateConfig = (key, value, description) =>
    http.put(`/system/configs/${key}`, { value, description });
