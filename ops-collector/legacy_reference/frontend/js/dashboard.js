/**
 * 仪表盘总览组件 - Dashboard Page
 * 展示设备在线率、告警统计、关键指标卡片
 */
const DashboardPage = {
    template: `
    <div class="dashboard">
        <div class="page-title">系统总览</div>

        <!-- 统计卡片 -->
        <el-row :gutter="16" style="margin-bottom: 16px">
            <el-col :xs="12" :sm="6">
                <div class="stat-card primary" style="position:relative">
                    <div class="stat-value">{{ overview.total_devices || 0 }}</div>
                    <div class="stat-label">设备总数</div>
                    <span class="stat-icon">&#9670;</span>
                </div>
            </el-col>
            <el-col :xs="12" :sm="6">
                <div class="stat-card success" style="position:relative">
                    <div class="stat-value">{{ overview.online_devices || 0 }}</div>
                    <div class="stat-label">在线设备</div>
                    <span class="stat-icon">&#10003;</span>
                </div>
            </el-col>
            <el-col :xs="12" :sm="6">
                <div class="stat-card warning" style="position:relative">
                    <div class="stat-value">{{ overview.alerts?.active || 0 }}</div>
                    <div class="stat-label">活跃告警</div>
                    <span class="stat-icon">&#9888;</span>
                </div>
            </el-col>
            <el-col :xs="12" :sm="6">
                <div class="stat-card danger" style="position:relative">
                    <div class="stat-value">{{ overview.alerts?.critical || 0 }}</div>
                    <div class="stat-label">严重告警</div>
                    <span class="stat-icon">&#9888;</span>
                </div>
            </el-col>
        </el-row>

        <!-- 在线率 + 设备分布 -->
        <el-row :gutter="16" style="margin-bottom: 16px">
            <el-col :xs="24" :sm="12">
                <div class="chart-card">
                    <div class="chart-title">设备在线率</div>
                    <div ref="onlineRateChart" class="chart-container" style="height:260px"></div>
                </div>
            </el-col>
            <el-col :xs="24" :sm="12">
                <div class="chart-card">
                    <div class="chart-title">设备分布</div>
                    <div ref="deviceDistChart" class="chart-container" style="height:260px"></div>
                </div>
            </el-col>
        </el-row>

        <!-- 快捷入口 -->
        <el-row :gutter="16">
            <el-col :xs="12" :sm="6" v-for="item in quickLinks" :key="item.page">
                <div class="stat-card" style="cursor:pointer;text-align:center"
                     @click="$emit('navigate', item.page)">
                    <div style="font-size:28px;margin-bottom:8px">{{ item.icon }}</div>
                    <div style="font-weight:600">{{ item.title }}</div>
                    <div class="stat-label">{{ item.desc }}</div>
                </div>
            </el-col>
        </el-row>
    </div>
    `,
    emits: ['navigate'],
    setup() {
        const overview = Vue.ref({});
        const onlineRateChart = Vue.ref(null);
        const deviceDistChart = Vue.ref(null);

        const quickLinks = [
            { page: 'switches', icon: '\u21C4', title: '交换机管理', desc: '端口状态与流量监控' },
            { page: 'servers', icon: '\u25A3', title: '服务器监控', desc: 'CPU/内存/磁盘/进程' },
            { page: 'automation', icon: '\u2699', title: '自动化配置', desc: 'VLAN/端口批量配置' },
            { page: 'alerts', icon: '\u26A0', title: '告警管理', desc: '查看和处理告警' },
        ];

        const fetchData = async () => {
            try {
                const resp = await axios.get('/api/dashboard/overview');
                overview.value = resp.data;
                Vue.nextTick(() => renderCharts());
            } catch (e) {
                console.error('获取总览数据失败:', e);
            }
        };

        const renderCharts = () => {
            // 在线率环形图
            if (onlineRateChart.value) {
                const chart1 = echarts.getInstanceByDom(onlineRateChart.value) || echarts.init(onlineRateChart.value);
                const rate = overview.value.online_rate || 0;
                chart1.setOption({
                    series: [{
                        type: 'gauge',
                        startAngle: 180, endAngle: 0,
                        min: 0, max: 100,
                        radius: '90%',
                        pointer: { show: false },
                        progress: {
                            show: true, roundCap: true,
                            width: 20,
                            itemStyle: { color: rate >= 90 ? '#67C23A' : rate >= 70 ? '#E6A23C' : '#F56C6C' }
                        },
                        axisLine: { lineStyle: { width: 20, color: [[1, '#f0f0f0']] } },
                        axisTick: { show: false },
                        splitLine: { show: false },
                        axisLabel: { show: false },
                        detail: {
                            formatter: '{value}%',
                            fontSize: 28, fontWeight: 'bold',
                            offsetCenter: [0, '20%'],
                            color: '#333'
                        },
                        data: [{ value: rate }],
                        title: { show: true, offsetCenter: [0, '60%'], fontSize: 14, color: '#999' }
                    }]
                });
                window.addEventListener('resize', () => chart1.resize());
            }

            // 设备分布饼图
            if (deviceDistChart.value) {
                const chart2 = echarts.getInstanceByDom(deviceDistChart.value) || echarts.init(deviceDistChart.value);
                const sw = overview.value.switches || {};
                const srv = overview.value.servers || {};
                chart2.setOption({
                    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
                    legend: { bottom: 0, textStyle: { fontSize: 12 } },
                    series: [{
                        type: 'pie', radius: ['40%', '70%'],
                        center: ['50%', '45%'],
                        avoidLabelOverlap: true,
                        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
                        label: { show: true, formatter: '{b}\n{c}台' },
                        data: [
                            { value: sw.online || 0, name: '交换机在线', itemStyle: { color: '#67C23A' } },
                            { value: sw.offline || 0, name: '交换机离线', itemStyle: { color: '#F56C6C' } },
                            { value: srv.online || 0, name: '服务器在线', itemStyle: { color: '#409EFF' } },
                            { value: srv.offline || 0, name: '服务器离线', itemStyle: { color: '#E6A23C' } },
                        ]
                    }]
                });
                window.addEventListener('resize', () => chart2.resize());
            }
        };

        Vue.onMounted(() => {
            fetchData();
            // 实时推送: 统计变化立即更新卡片与图表 (30s 轮询仅作兜底)
            Realtime.on('overview', (o) => {
                if (!o) return;
                overview.value = Object.assign({}, overview.value, o);
                Vue.nextTick(() => renderCharts());
            });
            const timer = setInterval(fetchData, 30000);
            Vue.onUnmounted(() => clearInterval(timer));
        });

        return { overview, onlineRateChart, deviceDistChart, quickLinks };
    }
};
