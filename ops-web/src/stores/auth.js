import { defineStore } from 'pinia';
import { getToken, setToken } from '@/api/http';
import { changePassword, login as loginApi, logout as logoutApi, me } from '@/api/auth';

/**
 * 登录态与权限.
 * 角色只有三级 (ADMIN > OPERATOR > VIEWER), 路由与菜单按 role_code 控制;
 * permissions 清单用于按钮级显隐 (ADMIN 为 ["*"]).
 */
export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: getToken(),
        user: null,
        permissions: [],
        loaded: false,
    }),
    getters: {
        isLoggedIn: (state) => !!state.token,
        roleCode: (state) => (state.user && state.user.role_code) || '',
        isAdmin: (state) => (state.user && state.user.role_code) === 'ADMIN',
        /** 可执行写操作 (确认告警 / 点检 / 配置下发) */
        canWrite: (state) => ['ADMIN', 'OPERATOR'].includes(state.user ? state.user.role_code : ''),
        username: (state) => (state.user && state.user.username) || '',
        displayName: (state) => (state.user && state.user.display_name) || (state.user && state.user.username) || '',
    },
    actions: {
        async login(username, password) {
            const data = await loginApi(username, password);
            this.token = data.token;
            setToken(data.token);
            this.user = data.user;
            this.permissions = data.user.permissions || [];
            this.loaded = true;
            return data;
        },
        /** 路由守卫在刷新后恢复用户信息; 失败时由 http 拦截器统一处理 401 */
        async fetchMe() {
            if (!this.token) {
                return null;
            }
            const data = await me();
            this.user = data;
            this.permissions = data.permissions || [];
            this.loaded = true;
            return data;
        },
        async logout() {
            try {
                await logoutApi();
            } catch (e) {
                /* 登出失败也要清本地态 */
            }
            this.clear();
        },
        async changePassword(oldPassword, newPassword) {
            const data = await changePassword(oldPassword, newPassword);
            this.clear();
            return data;
        },
        clear() {
            this.token = '';
            this.user = null;
            this.permissions = [];
            this.loaded = false;
            setToken('');
        },
        hasPermission(code) {
            if (this.permissions.includes('*')) {
                return true;
            }
            return this.permissions.includes(code);
        },
    },
});
