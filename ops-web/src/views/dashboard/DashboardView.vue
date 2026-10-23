<template>
    <div class="dashboard">
        <div class="page-title">系统总览</div>

        <!-- 统计卡片 -->
        <el-row :gutter="16" class="mb-16">
            <el-col :xs="12" :sm="6">
                <StatCard label="设备总数" :value="overview.total_devices || 0" icon="Coin" type="primary"/>
            </el-col>
            <el-col :xs="12" :sm="6">
                <StatCard label="在线设备" :value="overview.online_devices || 0" icon="CircleCheck" type="success"/>
            </el-col>
            <el-col :xs="12" :sm="6">
                <StatCard label="活跃告警" :value="activeAlerts" icon="Warning" type="warning"/>
            </el-col>
            <el-col :xs="12" :sm="6">
                <StatCard label="严重告警" :value="criticalAlerts" icon="CircleClose" type="danger"/>
            </el-col>
        </el-row>

        <!-- 在线率 + 设备分布 -->
        <el-row :gutter="16" class="mb-16">
            <el-col :xs="24" :sm="12">
                <div class="chart-card">
                    <div class="chart-title">设备在线率</div>
                    <EChart :option="rateOption" height="260px"/>
                </div>
            </el-col>
            <el-col :xs="24" :sm="12">
                <div class="chart-card">
                    <div class="chart-title">设备分布</div>
                    <EChart :option="distOption" height="260px"/>
                </div>
            </el-col>
        </el-row>

        <!-- 点检 / 健康分布 (改造后总览新增的聚合) -->
        <el-row :gutter="16" class="mb-16">
            <el-col :xs="24" :sm="8">
                <div class="chart-card">
                    <div class="chart-title">设备点检</div>
                    <div class="grade-grid" style="grid-template-columns: repeat(2, 1fr)">
                        <div class="grade-cell g-zhong">
                            <div class="grade-count">{{ inspection.pending || 0 }}</div>
                            <div class="grade-name">待执行</div>
                        </div>
                        <div class="grade-cell g-cha">
                            <div class="grade-count">{{ inspection.overdue || 0 }}</div>
                            <div class="grade-name">已逾期</div>
                        </div>
                    </div>
                    <div class="text-muted" style="font-size:12px">
                        任务由业务层按计划的 crontab 生成, 采集层不参与
                    </div>
                </div>
            </el-col>
            <el-col :xs="24" :sm="8">
                <div class="chart-card">
                    <div class="chart-title">健康等级分布 (均分 {{ health.avg_score ?? '—' }})</div>
                    <div class="grade-grid">
                        <div v-for="cell in gradeCells" :key="cell.grade" class="grade-cell" :class="cell.css">
                            <div class="grade-count">{{ cell.count }}</div>
                            <div class="grade-name">{{ cell.grade }}</div>
                        </div>
                    </div>
                </div>
            </el-col>
            <el-col :xs="24" :sm="8">
                <div class="chart-card">
                    <div class="chart-title">采集层心跳</div>
                    <div class="factor-row">
                        <span>状态</span>
                        <el-tag :type="collectorAlive ? 'success' : 'danger'" size="small">
                            {{ collectorAlive ? '正常' : '异常' }}
                        </el-tag>
                    </div>
                    <div class="factor-row"><span>最近阶段</span><span>{{ lastCycle?.phase || '—' }}</span></div>
                    <div class="factor-row"><span>成功 / 总数</span><span>{{ beatText }}</span></div>
                    <div class="factor-row"><span>单轮耗时</span><span>{{ durationText }}</span></div>
                    <div class="factor-row"><span>心跳时间</span><span>{{ lastCycle?.beat_at || '—' }}</span></div>
                </div>
            </el-col>
        </el-row>

        <!-- 最近告警 -->
        <div class="chart-card mb-16">
            <div class="chart-title">最近活跃告警</div>
            <el-table :data="recentAlerts" size="small" empty-text="暂无活跃告警">
                <el-table-column prop="triggered_at" label="时间" width="160"/>
                <el-table-column prop="device_name" label="设备" width="180" show-overflow-tooltip/>
                <el-table-column label="级别" width="90">
                    <template #default="{ row }">
                        <el-tag :type="severityTag(row.severity)" size="small">{{ row.severity }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="title" label="内容" show-overflow-tooltip/>
                <el-table-column label="操作" width="90">
                    <template #default="{ row }">
                        <el-button link type="primary" size="small" @click="goAlerts">处理</el-button>
                    </template>
                </el-table-column>
            </el-table>
        </div>

        <!-- 快捷入口 -->
        <el-row :gutter="16">
            <el-col v-for="item in quickLinks" :key="item.path" :xs="12" :sm="6">
                <div class="stat-card quick-link" @click="router.push(item.path)">
                    <el-icon :size="28" class="mb-8"><component :is="item.icon"/></el-icon>
                    <div style="font-weight:600">{{ item.title }}</div>
                    <div class="stat-label">{{ item.desc }}</div>
                </div>
            </el-col>
        </el-row>
    </div>
</template>

<script setup>
/**
 * 系统总览.
 * 数据源只有一个: GET /api/dashboard/overview (业务层一次聚合 SQL),
 * 统计变化通过 SSE 的 overview 事件秒级并入, 30s 轮询仅作兜底 —— 与迁移前节奏一致.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import StatCard from '@/components/StatCard.vue';
import EChart from '@/components/EChart.vue';
import { dashboardOverview, status as systemStatusApi } from '@/api/system';
import { useRealtimeStore } from '@/stores/realtime';

const router = useRouter();
const realtime = useRealtimeStore();

const overview = ref({});
const collectorAlive = ref(false);
let timer = null;
let unsubs = [];

const activeAlerts = computed(() => (overview.value.alerts && overview.value.alerts.active) || 0);
const criticalAlerts = computed(() => (overview.value.alerts && overview.value.alerts.critical) || 0);
const inspection = computed(() => overview.value.inspection || {});
const health = computed(() => overview.value.health || {});
const recentAlerts = computed(() => overview.value.recent_alerts || []);
const lastCycle = computed(() => realtime.lastCycle);
const beatText = computed(() => (lastCycle.value
    ? `${lastCycle.value.success ?? 0} / ${lastCycle.value.total ?? 0}`
    : '—'));
const durationText = computed(() => (lastCycle.value && lastCycle.value.duration_ms != null
    ? `${lastCycle.value.duration_ms} ms`
    : '—'));

const rateOption = computed(() => {
    const rate = overview.value.online_rate || 0;
    return {
        series: [{
            type: 'gauge',
            startAngle: 180,
            endAngle: 0,
            min: 0,
            max: 100,
            radius: '90%',
            pointer: { show: false },
            progress: {
                show: true,
                roundCap: true,
                width: 20,
                itemStyle: { color: rate >= 90 ? '#67C23A' : rate >= 70 ? '#E6A23C' : '#F56C6C' },
            },
            axisLine: { lineStyle: { width: 20, color: [[1, '#f0f0f0']] } },
            axisTick: { show: false },
            splitLine: { show: false },
            axisLabel: { show: false },
            detail: {
                formatter: '{value}%',
                fontSize: 28,
                fontWeight: 'bold',
                offsetCenter: [0, '20%'],
                color: '#333',
            },
            title: { show: true, offsetCenter: [0, '60%'], fontSize: 14, color: '#999' },
            data: [{ value: rate, name: '在线率' }],
        }],
    };
});

const distOption = computed(() => {
    const sw = overview.value.switches || {};
    const srv = overview.value.servers || {};
    return {
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0, textStyle: { fontSize: 12 } },
        series: [{
            type: 'pie',
            radius: ['40%', '70%'],
            center: ['50%', '45%'],
            avoidLabelOverlap: true,
            itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
            label: { show: true, formatter: '{b}\n{c}台' },
            data: [
                { value: sw.online || 0, name: '交换机在线', itemStyle: { color: '#67C23A' } },
                { value: sw.offline || 0, name: '交换机离线', itemStyle: { color: '#F56C6C' } },
                { value: srv.online || 0, name: '服务器在线', itemStyle: { color: '#409EFF' } },
                { value: srv.offline || 0, name: '服务器离线', itemStyle: { color: '#E6A23C' } },
            ],
        }],
    };
});

const GRADE_CSS = { 优: 'g-you', 良: 'g-liang', 中: 'g-zhong', 差: 'g-cha' };
const gradeCells = computed(() => {
    const dist = health.value.distribution || {};
    return ['优', '良', '中', '差'].map((grade) => ({
        grade,
        css: GRADE_CSS[grade],
        count: dist[grade] || 0,
    }));
});

const quickLinks = [
    { path: '/switches', icon: 'Refresh', title: '交换机管理', desc: '端口状态与流量监控' },
    { path: '/servers', icon: 'Monitor', title: '服务器监控', desc: 'CPU/内存/磁盘/进程' },
    { path: '/automation', icon: 'Setting', title: '自动化配置', desc: 'VLAN/端口批量配置' },
    { path: '/alerts', icon: 'Warning', title: '告警管理', desc: '查看和处理告警' },
];

const severityTag = (severity) => ({
    CRITICAL: 'danger',
    WARNING: 'warning',
    INFO: 'info',
}[severity] || 'info');

const goAlerts = () => router.push('/alerts');

const fetchData = async () => {
    try {
        overview.value = await dashboardOverview();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const fetchStatus = async () => {
    try {
        const data = await systemStatusApi();
        collectorAlive.value = !!data.collector_alive;
    } catch (e) {
        collectorAlive.value = false;
    }
};

onMounted(() => {
    fetchData();
    fetchStatus();
    // SSE 的 overview 事件只带统计段, 与总览结果合并而不是整体替换
    unsubs.push(realtime.on('overview', (payload) => {
        if (!payload) {
            return;
        }
        overview.value = Object.assign({}, overview.value, payload);
    }));
    timer = setInterval(fetchData, 30000);
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    unsubs.forEach((fn) => fn && fn());
    unsubs = [];
});
</script>

<style scoped>
.quick-link {
    cursor: pointer;
    text-align: center;
}

.chart-card .text-muted {
    margin-top: 8px;
}
</style>
