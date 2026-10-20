/**
 * 设备点检组件 - Inspection Page
 * 三个页签: 点检计划 / 点检任务 / 点检履历
 */
const InspectionPage = {
    template: `
    <div class="inspection-page">
        <div class="page-title">设备点检</div>

        <!-- 概览统计 -->
        <el-row :gutter="16" style="margin-bottom:16px">
            <el-col :span="4" v-for="c in statCards" :key="c.key">
                <div class="stat-card" :class="c.cls" style="text-align:center;position:relative">
                    <div class="stat-value">{{ c.value }}</div>
                    <div class="stat-label">{{ c.label }}</div>
                </div>
            </el-col>
        </el-row>

        <el-tabs v-model="activeTab" @tab-change="onTabChange">
            <!-- ============ 点检计划 ============ -->
            <el-tab-pane label="点检计划" name="plans">
                <div class="toolbar">
                    <div class="search-bar">
                        <el-input v-model="planKeyword" placeholder="搜索计划名称" style="width:220px"
                                  clearable @keyup.enter="fetchPlans" />
                        <el-button @click="fetchPlans">查询</el-button>
                    </div>
                    <el-button type="primary" @click="openPlanDialog()">新建计划</el-button>
                </div>
                <el-table :data="plans" stripe border v-loading="planLoading">
                    <el-table-column prop="name" label="计划名称" min-width="180" show-overflow-tooltip />
                    <el-table-column label="对象" width="160">
                        <template #default="{row}">
                            <el-tag size="small" :type="row.device_type==='switch'?'':'success'">
                                {{ row.device_type==='switch'?'交换机':'服务器' }}
                            </el-tag>
                            <span style="margin-left:6px;color:#909399;font-size:12px">
                                {{ row.target_scope==='all'?'全部':(row.target_ids.length+' 台') }}
                            </span>
                        </template>
                    </el-table-column>
                    <el-table-column label="周期" width="210">
                        <template #default="{row}">{{ scheduleText(row) }}</template>
                    </el-table-column>
                    <el-table-column label="项目" width="70" align="center">
                        <template #default="{row}">{{ row.item_count }}</template>
                    </el-table-column>
                    <el-table-column prop="assignee" label="负责人" width="100" />
                    <el-table-column prop="next_run" label="下次执行" width="180" show-overflow-tooltip />
                    <el-table-column label="状态" width="80">
                        <template #default="{row}">
                            <el-tag size="small" :type="row.enabled?'success':'info'">
                                {{ row.enabled?'启用':'停用' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="300" align="center">
                        <template #default="{row}">
                            <el-button size="small" @click="runNow(row)">生成任务</el-button>
                            <el-button size="small" @click="openPlanDialog(row)">编辑</el-button>
                            <el-button size="small" :type="row.enabled?'warning':'success'"
                                       @click="togglePlan(row)">{{ row.enabled?'停用':'启用' }}</el-button>
                            <el-button size="small" type="danger" @click="deletePlan(row)">删除</el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </el-tab-pane>

            <!-- ============ 点检任务 ============ -->
            <el-tab-pane label="点检任务" name="tasks">
                <div class="search-bar">
                    <el-select v-model="taskStatus" style="width:150px" @change="fetchTasks">
                        <el-option label="待处理" value="todo" />
                        <el-option label="待执行" value="pending" />
                        <el-option label="进行中" value="in_progress" />
                        <el-option label="已逾期" value="overdue" />
                        <el-option label="已完成" value="completed" />
                        <el-option label="已跳过" value="skipped" />
                        <el-option label="全部" value="all" />
                    </el-select>
                    <el-date-picker v-model="taskDateRange" type="daterange" value-format="YYYY-MM-DD"
                                    start-placeholder="开始日期" end-placeholder="结束日期"
                                    style="width:260px" @change="fetchTasks" />
                    <el-button @click="fetchTasks">刷新</el-button>
                </div>
                <el-table :data="tasks" stripe border v-loading="taskLoading">
                    <el-table-column prop="plan_name" label="计划" min-width="150" show-overflow-tooltip />
                    <el-table-column label="设备" min-width="160" show-overflow-tooltip>
                        <template #default="{row}">
                            <el-tag size="small" :type="row.device_type==='switch'?'':'success'">
                                {{ row.device_type==='switch'?'交换机':'服务器' }}
                            </el-tag>
                            <span style="margin-left:6px">{{ row.device_name }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="planned_time" label="计划时间" width="165" />
                    <el-table-column prop="due_time" label="截止时间" width="165" />
                    <el-table-column prop="assignee" label="负责人" width="90" />
                    <el-table-column label="状态" width="90">
                        <template #default="{row}">
                            <el-tag size="small" :type="taskStatusType(row.status)">{{ taskStatusLabel(row.status) }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="结果" width="90">
                        <template #default="{row}">
                            <el-tag v-if="row.status==='completed'" size="small"
                                    :type="row.result==='abnormal'?'danger':'success'">
                                {{ row.result==='abnormal'?'异常':'正常' }}
                            </el-tag>
                            <span v-else style="color:#ccc">-</span>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="230" align="center">
                        <template #default="{row}">
                            <template v-if="row.status==='pending'||row.status==='overdue'">
                                <el-button size="small" @click="startTask(row)">开始</el-button>
                                <el-button size="small" type="primary" @click="openExec(row)">执行</el-button>
                                <el-button size="small" @click="skipTask(row)">跳过</el-button>
                            </template>
                            <template v-else-if="row.status==='in_progress'">
                                <el-button size="small" type="primary" @click="openExec(row)">执行</el-button>
                                <el-button size="small" @click="skipTask(row)">跳过</el-button>
                            </template>
                            <template v-else>
                                <el-button size="small" @click="viewTask(row)">查看</el-button>
                            </template>
                        </template>
                    </el-table-column>
                </el-table>
                <div style="margin-top:16px;display:flex;justify-content:flex-end">
                    <el-pagination v-model:current-page="taskPage" :page-size="pageSize" :total="taskTotal"
                                   layout="prev, pager, next, total" @current-change="fetchTasks" />
                </div>
            </el-tab-pane>

            <!-- ============ 点检履历 ============ -->
            <el-tab-pane label="点检履历" name="history">
                <div class="search-bar">
                    <el-select v-model="histDevice" style="width:220px" clearable filterable
                               placeholder="选择设备" @change="fetchHistory">
                        <el-option v-for="d in deviceOptions" :key="d.id"
                                   :label="d.name + ' (' + d.ip + ')'" :value="d.id" />
                    </el-select>
                    <el-select v-model="histResult" style="width:140px" clearable
                               placeholder="结果" @change="fetchHistory">
                        <el-option label="正常" value="normal" />
                        <el-option label="异常" value="abnormal" />
                    </el-select>
                    <el-date-picker v-model="histDateRange" type="daterange" value-format="YYYY-MM-DD"
                                    start-placeholder="开始日期" end-placeholder="结束日期"
                                    style="width:260px" @change="fetchHistory" />
                    <el-button @click="fetchHistory">刷新</el-button>
                </div>
                <el-table :data="history" stripe border v-loading="histLoading"
                          :row-class-name="histRowClass">
                    <el-table-column label="设备" min-width="180" show-overflow-tooltip>
                        <template #default="{row}">
                            <el-tag size="small" :type="row.device_type==='switch'?'':'success'">
                                {{ row.device_type==='switch'?'交换机':'服务器' }}
                            </el-tag>
                            <span style="margin-left:6px">{{ row.device_name }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="plan_name" label="计划" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="finished_at" label="完成时间" width="170" />
                    <el-table-column prop="operator" label="执行人" width="100" />
                    <el-table-column label="结果" width="140">
                        <template #default="{row}">
                            <template v-if="row.status==='skipped'">
                                <el-tag size="small" type="info">已跳过</el-tag>
                            </template>
                            <template v-else>
                                <el-tag size="small" :type="row.result==='abnormal'?'danger':'success'">
                                    {{ row.result==='abnormal'?'异常':'正常' }}
                                </el-tag>
                                <span v-if="row.abnormal_count" style="margin-left:6px;color:#F56C6C;font-size:12px">
                                    {{ row.abnormal_count }} 项
                                </span>
                            </template>
                        </template>
                    </el-table-column>
                    <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
                    <el-table-column label="操作" width="90" align="center">
                        <template #default="{row}">
                            <el-button size="small" @click="viewTask(row)">明细</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <div style="margin-top:16px;display:flex;justify-content:flex-end">
                    <el-pagination v-model:current-page="histPage" :page-size="pageSize" :total="histTotal"
                                   layout="prev, pager, next, total" @current-change="fetchHistory" />
                </div>
            </el-tab-pane>
        </el-tabs>

        <!-- ============ 计划编辑对话框 ============ -->
        <el-dialog v-model="planDialog" :title="planForm.id?'编辑计划':'新建计划'" width="820px" top="5vh">
            <el-form :model="planForm" label-width="90px">
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="计划名称" required>
                            <el-input v-model="planForm.name" placeholder="如: 汇聚交换机日常点检" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="负责人">
                            <el-input v-model="planForm.assignee" placeholder="执行人" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="设备类型">
                            <el-radio-group v-model="planForm.device_type" @change="onPlanDeviceTypeChange">
                                <el-radio-button label="switch">交换机</el-radio-button>
                                <el-radio-button label="server">服务器</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="点检范围">
                            <el-radio-group v-model="planForm.target_scope">
                                <el-radio-button label="all">全部</el-radio-button>
                                <el-radio-button label="devices">指定设备</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item v-if="planForm.target_scope==='devices'" label="选择设备">
                    <el-select v-model="planForm.target_ids" multiple filterable style="width:100%"
                               placeholder="选择要点检的设备">
                        <el-option v-for="d in deviceOptions" :key="d.id"
                                   :label="d.name + ' (' + d.ip + ')'" :value="d.id" />
                    </el-select>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="8">
                        <el-form-item label="周期类型">
                            <el-select v-model="planForm.schedule_type" style="width:100%">
                                <el-option label="每日" value="daily" />
                                <el-option label="每周" value="weekly" />
                                <el-option label="每月" value="monthly" />
                                <el-option label="自定义Cron" value="cron" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8" v-if="planForm.schedule_type!=='cron'">
                        <el-form-item label="执行时刻">
                            <el-time-select v-model="planForm.time" start="00:00" step="00:15" end="23:45"
                                            style="width:100%" placeholder="选择时刻" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="8" v-if="planForm.schedule_type==='monthly'">
                        <el-form-item label="每月几号">
                            <el-input v-model="planForm.daysText" placeholder="如 1,15,30" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="8" v-if="planForm.schedule_type==='cron'">
                        <el-form-item label="Cron">
                            <el-input v-model="planForm.cron_expr" placeholder="如 0 8 * * 1-5" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item v-if="planForm.schedule_type==='weekly'" label="星期">
                    <el-checkbox-group v-model="planForm.weekdays">
                        <el-checkbox v-for="(w,i) in weekLabels" :key="i" :label="i+1">{{ w }}</el-checkbox>
                    </el-checkbox-group>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="8">
                        <el-form-item label="完成时限">
                            <el-input-number v-model="planForm.grace_hours" :min="1" :max="168" />
                            <span style="margin-left:6px;color:#909399">小时</span>
                        </el-form-item>
                    </el-col>
                    <el-col :span="8">
                        <el-form-item label="启用">
                            <el-switch v-model="planForm.enabled" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="说明">
                    <el-input v-model="planForm.description" type="textarea" :rows="2" />
                </el-form-item>
            </el-form>

            <el-divider content-position="left">点检项目</el-divider>
            <div style="margin-bottom:8px">
                <el-button size="small" @click="loadTemplate()">载入模板</el-button>
                <el-button size="small" type="primary" @click="addItem()">添加检查项</el-button>
            </div>
            <el-table :data="planForm.items" border size="small" max-height="280">
                <el-table-column label="检查项名称" min-width="160">
                    <template #default="{row}"><el-input v-model="row.name" size="small" /></template>
                </el-table-column>
                <el-table-column label="类型" width="110">
                    <template #default="{row}">
                        <el-select v-model="row.check_type" size="small">
                            <el-option label="正常/异常" value="bool" />
                            <el-option label="数值" value="value" />
                            <el-option label="文本" value="text" />
                        </el-select>
                    </template>
                </el-table-column>
                <el-table-column label="标准/参考" min-width="140">
                    <template #default="{row}"><el-input v-model="row.standard" size="small" /></template>
                </el-table-column>
                <el-table-column label="单位" width="80">
                    <template #default="{row}"><el-input v-model="row.unit" size="small" /></template>
                </el-table-column>
                <el-table-column label="必填" width="70" align="center">
                    <template #default="{row}"><el-switch v-model="row.required" size="small" /></template>
                </el-table-column>
                <el-table-column label="操作" width="70" align="center">
                    <template #default="{ $index }">
                        <el-button size="small" type="danger" text @click="removeItem($index)">删除</el-button>
                    </template>
                </el-table-column>
            </el-table>

            <template #footer>
                <el-button @click="planDialog=false">取消</el-button>
                <el-button type="primary" @click="savePlan">保存</el-button>
            </template>
        </el-dialog>

        <!-- ============ 执行对话框 ============ -->
        <el-dialog v-model="execDialog" title="执行点检" width="760px" top="5vh">
            <el-descriptions :column="2" border size="small" style="margin-bottom:12px">
                <el-descriptions-item label="设备">{{ execTask.device_name }}</el-descriptions-item>
                <el-descriptions-item label="计划">{{ execTask.plan_name }}</el-descriptions-item>
                <el-descriptions-item label="计划时间">{{ execTask.planned_time }}</el-descriptions-item>
                <el-descriptions-item label="截止时间">{{ execTask.due_time }}</el-descriptions-item>
            </el-descriptions>
            <el-form :inline="true" style="margin-bottom:8px">
                <el-form-item label="执行人">
                    <el-input v-model="execForm.operator" style="width:160px" />
                </el-form-item>
            </el-form>
            <el-table :data="execForm.records" border size="small" max-height="360">
                <el-table-column prop="item_name" label="检查项" min-width="150" show-overflow-tooltip />
                <el-table-column prop="standard" label="标准" width="120" show-overflow-tooltip />
                <el-table-column label="实测/描述" min-width="140">
                    <template #default="{row}"><el-input v-model="row.value" size="small" /></template>
                </el-table-column>
                <el-table-column label="结果" width="210">
                    <template #default="{row}">
                        <el-radio-group v-model="row.result" size="small">
                            <el-radio-button label="normal">正常</el-radio-button>
                            <el-radio-button label="abnormal">异常</el-radio-button>
                            <el-radio-button label="na">不适用</el-radio-button>
                        </el-radio-group>
                    </template>
                </el-table-column>
                <el-table-column label="备注" min-width="120">
                    <template #default="{row}"><el-input v-model="row.remark" size="small" /></template>
                </el-table-column>
            </el-table>
            <el-form-item label="总体备注" style="margin-top:12px">
                <el-input v-model="execForm.remark" type="textarea" :rows="2" />
            </el-form-item>
            <template #footer>
                <el-button @click="execDialog=false">取消</el-button>
                <el-button type="primary" @click="submitExec">提交点检结果</el-button>
            </template>
        </el-dialog>

        <!-- ============ 明细查看对话框 ============ -->
        <el-dialog v-model="viewDialog" title="点检明细" width="720px" top="6vh">
            <el-descriptions :column="2" border size="small" style="margin-bottom:12px">
                <el-descriptions-item label="设备">{{ viewTaskData.device_name }}</el-descriptions-item>
                <el-descriptions-item label="计划">{{ viewTaskData.plan_name }}</el-descriptions-item>
                <el-descriptions-item label="完成时间">{{ viewTaskData.finished_at }}</el-descriptions-item>
                <el-descriptions-item label="执行人">{{ viewTaskData.operator }}</el-descriptions-item>
            </el-descriptions>
            <el-table :data="viewTaskData.records" border size="small">
                <el-table-column prop="item_name" label="检查项" min-width="150" />
                <el-table-column prop="standard" label="标准" width="120" />
                <el-table-column prop="value" label="实测/描述" min-width="130" />
                <el-table-column label="结果" width="90">
                    <template #default="{row}">
                        <el-tag size="small" :type="resultTagType(row.result)">{{ resultLabel(row.result) }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="remark" label="备注" min-width="120" />
            </el-table>
        </el-dialog>
    </div>
    `,
    setup() {
        const activeTab = Vue.ref('plans');
        const pageSize = 50;

        // 统计
        const stats = Vue.ref({});
        const statCards = Vue.computed(() => [
            { key: 'todo', label: '待处理', cls: 'warning', value: (stats.value.tasks || {}).todo || 0 },
            { key: 'overdue', label: '已逾期', cls: 'danger', value: (stats.value.tasks || {}).overdue || 0 },
            { key: 'today', label: '今日完成', cls: 'success', value: stats.value.completed_today || 0 },
            { key: 'abn', label: '今日异常', cls: 'danger', value: stats.value.abnormal_today || 0 },
            { key: 'plans', label: '启用计划', cls: 'primary', value: stats.value.plans_enabled || 0 },
            { key: 'rate', label: '完成率%', cls: '', value: stats.value.completion_rate || 0 },
        ]);

        // 计划
        const plans = Vue.ref([]);
        const planLoading = Vue.ref(false);
        const planKeyword = Vue.ref('');
        const planDialog = Vue.ref(false);
        const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
        const emptyPlan = () => ({
            id: null, name: '', description: '', device_type: 'switch', target_scope: 'all',
            target_ids: [], schedule_type: 'daily', time: '08:00', weekdays: [1],
            daysText: '1', cron_expr: '', assignee: '', grace_hours: 12, enabled: true, items: [],
        });
        const planForm = Vue.ref(emptyPlan());

        // 任务
        const tasks = Vue.ref([]);
        const taskLoading = Vue.ref(false);
        const taskStatus = Vue.ref('todo');
        const taskDateRange = Vue.ref(null);
        const taskPage = Vue.ref(1);
        const taskTotal = Vue.ref(0);

        // 履历
        const history = Vue.ref([]);
        const histLoading = Vue.ref(false);
        const histDevice = Vue.ref('');
        const histResult = Vue.ref('');
        const histDateRange = Vue.ref(null);
        const histPage = Vue.ref(1);
        const histTotal = Vue.ref(0);

        // 设备选项
        const deviceOptions = Vue.ref([]);

        // 执行
        const execDialog = Vue.ref(false);
        const execTask = Vue.ref({});
        const execForm = Vue.ref({ operator: 'admin', remark: '', records: [] });

        // 查看
        const viewDialog = Vue.ref(false);
        const viewTaskData = Vue.ref({ records: [] });

        const scheduleText = (row) => {
            const sv = row.schedule_value || {};
            const t = sv.time || '08:00';
            if (row.schedule_type === 'daily') return `每日 ${t}`;
            if (row.schedule_type === 'weekly') {
                const days = (sv.weekdays || []).map(d => weekLabels[d - 1]).join('、');
                return `每周 ${days} ${t}`;
            }
            if (row.schedule_type === 'monthly') {
                return `每月 ${(sv.days || []).join('、')} 日 ${t}`;
            }
            return `Cron: ${row.cron_expr}`;
        };

        const taskStatusType = (s) => ({ pending: 'info', in_progress: 'warning', overdue: 'danger', completed: 'success', skipped: 'info' }[s] || 'info');
        const taskStatusLabel = (s) => ({ pending: '待执行', in_progress: '进行中', overdue: '已逾期', completed: '已完成', skipped: '已跳过' }[s] || s);
        const resultLabel = (r) => ({ normal: '正常', abnormal: '异常', na: '不适用' }[r] || '-');
        const resultTagType = (r) => ({ normal: 'success', abnormal: 'danger', na: 'info' }[r] || 'info');
        const histRowClass = ({ row }) => (row.result === 'abnormal' ? 'el-table__row--critical' : '');

        const fetchStats = async () => {
            try {
                const resp = await axios.get('/api/inspection/stats');
                stats.value = resp.data;
            } catch (e) { /* ignore */ }
        };

        const fetchDevices = async (deviceType) => {
            try {
                const params = deviceType ? { device_type: deviceType } : {};
                const resp = await axios.get('/api/inspection/devices', { params });
                deviceOptions.value = resp.data.devices || [];
            } catch (e) { /* ignore */ }
        };

        const fetchPlans = async () => {
            planLoading.value = true;
            try {
                const resp = await axios.get('/api/inspection/plans', { params: { keyword: planKeyword.value } });
                plans.value = resp.data.items || [];
            } catch (e) {
                ElementPlus.ElMessage.error('获取计划失败');
            } finally {
                planLoading.value = false;
            }
        };

        const openPlanDialog = async (row) => {
            await fetchDevices(null);
            if (!row) {
                planForm.value = emptyPlan();
                planDialog.value = true;
                return;
            }
            try {
                const resp = await axios.get('/api/inspection/plans/' + row.id);
                const d = resp.data;
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
                    items: (d.items || []).map(it => ({
                        name: it.name, check_type: it.check_type, standard: it.standard,
                        unit: it.unit, required: it.required, sort_order: it.sort_order,
                    })),
                };
                planDialog.value = true;
            } catch (e) {
                ElementPlus.ElMessage.error('加载计划失败');
            }
        };

        const onPlanDeviceTypeChange = () => { planForm.value.target_ids = []; fetchDevices(planForm.value.device_type); };
        const addItem = () => { planForm.value.items.push({ name: '', check_type: 'bool', standard: '', unit: '', required: true, sort_order: planForm.value.items.length }); };
        const removeItem = (idx) => { planForm.value.items.splice(idx, 1); };

        const loadTemplate = async () => {
            try {
                const resp = await axios.get('/api/inspection/templates');
                const tpl = resp.data.templates[planForm.value.device_type];
                if (!tpl) { ElementPlus.ElMessage.warning('无可用模板'); return; }
                planForm.value.items = tpl.items.map((it, i) => ({
                    name: it.name, check_type: it.check_type, standard: it.standard || '',
                    unit: it.unit || '', required: it.required !== false, sort_order: i,
                }));
                ElementPlus.ElMessage.success('已载入模板: ' + tpl.name);
            } catch (e) {
                ElementPlus.ElMessage.error('载入模板失败');
            }
        };

        const savePlan = async () => {
            const f = planForm.value;
            if (!f.name) { ElementPlus.ElMessage.warning('请填写计划名称'); return; }
            if (f.target_scope === 'devices' && (!f.target_ids || !f.target_ids.length)) {
                ElementPlus.ElMessage.warning('请选择点检设备'); return;
            }
            if (f.schedule_type === 'cron' && !f.cron_expr) {
                ElementPlus.ElMessage.warning('请填写 cron 表达式'); return;
            }
            const sv = {};
            if (f.schedule_type === 'weekly') sv.weekdays = f.weekdays;
            if (f.schedule_type === 'monthly') sv.days = (f.daysText || '1').split(',').map(s => parseInt(s.trim())).filter(n => n > 0);
            if (f.schedule_type !== 'cron') sv.time = f.time || '08:00';
            const payload = {
                name: f.name, description: f.description, device_type: f.device_type,
                target_scope: f.target_scope, target_ids: f.target_ids,
                schedule_type: f.schedule_type, schedule_value: sv, cron_expr: f.cron_expr,
                assignee: f.assignee, grace_hours: f.grace_hours, enabled: f.enabled,
                items: f.items.filter(it => it.name).map((it, i) => ({
                    name: it.name, check_type: it.check_type, standard: it.standard || '',
                    unit: it.unit || '', required: !!it.required, sort_order: i,
                })),
            };
            try {
                if (f.id) await axios.put('/api/inspection/plans/' + f.id, payload);
                else await axios.post('/api/inspection/plans', payload);
                ElementPlus.ElMessage.success('保存成功');
                planDialog.value = false;
                fetchPlans();
            } catch (e) {
                ElementPlus.ElMessage.error('保存失败: ' + (e.response?.data?.detail || e.message));
            }
        };

        const togglePlan = async (row) => {
            try { await axios.post('/api/inspection/plans/' + row.id + '/toggle'); fetchPlans(); }
            catch (e) { ElementPlus.ElMessage.error('操作失败'); }
        };

        const deletePlan = async (row) => {
            try {
                await ElementPlus.ElMessageBox.confirm('确定删除计划「' + row.name + '」?', '提示', { type: 'warning' });
            } catch (e) { return; }
            try {
                await axios.delete('/api/inspection/plans/' + row.id);
                ElementPlus.ElMessage.success('已删除');
                fetchPlans();
            } catch (e) { ElementPlus.ElMessage.error('删除失败'); }
        };

        const runNow = async (row) => {
            try {
                const resp = await axios.post('/api/inspection/plans/' + row.id + '/run-now');
                ElementPlus.ElMessage.success('已生成 ' + resp.data.created + ' 条点检任务');
                fetchStats();
            } catch (e) { ElementPlus.ElMessage.error('生成失败'); }
        };

        const fetchTasks = async () => {
            taskLoading.value = true;
            try {
                const params = { page: taskPage.value, page_size: pageSize };
                if (taskStatus.value === 'todo') params.status = '';
                else params.status = taskStatus.value;
                if (taskDateRange.value && taskDateRange.value.length === 2) {
                    params.date_from = taskDateRange.value[0];
                    params.date_to = taskDateRange.value[1];
                }
                let resp;
                if (taskStatus.value === 'todo') {
                    // 待处理 = 待执行+进行中+逾期 (取全部后前端合并, 简化: 依次查询亦可)
                    resp = await axios.get('/api/inspection/tasks', { params: { ...params, status: 'all' } });
                    resp.data.items = (resp.data.items || []).filter(t => ['pending', 'in_progress', 'overdue'].includes(t.status));
                } else {
                    resp = await axios.get('/api/inspection/tasks', { params });
                }
                tasks.value = resp.data.items || [];
                taskTotal.value = resp.data.total || 0;
            } catch (e) {
                ElementPlus.ElMessage.error('获取任务失败');
            } finally {
                taskLoading.value = false;
            }
        };

        const startTask = async (row) => {
            try { await axios.post('/api/inspection/tasks/' + row.id + '/start', { operator: 'admin' }); fetchTasks(); fetchStats(); }
            catch (e) { ElementPlus.ElMessage.error('操作失败'); }
        };

        const skipTask = async (row) => {
            let reason = '';
            try {
                const r = await ElementPlus.ElMessageBox.prompt('请输入跳过原因', '跳过点检', { inputPlaceholder: '原因' });
                reason = r.value;
            } catch (e) { return; }
            try {
                await axios.post('/api/inspection/tasks/' + row.id + '/skip', { operator: 'admin', reason });
                ElementPlus.ElMessage.success('已跳过');
                fetchTasks(); fetchStats();
            } catch (e) { ElementPlus.ElMessage.error('操作失败'); }
        };

        const openExec = async (row) => {
            try {
                const resp = await axios.get('/api/inspection/tasks/' + row.id);
                const d = resp.data;
                execTask.value = d;
                execForm.value = {
                    operator: 'admin', remark: '',
                    records: (d.items || []).map(it => ({
                        item_name: it.name, check_type: it.check_type, standard: it.standard || '',
                        value: '', result: 'normal', remark: '',
                    })),
                };
                execDialog.value = true;
            } catch (e) {
                ElementPlus.ElMessage.error('加载任务失败');
            }
        };

        const submitExec = async () => {
            try {
                const resp = await axios.post('/api/inspection/tasks/' + execTask.value.id + '/execute', execForm.value);
                ElementPlus.ElMessage.success(resp.data.result === 'abnormal' ? '提交成功（存在异常项）' : '提交成功');
                execDialog.value = false;
                fetchTasks(); fetchStats(); fetchHistory();
            } catch (e) {
                ElementPlus.ElMessage.error('提交失败: ' + (e.response?.data?.detail || e.message));
            }
        };

        const viewTask = async (row) => {
            try {
                const resp = await axios.get('/api/inspection/tasks/' + row.id);
                viewTaskData.value = resp.data;
                viewDialog.value = true;
            } catch (e) { ElementPlus.ElMessage.error('加载失败'); }
        };

        const fetchHistory = async () => {
            histLoading.value = true;
            try {
                const params = { page: histPage.value, page_size: pageSize };
                if (histDevice.value) params.device_id = histDevice.value;
                if (histResult.value) params.result = histResult.value;
                if (histDateRange.value && histDateRange.value.length === 2) {
                    params.date_from = histDateRange.value[0];
                    params.date_to = histDateRange.value[1];
                }
                const resp = await axios.get('/api/inspection/history', { params });
                history.value = resp.data.items || [];
                histTotal.value = resp.data.total || 0;
            } catch (e) {
                ElementPlus.ElMessage.error('获取履历失败');
            } finally {
                histLoading.value = false;
            }
        };

        const onTabChange = (name) => {
            if (name === 'tasks') fetchTasks();
            else if (name === 'history') fetchHistory();
            else fetchPlans();
        };

        Vue.onMounted(() => {
            fetchStats();
            fetchPlans();
            fetchDevices(null);
            const timer = setInterval(fetchStats, 30000);
            Vue.onUnmounted(() => clearInterval(timer));
        });

        return {
            activeTab, pageSize,
            stats, statCards,
            plans, planLoading, planKeyword, planDialog, planForm, weekLabels,
            tasks, taskLoading, taskStatus, taskDateRange, taskPage, taskTotal,
            history, histLoading, histDevice, histResult, histDateRange, histPage, histTotal,
            deviceOptions,
            execDialog, execTask, execForm, viewDialog, viewTaskData,
            scheduleText, taskStatusType, taskStatusLabel, resultLabel, resultTagType, histRowClass,
            fetchPlans, openPlanDialog, onPlanDeviceTypeChange, addItem, removeItem, loadTemplate,
            savePlan, togglePlan, deletePlan, runNow,
            fetchTasks, startTask, skipTask, openExec, submitExec, viewTask, fetchHistory, onTabChange,
        };
    }
};
