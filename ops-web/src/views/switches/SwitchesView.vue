<template>
    <div class="switches-page">
        <div class="page-title">交换机管理</div>

        <!-- 搜索栏 -->
        <div class="search-bar">
            <el-input v-model="keyword" placeholder="搜索交换机名称/IP/位置" clearable
                      style="width:300px" @clear="search" @keyup.enter="search"/>
            <el-checkbox v-model="onlineOnly" @change="search">仅显示在线</el-checkbox>
            <el-button type="primary" @click="search">搜索</el-button>
            <el-button @click="fetchData">刷新</el-button>
            <span class="text-muted" style="margin-left:auto">共 {{ total }} 台设备</span>
        </div>

        <!-- 设备列表 -->
        <el-table :data="switchList" stripe v-loading="loading" border style="width:100%">
            <el-table-column label="状态" width="70" align="center">
                <template #default="{ row }">
                    <span class="status-dot" :class="row.is_online ? 'online' : 'offline'"></span>
                    {{ row.is_online ? '在线' : '离线' }}
                </template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip/>
            <el-table-column prop="ip" label="IP地址" width="140"/>
            <el-table-column prop="model" label="型号" width="120"/>
            <el-table-column prop="location" label="位置" min-width="140" show-overflow-tooltip/>
            <el-table-column label="端口" width="70" align="center">
                <template #default="{ row }">{{ row.total_ports || '-' }}</template>
            </el-table-column>
            <el-table-column label="温度" width="90" align="center">
                <template #default="{ row }">
                    <span v-if="row.is_online && row.temperature != null"
                          :style="{ color: row.temperature > 50 ? '#f56c6c' : '#67c23a' }">
                        {{ row.temperature }}°C
                    </span>
                    <span v-else style="color:#ccc">-</span>
                </template>
            </el-table-column>
            <el-table-column label="运行时长" width="120">
                <template #default="{ row }">
                    <span v-if="row.is_online">{{ formatUptime(row.uptime_seconds) }}</span>
                    <span v-else style="color:#ccc">-</span>
                </template>
            </el-table-column>
            <el-table-column label="操作" width="250" align="center">
                <template #default="{ row }">
                    <el-button size="small" @click="showPorts(row)">端口</el-button>
                    <el-button size="small" type="primary" @click="showDetail(row)">详情</el-button>
                    <el-button v-if="auth.canWrite" size="small" text @click="refreshOne(row)">重采</el-button>
                    <el-button v-if="auth.isAdmin" size="small" text type="warning" @click="openEdit(row)">编辑</el-button>
                </template>
            </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="pager">
            <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
                           layout="prev, pager, next, total" @current-change="fetchData"/>
        </div>

        <!-- 端口状态抽屉 -->
        <el-drawer v-model="portsDrawer" :title="'端口状态 - ' + selectedSwitch.name" size="600px">
            <div v-loading="portsLoading">
                <div class="mb-16">
                    <el-tag type="success" size="small">Up: {{ upCount }}</el-tag>
                    <el-tag type="danger" size="small" style="margin-left:8px">Down: {{ downCount }}</el-tag>
                    <el-tag type="info" size="small" style="margin-left:8px">Disabled: {{ disabledCount }}</el-tag>
                </div>
                <!-- 端口矩阵 -->
                <div class="port-grid">
                    <el-tooltip v-for="port in portsData" :key="port.port_index"
                                :content="`${port.port_name} | ${port.is_up ? 'UP' : 'DOWN'} | ${formatSpeed(port.speed_bps)}`">
                        <div class="port-cell"
                             :class="port.admin_status !== 1 ? 'disabled' : (port.is_up ? 'up' : 'down')"
                             @click="showTraffic(port)">
                            {{ port.port_index }}
                        </div>
                    </el-tooltip>
                </div>

                <!-- 端口列表详情 -->
                <el-table :data="portsData" stripe size="small" class="mt-16" max-height="400">
                    <el-table-column prop="port_index" label="#" width="50"/>
                    <el-table-column prop="port_name" label="端口名称" min-width="140" show-overflow-tooltip/>
                    <el-table-column label="状态" width="70">
                        <template #default="{ row }">
                            <el-tag :type="row.is_up ? 'success' : 'danger'" size="small">
                                {{ row.is_up ? 'UP' : 'DOWN' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="速率" width="100">
                        <template #default="{ row }">{{ formatSpeed(row.speed_bps) }}</template>
                    </el-table-column>
                    <el-table-column label="VLAN" width="80">
                        <template #default="{ row }">{{ row.vlan_id || '-' }}</template>
                    </el-table-column>
                </el-table>
            </div>
        </el-drawer>

        <!-- 流量趋势对话框 -->
        <el-dialog v-model="trafficDialog" :title="`流量趋势 - ${selectedPort.port_name || 'Port ' + selectedPort.port_index}`"
                   width="720px">
            <div class="toolbar">
                <el-radio-group v-model="trafficHours" size="small" @change="loadTraffic">
                    <el-radio-button :label="1">1 小时</el-radio-button>
                    <el-radio-button :label="6">6 小时</el-radio-button>
                    <el-radio-button :label="24">24 小时</el-radio-button>
                </el-radio-group>
            </div>
            <EChart :option="trafficOption" height="320px"/>
        </el-dialog>

        <!-- 详情抽屉 -->
        <el-drawer v-model="detailDrawer"
                   :title="`设备详情 - ${selectedSwitch.name || selectedSwitch.ip}`" size="560px">
            <el-descriptions :column="1" border>
                <el-descriptions-item label="设备名称">{{ selectedSwitch.name }}</el-descriptions-item>
                <el-descriptions-item label="IP地址">{{ selectedSwitch.ip }}</el-descriptions-item>
                <el-descriptions-item label="型号">{{ selectedSwitch.model || '-' }}</el-descriptions-item>
                <el-descriptions-item label="位置">{{ selectedSwitch.location || '-' }}</el-descriptions-item>
                <el-descriptions-item label="固件版本">{{ selectedSwitch.firmware_version || '-' }}</el-descriptions-item>
                <el-descriptions-item label="序列号">{{ selectedSwitch.serial_number || '-' }}</el-descriptions-item>
                <el-descriptions-item label="运行状态">
                    <el-tag :type="selectedSwitch.is_online ? 'success' : 'danger'">
                        {{ selectedSwitch.is_online ? '在线' : '离线' }}
                    </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="CPU / 内存">
                    {{ pct(selectedSwitch.cpu_usage) }} / {{ pct(selectedSwitch.memory_usage) }}
                </el-descriptions-item>
                <el-descriptions-item label="端口数">{{ selectedSwitch.total_ports || 0 }}</el-descriptions-item>
                <el-descriptions-item label="运行时长">{{ formatUptime(selectedSwitch.uptime_seconds) }}</el-descriptions-item>
                <el-descriptions-item label="最后采集">{{ selectedSwitch.last_poll_time || '-' }}</el-descriptions-item>
            </el-descriptions>

            <div class="chart-card mt-16">
                <div class="chart-title">硬件健康 (最近 {{ hardwareHours }} 小时)</div>
                <div class="toolbar">
                    <el-radio-group v-model="hardwareHours" size="small" @change="loadHardware">
                        <el-radio-button :label="1">1 小时</el-radio-button>
                        <el-radio-button :label="6">6 小时</el-radio-button>
                        <el-radio-button :label="24">24 小时</el-radio-button>
                    </el-radio-group>
                </div>
                <EChart :option="hardwareOption" height="260px"/>
            </div>
        </el-drawer>

        <!-- 台账编辑 (仅 ADMIN; 采集字段由 Python 写入, 这里只能改名称/位置/凭据/启停) -->
        <el-dialog v-model="editDialog" title="编辑交换机台账" width="560px">
            <el-form :model="editForm" label-width="110px">
                <el-form-item label="设备 ID">
                    <el-input :model-value="editForm.id" disabled/>
                </el-form-item>
                <el-form-item label="名称">
                    <el-input v-model="editForm.name" maxlength="200"/>
                </el-form-item>
                <el-form-item label="位置">
                    <el-input v-model="editForm.location" maxlength="200"/>
                </el-form-item>
                <el-form-item label="启用采集">
                    <el-switch v-model="editForm.enabled"/>
                </el-form-item>
                <el-divider content-position="left">SNMP 凭据</el-divider>
                <el-form-item label="团体名">
                    <el-input v-model="editForm.snmp_community" placeholder="留空或保持掩码 = 不修改"/>
                </el-form-item>
                <el-divider content-position="left">SSH 凭据 (配置下发用)</el-divider>
                <el-row :gutter="8">
                    <el-col :span="12">
                        <el-form-item label="用户名">
                            <el-input v-model="editForm.ssh_username"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="端口">
                            <el-input-number v-model="editForm.ssh_port" :min="1" :max="65535"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="口令">
                    <el-input v-model="editForm.ssh_password" type="password" show-password
                              placeholder="留空或保持掩码 = 不修改"/>
                </el-form-item>
                <el-form-item label="下发地址">
                    <el-input v-model="editForm.ssh_host" placeholder="留空 = 使用设备管理 IP"/>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="editDialog = false">取消</el-button>
                <el-button type="danger" plain @click="removeDevice">删除设备</el-button>
                <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * 交换机管理.
 * 数据来源全部是业务层读库: 采集字段 (在线/端口/流量/硬件) 由 Python 写入,
 * 页面里的"重采"只是往 collector_command 投一条指令, 不由本进程发起 SNMP.
 * 时钟序列 (timestamp) 已由业务层统一格式化, 因此这里的时间轴切分与迁移前不同.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import EChart from '@/components/EChart.vue';
import * as switchesApi from '@/api/switches';
import { useAuthStore } from '@/stores/auth';
import { useRealtimeStore } from '@/stores/realtime';

const auth = useAuthStore();
const realtime = useRealtimeStore();

const switchList = ref([]);
const loading = ref(false);
const keyword = ref('');
const onlineOnly = ref(false);
const page = ref(1);
const pageSize = 50;
const total = ref(0);

const portsDrawer = ref(false);
const portsLoading = ref(false);
const portsData = ref([]);
const selectedSwitch = ref({});

const trafficDialog = ref(false);
// 默认 6 小时: 流量轮周期 900s, 1 小时窗口只有 3~4 个点, 曲线看起来像直线
const trafficHours = ref(6);
const trafficData = ref([]);
const selectedPort = ref({});

const detailDrawer = ref(false);
// 默认 24 小时: 硬件轮周期 1800s, 1 小时窗口常常只有 1 个点, 单点图基本看不出东西
const hardwareHours = ref(24);
const hardwareData = ref([]);

const editDialog = ref(false);
const saving = ref(false);
const editForm = ref({});

let timer = null;
let unsub = null;

const upCount = computed(() => portsData.value.filter((p) => p.is_up).length);
const downCount = computed(() => portsData.value.filter((p) => !p.is_up && p.admin_status === 1).length);
const disabledCount = computed(() => portsData.value.filter((p) => p.admin_status !== 1).length);

const formatSpeed = (bps) => {
    if (!bps) return '-';
    if (bps >= 1e9) return `${(bps / 1e9).toFixed(1)} Gbps`;
    if (bps >= 1e6) return `${(bps / 1e6).toFixed(1)} Mbps`;
    if (bps >= 1e3) return `${(bps / 1e3).toFixed(1)} Kbps`;
    return `${bps} bps`;
};

const formatUptime = (sec) => {
    if (!sec) return '-';
    const d = Math.floor(sec / 86400);
    const h = Math.floor((sec % 86400) / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return d > 0 ? `${d}天${h}时${m}分` : `${h}时${m}分`;
};

const pct = (v) => (v == null ? '-' : `${Number(v).toFixed(1)}%`);

/** 业务层统一输出 'yyyy-MM-dd HH:mm:ss', 时间轴只取时分秒 */
const timeAxis = (list) => list.map((d) => (d.timestamp || '').slice(11, 19));

const trafficOption = computed(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['入方向速率', '出方向速率'], bottom: 0 },
    // 速率刻度是 "15.0 Mbps" 这种长文本, 固定 left 会把首位数字裁掉, 改用 containLabel 让 grid 自动留出轴标签宽度;
    // containLabel 不会把 Y 轴名称 "bps" 算进去, 所以 top 要额外留出轴名的高度, 否则名称上半被画布顶边切掉
    grid: { left: 8, right: 20, top: 48, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: timeAxis(trafficData.value), axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', name: 'bps', axisLabel: { formatter: (v) => formatSpeed(v) } },
    series: [
        {
            // 流量轮周期 900s, 选 1h 只有 2~4 个点; 点数少时必须显示 symbol, 否则曲线看起来是空的
            name: '入方向速率', type: 'line', smooth: true, showSymbol: trafficData.value.length <= 12, symbolSize: 7,
            data: trafficData.value.map((d) => d.in_rate_bps), areaStyle: { opacity: 0.1 },
        },
        {
            name: '出方向速率', type: 'line', smooth: true, showSymbol: trafficData.value.length <= 12, symbolSize: 7,
            data: trafficData.value.map((d) => d.out_rate_bps), areaStyle: { opacity: 0.1 },
        },
    ],
}));

const hardwareOption = computed(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['温度 °C', 'CPU %', '内存 %'], bottom: 0 },
    grid: { left: 8, right: 8, top: 48, bottom: 40, containLabel: true },
    xAxis: { type: 'category', data: timeAxis(hardwareData.value), axisLabel: { fontSize: 10 } },
    yAxis: [
        { type: 'value', name: '°C', axisLabel: { fontSize: 10 } },
        { type: 'value', name: '%', min: 0, max: 100, axisLabel: { fontSize: 10 } },
    ],
    series: [
        // 硬件轮周期 1800s, 1h 往往只有 1 个点, 隐藏 symbol 会让图看起来完全空白
        { name: '温度 °C', type: 'line', smooth: true, showSymbol: hardwareData.value.length <= 12, symbolSize: 7, data: hardwareData.value.map((d) => d.temperature) },
        {
            name: 'CPU %', type: 'line', smooth: true, showSymbol: hardwareData.value.length <= 12, symbolSize: 7, yAxisIndex: 1,
            data: hardwareData.value.map((d) => d.cpu_usage),
        },
        {
            name: '内存 %', type: 'line', smooth: true, showSymbol: hardwareData.value.length <= 12, symbolSize: 7, yAxisIndex: 1,
            data: hardwareData.value.map((d) => d.memory_usage),
        },
    ],
}));

