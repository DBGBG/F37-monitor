/**
 * 交换机管理组件 - Switches Page
 * 设备列表、端口状态矩阵、流量趋势图
 */
const SwitchesPage = {
    template: `
    <div class="switches-page">
        <div class="page-title">交换机管理</div>

        <!-- 搜索栏 -->
        <div class="search-bar">
            <el-input v-model="keyword" placeholder="搜索交换机名称/IP/位置" clearable
                      style="width:300px" @clear="fetchData" @keyup.enter="fetchData" />
            <el-checkbox v-model="onlineOnly" @change="fetchData">仅显示在线</el-checkbox>
            <el-button type="primary" @click="fetchData">搜索</el-button>
            <el-button @click="refreshAll">刷新</el-button>
            <span style="margin-left:auto;color:#999">共 {{ total }} 台设备</span>
        </div>

        <!-- 设备列表 -->
        <el-table :data="switchList" stripe v-loading="loading" border style="width:100%">
            <el-table-column label="状态" width="70" align="center">
                <template #default="{ row }">
                    <span class="status-dot" :class="row.is_online ? 'online' : 'offline'"></span>
                    {{ row.is_online ? '在线' : '离线' }}
                </template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="ip" label="IP地址" width="140" />
            <el-table-column prop="model" label="型号" width="120" />
            <el-table-column prop="location" label="位置" min-width="140" show-overflow-tooltip />
            <el-table-column label="端口" width="70" align="center">
                <template #default="{ row }">
                    <span>{{ row.total_ports || '-' }}</span>
                </template>
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
            <el-table-column label="操作" width="160" align="center">
                <template #default="{ row }">
                    <el-button size="small" @click="showPorts(row)">端口</el-button>
                    <el-button size="small" type="primary" @click="showDetail(row)">详情</el-button>
                </template>
            </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div style="margin-top:16px;display:flex;justify-content:flex-end">
            <el-pagination
                v-model:current-page="page"
                :page-size="pageSize"
                :total="total"
                layout="prev, pager, next"
                @current-change="fetchData"
            />
        </div>

        <!-- 端口状态抽屉 -->
        <el-drawer v-model="portsDrawer" :title="'端口状态 - ' + selectedSwitch.name" size="600px">
            <div v-loading="portsLoading">
                <div style="margin-bottom:12px;color:#666">
                    <el-tag type="success" size="small">Up: {{ portsData.filter(p => p.is_up).length }}</el-tag>
                    <el-tag type="danger" size="small" style="margin-left:8px">
                        Down: {{ portsData.filter(p => !p.is_up && p.admin_status === 1).length }}
                    </el-tag>
                    <el-tag type="info" size="small" style="margin-left:8px">
                        Disabled: {{ portsData.filter(p => p.admin_status !== 1).length }}
                    </el-tag>
                </div>
                <!-- 端口矩阵 -->
                <div class="port-grid">
                    <el-tooltip v-for="port in portsData" :key="port.port_index"
                        :content="port.port_name + ' | ' + (port.is_up ? 'UP' : 'DOWN') + ' | ' + formatSpeed(port.speed_bps)">
                        <div class="port-cell"
                             :class="port.admin_status !== 1 ? 'disabled' : (port.is_up ? 'up' : 'down')"
                             @click="showTraffic(port)">
                            {{ port.port_index }}
                        </div>
                    </el-tooltip>
                </div>

                <!-- 端口列表详情 -->
                <el-table :data="portsData" stripe size="small" style="margin-top:16px" max-height="400">
                    <el-table-column prop="port_index" label="#" width="50" />
                    <el-table-column prop="port_name" label="端口名称" min-width="140" show-overflow-tooltip />
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
                </el-table>
            </div>
        </el-drawer>

        <!-- 流量趋势对话框 -->
        <el-dialog v-model="trafficDialog" :title="'流量趋势 - Port ' + selectedPort.port_index" width="700px">
            <div ref="trafficChart" class="chart-container"></div>
        </el-dialog>

        <!-- 详情抽屉 -->
        <el-drawer v-model="detailDrawer" :title="'设备详情 - ' + (selectedSwitch.name || selectedSwitch.ip)" size="500px">
            <el-descriptions :column="1" border>
                <el-descriptions-item label="设备名称">{{ selectedSwitch.name }}</el-descriptions-item>
                <el-descriptions-item label="IP地址">{{ selectedSwitch.ip }}</el-descriptions-item>
                <el-descriptions-item label="型号">{{ selectedSwitch.model || '-' }}</el-descriptions-item>
                <el-descriptions-item label="位置">{{ selectedSwitch.location || '-' }}</el-descriptions-item>
                <el-descriptions-item label="固件版本">{{ selectedSwitch.firmware_version || '-' }}</el-descriptions-item>
                <el-descriptions-item label="运行状态">
                    <el-tag :type="selectedSwitch.is_online ? 'success' : 'danger'">
                        {{ selectedSwitch.is_online ? '在线' : '离线' }}
                    </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="端口数">{{ selectedSwitch.total_ports || 0 }}</el-descriptions-item>
                <el-descriptions-item label="运行时长">{{ formatUptime(selectedSwitch.uptime_seconds) }}</el-descriptions-item>
                <el-descriptions-item label="最后采集">{{ selectedSwitch.last_poll_time || '-' }}</el-descriptions-item>
            </el-descriptions>
        </el-drawer>
    </div>
    `,
    setup() {
        const switchList = Vue.ref([]);
        const loading = Vue.ref(false);
        const keyword = Vue.ref('');
        const onlineOnly = Vue.ref(false);
        const page = Vue.ref(1);
        const pageSize = 50;
        const total = Vue.ref(0);

        // 端口
        const portsDrawer = Vue.ref(false);
        const portsLoading = Vue.ref(false);
        const portsData = Vue.ref([]);
        const selectedSwitch = Vue.ref({});

        // 流量
        const trafficDialog = Vue.ref(false);
        const trafficChart = Vue.ref(null);
        const selectedPort = Vue.ref({});

        // 详情
        const detailDrawer = Vue.ref(false);

        const fetchData = async () => {
            loading.value = true;
            try {
                const resp = await axios.get('/api/switches/list', {
                    params: { page: page.value, page_size: pageSize, keyword: keyword.value, online_only: onlineOnly.value }
                });
                switchList.value = resp.data.items || [];
                total.value = resp.data.total || 0;
            } catch (e) {
                console.error('获取交换机列表失败:', e);
            } finally {
                loading.value = false;
            }
        };

        const refreshAll = () => fetchData();

        const showPorts = async (row) => {
            selectedSwitch.value = row;
            portsDrawer.value = true;
            portsLoading.value = true;
            try {
                const resp = await axios.get(`/api/switches/${row.id}/ports`);
                portsData.value = resp.data.ports || [];
            } catch (e) {
                console.error('获取端口数据失败:', e);
            } finally {
                portsLoading.value = false;
            }
        };

        const showDetail = (row) => {
            selectedSwitch.value = row;
            detailDrawer.value = true;
        };

        const showTraffic = async (port) => {
            selectedPort.value = port;
            trafficDialog.value = true;
            try {
                const resp = await axios.get(`/api/switches/${selectedSwitch.value.id}/traffic`, {
                    params: { port_index: port.port_index, hours: 1 }
                });
                Vue.nextTick(() => renderTrafficChart(resp.data.data || []));
            } catch (e) {
                console.error('获取流量数据失败:', e);
            }
        };

        const renderTrafficChart = (data) => {
            if (!trafficChart.value) return;
            const chart = echarts.init(trafficChart.value);
            const timestamps = data.map(d => d.timestamp?.split('T')[1]?.split('.')[0] || '');
            chart.setOption({
                tooltip: { trigger: 'axis' },
                legend: { data: ['入方向速率', '出方向速率'], bottom: 0 },
                grid: { left: 60, right: 20, top: 20, bottom: 40 },
                xAxis: { type: 'category', data: timestamps, axisLabel: { fontSize: 10 } },
                yAxis: { type: 'value', name: 'bps', axisLabel: { formatter: v => formatSpeed(v) } },
                series: [
                    { name: '入方向速率', type: 'line', smooth: true, data: data.map(d => d.in_rate_bps), areaStyle: { opacity: 0.1 } },
                    { name: '出方向速率', type: 'line', smooth: true, data: data.map(d => d.out_rate_bps), areaStyle: { opacity: 0.1 } },
                ]
            });
            window.addEventListener('resize', () => chart.resize());
        };

        const getUsageClass = (v) => v > 80 ? 'high' : v > 50 ? 'medium' : 'low';

        const formatSpeed = (bps) => {
            if (!bps) return '-';
            if (bps >= 1e9) return (bps / 1e9).toFixed(1) + ' Gbps';
            if (bps >= 1e6) return (bps / 1e6).toFixed(1) + ' Mbps';
            if (bps >= 1e3) return (bps / 1e3).toFixed(1) + ' Kbps';
            return bps + ' bps';
        };

        const formatUptime = (sec) => {
            if (!sec) return '-';
            const d = Math.floor(sec / 86400);
            const h = Math.floor((sec % 86400) / 3600);
            const m = Math.floor((sec % 3600) / 60);
            return d > 0 ? d + '天' + h + '时' + m + '分' : h + '时' + m + '分';
        };

        Vue.onMounted(() => fetchData());

        return {
            switchList, loading, keyword, onlineOnly, page, pageSize, total,
            portsDrawer, portsLoading, portsData, selectedSwitch,
            trafficDialog, trafficChart, selectedPort, detailDrawer,
            fetchData, refreshAll, showPorts, showDetail, showTraffic,
            formatSpeed, formatUptime,
        };
    }
};
