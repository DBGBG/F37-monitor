<template>
    <div class="inspection">
        <div class="page-title">设备点检</div>

        <el-row :gutter="16" class="mb-16">
            <el-col v-for="c in statCards" :key="c.key" :xs="12" :sm="8" :md="4">
                <StatCard :label="c.label" :value="c.value" :suffix="c.suffix" :type="c.type"/>
            </el-col>
        </el-row>

        <el-tabs v-model="tab" @tab-change="onTabChange">
            <!-- ==================== 点检计划 ==================== -->
            <el-tab-pane label="点检计划" name="plans">
                <div class="toolbar">
                    <div class="search-bar" style="margin:0">
                        <el-input v-model="planKeyword" placeholder="搜索计划名称" style="width:220px" clearable
                                  @keyup.enter="fetchPlans"/>
                        <el-button @click="fetchPlans">查询</el-button>
                    </div>
                    <el-button v-if="auth.canWrite" type="primary" @click="openPlanDialog()">新建计划</el-button>
                </div>
                <el-table :data="plans" size="small" v-loading="loading.plans">
                    <el-table-column prop="name" label="计划名称" min-width="180" show-overflow-tooltip/>
                    <el-table-column label="对象" width="170">
                        <template #default="{ row }">
                            <el-tag size="small" :type="row.device_type === 'switch' ? '' : 'success'">
                                {{ row.device_type === 'switch' ? '交换机' : '服务器' }}
                            </el-tag>
                            <span class="text-muted" style="margin-left:6px">
                                {{ row.target_scope === 'all' ? '全部' : (row.target_ids.length + ' 台') }}
                            </span>
                        </template>
                    </el-table-column>
                    <el-table-column label="周期" width="220">
                        <template #default="{ row }">{{ scheduleText(row) }}</template>
                    </el-table-column>
                    <el-table-column prop="item_count" label="项目" width="70" align="center"/>
                    <el-table-column prop="assignee" label="负责人" width="100"/>
                    <el-table-column prop="next_run" label="下次执行" width="180" show-overflow-tooltip/>
                    <el-table-column label="状态" width="80">
                        <template #default="{ row }">
                            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                                {{ row.enabled ? '启用' : '停用' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column v-if="auth.canWrite" label="操作" width="290" align="center">
                        <template #default="{ row }">
                            <el-button link type="primary" @click="runNow(row)">生成任务</el-button>
                            <el-button link type="primary" @click="openPlanDialog(row)">编辑</el-button>
                            <el-button link :type="row.enabled ? 'warning' : 'success'" @click="togglePlan(row)">
                                {{ row.enabled ? '停用' : '启用' }}
                            </el-button>
                            <el-button link type="danger" @click="deletePlan(row)">删除</el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </el-tab-pane>

            <!-- ==================== 点检任务 ==================== -->
            <el-tab-pane label="点检任务" name="tasks">
                <div class="search-bar">
                    <el-select v-model="taskStatus" style="width:150px" @change="onTaskStatusChange">
                        <el-option label="待处理" value="todo"/>
                        <el-option label="待执行" value="pending"/>
                        <el-option label="进行中" value="in_progress"/>
                        <el-option label="已逾期" value="overdue"/>
                        <el-option label="已完成" value="completed"/>
                        <el-option label="已跳过" value="skipped"/>
                        <el-option label="全部" value="all"/>
                    </el-select>
                    <el-date-picker v-model="taskDateRange" type="daterange" value-format="YYYY-MM-DD"
                                    start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px"
                                    @change="onTaskStatusChange"/>
                    <el-button @click="fetchTasks">刷新</el-button>
                </div>
                <el-table :data="tasks" size="small" v-loading="loading.tasks">
                    <el-table-column prop="plan_name" label="计划" min-width="150" show-overflow-tooltip/>
                    <el-table-column label="设备" min-width="170" show-overflow-tooltip>
                        <template #default="{ row }">
                            <el-tag size="small" :type="row.device_type === 'switch' ? '' : 'success'">
                                {{ row.device_type === 'switch' ? '交换机' : '服务器' }}
                            </el-tag>
                            <span style="margin-left:6px">{{ row.device_name }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="planned_time" label="计划时间" width="165"/>
                    <el-table-column prop="due_time" label="截止时间" width="165"/>
                    <el-table-column prop="assignee" label="负责人" width="90"/>
                    <el-table-column label="状态" width="90">
                        <template #default="{ row }">
                            <el-tag size="small" :type="taskStatusType(row.status)">{{ taskStatusLabel(row.status) }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="结果" width="90">
                        <template #default="{ row }">
                            <el-tag v-if="row.status === 'completed'" size="small"
                                    :type="row.result === 'abnormal' ? 'danger' : 'success'">
                                {{ row.result === 'abnormal' ? '异常' : '正常' }}
                            </el-tag>
                            <span v-else class="text-muted">-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="200" align="center">
                        <template #default="{ row }">
                            <template v-if="row.status === 'pending' || row.status === 'overdue'">
                                <el-button v-if="auth.canWrite" link @click="startTask(row)">开始</el-button>
                                <el-button v-if="auth.canWrite" link type="primary" @click="openExec(row)">执行</el-button>
                                <el-button v-if="auth.canWrite" link @click="skipTask(row)">跳过</el-button>
                                <el-button v-if="!auth.canWrite" link @click="viewTask(row)">查看</el-button>
                            </template>
                            <template v-else-if="row.status === 'in_progress'">
                                <el-button v-if="auth.canWrite" link type="primary" @click="openExec(row)">执行</el-button>
                                <el-button v-if="auth.canWrite" link @click="skipTask(row)">跳过</el-button>
                                <el-button v-if="!auth.canWrite" link @click="viewTask(row)">查看</el-button>
                            </template>
                            <template v-else>
                                <el-button link @click="viewTask(row)">查看</el-button>
                            </template>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="taskTotal" :page-size="pageSize" :current-page="taskPage"
                               @current-change="(p) => { taskPage = p; fetchTasks(); }"/>
            </el-tab-pane>

            <!-- ==================== 点检履历 ==================== -->
            <el-tab-pane label="点检履历" name="history">
                <div class="search-bar">
                    <el-select v-model="histDevice" style="width:220px" clearable filterable placeholder="选择设备"
                               @change="fetchHistory">
                        <el-option v-for="d in deviceOptions" :key="d.id" :label="`${d.name} (${d.ip})`" :value="d.id"/>
                    </el-select>
                    <el-select v-model="histResult" style="width:140px" clearable placeholder="结果" @change="fetchHistory">
                        <el-option label="正常" value="normal"/>
                        <el-option label="异常" value="abnormal"/>
                    </el-select>
                    <el-date-picker v-model="histDateRange" type="daterange" value-format="YYYY-MM-DD"
                                    start-placeholder="开始日期" end-placeholder="结束日期" style="width:260px"
                                    @change="fetchHistory"/>
                    <el-button @click="fetchHistory">刷新</el-button>
                </div>
                <el-table :data="history" size="small" v-loading="loading.history" :row-class-name="histRowClass">
                    <el-table-column label="设备" min-width="180" show-overflow-tooltip>
                        <template #default="{ row }">
                            <el-tag size="small" :type="row.device_type === 'switch' ? '' : 'success'">
                                {{ row.device_type === 'switch' ? '交换机' : '服务器' }}
                            </el-tag>
                            <span style="margin-left:6px">{{ row.device_name }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="plan_name" label="计划" min-width="150" show-overflow-tooltip/>
                    <el-table-column prop="finished_at" label="完成时间" width="170"/>
                    <el-table-column prop="operator" label="执行人" width="100"/>
                    <el-table-column label="结果" width="150">
                        <template #default="{ row }">
                            <el-tag v-if="row.status === 'skipped'" size="small" type="info">已跳过</el-tag>
                            <template v-else>
                                <el-tag size="small" :type="row.result === 'abnormal' ? 'danger' : 'success'">
                                    {{ row.result === 'abnormal' ? '异常' : '正常' }}
                                </el-tag>
                                <span v-if="row.abnormal_count" class="text-danger" style="margin-left:6px;font-size:12px">
                                    {{ row.abnormal_count }} 项
                                </span>
                            </template>
                        </template>
                    </el-table-column>
                    <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip/>
                    <el-table-column label="操作" width="90" align="center">
                        <template #default="{ row }">
                            <el-button link @click="viewTask(row)">明细</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="histTotal" :page-size="pageSize" :current-page="histPage"
                               @current-change="(p) => { histPage = p; fetchHistory(); }"/>
            </el-tab-pane>
        </el-tabs>

        <!-- ==================== 计划编辑 ==================== -->
        <el-dialog v-model="planDialog" :title="planForm.id ? '编辑计划' : '新建计划'" width="820px" top="5vh">
            <el-form :model="planForm" label-width="90px">
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="计划名称" required>
                            <el-input v-model="planForm.name" placeholder="如: 汇聚交换机日常点检"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="负责人">
                            <el-input v-model="planForm.assignee" placeholder="执行人"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="设备类型">
                            <el-radio-group v-model="planForm.device_type" @change="onPlanDeviceTypeChange">
                                <el-radio-button value="switch">交换机</el-radio-button>
                                <el-radio-button value="server">服务器</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="点检范围">
                            <el-radio-group v-model="planForm.target_scope">
                                <el-radio-button value="all">全部</el-radio-button>
                                <el-radio-button value="devices">指定设备</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item v-if="planForm.target_scope === 'devices'" label="选择设备">
                    <el-select v-model="planForm.target_ids" multiple filterable style="width:100%"
                               placeholder="选择要点检的设备">
                        <el-option v-for="d in deviceOptions" :key="d.id" :label="`${d.name} (${d.ip})`" :value="d.id"/>
                    </el-select>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="8">
                        <el-form-item label="周期类型">
                            <el-select v-model="planForm.schedule_type" style="width:100%">
                                <el-option label="每日" value="daily"/>
                                <el-option label="每周" value="weekly"/>
                                <el-option label="每月" value="monthly"/>
                                <el-option label="自定义Cron" value="cron"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col v-if="planForm.schedule_type !== 'cron'" :span="8">
                        <el-form-item label="执行时刻">
                            <el-time-select v-model="planForm.time" start="00:00" step="00:15" end="23:45"
                                            style="width:100%" placeholder="选择时刻"/>
                        </el-form-item>
                    </el-col>
                    <el-col v-if="planForm.schedule_type === 'monthly'" :span="8">
                        <el-form-item label="每月几号">
                            <el-input v-model="planForm.daysText" placeholder="如 1,15,30"/>
                        </el-form-item>
                    </el-col>
                    <el-col v-if="planForm.schedule_type === 'cron'" :span="8">
                        <el-form-item label="Cron">
                            <el-input v-model="planForm.cron_expr" placeholder="如 0 8 * * 1-5"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item v-if="planForm.schedule_type === 'weekly'" label="星期">
                    <el-checkbox-group v-model="planForm.weekdays">
                        <el-checkbox v-for="(w, i) in weekLabels" :key="i" :value="i + 1">{{ w }}</el-checkbox>
                    </el-checkbox-group>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="8">
                        <el-form-item label="完成时限">
                            <el-input-number v-model="planForm.grace_hours" :min="1" :max="168"/>
                            <span class="text-muted" style="margin-left:6px">小时</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="启用">
                            <el-switch v-model="planForm.enabled"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="说明">
                    <el-input v-model="planForm.description" type="textarea" :rows="2"/>
                </el-form-item>
            </el-form>

            <el-divider content-position="left">点检项目</el-divider>
            <div class="mb-8">
                <el-button size="small" @click="loadTemplate">载入模板</el-button>
                <el-button size="small" type="primary" @click="addItem">添加检查项</el-button>
            </div>
            <el-table :data="planForm.items" border size="small" max-height="280">
                <el-table-column label="检查项名称" min-width="160">
                    <template #default="{ row }"><el-input v-model="row.name" size="small"/></template>
                </el-table-column>
                <el-table-column label="类型" width="120">
                    <template #default="{ row }">
                        <el-select v-model="row.check_type" size="small">
                            <el-option label="正常/异常" value="bool"/>
                            <el-option label="数值" value="value"/>
                            <el-option label="文本" value="text"/>
                        </el-select>
                    </template>
                </el-table-column>
                <el-table-column label="标准/参考" min-width="140">
                    <template #default="{ row }"><el-input v-model="row.standard" size="small"/></template>
                </el-table-column>
                <el-table-column label="单位" width="90">
                    <template #default="{ row }"><el-input v-model="row.unit" size="small"/></template>
                </el-table-column>
                <el-table-column label="必填" width="70" align="center">
                    <template #default="{ row }"><el-switch v-model="row.required" size="small"/></template>
                </el-table-column>
                <el-table-column label="操作" width="70" align="center">
                    <template #default="{ $index }">
                        <el-button size="small" type="danger" text @click="removeItem($index)">删除</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <template #footer>
                <el-button @click="planDialog = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="savePlan">保存</el-button>
            </template>
        </el-dialog>

        <!-- ==================== 执行点检 ==================== -->
        <el-dialog v-model="execDialog" title="执行点检" width="760px" top="5vh">
            <el-descriptions :column="2" border size="small" class="mb-8">
                <el-descriptions-item label="设备">{{ execTask.device_name }}</el-descriptions-item>
                <el-descriptions-item label="计划">{{ execTask.plan_name }}</el-descriptions-item>
                <el-descriptions-item label="计划时间">{{ execTask.planned_time }}</el-descriptions-item>
                <el-descriptions-item label="截止时间">{{ execTask.due_time }}</el-descriptions-item>
            </el-descriptions>
            <el-form :inline="true" class="mb-8">
                <el-form-item label="执行人">
                    <el-input v-model="execForm.operator" style="width:160px"/>
                </el-form-item>
            </el-form>
            <el-table :data="execForm.records" border size="small" max-height="360">
                <el-table-column prop="item_name" label="检查项" min-width="150" show-overflow-tooltip/>
                <el-table-column prop="standard" label="标准" width="120" show-overflow-tooltip/>
                <el-table-column label="实测/描述" min-width="140">
                    <template #default="{ row }"><el-input v-model="row.value" size="small"/></template>
                </el-table-column>
                <el-table-column label="结果" width="210">
                    <template #default="{ row }">
                        <el-radio-group v-model="row.result" size="small">
                            <el-radio-button value="normal">正常</el-radio-button>
                            <el-radio-button value="abnormal">异常</el-radio-button>
                            <el-radio-button value="na">不适用</el-radio-button>
                        </el-radio-group>
                    </template>
                </el-table-column>
                <el-table-column label="备注" min-width="120">
                    <template #default="{ row }"><el-input v-model="row.remark" size="small"/></template>
                </el-table-column>
            </el-table>
            <el-form-item label="总体备注" class="mt-16">
                <el-input v-model="execForm.remark" type="textarea" :rows="2"/>
            </el-form-item>
            <template #footer>
                <el-button @click="execDialog = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="submitExec">提交点检结果</el-button>
            </template>
        </el-dialog>

        <!-- ==================== 明细查看 ==================== -->
        <el-dialog v-model="viewDialog" title="点检明细" width="720px" top="6vh">
            <el-descriptions :column="2" border size="small" class="mb-8">
                <el-descriptions-item label="设备">{{ viewTaskData.device_name }}</el-descriptions-item>
                <el-descriptions-item label="计划">{{ viewTaskData.plan_name }}</el-descriptions-item>
                <el-descriptions-item label="完成时间">{{ viewTaskData.finished_at }}</el-descriptions-item>
                <el-descriptions-item label="执行人">{{ viewTaskData.operator }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="viewTaskData.records" border size="small">
                <el-table-column prop="item_name" label="检查项" min-width="150"/>
                <el-table-column prop="standard" label="标准" width="120"/>
                <el-table-column prop="value" label="实测/描述" min-width="130"/>
                <el-table-column label="结果" width="90">
                    <template #default="{ row }">
                        <el-tag size="small" :type="resultTagType(row.result)">{{ resultLabel(row.result) }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="remark" label="备注" min-width="120"/>
            </el-table>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * 设备点检页: 计划 / 任务 / 履历 三个页签, 迁移自 legacy frontend/js/inspection.js.
 * 与迁移前的差异: 执行人不再硬编码 admin (取当前登录用户), 统计卡 30s 刷新 + SSE 事件即时刷新.
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatCard from '../../components/StatCard.vue';
import * as inspectionApi from '../../api/inspection';
import { useAuthStore } from '../../stores/auth';
import { useRealtimeStore } from '../../stores/realtime';

const auth = useAuthStore();
const realtime = useRealtimeStore();

const tab = ref('plans');
const pageSize = 50;
const saving = ref(false);
const loading = ref({ plans: false, tasks: false, history: false });

// ---------------- 统计 ----------------
const stats = ref({});
const statCards = computed(() => [
    { key: 'todo', label: '待处理', type: 'warning', value: (stats.value.tasks || {}).todo || 0, suffix: '' },
    { key: 'overdue', label: '已逾期', type: 'danger', value: (stats.value.tasks || {}).overdue || 0, suffix: '' },
    { key: 'today', label: '今日完成', type: 'success', value: stats.value.completed_today || 0, suffix: '' },
    { key: 'abn', label: '今日异常', type: 'danger', value: stats.value.abnormal_today || 0, suffix: '' },
    { key: 'plans', label: '启用计划', type: 'primary', value: stats.value.plans_enabled || 0, suffix: '' },
    { key: 'rate', label: '完成率', type: '', value: stats.value.completion_rate || 0, suffix: '%' },
]);

// ---------------- 计划 ----------------
const plans = ref([]);
const planKeyword = ref('');
const planDialog = ref(false);
const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const emptyPlan = () => ({
    id: null, name: '', description: '', device_type: 'switch', target_scope: 'all',
    target_ids: [], schedule_type: 'daily', time: '08:00', weekdays: [1],
    daysText: '1', cron_expr: '', assignee: '', grace_hours: 12, enabled: true, items: [],
});
const planForm = ref(emptyPlan());

// ---------------- 任务 ----------------
const tasks = ref([]);
const taskStatus = ref('todo');
const taskDateRange = ref(null);
const taskPage = ref(1);
const taskTotal = ref(0);

// ---------------- 履历 ----------------
const history = ref([]);
const histDevice = ref('');
const histResult = ref('');
const histDateRange = ref(null);
const histPage = ref(1);
const histTotal = ref(0);

const deviceOptions = ref([]);

// ---------------- 执行 / 查看 ----------------
const execDialog = ref(false);
const execTask = ref({});
const execForm = ref({ operator: '', remark: '', records: [] });
const viewDialog = ref(false);
const viewTaskData = ref({ records: [] });

function scheduleText(row) {
    const sv = row.schedule_value || {};
    const t = sv.time || '08:00';
    if (row.schedule_type === 'daily') return `每日 ${t}`;
    if (row.schedule_type === 'weekly') {
        const days = (sv.weekdays || []).map((d) => weekLabels[d - 1]).join('、');
        return `每周 ${days} ${t}`;
    }
    if (row.schedule_type === 'monthly') return `每月 ${(sv.days || []).join('、')} 日 ${t}`;
    return `Cron: ${row.cron_expr}`;
}

const taskStatusType = (s) => ({
    pending: 'info', in_progress: 'warning', overdue: 'danger', completed: 'success', skipped: 'info',
}[s] || 'info');
const taskStatusLabel = (s) => ({
    pending: '待执行', in_progress: '进行中', overdue: '已逾期', completed: '已完成', skipped: '已跳过',
}[s] || s);
const resultLabel = (r) => ({ normal: '正常', abnormal: '异常', na: '不适用' }[r] || '-');
const resultTagType = (r) => ({ normal: 'success', abnormal: 'danger', na: 'info' }[r] || 'info');
const histRowClass = ({ row }) => (row.result === 'abnormal' ? 'row-critical' : '');

async function fetchStats() {
    try {
        stats.value = await inspectionApi.stats();
    } catch (e) { /* 统计失败不打扰 */ }
}

async function fetchDevices(deviceType) {
    try {
        const data = await inspectionApi.devices(deviceType);
        deviceOptions.value = data.devices || [];
    } catch (e) { /* ignore */ }
}

async function fetchPlans() {
    loading.plans = true;
    try {
        const data = await inspectionApi.plans({ keyword: planKeyword.value });
        plans.value = data.items || [];
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.plans = false;
    }
}

async function openPlanDialog(row) {
    await fetchDevices(null);
    if (!row) {
        planForm.value = emptyPlan();
        planDialog.value = true;
        return;
    }
    try {
        const d = await inspectionApi.planDetail(row.id);
        const sv = d.schedule_value || {};
        planForm.value = {
            id: d.id, name: d.name, description: d.description,
            device_type: d.device_type, target_scope: d.target_scope,
            target_ids: d.target_ids || [],
            schedule_type: d.schedule_type,
            time: sv.time || '08:00',
            weekdays: sv.weekdays || [1],
            daysText: (sv.days || [1]).join(','),
            cron_expr: d.cron_expr || '',
            assignee: d.assignee, grace_hours: d.grace_hours,
            enabled: d.enabled,
            items: (d.items || []).map((it) => ({
                name: it.name, check_type: it.check_type, standard: it.standard,
                unit: it.unit, required: it.required, sort_order: it.sort_order,
            })),
        };
        planDialog.value = true;
    } catch (e) {
        /* 拦截器已提示 */
    }
}

function onPlanDeviceTypeChange() {
    planForm.value.target_ids = [];
    fetchDevices(planForm.value.device_type);
}

function addItem() {
    planForm.value.items.push({
        name: '', check_type: 'bool', standard: '', unit: '',
        required: true, sort_order: planForm.value.items.length,
    });
}

function removeItem(idx) {
    planForm.value.items.splice(idx, 1);
}

async function loadTemplate() {
    const data = await inspectionApi.templates();
    const tpl = (data.templates || {})[planForm.value.device_type];
    if (!tpl) {
        ElMessage.warning('无可用模板');
        return;
    }
    planForm.value.items = tpl.items.map((it, i) => ({
        name: it.name, check_type: it.check_type, standard: it.standard || '',
        unit: it.unit || '', required: it.required !== false, sort_order: i,
    }));
    ElMessage.success('已载入模板: ' + tpl.name);
}

async function savePlan() {
    const f = planForm.value;
    if (!f.name) return ElMessage.warning('请填写计划名称');
    if (f.target_scope === 'devices' && (!f.target_ids || !f.target_ids.length)) {
        return ElMessage.warning('请选择点检设备');
    }
    if (f.schedule_type === 'cron' && !f.cron_expr) return ElMessage.warning('请填写 cron 表达式');

    const sv = {};
    if (f.schedule_type === 'weekly') sv.weekdays = f.weekdays;
    if (f.schedule_type === 'monthly') {
        sv.days = (f.daysText || '1').split(',').map((s) => parseInt(s.trim(), 10)).filter((n) => n > 0);
    }
    if (f.schedule_type !== 'cron') sv.time = f.time || '08:00';

    const payload = {
        name: f.name, description: f.description, device_type: f.device_type,
        target_scope: f.target_scope, target_ids: f.target_ids,
        schedule_type: f.schedule_type, schedule_value: sv, cron_expr: f.cron_expr,
        assignee: f.assignee, grace_hours: f.grace_hours, enabled: f.enabled,
        items: f.items.filter((it) => it.name).map((it, i) => ({
            name: it.name, check_type: it.check_type, standard: it.standard || '',
            unit: it.unit || '', required: !!it.required, sort_order: i,
        })),
    };
    saving.value = true;
    try {
        if (f.id) await inspectionApi.updatePlan(f.id, payload);
        else await inspectionApi.createPlan(payload);
        ElMessage.success('保存成功');
        planDialog.value = false;
        fetchPlans();
        fetchStats();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        saving.value = false;
    }
}

async function togglePlan(row) {
    await inspectionApi.togglePlan(row.id);
    fetchPlans();
    fetchStats();
}

async function deletePlan(row) {
    try {
        await ElMessageBox.confirm(`确定删除计划「${row.name}」?`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    await inspectionApi.deletePlan(row.id);
    ElMessage.success('已删除');
    fetchPlans();
    fetchStats();
}

async function runNow(row) {
    const data = await inspectionApi.runPlanNow(row.id);
    ElMessage.success(`已生成 ${data.created} 条点检任务`);
    fetchStats();
    if (tab.value === 'tasks') fetchTasks();
}

// ---------------- 任务 ----------------
function onTaskStatusChange() {
    taskPage.value = 1;
    fetchTasks();
}

async function fetchTasks() {
    loading.tasks = true;
    try {
        const params = { page: taskPage.value, pageSize };
        if (taskStatus.value === 'todo') {
            // 待处理 = 待执行 + 进行中 + 已逾期, 后端无组合状态, 取全量后前端筛
            params.status = 'all';
        } else {
            params.status = taskStatus.value;
        }
        if (taskDateRange.value && taskDateRange.value.length === 2) {
            params.dateFrom = taskDateRange.value[0];
            params.dateTo = taskDateRange.value[1];
        }
        const data = await inspectionApi.tasks(params);
        let items = data.items || [];
        if (taskStatus.value === 'todo') {
            items = items.filter((t) => ['pending', 'in_progress', 'overdue'].includes(t.status));
        }
        tasks.value = items;
        taskTotal.value = data.total || 0;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.tasks = false;
    }
}

async function startTask(row) {
    await inspectionApi.startTask(row.id, auth.username);
    fetchTasks();
    fetchStats();
}

async function skipTask(row) {
    let reason = '';
    try {
        const r = await ElMessageBox.prompt('请输入跳过原因', '跳过点检', { inputPlaceholder: '原因' });
        reason = r.value;
    } catch (e) {
        return;
    }
    await inspectionApi.skipTask(row.id, { operator: auth.username, reason });
    ElMessage.success('已跳过');
    fetchTasks();
    fetchStats();
}

async function openExec(row) {
    const d = await inspectionApi.taskDetail(row.id);
    execTask.value = d;
    execForm.value = {
        operator: d.operator || auth.username, remark: d.remark || '',
        records: (d.items || []).map((it) => ({
            item_name: it.name, check_type: it.check_type, standard: it.standard || '',
            value: '', result: 'normal', remark: '',
        })),
    };
    execDialog.value = true;
}

async function submitExec() {
    saving.value = true;
    try {
        const data = await inspectionApi.executeTask(execTask.value.id, execForm.value);
        ElMessage.success(data.result === 'abnormal' ? '提交成功（存在异常项）' : '提交成功');
        execDialog.value = false;
        fetchTasks();
        fetchStats();
        if (tab.value === 'history') fetchHistory();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        saving.value = false;
    }
}

async function viewTask(row) {
    viewTaskData.value = await inspectionApi.taskDetail(row.id);
    viewDialog.value = true;
}

// ---------------- 履历 ----------------
async function fetchHistory() {
    loading.history = true;
    try {
        const params = { page: histPage.value, pageSize };
        if (histDevice.value) params.deviceId = histDevice.value;
        if (histResult.value) params.result = histResult.value;
        if (histDateRange.value && histDateRange.value.length === 2) {
            params.dateFrom = histDateRange.value[0];
            params.dateTo = histDateRange.value[1];
        }
        const data = await inspectionApi.history(params);
        history.value = data.items || [];
        histTotal.value = data.total || 0;
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.history = false;
    }
}

function onTabChange(name) {
    if (name === 'tasks') fetchTasks();
    else if (name === 'history') fetchHistory();
    else fetchPlans();
}

let timer = null;
let unsub = null;

onMounted(() => {
    fetchStats();
    fetchPlans();
    fetchDevices(null);
    // 点检任务由业务层定时生成, 状态变化靠轮询兜底, 采集/告警事件到达时立即刷新统计
    timer = setInterval(fetchStats, 30000);
    unsub = realtime.on('cycle', () => {
        if (!planDialog.value && !execDialog.value && !viewDialog.value) fetchStats();
    });
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    if (unsub) unsub();
});
</script>

<style scoped>
/* 异常履历行: el-table 自己渲染 tr, 需要 :deep 才能命中 */
:deep(.row-critical) {
    background: #fff6f6;
}

.text-danger {
    color: #F56C6C;
}
</style>
