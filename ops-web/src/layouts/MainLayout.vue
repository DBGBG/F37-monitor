<template>
    <el-container class="app-container">
        <!-- 侧边栏 -->
        <el-aside :width="isCollapsed ? '64px' : '220px'" class="app-aside">
            <div class="logo-area">
                <span class="logo-icon">&#9670;</span>
                <span v-show="!isCollapsed" class="logo-text">主动运维系统</span>
            </div>
            <el-menu
                :default-active="activeMenu"
                :collapse="isCollapsed"
                background-color="#1d1e3c"
                text-color="#a0a4c8"
                active-text-color="#409EFF"
                router
            >
                <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
                    <el-icon><component :is="item.icon"/></el-icon>
                    <template #title>{{ item.title }}</template>
                    <el-badge
                        v-if="item.name === 'alerts' && activeAlerts > 0"
                        :value="activeAlerts"
                        class="alert-badge"
                        type="danger"
                    />
                </el-menu-item>
            </el-menu>
        </el-aside>

        <el-container>
            <!-- 顶部栏 -->
            <el-header class="app-header">
                <div class="header-left">
                    <el-button text @click="isCollapsed = !isCollapsed">
                        <el-icon><Fold v-if="!isCollapsed"/><Expand v-else/></el-icon>
                    </el-button>
                    <el-breadcrumb separator="/">
                        <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
                        <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
                    </el-breadcrumb>
                </div>
                <div class="header-right">
                    <el-tag
                        :type="realtime.status === 'open' ? 'success' : 'warning'"
                        effect="plain"
                        size="small"
                        :title="`实时推送通道 (SSE) · 最近事件 ${realtime.lastEventAt || '—'}`"
                    >
                        {{ realtime.status === 'open' ? '● 实时' : '○ 连接中' }}
                    </el-tag>
                    <el-tag
                        :type="collectorAlive ? 'success' : 'danger'"
                        effect="plain"
                        size="small"
                        title="采集层心跳 (Python 进程)"
                    >
                        {{ collectorAlive ? '采集正常' : '采集异常' }}
                    </el-tag>
                    <el-tag :type="systemStatus === 'running' ? 'info' : 'danger'" effect="dark" size="small">
                        {{ systemStatus === 'running' ? '业务层运行中' : '业务层异常' }}
                    </el-tag>
                    <span class="header-time">{{ currentTime }}</span>
                    <el-dropdown @command="handleCommand">
                        <span class="header-user">
                            <el-icon><UserFilled/></el-icon>
                            {{ auth.displayName }}
                            <el-tag size="small" effect="plain">{{ roleLabel }}</el-tag>
                        </span>
                        <template #dropdown>
                            <el-dropdown-menu>
                                <el-dropdown-item command="password">修改口令</el-dropdown-item>
                                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                            </el-dropdown-menu>
                        </template>
                    </el-dropdown>
                </div>
            </el-header>

            <el-main class="app-main">
                <router-view v-slot="{ Component }">
                    <keep-alive :max="3">
                        <component :is="Component"/>
                    </keep-alive>
                </router-view>
            </el-main>
        </el-container>

        <!-- 修改口令 -->
        <el-dialog v-model="pwdVisible" title="修改口令" width="380px">
            <el-form label-width="80px">
                <el-form-item label="原口令">
                    <el-input v-model="pwdForm.oldPassword" type="password" show-password/>
                </el-form-item>
                <el-form-item label="新口令">
                    <el-input v-model="pwdForm.newPassword" type="password" show-password/>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="pwdVisible = false">取消</el-button>
                <el-button type="primary" :loading="pwdLoading" @click="submitPassword">确定</el-button>
            </template>
        </el-dialog>
    </el-container>
</template>

