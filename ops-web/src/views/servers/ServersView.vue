<template>
    <div class="servers-page">
        <div class="page-title">服务器监控</div>

        <!-- 搜索栏 -->
        <div class="search-bar">
            <el-input v-model="keyword" placeholder="搜索服务器名称/IP" clearable
                      style="width:300px" @clear="search" @keyup.enter="search"/>
            <el-button type="primary" @click="search">搜索</el-button>
            <el-button @click="fetchData">刷新</el-button>
            <el-button v-if="auth.isAdmin" type="success" @click="openCreate">新增服务器</el-button>
            <span class="text-muted" style="margin-left:auto">共 {{ total }} 台服务器</span>
        </div>

        <!-- 服务器卡片网格 -->
        <el-row :gutter="16">
            <el-col v-for="server in serverList" :key="server.id" :xs="24" :sm="12" :lg="8"
                    style="margin-bottom:16px">
                <el-card shadow="hover" :body-style="{ padding: '16px' }">
                    <div class="card-head">
                        <div>
                            <span class="status-dot" :class="server.is_online ? 'online' : 'offline'"></span>
                            <strong>{{ server.name }}</strong>
                            <el-tag size="small" style="margin-left:8px"
                                    :type="server.os_type === 'windows' ? 'primary' : 'success'">
                                {{ server.os_type }}
                            </el-tag>
                            <el-tag v-if="server.enabled === false" size="small" type="info" style="margin-left:6px">
                                已停用
                            </el-tag>
                        </div>
                        <el-button size="small" text type="primary" @click="showDetail(server)">详情</el-button>
                    </div>
                    <div class="card-sub">{{ server.id }} | {{ server.ip }} | {{ server.hostname || server.location }}</div>

                    <div v-if="server.metrics && server.is_online">
                        <div v-for="m in getMetrics(server.metrics)" :key="m.label" class="metric-row">
                            <div class="metric-head">
                                <span>{{ m.label }}</span>
                                <span :style="{ color: m.value > 80 ? '#F56C6C' : m.value > 50 ? '#E6A23C' : '#67C23A' }">
                                    {{ m.value.toFixed(1) }}{{ m.unit }}
                                </span>
                            </div>
                            <div class="usage-bar">
                                <div class="usage-fill"
                                     :class="m.value > 80 ? 'high' : m.value > 50 ? 'medium' : 'low'"
                                     :style="{ width: Math.min(m.value, 100) + '%' }"></div>
                            </div>
                        </div>
                        <div class="metric-row text-muted" style="font-size:12px">
                            网卡 {{ formatRate(server.metrics.net_recv_rate_bps) }} ↓ /
                            {{ formatRate(server.metrics.net_send_rate_bps) }} ↑
                        </div>
                    </div>
                    <div v-else class="card-empty">
                        {{ server.is_online ? '暂无数据' : '设备离线' }}
                    </div>
                </el-card>
            </el-col>
        </el-row>

        <div class="pager">
            <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
                           layout="prev, pager, next, total" @current-change="fetchData"/>
        </div>

        <!-- 详情抽屉 -->
        <el-drawer v-model="detailDrawer" :title="`服务器详情 - ${selectedServer.name}`" size="680px">
            <el-descriptions :column="2" border class="mb-16">
                <el-descriptions-item label="名称">{{ selectedServer.name }}</el-descriptions-item>
                <el-descriptions-item label="设备 ID">{{ selectedServer.id }}</el-descriptions-item>
                <el-descriptions-item label="IP">{{ selectedServer.ip }}</el-descriptions-item>
                <el-descriptions-item label="系统">{{ selectedServer.os_type }}</el-descriptions-item>
                <el-descriptions-item label="主机名">{{ selectedServer.hostname || '-' }}</el-descriptions-item>
                <el-descriptions-item label="系统版本">{{ selectedServer.os_version || '-' }}</el-descriptions-item>
                <el-descriptions-item label="位置">{{ selectedServer.location || '-' }}</el-descriptions-item>
                <el-descriptions-item label="CPU / 内存">
                    {{ selectedServer.cpu_cores || '-' }} 核 / {{ memText(selectedServer.total_memory_mb) }}
                </el-descriptions-item>
                <el-descriptions-item label="状态">
                    <el-tag :type="selectedServer.is_online ? 'success' : 'danger'">
                        {{ selectedServer.is_online ? '在线' : '离线' }}
                    </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="最后采集">{{ selectedServer.last_poll_time || '-' }}</el-descriptions-item>
            </el-descriptions>

            <div class="toolbar">
                <div class="chart-title" style="margin:0">资源趋势</div>
                <el-radio-group v-model="metricsHours" size="small" @change="loadMetrics">
                    <el-radio-button :label="1">1 小时</el-radio-button>
                    <el-radio-button :label="6">6 小时</el-radio-button>
                    <el-radio-button :label="24">24 小时</el-radio-button>
                </el-radio-group>
            </div>
            <div class="chart-card mb-16">
                <EChart :option="metricsOption" height="280px"/>
            </div>

            <!-- 进程列表 -->
            <div class="chart-card">
                <div class="chart-title">进程/服务状态</div>
                <el-table :data="processList" stripe size="small" v-loading="procLoading" max-height="300">
                    <el-table-column prop="process_name" label="进程名" width="140"/>
                    <el-table-column prop="pid" label="PID" width="70"/>
                    <el-table-column label="状态" width="80">
                        <template #default="{ row }">
                            <el-tag :type="row.is_running ? 'success' : 'danger'" size="small">
                                {{ row.is_running ? '运行中' : '已停止' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="CPU" width="70">
                        <template #default="{ row }">{{ fixed(row.cpu_percent) }}%</template>
                    </el-table-column>
                    <el-table-column label="内存(MB)" width="90">
                        <template #default="{ row }">{{ fixed(row.memory_mb) }}</template>
                    </el-table-column>
                    <el-table-column label="端口" width="80">
                        <template #default="{ row }">
                            <el-tag v-if="row.port" :type="row.port_open ? 'success' : 'danger'" size="small">
                                {{ row.port }}
                            </el-tag>
                            <span v-else>-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="关键" width="60">
                        <template #default="{ row }">
                            <el-tag v-if="row.is_critical" type="danger" size="small">关键</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="status_detail" label="详情" min-width="120" show-overflow-tooltip/>
                </el-table>
            </div>

            <div v-if="auth.isAdmin" class="mt-16">
                <el-button @click="openEdit(selectedServer)">编辑台账</el-button>
            </div>
        </el-drawer>

        <!-- 新增 / 编辑台账 -->
        <el-dialog v-model="editDialog" :title="editForm.id && !creating ? '编辑服务器' : '新增服务器'" width="680px" top="6vh">
            <el-form :model="editForm" label-width="110px">
                <el-row :gutter="10">
                    <el-col :span="12">
                        <el-form-item label="名称" required>
                            <el-input v-model="editForm.name" placeholder="如: SCADA 实时服务器"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="IP" required>
                            <el-input v-model="editForm.ip" :disabled="!creating" placeholder="192.168.1.100"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="系统类型">
                            <el-radio-group v-model="editForm.os_type" :disabled="!creating">
                                <el-radio-button label="linux">Linux</el-radio-button>
                                <el-radio-button label="windows">Windows</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="位置">
                            <el-input v-model="editForm.location"/>
                        </el-form-item>
                    </el-col>
                </el-row>

                <el-divider content-position="left">采集方式</el-divider>
                <el-row :gutter="10">
                    <el-col :span="12">
                        <el-form-item label="方式">
                            <el-select v-model="editForm.method" style="width:100%">
                                <el-option label="Agent (9100)" value="agent"/>
                                <el-option label="SSH" value="ssh"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="Agent 端口">
                            <el-input-number v-model="editForm.agent_port" :min="1" :max="65535"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="10">
                    <el-col :span="8">
                        <el-form-item label="SSH 用户名">
                            <el-input v-model="editForm.ssh_username"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="SSH 端口">
                            <el-input-number v-model="editForm.ssh_port" :min="1" :max="65535"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="SSH 口令">
                            <el-input v-model="editForm.ssh_password" type="password" show-password
                                      placeholder="留空或掩码 = 不修改"/>
                        </el-form-item>
                    </el-col>
                </el-row>

                <el-divider content-position="left">
                    关键进程监控
                    <el-button link type="primary" size="small" class="ml-8" @click="addProcess">添加</el-button>
                </el-divider>
                <el-table :data="editForm.processes" border size="small" max-height="220">
                    <el-table-column label="进程名" min-width="150">
                        <template #default="{ row }">
                            <el-input v-model="row.name" size="small" placeholder="mysqld / SCADAService"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="监听端口" width="130">
                        <template #default="{ row }">
                            <el-input-number v-model="row.port" size="small" :min="0" :max="65535" controls-position="right"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="关键" width="80" align="center">
                        <template #default="{ row }"><el-switch v-model="row.critical" size="small"/></template>
                    </el-table-column>
                    <el-table-column label="操作" width="70" align="center">
                        <template #default="{ $index }">
                            <el-button size="small" type="danger" text @click="editForm.processes.splice($index, 1)">
                                删除
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>

                <el-form-item v-if="!creating" label="启用采集" class="mt-16">
                    <el-switch v-model="editForm.enabled"/>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="editDialog = false">取消</el-button>
                <el-button v-if="!creating" type="danger" plain @click="removeServer">删除</el-button>
                <el-button type="primary" :loading="saving" @click="saveServer">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * 服务器监控.
 * 迁移前服务器清单来自 devices.yaml, 改造后台账落 server_devices 表,
 * 因此这一页除了看指标还能增删改 (仅 ADMIN); 指标与进程一律由 Python 采集写入.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import EChart from '@/components/EChart.vue';
import * as serversApi from '@/api/servers';
import { useAuthStore } from '@/stores/auth';
import { useRealtimeStore } from '@/stores/realtime';

const auth = useAuthStore();
const realtime = useRealtimeStore();

const serverList = ref([]);
const keyword = ref('');
const page = ref(1);
const pageSize = 20;
const total = ref(0);

const detailDrawer = ref(false);
const selectedServer = ref({});
const processList = ref([]);
const procLoading = ref(false);
const metricsHours = ref(1);
const metricsData = ref([]);

const editDialog = ref(false);
const creating = ref(false);
const saving = ref(false);
const editForm = ref(emptyForm());

let timer = null;
let unsub = null;

function emptyForm() {
    return {
        id: '', name: '', ip: '', os_type: 'linux', location: '', enabled: true,
        method: 'agent', agent_port: 9100,
        ssh_username: '', ssh_port: 22, ssh_password: '',
        processes: [],
    };
}

const getMetrics = (m) => [
    { label: 'CPU', value: m.cpu_usage || 0, unit: '%' },
    { label: '内存', value: m.memory_usage || 0, unit: '%' },
    { label: '磁盘', value: m.disk_usage || 0, unit: '%' },
];

const fixed = (v) => (v == null ? '-' : Number(v).toFixed(1));

const memText = (mb) => (mb ? `${(mb / 1024).toFixed(1)} GB` : '-');

const formatRate = (bps) => {
    if (!bps) return '0';
    if (bps >= 1e6) return `${(bps / 1e6).toFixed(1)}Mbps`;
    if (bps >= 1e3) return `${(bps / 1e3).toFixed(1)}Kbps`;
    return `${bps}bps`;
};

const metricsOption = computed(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU %', '内存 %', '磁盘 %'], bottom: 0 },
    grid: { left: 45, right: 15, top: 15, bottom: 40 },
    xAxis: { type: 'category', data: metricsData.value.map((d) => (d.timestamp || '').slice(11, 19)), axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [
        { name: 'CPU %', type: 'line', smooth: true, showSymbol: false, data: metricsData.value.map((d) => d.cpu_usage) },
        { name: '内存 %', type: 'line', smooth: true, showSymbol: false, data: metricsData.value.map((d) => d.memory_usage) },
        { name: '磁盘 %', type: 'line', smooth: true, showSymbol: false, data: metricsData.value.map((d) => d.disk_usage) },
    ],
}));

const fetchData = async () => {
    try {
        const data = await serversApi.list({ page: page.value, pageSize, keyword: keyword.value });
        serverList.value = data.items || [];
        total.value = data.total || 0;
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const search = () => {
    page.value = 1;
    fetchData();
};

const loadMetrics = async () => {
    try {
        const data = await serversApi.metrics(selectedServer.value.id, metricsHours.value);
        metricsData.value = data.data || [];
    } catch (e) {
        metricsData.value = [];
    }
};

const showDetail = async (server) => {
    selectedServer.value = server;
    detailDrawer.value = true;
    metricsHours.value = 1;
    metricsData.value = [];
    processList.value = [];
    procLoading.value = true;
    try {
        selectedServer.value = await serversApi.detail(server.id);
        const p = await serversApi.processes(server.id);
        processList.value = p.processes || [];
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        procLoading.value = false;
    }
    await loadMetrics();
};

const openCreate = () => {
    creating.value = true;
    editForm.value = emptyForm();
    editDialog.value = true;
};

const openEdit = async (server) => {
    try {
        const d = await serversApi.forEdit(server.id);
        const mc = d.monitoring_config || {};
        const ssh = mc.ssh || {};
        creating.value = false;
        editForm.value = {
            id: d.id,
            name: d.name,
            ip: d.ip,
            os_type: d.os_type || 'linux',
            location: d.location || '',
            enabled: d.enabled !== false,
            method: mc.method || 'agent',
            agent_port: mc.agent_port || 9100,
            ssh_username: ssh.username || '',
            ssh_port: ssh.port || 22,
            ssh_password: ssh.password || '',
            processes: (d.processes_config || []).map((p) => ({
                name: p.name, port: p.port || 0, critical: p.critical !== false,
            })),
        };
        editDialog.value = true;
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const addProcess = () => editForm.value.processes.push({ name: '', port: 0, critical: true });

const buildPayload = () => {
    const f = editForm.value;
    const monitoring = { method: f.method, agent_port: f.agent_port };
    if (f.method === 'ssh') {
        monitoring.ssh = { username: f.ssh_username, port: f.ssh_port, password: f.ssh_password };
    }
    return {
        monitoring_config: monitoring,
        processes_config: f.processes
            .filter((p) => p.name)
            .map((p) => ({ name: p.name, port: p.port || 0, critical: !!p.critical })),
    };
};

const saveServer = async () => {
    const f = editForm.value;
    if (!f.name || !f.ip) {
        ElMessage.warning('请填写名称与 IP');
        return;
    }
    saving.value = true;
    try {
        if (creating.value) {
            await serversApi.create({ id: f.id, name: f.name, ip: f.ip, os_type: f.os_type, location: f.location, ...buildPayload() });
            ElMessage.success('已新增, 采集层将在下一轮热加载后开始采集');
        } else {
            await serversApi.update(f.id, { name: f.name, location: f.location, enabled: f.enabled, ...buildPayload() });
            ElMessage.success('台账已更新');
        }
        editDialog.value = false;
        fetchData();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        saving.value = false;
    }
};

const removeServer = async () => {
    const f = editForm.value;
    try {
        await ElMessageBox.confirm(`删除服务器「${f.name}」及其全部历史数据?`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    try {
        await serversApi.remove(f.id);
        ElMessage.success('已删除');
        editDialog.value = false;
        detailDrawer.value = false;
        fetchData();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

onMounted(() => {
    fetchData();
    unsub = realtime.on('cycle', () => {
        if (!detailDrawer.value && !editDialog.value) fetchData();
    });
    // 迁移前是 30s 轮询整页, 这里保持一致
    timer = setInterval(fetchData, 30000);
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    if (unsub) unsub();
});
</script>

<style scoped>
.card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
}

.card-sub {
    color: #999;
    font-size: 12px;
    margin-bottom: 12px;
}

.metric-row {
    margin-bottom: 8px;
}

.metric-head {
    display: flex;
    justify-content: space-between;
    font-size: 13px;
}

.card-empty {
    text-align: center;
    color: #ccc;
    padding: 20px;
}

.pager {
    display: flex;
    justify-content: flex-end;
}

.ml-8 {
    margin-left: 8px;
}
</style>
