import http from './http';

/** 服务器接口: 指标与进程来自采集层, 台账由业务层维护 */

export const summary = () => http.get('/servers/summary');

export const list = (params = {}) => http.get('/servers/list', {
    params: {
        page: params.page || 1,
        page_size: params.pageSize || 20,
        keyword: params.keyword || '',
    },
});

export const detail = (id) => http.get(`/servers/${id}/detail`);

export const metrics = (id, hours = 1) => http.get(`/servers/${id}/metrics`, { params: { hours } });

export const processes = (id) => http.get(`/servers/${id}/processes`);

export const forEdit = (id) => http.get(`/servers/${id}/edit`);

/** 新增/编辑: 字段名一律 snake_case (业务层 Jackson 全局策略) */
export const create = (payload) => http.post('/servers', payload);

export const update = (id, payload) => http.put(`/servers/${id}`, payload);

export const remove = (id) => http.delete(`/servers/${id}`);
