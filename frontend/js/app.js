/**
 * MOXA 主动运维监控系统 - Vue 3 主应用
 * 注册组件、路由、全局状态
 */

const { createApp, ref, computed, onMounted, onUnmounted } = Vue;

const app = createApp({
    components: {
        DashboardPage,
        SwitchesPage,
        ServersPage,
        ProactivePage,
        AutomationPage,
        AlertsPage,
        InspectionPage,
    },
    setup() {
        const currentPage = ref('dashboard');
        const isCollapsed = ref(false);
        const systemStatus = ref('running');
        const currentTime = ref('');
        const activeAlertCount = ref(0);
        const realtimeStatus = Realtime.status;

        const pageTitles = {
            dashboard: '系统总览',
            switches: '交换机管理',
            servers: '服务器监控',
            proactive: '主动运维',
            automation: '自动化配置',
            alerts: '告警管理',
            inspection: '设备点检',
        };

        const pageTitle = computed(() => pageTitles[currentPage.value] || '');

        const handleMenuSelect = (index) => {
            currentPage.value = index;
        };

        // 时钟更新
        let clockTimer = null;
        const updateClock = () => {
            const now = new Date();
            currentTime.value = now.toLocaleString('zh-CN', {
                year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit',
            });
        };

        // 定期检查活跃告警数
        let alertTimer = null;
        const fetchAlertCount = async () => {
            try {
                const resp = await axios.get('/api/alerts/summary');
                activeAlertCount.value = resp.data.active || 0;
            } catch (e) { /* ignore */ }
        };

        onMounted(() => {
            updateClock();
            clockTimer = setInterval(updateClock, 1000);

            // 实时推送: 活跃告警计数秒级更新 (30s 轮询仅作兜底)
            Realtime.connect();
            Realtime.on('overview', (o) => {
                if (o && o.alerts) activeAlertCount.value = o.alerts.active || 0;
            });
            Realtime.on('alerts', (a) => {
                activeAlertCount.value = (a && a.active) || 0;
            });

            fetchAlertCount();
            alertTimer = setInterval(fetchAlertCount, 30000);
        });

        onUnmounted(() => {
            if (clockTimer) clearInterval(clockTimer);
            if (alertTimer) clearInterval(alertTimer);
        });

        return {
            currentPage, isCollapsed, systemStatus,
            currentTime, activeAlertCount, pageTitle,
            realtimeStatus,
            handleMenuSelect,
        };
    }
});

// 使用 Element Plus
app.use(ElementPlus, { locale: ElementPlusLocaleZhCn });
app.mount('#app');
