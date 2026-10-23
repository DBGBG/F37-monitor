import http from './http';

/** 鉴权与用户管理接口 */

export const login = (username, password) => http.post('/auth/login', { username, password });

export const me = () => http.get('/auth/me');

export const logout = () => http.post('/auth/logout');

export const changePassword = (oldPassword, newPassword) =>
    http.post('/auth/change-password', { old_password: oldPassword, new_password: newPassword });

export const listUsers = () => http.get('/users');

export const listRoles = () => http.get('/users/roles');

export const createUser = (payload) => http.post('/users', payload);

export const updateUser = (id, payload) => http.put(`/users/${id}`, payload);

export const deleteUser = (id) => http.delete(`/users/${id}`);
