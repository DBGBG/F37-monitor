import http from './http';

/** 交换机接口 (业务层只读采集层写入的字段, 台账改动走 PUT) */

export const summary = () => http.get('/switches/summary');

export const list = (params = {}) => http.get('/switches/list', {
    params: {
        page: params.page || 1,
        page_size: params.pageSize || 50,
        keyword: params.keyword || '',
        online_only: params.onlineOnly === true,
    },
});

export const detail = (id) => http.get(`/switches/${id}/detail`);

export const ports = (id) => http.get(`/switches/${id}/ports`);

export const traffic = (id, portIndex, hours = 1) =>
    http.get(`/switches/${id}/traffic`, { params: { port_index: portIndex, hours } });

export const hardware = (id, hours = 1) => http.get(`/switches/${id}/hardware`, { params: { hours } });

/** 编辑表单初始值 (凭据为掩码) */
export const forEdit = (id) => http.get(`/switches/${id}/edit`);

export const update = (id, payload) => http.put(`/switches/${id}`, payload);

export const remove = (id) => http.delete(`/switches/${id}`);

/** 通知采集层定向重采一次 (异步, 结果看心跳) */
export const refresh = (id) => http.post(`/switches/${id}/refresh`);
