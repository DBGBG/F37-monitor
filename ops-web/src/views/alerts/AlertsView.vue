<template>
    <div class="alerts-page">
        <div class="page-title">告警管理</div>

        <!-- 统计卡片 -->
        <el-row :gutter="16" class="mb-16">
            <el-col v-for="item in summaryCards" :key="item.key" :xs="12" :sm="8" :md="4">
                <div class="stat-card" :class="item.cls" style="text-align:center;position:relative">
                    <div class="stat-value">{{ summary[item.key] || 0 }}</div>
                    <div class="stat-label">{{ item.label }}</div>
                </div>
            </el-col>
        </el-row>

        <!-- 过滤栏 -->
        <div class="search-bar">
            <el-select v-model="statusFilter" style="width:140px" @change="search">
                <el-option label="活跃告警" value="active"/>
                <el-option label="已确认" value="acknowledged"/>
                <el-option label="已恢复" value="resolved"/>
                <el-option label="全部" value="all"/>
            </el-select>
            <el-select v-model="severityFilter" style="width:140px" clearable placeholder="告警级别" @change="search">
                <el-option label="CRITICAL" value="CRITICAL"/>
                <el-option label="WARNING" value="WARNING"/>
                <el-option label="INFO" value="INFO"/>
            </el-select>
            <el-button @click="fetchAlerts">刷新</el-button>
            <span class="text-muted" style="margin-left:auto">
                通知渠道: {{ notifySummary }}
            </span>
        </div>

        <!-- 告警列表 -->
        <el-table :data="alertList" stripe border v-loading="loading" :row-class-name="alertRowClass">
            <el-table-column type="expand">
                <template #default="{ row }">
                    <div class="alert-detail">
                        <div class="factor-row"><span>规则</span><span>{{ row.rule_name }}</span></div>
                        <div class="factor-row"><span>设备</span><span>{{ row.device_name }} ({{ row.device_id }})</span></div>
                        <div class="factor-row">
                            <span>实测 / 阈值</span>
                            <span>{{ fmtNum(row.metric_value) }} / {{ fmtNum(row.threshold) }}</span>
                        </div>
                        <div class="factor-row">
                            <span>通知</span>
                            <span>
                                <el-tag :type="row.notified ? 'success' : 'info'" size="small">
                                    {{ row.notified ? '已推送' : '未推送' }}
                                </el-tag>
                            </span>
                        </div>
                        <div class="mt-8">{{ row.description }}</div>
                        <div class="text-muted mt-8" style="font-size:12px">
                            确认人 {{ row.acknowledged_by || '—' }} · 恢复时间 {{ row.resolved_at || '—' }}
                        </div>
                    </div>
                </template>
            </el-table-column>
            <el-table-column label="级别" width="100" align="center">
                <template #default="{ row }">
                    <el-tag :type="severityType(row.severity)" effect="dark" size="small">{{ row.severity }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="title" label="告警标题" min-width="260" show-overflow-tooltip/>
            <el-table-column prop="device_name" label="设备" width="160" show-overflow-tooltip/>
            <el-table-column label="类型" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="row.device_type === 'switch' ? '' : 'success'">
                        {{ row.device_type === 'switch' ? '交换机' : '服务器' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
                <template #default="{ row }">
                    <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="triggered_at" label="触发时间" width="170"/>
            <el-table-column label="操作" width="180" align="center">
                <template #default="{ row }">
                    <template v-if="row.status === 'active' && auth.canWrite">
                        <el-button size="small" type="warning" @click="acknowledge(row)">确认</el-button>
                        <el-button size="small" type="success" @click="resolve(row)">恢复</el-button>
                    </template>
                    <span v-else-if="row.status === 'active'" class="text-muted">只读账号</span>
                    <span v-else style="color:#ccc">已处理</span>
                </template>
            </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="pager">
            <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
                           layout="prev, pager, next, total" @current-change="fetchAlerts"/>
        </div>
    </div>
</template>

<script setup>
/**
 * 告警管理.
 * 告警由业务层 AlertEngineService 每 30s 巡检产生 (采集层不参与判定),
 * 因此这里的实时性来自 SSE: 收到 alert 事件去抖 800ms 后刷新, 30s 轮询只作兜底.
 * 操作人不再硬编码 'admin', 统一取当前登录用户.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import * as alertsApi from '@/api/alerts';
import { useAuthStore } from '@/stores/auth';
import { useRealtimeStore } from '@/stores/realtime';

const auth = useAuthStore();
const realtime = useRealtimeStore();

const alertList = ref([]);
const loading = ref(false);
const statusFilter = ref('active');
const severityFilter = ref('');
const page = ref(1);
const pageSize = 50;
const total = ref(0);
const summary = ref({});

const summaryCards = [
    { key: 'active', label: '活跃告警', cls: 'danger' },
    { key: 'critical', label: '严重告警', cls: 'danger' },
    { key: 'warning', label: '警告', cls: 'warning' },
    { key: 'acknowledged', label: '已确认', cls: 'primary' },
    { key: 'resolved', label: '已恢复', cls: 'success' },
    { key: 'recent_24h', label: '24h新增', cls: '' },
];

const notifySummary = computed(() => {
    const list = alertList.value;
    if (!list.length) return '—';
    const pushed = list.filter((a) => a.notified).length;
    return `${pushed} / ${list.length} 条已推送`;
});

const severityType = (s) => (s === 'CRITICAL' ? 'danger' : s === 'WARNING' ? 'warning' : 'info');
const statusType = (s) => (s === 'active' ? 'danger' : s === 'acknowledged' ? 'warning' : 'success');
const statusLabel = (s) => (s === 'active' ? '活跃' : s === 'acknowledged' ? '已确认' : '已恢复');
const alertRowClass = ({ row }) => (row.severity === 'CRITICAL' && row.status === 'active' ? 'row-critical' : '');
const fmtNum = (v) => (v == null ? '—' : Math.round(Number(v) * 100) / 100);

const fetchSummary = async () => {
    try {
        summary.value = await alertsApi.summary();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const fetchAlerts = async () => {
    loading.value = true;
    try {
        const data = await alertsApi.list({
            status: statusFilter.value, severity: severityFilter.value, page: page.value, pageSize,
        });
        alertList.value = data.items || [];
        total.value = data.total || 0;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.value = false;
    }
};

const search = () => {
    page.value = 1;
    fetchAlerts();
};

const acknowledge = async (row) => {
    try {
        await alertsApi.acknowledge(row.alert_id);
        ElMessage.success('告警已确认');
        fetchAlerts();
        fetchSummary();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const resolve = async (row) => {
    try {
        await alertsApi.resolve(row.alert_id);
        ElMessage.success('告警已恢复');
        fetchAlerts();
        fetchSummary();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

let timer = null;
let refreshTimer = null;
let unsubs = [];

/** 告警推送去抖: 短时间内多条告警只刷新一次, 避免告警风暴下频繁请求 */
const scheduleRefresh = () => {
    if (refreshTimer) return;
    refreshTimer = setTimeout(() => {
        refreshTimer = null;
        fetchSummary();
        fetchAlerts();
    }, 800);
};

onMounted(() => {
    fetchSummary();
    fetchAlerts();
    unsubs.push(realtime.on('alert', scheduleRefresh));
    unsubs.push(realtime.on('alerts', (data) => {
        if (data) summary.value = Object.assign({}, summary.value, data);
    }));
    timer = setInterval(() => {
        fetchSummary();
        fetchAlerts();
    }, 30000);
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    if (refreshTimer) clearTimeout(refreshTimer);
    unsubs.forEach((fn) => fn && fn());
});
</script>

<style scoped>
.pager {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
}

.alert-detail {
    padding: 8px 24px;
    color: #666;
    font-size: 13px;
}

:deep(.row-critical) {
    background: #fff6f6;
}
</style>
