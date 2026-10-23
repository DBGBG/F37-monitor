import http from './http';

/** 点检标准库接口: 标准 CRUD / 启停 / 下拉字典 */

export const standards = (params = {}) => http.get('/inspection/standards', {
    params: {
        keyword: params.keyword || '',
        category: params.category || 'all',
        enabled: params.enabled == null ? '' : params.enabled,
    },
});

export const standardOptions = () => http.get('/inspection/standards/options');

export const createStandard = (payload) => http.post('/inspection/standards', payload);

export const updateStandard = (id, payload) => http.put(`/inspection/standards/${id}`, payload);

export const toggleStandard = (id) => http.post(`/inspection/standards/${id}/toggle`);

export const deleteStandard = (id) => http.delete(`/inspection/standards/${id}`);