const fetchData = async () => {
    loading.value = true;
    try {
        const data = await switchesApi.list({ page: page.value, pageSize, keyword: keyword.value, onlineOnly: onlineOnly.value });
        switchList.value = data.items || [];
        total.value = data.total || 0;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.value = false;
    }
};

const search = () => {
    page.value = 1;
    fetchData();
};

const showPorts = async (row) => {
    selectedSwitch.value = row;
    portsDrawer.value = true;
    portsLoading.value = true;
    try {
        const data = await switchesApi.ports(row.id);
        portsData.value = data.ports || [];
    } catch (e) {
        portsData.value = [];
    } finally {
        portsLoading.value = false;
    }
};

const loadTraffic = async () => {
    try {
        const data = await switchesApi.traffic(selectedSwitch.value.id, selectedPort.value.port_index, trafficHours.value);
        trafficData.value = data.data || [];
    } catch (e) {
        trafficData.value = [];
    }
};

const showTraffic = async (port) => {
    selectedPort.value = port;
    trafficHours.value = 6;
    trafficDialog.value = true;
    await loadTraffic();
};

const showDetail = async (row) => {
    selectedSwitch.value = row;
    detailDrawer.value = true;
    hardwareHours.value = 24;
    hardwareData.value = [];
    try {
        selectedSwitch.value = await switchesApi.detail(row.id);
    } catch (e) {
        /* 保留列表行数据 */
    }
    await loadHardware();
};

