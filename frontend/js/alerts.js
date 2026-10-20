/**
 * 告警管理组件 - Alerts Page
 * 告警列表、过滤、确认/恢复操作
 */
const AlertsPage = {
    template: `
    <div class="alerts-page">
        <div class="page-title">告警管理</div>

        <!-- 统计卡片 -->
        <el-row :gutter="16" style="margin-bottom:16px">
            <el-col :span="4" v-for="item in summaryCards" :key="item.key">
                <div class="stat-card" :class="item.cls" style="text-align:center;position:relative">
                    <div class="stat-value">{{ summary[item.key] || 0 }}</div>
                    <div class="stat-label">{{ item.label }}</div>
                </div>
            </el-col>
        </el-row>

        <!-- 过滤栏 -->
        <div class="search-bar">
            <el-select v-model="statusFilter" style="width:140px" @change="fetchAlerts">
                <el-option label="活跃告警" value="active" />
                <el-option label="已确认" value="acknowledged" />
                <el-option label="已恢复" value="resolved" />
                <el-option label="全部" value="all" />
            </el-select>
            <el-select v-model="severityFilter" style="width:140px" clearable
                       placeholder="告警级别" @change="fetchAlerts">
                <el-option label="CRITICAL" value="CRITICAL" />
                <el-option label="WARNING" value="WARNING" />
                <el-option label="INFO" value="INFO" />
            </el-select>
            <el-button @click="fetchAlerts">刷新</el-button>
        </div>

        <!-- 告警列表 -->
        <el-table :data="alertList" stripe border v-loading="loading"
                  :row-class-name="alertRowClass">
            <el-table-column label="级别" width="100" align="center">
                <template #default="{ row }">
                    <el-tag :type="severityType(row.severity)" effect="dark" size="small">
                        {{ row.severity }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="title" label="告警标题" min-width="280" show-overflow-tooltip />
            <el-table-column prop="device_name" label="设备" width="160" show-overflow-tooltip />
            <el-table-column label="类型" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="row.device_type === 'switch' ? '' : 'success'">
                        {{ row.device_type === 'switch' ? '交换机' : '服务器' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
                <template #default="{ row }">
                    <el-tag :type="statusType(row.status)" size="small">
                        {{ statusLabel(row.status) }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="triggered_at" label="触发时间" width="170" />
            <el-table-column label="操作" width="180" align="center">
                <template #default="{ row }">
                    <template v-if="row.status === 'active'">
                        <el-button size="small" type="warning" @click="acknowledge(row)">确认</el-button>
                        <el-button size="small" type="success" @click="resolve(row)">恢复</el-button>
                    </template>
                    <span v-else style="color:#ccc">已处理</span>
                </template>
            </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div style="margin-top:16px;display:flex;justify-content:flex-end">
            <el-pagination
                v-model:current-page="page"
                :page-size="pageSize"
                :total="total"
                layout="prev, pager, next, total"
                @current-change="fetchAlerts"
            />
        </div>
    </div>
    `,
    setup() {
        const alertList = Vue.ref([]);
        const loading = Vue.ref(false);
        const statusFilter = Vue.ref('active');
        const severityFilter = Vue.ref(null);
        const page = Vue.ref(1);
        const pageSize = 50;
        const total = Vue.ref(0);
        const summary = Vue.ref({});

        const summaryCards = [
            { key: 'active', label: '活跃告警', cls: 'danger' },
            { key: 'critical', label: '严重告警', cls: 'danger' },
            { key: 'warning', label: '警告', cls: 'warning' },
            { key: 'acknowledged', label: '已确认', cls: 'primary' },
            { key: 'resolved', label: '已恢复', cls: 'success' },
            { key: 'recent_24h', label: '24h新增', cls: '' },
        ];

        const fetchSummary = async () => {
            try {
                const resp = await axios.get('/api/alerts/summary');
                summary.value = resp.data;
            } catch (e) { /* ignore */ }
        };

        const fetchAlerts = async () => {
            loading.value = true;
            try {
                const params = {
                    status: statusFilter.value,
                    page: page.value,
                    page_size: pageSize,
                };
                if (severityFilter.value) params.severity = severityFilter.value;
                const resp = await axios.get('/api/alerts/list', { params });
                alertList.value = resp.data.items || [];
                total.value = resp.data.total || 0;
            } catch (e) {
                console.error('获取告警列表失败:', e);
            } finally {
                loading.value = false;
            }
        };

        const acknowledge = async (row) => {
            try {
                await axios.post('/api/alerts/acknowledge', {
                    alert_id: row.alert_id, acknowledged_by: 'admin'
                });
                ElementPlus.ElMessage.success('告警已确认');
                fetchAlerts();
                fetchSummary();
            } catch (e) {
                ElementPlus.ElMessage.error('操作失败');
            }
        };

        const resolve = async (row) => {
            try {
                await axios.post('/api/alerts/resolve', {
                    alert_id: row.alert_id, resolved_by: 'admin'
                });
                ElementPlus.ElMessage.success('告警已恢复');
                fetchAlerts();
                fetchSummary();
            } catch (e) {
                ElementPlus.ElMessage.error('操作失败');
            }
        };

        const severityType = (s) => s === 'CRITICAL' ? 'danger' : s === 'WARNING' ? 'warning' : 'info';
        const statusType = (s) => s === 'active' ? 'danger' : s === 'acknowledged' ? 'warning' : 'success';
        const statusLabel = (s) => s === 'active' ? '活跃' : s === 'acknowledged' ? '已确认' : '已恢复';
        const alertRowClass = ({ row }) => {
            if (row.severity === 'CRITICAL' && row.status === 'active') return 'el-table__row--critical';
            return '';
        };

        // 告警推送去抖: 短时间内多条告警只刷新一次, 避免"告警风暴"下频繁请求
        let refreshTimer = null;
        const scheduleRefresh = () => {
            if (refreshTimer) return;
            refreshTimer = setTimeout(() => {
                refreshTimer = null;
                fetchSummary();
                fetchAlerts();
            }, 800);
        };

        Vue.onMounted(() => {
            fetchSummary();
            fetchAlerts();
            // 实时推送: 有新告警/告警恢复立即刷新
            Realtime.on('alert', scheduleRefresh);
            Realtime.on('alerts', (a) => {
                if (a) summary.value = Object.assign({}, summary.value, a);
            });
            const timer = setInterval(() => { fetchSummary(); fetchAlerts(); }, 30000);
            Vue.onUnmounted(() => clearInterval(timer));
        });

        return {
            alertList, loading, statusFilter, severityFilter, page, pageSize, total,
            summary, summaryCards,
            fetchAlerts, acknowledge, resolve,
            severityType, statusType, statusLabel, alertRowClass,
        };
    }
};
