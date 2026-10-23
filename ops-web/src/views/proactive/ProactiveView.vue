<template>
    <div class="proactive">
        <div class="page-title">主动运维</div>

        <el-tabs v-model="tab" @tab-change="onTabChange">
            <!-- 健康总览 -->
            <el-tab-pane label="健康总览" name="overview">
                <el-row :gutter="16" class="mb-16">
                    <el-col :xs="12" :sm="6">
                        <StatCard label="已分析设备" :value="overview.health?.analyzed || 0" icon="DataLine" type="primary"/>
                    </el-col>
                    <el-col :xs="12" :sm="6">
                        <StatCard label="平均健康分" :value="overview.health?.avg_score ?? '-'" icon="CircleCheck" type="success"/>
                    </el-col>
                    <el-col :xs="12" :sm="6">
                        <StatCard label="未处理异常" :value="overview.anomalies?.open || 0" icon="Warning" type="warning"/>
                    </el-col>
                    <el-col :xs="12" :sm="6">
                        <StatCard label="生效维护窗口" :value="overview.maintenance?.active_windows || 0" icon="Clock" type="danger"/>
                    </el-col>
                </el-row>

                <el-row :gutter="16" class="mb-16">
                    <el-col :xs="24" :sm="10">
                        <div class="chart-card">
                            <div class="chart-title">健康等级分布</div>
                            <div class="grade-grid">
                                <div v-for="g in gradeCards" :key="g.name" class="grade-cell" :class="g.cls">
                                    <div class="grade-count">{{ g.count }}</div>
                                    <div class="grade-name">{{ g.name }}</div>
                                </div>
                            </div>
                        </div>
                    </el-col>
                    <el-col :xs="24" :sm="14">
                        <div class="chart-card">
                            <div class="chart-title">最需关注设备</div>
                            <el-table :data="overview.worst_devices || []" size="small" height="220" @row-click="openDetail">
                                <el-table-column prop="device_name" label="设备" show-overflow-tooltip/>
                                <el-table-column label="类型" width="80">
                                    <template #default="{ row }">{{ typeLabel(row.device_type) }}</template>
                                </el-table-column>
                                <el-table-column label="健康分" width="160">
                                    <template #default="{ row }">
                                        <el-progress :percentage="row.score || 0" :color="scoreColor(row.score)" :stroke-width="12"/>
                                    </template>
                                </el-table-column>
                                <el-table-column label="等级" width="70">
                                    <template #default="{ row }">
                                        <el-tag :type="gradeType(row.grade)" size="small">{{ row.grade }}</el-tag>
                                    </template>
                                </el-table-column>
                            </el-table>
                        </div>
                    </el-col>
                </el-row>

                <div class="chart-card">
                    <div class="chart-title">最近异常</div>
                    <el-table :data="overview.recent_anomalies || []" size="small" @row-click="openDetail">
                        <el-table-column prop="device_name" label="设备" show-overflow-tooltip/>
                        <el-table-column label="指标" width="110">
                            <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
                        </el-table-column>
                        <el-table-column label="级别" width="90">
                            <template #default="{ row }">
                                <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'" size="small">
                                    {{ row.severity }}
                                </el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column label="当前/基线" width="150">
                            <template #default="{ row }">
                                {{ fmt(row.value) }}{{ row.unit }} / {{ fmt(row.baseline) }}{{ row.unit }}
                            </template>
                        </el-table-column>
                        <el-table-column label="状态" width="90">
                            <template #default="{ row }">
                                <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column prop="last_seen_at" label="最近出现" width="170"/>
                    </el-table>
                </div>
            </el-tab-pane>

            <!-- 健康评分 -->
            <el-tab-pane label="健康评分" name="health">
                <div class="toolbar">
                    <div class="search-bar" style="margin:0">
                        <el-select v-model="healthFilter.deviceType" placeholder="设备类型" clearable
                                   style="width:130px" @change="fetchHealth">
                            <el-option label="交换机" value="switch"/>
                            <el-option label="服务器" value="server"/>
                        </el-select>
                        <el-select v-model="healthFilter.grade" placeholder="等级" clearable
                                   style="width:110px" @change="fetchHealth">
                            <el-option v-for="g in ['优', '良', '中', '差']" :key="g" :label="g" :value="g"/>
                        </el-select>
                        <el-input v-model="healthFilter.keyword" placeholder="设备名/IP" clearable
                                  style="width:200px" @keyup.enter="fetchHealth"/>
                        <el-button type="primary" @click="fetchHealth">查询</el-button>
                    </div>
                    <el-button v-if="auth.isAdmin" @click="runAnalyze">立即分析</el-button>
                </div>
                <el-table :data="health.items" size="small" v-loading="loading.health">
                    <el-table-column prop="device_name" label="设备" show-overflow-tooltip/>
                    <el-table-column label="类型" width="80">
                        <template #default="{ row }">{{ typeLabel(row.device_type) }}</template>
                    </el-table-column>
                    <el-table-column label="健康分" width="200">
                        <template #default="{ row }">
                            <el-progress :percentage="row.score || 0" :color="scoreColor(row.score)" :stroke-width="14"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="等级" width="70">
                        <template #default="{ row }">
                            <el-tag :type="gradeType(row.grade)" size="small">{{ row.grade }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="summary" label="健康摘要" show-overflow-tooltip/>
                    <el-table-column prop="updated_at" label="评分时间" width="160"/>
                    <el-table-column label="操作" width="90">
                        <template #default="{ row }">
                            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="health.total" :page-size="health.page_size" :current-page="health.page"
                               @current-change="(p) => { health.page = p; fetchHealth(); }"/>
            </el-tab-pane>

            <!-- 异常中心 -->
            <el-tab-pane label="异常中心" name="anomalies">
                <div class="toolbar">
                    <div class="search-bar" style="margin:0">
                        <el-select v-model="anomFilter.status" style="width:130px" @change="fetchAnomalies">
                            <el-option label="未处理" value="open"/>
                            <el-option label="已确认" value="acknowledged"/>
                            <el-option label="已恢复" value="resolved"/>
                            <el-option label="全部" value="all"/>
                        </el-select>
                        <el-select v-model="anomFilter.severity" placeholder="级别" clearable
                                   style="width:120px" @change="fetchAnomalies">
                            <el-option label="严重" value="CRITICAL"/>
                            <el-option label="警告" value="WARNING"/>
                        </el-select>
                        <el-select v-model="anomFilter.metric" placeholder="指标" clearable
                                   style="width:150px" @change="fetchAnomalies">
                            <el-option v-for="(l, m) in metricMap" :key="m" :label="l" :value="m"/>
                        </el-select>
                    </div>
                    <el-button v-if="auth.isAdmin" @click="runAnalyze">立即分析</el-button>
                </div>
                <el-table :data="anomalies.items" size="small" v-loading="loading.anomalies">
                    <el-table-column prop="device_name" label="设备" show-overflow-tooltip/>
                    <el-table-column label="指标" width="110">
                        <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
                    </el-table-column>
                    <el-table-column label="级别" width="90">
                        <template #default="{ row }">
                            <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'" size="small">
                                {{ row.severity }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="当前/基线" width="150">
                        <template #default="{ row }">
                            {{ fmt(row.value) }}{{ row.unit }} / {{ fmt(row.baseline) }}{{ row.unit }}
                        </template>
                    </el-table-column>
                    <el-table-column label="偏离(z)" width="120">
                        <template #default="{ row }">{{ fmt(row.deviation) }}{{ row.unit }} ({{ fmt(row.score) }}σ)</template>
                    </el-table-column>
                    <el-table-column label="升级告警" width="80">
                        <template #default="{ row }">
                            <el-tag v-if="row.escalated" type="danger" size="small">已升级</el-tag>
                            <span v-else class="text-muted">-</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="last_seen_at" label="最近出现" width="170"/>
                    <el-table-column label="状态" width="80">
                        <template #default="{ row }">
                            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="150">
                        <template #default="{ row }">
                            <el-button v-if="row.status === 'open' && auth.canWrite" link type="warning" @click="ack(row)">
                                确认
                            </el-button>
                            <el-button v-if="row.status !== 'resolved' && auth.canWrite" link type="success" @click="resolveAnom(row)">
                                恢复
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="anomalies.total" :page-size="anomalies.page_size" :current-page="anomalies.page"
                               @current-change="(p) => { anomalies.page = p; fetchAnomalies(); }"/>
            </el-tab-pane>

            <!-- 预防建议 -->
            <el-tab-pane label="预防建议" name="recommendations">
                <div v-if="!recommendations.length" class="text-muted" style="padding:20px">
                    暂无需要处理的预防性建议
                </div>
                <el-row :gutter="16">
                    <el-col v-for="r in recommendations" :key="r.device_id + r.metric" :xs="24" :sm="12"
                            style="margin-bottom:16px">
                        <div class="rec-card" :class="r.priority">
                            <div class="rec-head">
                                <el-tag :type="r.priority === 'high' ? 'danger' : (r.priority === 'medium' ? 'warning' : 'info')"
                                        size="small">
                                    {{ priorityLabel(r.priority) }}
                                </el-tag>
                                <span class="rec-device">{{ r.device_name }} · {{ typeLabel(r.device_type) }}</span>
                            </div>
                            <div class="rec-action">{{ r.action }}</div>
                            <div class="rec-detail">{{ r.detail }}</div>
                            <div class="rec-meta">
                                {{ metricLabel(r.metric) }}：{{ fmt(r.value) }}{{ r.unit }}
                                （基线 {{ fmt(r.baseline) }}{{ r.unit }}） · {{ r.occurred_at }}
                            </div>
                        </div>
                    </el-col>
                </el-row>
            </el-tab-pane>

            <!-- 指标基线 -->
            <el-tab-pane label="指标基线" name="baselines">
                <div class="search-bar">
                    <el-select v-model="baseFilter.deviceId" placeholder="设备" clearable filterable
                               style="width:240px" @change="fetchBaselines">
                        <el-option v-for="d in deviceOptions" :key="d.id" :label="`${d.name} (${d.ip})`" :value="d.id"/>
                    </el-select>
                    <el-select v-model="baseFilter.metric" placeholder="指标" clearable style="width:150px"
                               @change="fetchBaselines">
                        <el-option v-for="(l, m) in metricMap" :key="m" :label="l" :value="m"/>
                    </el-select>
                    <el-button @click="fetchBaselines">刷新</el-button>
                    <span class="text-muted" style="margin-left:auto">
                        EWMA α=0.15, 样本不足 20 条的指标不参与异常判定
                    </span>
                </div>
                <el-table :data="baselines.items" size="small" v-loading="loading.baselines">
                    <el-table-column prop="device_id" label="设备" min-width="160" show-overflow-tooltip/>
                    <el-table-column label="类型" width="80">
                        <template #default="{ row }">{{ typeLabel(row.device_type) }}</template>
                    </el-table-column>
                    <el-table-column label="指标" width="130">
                        <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
                    </el-table-column>
                    <el-table-column prop="mean" label="基线均值" width="110"/>
                    <el-table-column prop="std" label="波动σ" width="100"/>
                    <el-table-column prop="last_value" label="最新值" width="110"/>
                    <el-table-column prop="sample_count" label="样本数" width="100"/>
                    <el-table-column prop="updated_at" label="更新时间" width="170"/>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="baselines.total" :page-size="baselines.page_size" :current-page="baselines.page"
                               @current-change="(p) => { baselines.page = p; fetchBaselines(); }"/>
            </el-tab-pane>

            <!-- 维护窗口 -->
            <el-tab-pane label="维护窗口" name="maintenance">
                <div class="toolbar">
                    <div class="stat-label">维护窗口内将自动抑制告警与主动异常，用于计划停电、割接、升级等场景</div>
                    <el-button v-if="auth.canWrite" type="primary" @click="openWindowDialog()">新建窗口</el-button>
                </div>
                <el-table :data="windows" size="small" v-loading="loading.windows">
                    <el-table-column prop="name" label="名称" show-overflow-tooltip/>
                    <el-table-column label="范围" width="140">
                        <template #default="{ row }">
                            {{ row.device_type === 'all' ? '全部设备' : typeLabel(row.device_type) }}
                            <span v-if="row.device_ids && row.device_ids.length"> ({{ row.device_ids.length }})</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="start_time" label="开始" width="170"/>
                    <el-table-column prop="end_time" label="结束" width="170"/>
                    <el-table-column prop="reason" label="原因" show-overflow-tooltip/>
                    <el-table-column prop="created_by" label="创建人" width="100"/>
                    <el-table-column label="状态" width="90">
                        <template #default="{ row }">
                            <el-tag v-if="row.active" type="warning" size="small">生效中</el-tag>
                            <el-tag v-else-if="row.enabled" type="info" size="small">未开始</el-tag>
                            <el-tag v-else type="info" size="small" effect="plain">已停用</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column v-if="auth.canWrite" label="操作" width="180">
                        <template #default="{ row }">
                            <el-button link type="primary" @click="openWindowDialog(row)">编辑</el-button>
                            <el-button link @click="toggleWindow(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                            <el-button link type="danger" @click="deleteWindow(row)">删除</el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </el-tab-pane>
        </el-tabs>

        <!-- 设备健康详情 -->
        <el-drawer v-model="detailVisible" size="540px" :title="detailTitle">
            <div v-if="detail.health">
                <div class="detail-score">
                    <el-progress type="dashboard" :percentage="detail.health.score || 0"
                                 :color="scoreColor(detail.health.score)" :width="120"/>
                    <div class="mt-8">
                        <el-tag :type="gradeType(detail.health.grade)">{{ detail.health.grade }}</el-tag>
                        <span style="margin-left:8px;color:#666">{{ detail.health.summary }}</span>
                    </div>
                </div>
                <div class="drawer-section-title">扣分因子</div>
                <div v-if="!detail.health.factors.length" class="text-muted">无</div>
                <div v-for="(f, i) in detail.health.factors" :key="i" class="factor-row">
                    <span>{{ f.factor }}</span>
                    <span class="text-muted">{{ f.detail }}</span>
                    <span style="color:#F56C6C">-{{ f.deduction }}</span>
                </div>
                <div class="drawer-section-title">动态基线</div>
                <el-table :data="detail.baselines" size="small">
                    <el-table-column label="指标">
                        <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
                    </el-table-column>
                    <el-table-column prop="mean" label="基线值" width="90"/>
                    <el-table-column prop="std" label="波动σ" width="90"/>
                    <el-table-column prop="last_value" label="最新值" width="90"/>
                    <el-table-column prop="sample_count" label="样本数" width="80"/>
                </el-table>
                <div class="drawer-section-title">未处理异常</div>
                <div v-if="!detail.anomalies.length" class="text-muted">无</div>
                <div v-for="a in detail.anomalies" :key="a.id" class="factor-row">
                    <el-tag :type="a.severity === 'CRITICAL' ? 'danger' : 'warning'" size="small">
                        {{ metricLabel(a.metric) }}
                    </el-tag>
                    <span style="color:#666">{{ fmt(a.value) }}{{ a.unit }} / 基线 {{ fmt(a.baseline) }}{{ a.unit }}</span>
                    <span class="text-muted">{{ a.last_seen_at }}</span>
                </div>
            </div>
        </el-drawer>

        <!-- 维护窗口编辑 -->
        <el-dialog v-model="windowDialog" :title="windowForm.id ? '编辑维护窗口' : '新建维护窗口'" width="560px">
            <el-form :model="windowForm" label-width="90px">
                <el-form-item label="名称">
                    <el-input v-model="windowForm.name" placeholder="如: 3号机房计划停电"/>
                </el-form-item>
                <el-form-item label="作用范围">
                    <el-radio-group v-model="windowForm.device_type">
                        <el-radio-button label="all">全部设备</el-radio-button>
                        <el-radio-button label="switch">交换机</el-radio-button>
                        <el-radio-button label="server">服务器</el-radio-button>
                    </el-radio-group>
                </el-form-item>
                <el-form-item v-if="windowForm.device_type !== 'all'" label="指定设备">
                    <el-select v-model="windowForm.device_ids" multiple filterable placeholder="留空=该类型全部"
                               style="width:100%">
                        <el-option v-for="d in scopedDevices" :key="d.id" :label="d.name" :value="d.id"/>
                    </el-select>
                </el-form-item>
                <el-form-item label="开始时间">
                    <el-date-picker v-model="windowForm.start_time" type="datetime"
                                    value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"/>
                </el-form-item>
                <el-form-item label="结束时间">
                    <el-date-picker v-model="windowForm.end_time" type="datetime"
                                    value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"/>
                </el-form-item>
                <el-form-item label="原因">
                    <el-input v-model="windowForm.reason" type="textarea" :rows="2"/>
                </el-form-item>
                <el-form-item label="启用">
                    <el-switch v-model="windowForm.enabled"/>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="windowDialog = false">取消</el-button>
                <el-button type="primary" @click="saveWindow">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * 主动运维: 健康评分 / 异常中心 / 预防建议 / 指标基线 / 维护窗口.
 * 分析引擎已迁到业务层 (每 300s 一轮 EWMA), "立即分析" 是同步执行, 只给 ADMIN.
 * 操作人统一取当前登录用户, 不再硬编码 'admin'.
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatCard from '@/components/StatCard.vue';
import * as proactiveApi from '@/api/proactive';
import * as inspectionApi from '@/api/inspection';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

const tab = ref('overview');
const overview = ref({});
const recommendations = ref([]);
const windows = ref([]);
const deviceOptions = ref([]);
const detailVisible = ref(false);
const detail = ref({});
const windowDialog = ref(false);
const loading = reactive({ health: false, anomalies: false, windows: false, baselines: false });

const health = reactive({ items: [], total: 0, page: 1, page_size: 50 });
const healthFilter = reactive({ deviceType: '', grade: '', keyword: '' });
const anomalies = reactive({ items: [], total: 0, page: 1, page_size: 50 });
const anomFilter = reactive({ status: 'open', severity: '', metric: '' });
const baselines = reactive({ items: [], total: 0, page: 1, page_size: 100 });
const baseFilter = reactive({ deviceId: '', metric: '' });

const emptyWindow = () => ({
    id: null, name: '', device_type: 'all', device_ids: [],
    start_time: '', end_time: '', reason: '', enabled: true,
});
const windowForm = reactive(emptyWindow());

const metricMap = {
    temperature: '设备温度', cpu: 'CPU 使用率', memory: '内存使用率',
    disk: '磁盘使用率', port_error: '端口误码增量',
};
const metricLabel = (m) => metricMap[m] || m;
const typeLabel = (t) => (t === 'switch' ? '交换机' : t === 'server' ? '服务器' : t);
const priorityLabel = (p) => ({ high: '高', medium: '中', low: '低' }[p] || p);
const statusLabel = (s) => ({ open: '未处理', acknowledged: '已确认', resolved: '已恢复' }[s] || s);
const statusType = (s) => ({ open: 'danger', acknowledged: 'warning', resolved: 'success' }[s] || 'info');
const fmt = (v) => (v === null || v === undefined ? '-' : Math.round(v * 10) / 10);
const scoreColor = (s) => (s >= 90 ? '#67C23A' : s >= 75 ? '#409EFF' : s >= 60 ? '#E6A23C' : '#F56C6C');
const gradeType = (g) => ({ 优: 'success', 良: 'primary', 中: 'warning', 差: 'danger' }[g] || 'info');

const gradeCards = computed(() => {
    const dist = (overview.value.health || {}).distribution || {};
    return [
        { name: '优', count: dist['优'] || 0, cls: 'g-you' },
        { name: '良', count: dist['良'] || 0, cls: 'g-liang' },
        { name: '中', count: dist['中'] || 0, cls: 'g-zhong' },
        { name: '差', count: dist['差'] || 0, cls: 'g-cha' },
    ];
});

const detailTitle = computed(() => (detail.value.health
    ? `${detail.value.health.device_name} · 健康详情`
    : '设备健康详情'));

const scopedDevices = computed(() => deviceOptions.value
    .filter((d) => windowForm.device_type === 'all' || d.type === windowForm.device_type));

const fetchOverview = async () => {
    try {
        overview.value = await proactiveApi.overview();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const fetchHealth = async () => {
    loading.health = true;
    try {
        const d = await proactiveApi.health({
            page: health.page, pageSize: health.page_size,
            deviceType: healthFilter.deviceType, grade: healthFilter.grade, keyword: healthFilter.keyword,
        });
        health.items = d.items;
        health.total = d.total;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.health = false;
    }
};

const fetchAnomalies = async () => {
    loading.anomalies = true;
    try {
        const d = await proactiveApi.anomalies({
            page: anomalies.page, pageSize: anomalies.page_size,
            status: anomFilter.status, severity: anomFilter.severity, metric: anomFilter.metric,
        });
        anomalies.items = d.items;
        anomalies.total = d.total;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.anomalies = false;
    }
};

const fetchRecommendations = async () => {
    try {
        recommendations.value = (await proactiveApi.recommendations()).items || [];
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const fetchBaselines = async () => {
    loading.baselines = true;
    try {
        const d = await proactiveApi.baselines({
            page: baselines.page, pageSize: baselines.page_size,
            deviceId: baseFilter.deviceId, metric: baseFilter.metric,
        });
        baselines.items = d.items;
        baselines.total = d.total;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.baselines = false;
    }
};

const fetchWindows = async () => {
    loading.windows = true;
    try {
        windows.value = (await proactiveApi.windows()).items || [];
        if (!deviceOptions.value.length) {
            deviceOptions.value = (await inspectionApi.devices()).devices || [];
        }
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.windows = false;
    }
};

const onTabChange = (name) => {
    if (name === 'health') {
        health.page = 1;
        fetchHealth();
    } else if (name === 'anomalies') {
        anomalies.page = 1;
        fetchAnomalies();
    } else if (name === 'recommendations') {
        fetchRecommendations();
    } else if (name === 'baselines') {
        baselines.page = 1;
        fetchBaselines();
    } else if (name === 'maintenance') {
        fetchWindows();
    } else {
        fetchOverview();
    }
};

const runAnalyze = async () => {
    try {
        const d = await proactiveApi.analyze();
        const s = d.stats || {};
        ElMessage.success(`已执行主动分析: 样本 ${s.samples || 0}, 新异常 ${s.new_anomalies || 0}, 恢复 ${s.resolved || 0}`);
        fetchOverview();
        fetchRecommendations();
        if (tab.value === 'health') fetchHealth();
        if (tab.value === 'anomalies') fetchAnomalies();
        if (tab.value === 'baselines') fetchBaselines();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const ack = async (row) => {
    try {
        await proactiveApi.acknowledgeAnomaly(row.id);
        ElMessage.success('已确认');
        fetchAnomalies();
        fetchOverview();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const resolveAnom = async (row) => {
    try {
        await proactiveApi.resolveAnomaly(row.id);
        ElMessage.success('已标记恢复');
        fetchAnomalies();
        fetchOverview();
        fetchRecommendations();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const openDetail = async (row) => {
    if (!row || !row.device_id) return;
    try {
        detail.value = await proactiveApi.healthDetail(row.device_id);
        detailVisible.value = true;
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const openWindowDialog = (row) => {
    Object.assign(windowForm, emptyWindow());
    if (row) {
        Object.assign(windowForm, {
            id: row.id, name: row.name, device_type: row.device_type,
            device_ids: [...(row.device_ids || [])], start_time: row.start_time,
            end_time: row.end_time, reason: row.reason, enabled: row.enabled,
        });
    }
    if (!deviceOptions.value.length) fetchWindows();
    windowDialog.value = true;
};

const saveWindow = async () => {
    if (!windowForm.name || !windowForm.start_time || !windowForm.end_time) {
        ElMessage.warning('请填写名称与起止时间');
        return;
    }
    const payload = {
        name: windowForm.name,
        device_type: windowForm.device_type,
        device_ids: windowForm.device_type === 'all' ? [] : windowForm.device_ids,
        start_time: windowForm.start_time,
        end_time: windowForm.end_time,
        reason: windowForm.reason,
        enabled: windowForm.enabled,
    };
    try {
        if (windowForm.id) await proactiveApi.updateWindow(windowForm.id, payload);
        else await proactiveApi.createWindow(payload);
        ElMessage.success('已保存');
        windowDialog.value = false;
        fetchWindows();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const toggleWindow = async (row) => {
    try {
        await proactiveApi.toggleWindow(row.id);
        fetchWindows();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

const deleteWindow = async (row) => {
    try {
        await ElMessageBox.confirm(`确认删除维护窗口「${row.name}」?`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    try {
        await proactiveApi.deleteWindow(row.id);
        ElMessage.success('已删除');
        fetchWindows();
    } catch (e) {
        /* 拦截器已提示 */
    }
};

let timer = null;

onMounted(() => {
    fetchOverview();
    // 迁移前 60s 只刷新总览, 保持一致
    timer = setInterval(() => {
        if (tab.value === 'overview') fetchOverview();
    }, 60000);
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
});
</script>

<style scoped>
.mt-8 {
    margin-top: 8px;
}
</style>
