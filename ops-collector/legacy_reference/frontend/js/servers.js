/**
 * 服务器监控组件 - Servers Page
 * 资源使用仪表盘、进程列表
 */
const ServersPage = {
    template: `
    <div class="servers-page">
        <div class="page-title">服务器监控</div>

        <!-- 搜索栏 -->
        <div class="search-bar">
            <el-input v-model="keyword" placeholder="搜索服务器名称/IP" clearable
                      style="width:300px" @clear="fetchData" @keyup.enter="fetchData" />
            <el-button type="primary" @click="fetchData">搜索</el-button>
            <el-button @click="fetchData">刷新</el-button>
            <span style="margin-left:auto;color:#999">共 {{ total }} 台服务器</span>
        </div>

        <!-- 服务器卡片网格 -->
        <el-row :gutter="16">
            <el-col :xs="24" :sm="12" :lg="8" v-for="server in serverList" :key="server.id"
                    style="margin-bottom:16px">
                <el-card shadow="hover" :body-style="{ padding: '16px' }">
                    <!-- 头部 -->
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                        <div>
                            <span class="status-dot" :class="server.is_online ? 'online' : 'offline'"></span>
                            <strong>{{ server.name }}</strong>
                            <el-tag size="small" style="margin-left:8px"
                                    :type="server.os_type === 'windows' ? 'primary' : 'success'">
                                {{ server.os_type }}
                            </el-tag>
                        </div>
                        <el-button size="small" text type="primary" @click="showDetail(server)">详情</el-button>
                    </div>
                    <div style="color:#999;font-size:12px;margin-bottom:12px">
                        {{ server.ip }} | {{ server.hostname || server.location }}
                    </div>

                    <!-- 指标 -->
                    <div v-if="server.metrics && server.is_online">
                        <div style="margin-bottom:8px" v-for="m in getMetrics(server.metrics)" :key="m.label">
                            <div style="display:flex;justify-content:space-between;font-size:13px">
                                <span>{{ m.label }}</span>
                                <span :style="{ color: m.value > 80 ? '#F56C6C' : m.value > 50 ? '#E6A23C' : '#67C23A' }">
                                    {{ m.value.toFixed(1) }}{{ m.unit }}
                                </span>
                            </div>
                            <div class="usage-bar">
                                <div class="usage-fill" :class="m.value > 80 ? 'high' : m.value > 50 ? 'medium' : 'low'"
                                     :style="{ width: Math.min(m.value, 100) + '%' }"></div>
                            </div>
                        </div>
                    </div>
                    <div v-else style="text-align:center;color:#ccc;padding:20px">
                        {{ server.is_online ? '暂无数据' : '设备离线' }}
                    </div>
                </el-card>
            </el-col>
        </el-row>

        <!-- 详情抽屉 -->
        <el-drawer v-model="detailDrawer" :title="'服务器详情 - ' + selectedServer.name" size="650px">
            <el-descriptions :column="2" border style="margin-bottom:16px">
                <el-descriptions-item label="名称">{{ selectedServer.name }}</el-descriptions-item>
                <el-descriptions-item label="IP">{{ selectedServer.ip }}</el-descriptions-item>
                <el-descriptions-item label="系统">{{ selectedServer.os_type }}</el-descriptions-item>
                <el-descriptions-item label="主机名">{{ selectedServer.hostname }}</el-descriptions-item>
                <el-descriptions-item label="位置">{{ selectedServer.location }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                    <el-tag :type="selectedServer.is_online ? 'success' : 'danger'">
                        {{ selectedServer.is_online ? '在线' : '离线' }}
                    </el-tag>
                </el-descriptions-item>
            </el-descriptions>

            <!-- 趋势图 -->
            <div class="chart-card" style="margin-bottom:16px" v-if="selectedServer.is_online">
                <div class="chart-title">资源趋势 (最近1小时)</div>
                <div ref="metricsChart" class="chart-container" style="height:280px"></div>
            </div>

            <!-- 进程列表 -->
            <div class="chart-card">
                <div class="chart-title">进程/服务状态</div>
                <el-table :data="processList" stripe size="small" v-loading="procLoading" max-height="300">
                    <el-table-column prop="process_name" label="进程名" width="140" />
                    <el-table-column prop="pid" label="PID" width="70" />
                    <el-table-column label="状态" width="80">
                        <template #default="{ row }">
                            <el-tag :type="row.is_running ? 'success' : 'danger'" size="small">
                                {{ row.is_running ? '运行中' : '已停止' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="CPU" width="70">
                        <template #default="{ row }">{{ row.cpu_percent?.toFixed(1) }}%</template>
                    </el-table-column>
                    <el-table-column label="内存(MB)" width="90">
                        <template #default="{ row }">{{ row.memory_mb?.toFixed(1) }}</template>
                    </el-table-column>
                    <el-table-column label="端口" width="70">
                        <template #default="{ row }">
                            <span v-if="row.port">
                                <el-tag :type="row.port_open ? 'success' : 'danger'" size="small">
                                    {{ row.port }}
                                </el-tag>
                            </span>
                            <span v-else>-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="关键" width="60">
                        <template #default="{ row }">
                            <el-tag v-if="row.is_critical" type="danger" size="small">关键</el-tag>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
        </el-drawer>
    </div>
    `,
    setup() {
        const serverList = Vue.ref([]);
        const keyword = Vue.ref('');
        const page = Vue.ref(1);
        const pageSize = 20;
        const total = Vue.ref(0);
        const detailDrawer = Vue.ref(false);
        const selectedServer = Vue.ref({});
        const processList = Vue.ref([]);
        const procLoading = Vue.ref(false);
        const metricsChart = Vue.ref(null);

        const fetchData = async () => {
            try {
                const resp = await axios.get('/api/servers/list', {
                    params: { page: page.value, page_size: pageSize, keyword: keyword.value }
                });
                serverList.value = resp.data.items || [];
                total.value = resp.data.total || 0;
            } catch (e) {
                console.error('获取服务器列表失败:', e);
            }
        };

        const showDetail = async (server) => {
            selectedServer.value = server;
            detailDrawer.value = true;
            // 加载进程
            procLoading.value = true;
            try {
                const resp = await axios.get(`/api/servers/${server.id}/processes`);
                processList.value = resp.data.processes || [];
            } catch (e) {
                processList.value = [];
            } finally {
                procLoading.value = false;
            }
            // 加载趋势
            try {
                const resp = await axios.get(`/api/servers/${server.id}/metrics`, { params: { hours: 1 } });
                Vue.nextTick(() => renderMetricsChart(resp.data.data || []));
            } catch (e) { /* ignore */ }
        };

        const renderMetricsChart = (data) => {
            if (!metricsChart.value) return;
            const chart = echarts.init(metricsChart.value);
            const timestamps = data.map(d => d.timestamp?.split('T')[1]?.split('.')[0] || '');
            chart.setOption({
                tooltip: { trigger: 'axis' },
                legend: { data: ['CPU %', '内存 %', '磁盘 %'], bottom: 0 },
                grid: { left: 45, right: 15, top: 15, bottom: 40 },
                xAxis: { type: 'category', data: timestamps, axisLabel: { fontSize: 10 } },
                yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
                series: [
                    { name: 'CPU %', type: 'line', smooth: true, data: data.map(d => d.cpu_usage) },
                    { name: '内存 %', type: 'line', smooth: true, data: data.map(d => d.memory_usage) },
                    { name: '磁盘 %', type: 'line', smooth: true, data: data.map(d => d.disk_usage) },
                ]
            });
            window.addEventListener('resize', () => chart.resize());
        };

        const getMetrics = (m) => [
            { label: 'CPU', value: m.cpu_usage || 0, unit: '%' },
            { label: '内存', value: m.memory_usage || 0, unit: '%' },
            { label: '磁盘', value: m.disk_usage || 0, unit: '%' },
        ];

        Vue.onMounted(() => fetchData());

        return {
            serverList, keyword, page, pageSize, total,
            detailDrawer, selectedServer, processList, procLoading,
            metricsChart, fetchData, showDetail, getMetrics,
        };
    }
};
