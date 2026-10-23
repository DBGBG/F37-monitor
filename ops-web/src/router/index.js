import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

/**
 * 路由表.
 * meta.roles 为空表示登录即可访问; 菜单与路由双重校验, 权限不足回总览.
 * 一级路径全部单段, 保证打包成相对路径 (base: './') 后静态资源仍能正确解析.
 */
const routes = [
    {
        path: '/login',
        name: 'login',
        component: () => import('@/views/login/LoginView.vue'),
        meta: { public: true, title: '登录' },
    },
    {
        path: '/',
        component: () => import('@/layouts/MainLayout.vue'),
        children: [
            { path: '', redirect: '/dashboard' },
            {
                path: 'dashboard',
                name: 'dashboard',
                component: () => import('@/views/dashboard/DashboardView.vue'),
                meta: { title: '系统总览', icon: 'HomeFilled' },
            },
            {
                path: 'switches',
                name: 'switches',
                component: () => import('@/views/switches/SwitchesView.vue'),
                meta: { title: '交换机管理', icon: 'Refresh' },
            },
            {
                path: 'servers',
                name: 'servers',
                component: () => import('@/views/servers/ServersView.vue'),
                meta: { title: '服务器监控', icon: 'Monitor' },
            },
            {
                path: 'proactive',
                name: 'proactive',
                component: () => import('@/views/proactive/ProactiveView.vue'),
                meta: { title: '主动运维', icon: 'TrendCharts' },
            },
            {
                path: 'automation',
                name: 'automation',
                component: () => import('@/views/automation/AutomationView.vue'),
                meta: { title: '自动化配置', icon: 'Setting', roles: ['ADMIN', 'OPERATOR'] },
            },
            {
                path: 'alerts',
                name: 'alerts',
                component: () => import('@/views/alerts/AlertsView.vue'),
                meta: { title: '告警管理', icon: 'Warning' },
            },
            {
                path: 'inspection',
                name: 'inspection',
                component: () => import('@/views/inspection/InspectionView.vue'),
                meta: { title: '设备点检', icon: 'CircleCheck' },
            },
            {
                path: 'standards',
                name: 'standards',
                component: () => import('@/views/standards/StandardsView.vue'),
                meta: { title: '点检标准', icon: 'Memo' },
            },
            {
                path: 'skills',
                name: 'skills',
                component: () => import('@/views/skills/SkillsView.vue'),
                meta: { title: 'Skill 管理', icon: 'MagicStick' },
            },
            {
                path: 'duties',
                name: 'duties',
                component: () => import('@/views/duties/DutyView.vue'),
                meta: { title: '职责分工', icon: 'Avatar' },
            },
            {
                path: 'system',
                name: 'system',
                component: () => import('@/views/system/SystemView.vue'),
                meta: { title: '系统管理', icon: 'Files', roles: ['ADMIN', 'OPERATOR'] },
            },
        ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
});

router.beforeEach(async (to) => {
    const auth = useAuthStore();
    if (to.meta.public) {
        // 已登录且用户信息可用才跳总览; loaded=false 时停在登录页, 避免与下面的回退互相打转
        return auth.isLoggedIn && auth.loaded && to.path === '/login' ? { path: '/dashboard' } : true;
    }
    if (!auth.isLoggedIn) {
        return { path: '/login', query: to.fullPath === '/dashboard' ? {} : { redirect: to.fullPath } };
    }
    if (!auth.loaded) {
        try {
            await auth.fetchMe();
        } catch (e) {
            // 只有 401/403 才是登录态真的没了, 这时才清 token 重新登录;
            // 采集轮写入远端 MySQL 时的超时/瞬时 500 属于后端抖动, 保留 token 让用户重进即可
            if (e && e.authExpired) {
                auth.clear();
            }
            return { path: '/login', query: { redirect: to.fullPath } };
        }
    }
    const roles = to.meta.roles;
    if (roles && roles.length && !roles.includes(auth.roleCode)) {
        return { path: '/dashboard' };
    }
    return true;
});

router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} - 主动运维系统` : '主动运维系统';
});

export default router;
