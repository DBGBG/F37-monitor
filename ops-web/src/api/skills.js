import http from './http';

/** 智能体技能清单接口: 技能 CRUD / 启停 / 下拉字典 */

export const skills = (params = {}) => http.get('/agent-skills', {
    params: {
        keyword: params.keyword || '',
        category: params.category || 'all',
        enabled: params.enabled == null ? '' : params.enabled,
    },
});

export const skillOptions = () => http.get('/agent-skills/options');

export const createSkill = (payload) => http.post('/agent-skills', payload);

export const updateSkill = (id, payload) => http.put(`/agent-skills/${id}`, payload);

export const toggleSkill = (id) => http.post(`/agent-skills/${id}/toggle`);

export const deleteSkill = (id) => http.delete(`/agent-skills/${id}`);