const loadHardware = async () => {
    try {
        const data = await switchesApi.hardware(selectedSwitch.value.id, hardwareHours.value);
        hardwareData.value = data.data || [];
    } catch (e) {
        hardwareData.value = [];
    }
};

const refreshOne = async (row) => {
    try {
        await switchesApi.refresh(row.id);
        ElMessage.success('已通知采集层重采, 稍后刷新查看');
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const openEdit = async (row) => {
    try {
        const d = await switchesApi.forEdit(row.id);
        const snmp = d.snmp_config || {};
        const ssh = d.ssh_config || {};
        editForm.value = {
            id: d.id,
            name: d.name,
            location: d.location,
            enabled: d.enabled !== false,
            snmp_version: snmp.version || 'v2c',
            snmp_community: snmp.community || '',
            ssh_username: ssh.username || '',
            ssh_port: ssh.port || 22,
            ssh_password: ssh.password || '',
            ssh_host: ssh.host || '',
        };
        editDialog.value = true;
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const saveEdit = async () => {
    const f = editForm.value;
    saving.value = true;
    try {
        await switchesApi.update(f.id, {
            name: f.name,
            location: f.location,
            enabled: f.enabled,
            snmp_config: { version: f.snmp_version || 'v2c', community: f.snmp_community },
            ssh_config: {
                host: f.ssh_host,
                username: f.ssh_username,
                port: f.ssh_port,
                password: f.ssh_password,
            },
        });
        ElMessage.success('台账已更新');
        editDialog.value = false;
        fetchData();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        saving.value = false;
    }
};

const removeDevice = async () => {
    const f = editForm.value;
    try {
        await ElMessageBox.confirm(`删除交换机「${f.name}」及其全部历史数据?`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    try {
        await switchesApi.remove(f.id);
        ElMessage.success('已删除');
        editDialog.value = false;
        fetchData();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

onMounted(() => {
    fetchData();
    // 采集心跳推进后顺带刷新列表, 在线状态变化无需等 30s
    unsub = realtime.on('cycle', () => {
        if (!portsDrawer.value && !detailDrawer.value && !editDialog.value) fetchData();
    });
    timer = setInterval(fetchData, 30000);
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    if (unsub) unsub();
});
</script>

<style scoped>
.pager {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
}
</style>
