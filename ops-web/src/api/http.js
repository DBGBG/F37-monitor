import axios from 'axios';
import { ElMessage } from 'element-plus';

/**
 * 唯一的 HTTP 出口.
 * 展示层只允许访问业务层 /api/**, 采集层不暴露任何 HTTP 接口.
 *
 * 统一约定 (与业务层 ops-server 的 Result 信封一一对应):
 *   {"code":0,"message":"success","data":{...},"timestamp":"yyyy-MM-dd HH:mm:ss"}
 * - code !== 0 时在此拦截并弹出 message, 业务代码只处理解包后的 data;
 * - 401xx 视为登录失效, 清 token 后跳登录页;
 * - 超时 15s (设备 CLI 下发接口单独放大, 见 automation.js).
 */

export const TOKEN_KEY = 'moxa_token';

export function getToken() {
    return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token) {
    if (token) {
        localStorage.setItem(TOKEN_KEY, token);
    } else {
        localStorage.removeItem(TOKEN_KEY);
    }
}

const http = axios.create({
    baseURL: '/api',
    timeout: 15000,
});

http.interceptors.request.use((config) => {
    const token = getToken();
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

/** 401 只跳转一次, 避免并发请求刷屏 */
let redirecting = false;

function toLogin() {
    setToken('');
    if (redirecting) {
        return;
    }
    redirecting = true;
    // 动态引入, 防止 router -> views -> api -> http 的循环依赖
    import('@/router').then(({ default: router }) => {
        router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } });
    }).finally(() => {
        redirecting = false;
    });
}

http.interceptors.response.use(
    (response) => {
        const body = response.data;
        // 非信封响应 (直接下载等非 JSON 场景) 原样返回
        if (!body || typeof body !== 'object' || body.code === undefined) {
            return body;
        }
        if (body.code === 0) {
            return body.data;
        }
        const authExpired = Math.floor(body.code / 100) === 401;
        if (authExpired) {
            toLogin();
        } else if (!response.config.silent) {
            ElMessage.error(body.message || '请求失败');
        }
        // authExpired 供路由守卫区分"登录真失效"与"后端抖动", 见 router/index.js
        return Promise.reject(Object.assign(new Error(body.message || '请求失败'),
            { code: body.code, authExpired }));
    },
    (error) => {
        const status = error.response ? error.response.status : 0;
        error.authExpired = status === 401 || status === 403;
        if (error.authExpired) {
            toLogin();
            ElMessage.error(status === 401 ? '登录已过期, 请重新登录' : '没有该操作的权限');
        } else if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' || error.type === 'timeout') {
            // 超时中止 ≠ 业务服务没起: 后端是连上了的, 只是这条查询太慢.
            // 原先两种情况共用同一句"业务服务未启动"提示, 导致采集层明明活着却被页面报"异常"
            ElMessage.error('接口响应超时 (15s), 业务层可能正在等数据库, 请重试');
        } else if (!error.response) {
            ElMessage.error('无法连接业务服务, 请确认业务层服务已启动');
        } else {
            // 业务层异常也被 GlobalExceptionHandler 转成信封, 这里只兜住容器级 5xx
            const message = error.response.data && error.response.data.message;
            ElMessage.error(message || `请求失败 (HTTP ${status})`);
        }
        return Promise.reject(error);
    },
);

export default http;