<script setup>
/**
 * 主框架: 侧边菜单 + 顶栏状态 + 路由出口.
 * 活跃告警数优先取 SSE 推送, 30s 轮询 /api/alerts/summary 仅作兜底 (与迁移前一致);
 * 采集层存活取 /api/system/status, 60s 一次 —— 采集层已不再是本进程的线程, 只能靠心跳表判断.
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useRealtimeStore } from '@/stores/realtime';
import { status as systemStatusApi } from '@/api/system';
import * as alertsApi from '@/api/alerts';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const realtime = useRealtimeStore();

const isCollapsed = ref(false);
const currentTime = ref('');
const systemStatus = ref('running');
const collectorAlive = ref(false);
const polledAlerts = ref(0);

const ROLE_LABELS = { ADMIN: '管理员', OPERATOR: '运维', VIEWER: '只读' };
const roleLabel = computed(() => ROLE_LABELS[auth.roleCode] || auth.roleCode || '未登录');

const activeAlerts = computed(() => {
    const pushed = realtime.alerts && realtime.alerts.active;
    return pushed || polledAlerts.value || 0;
});

const pageTitle = computed(() => route.meta.title || '');
const activeMenu = computed(() => route.path);

/** 菜单顺序与路由表一致, 单段路径按业务习惯固定排列 */
const MENU_ORDER = [
    'dashboard', 'switches', 'servers', 'proactive', 'automation', 'alerts',
    'inspection', 'standards', 'skills', 'duties', 'system',
];

const menus = computed(() => router.getRoutes()
    .filter((r) => r.meta && r.meta.title && !r.meta.public)
    .filter((r) => !r.meta.roles || r.meta.roles.includes(auth.roleCode))
    .map((r) => ({
        path: r.path,
        name: r.name,
        title: r.meta.title,
        icon: r.meta.icon,
        order: MENU_ORDER.indexOf(r.name),
    }))
    .sort((a, b) => (a.order < 0 ? 99 : a.order) - (b.order < 0 ? 99 : b.order)));

let clockTimer = null;
let alertTimer = null;
let statusTimer = null;
let unsubs = [];

const updateClock = () => {
    currentTime.value = new Date().toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
};

const fetchAlertCount = async () => {
    try {
        const data = await alertsApi.summary();
        polledAlerts.value = data.active || 0;
    } catch (e) {
        /* 兜底轮询失败不打扰用户 */
    }
};

const fetchStatus = async () => {
    try {
        const data = await systemStatusApi();
        systemStatus.value = data.status || 'running';
        collectorAlive.value = !!data.collector_alive;
    } catch (e) {
        systemStatus.value = 'stopped';
    }
};

const pwdVisible = ref(false);
const pwdLoading = ref(false);
const pwdForm = reactive({ oldPassword: '', newPassword: '' });

const handleCommand = async (command) => {
    if (command === 'logout') {
        try {
            await ElMessageBox.confirm('确认退出登录?', '提示', { type: 'warning' });
        } catch (e) {
            return; // 用户取消
        }
        realtime.close();
        await auth.logout();
        router.replace('/login');
        return;
    }
    pwdForm.oldPassword = '';
    pwdForm.newPassword = '';
    pwdVisible.value = true;
};

const submitPassword = async () => {
    if (!pwdForm.oldPassword || !pwdForm.newPassword) {
        ElMessage.warning('请填写原口令与新口令');
        return;
    }
    pwdLoading.value = true;
    try {
        await auth.changePassword(pwdForm.oldPassword, pwdForm.newPassword);
        ElMessage.success('口令已修改, 请重新登录');
        pwdVisible.value = false;
        realtime.close();
        router.replace('/login');
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        pwdLoading.value = false;
    }
};

onMounted(() => {
    updateClock();
    clockTimer = setInterval(updateClock, 1000);

    realtime.connect();
    unsubs.push(realtime.on('cycle', () => {
        collectorAlive.value = true;
        fetchStatus();
    }));
    unsubs.push(realtime.on('alerts', (data) => {
        if (data) polledAlerts.value = data.active || 0;
    }));

    fetchAlertCount();
    fetchStatus();
    alertTimer = setInterval(fetchAlertCount, 30000);
    statusTimer = setInterval(fetchStatus, 60000);
});

onUnmounted(() => {
    if (clockTimer) clearInterval(clockTimer);
    if (alertTimer) clearInterval(alertTimer);
    if (statusTimer) clearInterval(statusTimer);
    unsubs.forEach((fn) => fn && fn());
    realtime.close();
});
</script>

<style scoped>
.el-badge.alert-badge {
    line-height: 1;
}
</style>
