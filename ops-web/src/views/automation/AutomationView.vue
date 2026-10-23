<template>
    <div class="automation">
        <div class="page-title">自动化配置</div>

        <el-alert v-if="!auth.canWrite" type="info" :closable="false" class="mb-16"
                  title="当前角色只能查看审计日志，配置下发需要 OPERATOR 及以上权限"/>

        <el-tabs v-model="tab">
            <!-- ==================== VLAN 管理 ==================== -->
            <el-tab-pane label="VLAN 管理" name="vlan">
                <el-row :gutter="16">
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">创建 / 删除 VLAN</div>
                            <el-form :model="vlanForm" label-width="100px">
                                <el-form-item label="交换机">
                                    <el-select v-model="vlanForm.switch_id" placeholder="选择交换机" filterable
                                               style="width:100%">
                                        <el-option v-for="sw in switchOptions" :key="sw.id"
                                                   :label="`${sw.name} (${sw.ip})`" :value="sw.id"/>
                                    </el-select>
                                </el-form-item>
                                <el-form-item label="VLAN ID">
                                    <el-input-number v-model="vlanForm.vlan_id" :min="1" :max="4094"/>
                                </el-form-item>
                                <el-form-item label="VLAN 名称">
                                    <el-input v-model="vlanForm.vlan_name" placeholder="例: Management"/>
                                </el-form-item>
                                <el-form-item label="描述">
                                    <el-input v-model="vlanForm.description" placeholder="可选"/>
                                </el-form-item>
                                <el-form-item label="操作人">
                                    <el-input v-model="vlanForm.operator" style="width:160px"/>
                                </el-form-item>
                                <el-form-item>
                                    <el-button type="primary" :loading="executing" :disabled="!auth.canWrite"
                                               @click="createVlan">创建 VLAN</el-button>
                                    <el-button type="danger" plain :loading="executing" :disabled="!auth.canWrite"
                                               @click="deleteVlan">删除 VLAN</el-button>
                                </el-form-item>
                            </el-form>
                        </div>
                    </el-col>
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">VLAN 规划（运行参数 vlan_plan）</div>
                            <el-table :data="vlanPlan" size="small" max-height="260">
                                <el-table-column prop="vlan_id" label="VLAN ID" width="100"/>
                                <el-table-column prop="name" label="名称" width="140"/>
                                <el-table-column prop="description" label="描述" show-overflow-tooltip/>
                                <el-table-column prop="subnet" label="子网" width="160"/>
                            </el-table>
                            <div class="mt-8">
                                <el-select v-model="batchSwitchId" placeholder="选择交换机" filterable style="width:240px">
                                    <el-option v-for="sw in switchOptions" :key="sw.id"
                                               :label="`${sw.name} (${sw.ip})`" :value="sw.id"/>
                                </el-select>
                                <el-button type="success" :loading="executing" :disabled="!batchSwitchId || !auth.canWrite"
                                           style="margin-left:8px" @click="batchCreateVlans">
                                    一键下发 VLAN 规划
                                </el-button>
                            </div>
                        </div>
                    </el-col>
                </el-row>
                <ExecResult :result="vlanResult"/>
            </el-tab-pane>

            <!-- ==================== 端口管理 ==================== -->
            <el-tab-pane label="端口管理" name="port">
                <div class="chart-card">
                    <div class="chart-title">端口模式配置</div>
                    <el-form :inline="true" :model="portForm">
                        <el-form-item label="交换机">
                            <el-select v-model="portForm.switch_id" placeholder="选择交换机" filterable style="width:220px">
                                <el-option v-for="sw in switchOptions" :key="sw.id"
                                           :label="`${sw.name} (${sw.ip})`" :value="sw.id"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="端口">
                            <el-select v-model="portForm.port_name" placeholder="选择端口" filterable allow-create
                                       default-first-option style="width:180px" @visible-change="loadPortNames">
                                <el-option v-for="p in portNames" :key="p" :label="p" :value="p"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="模式">
                            <el-radio-group v-model="portMode">
                                <el-radio-button value="access">Access</el-radio-button>
                                <el-radio-button value="trunk">Trunk</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                        <el-form-item v-if="portMode === 'access'" label="VLAN ID">
                            <el-input-number v-model="portForm.vlan_id" :min="1" :max="4094"/>
                        </el-form-item>
                        <el-form-item v-else label="允许 VLAN">
                            <el-input v-model="portForm.allowed_vlans_str" placeholder="10,20,30" style="width:200px"/>
                        </el-form-item>
                        <el-form-item>
                            <el-button type="primary" :loading="executing" :disabled="!auth.canWrite" @click="setPortMode">
                                执行
                            </el-button>
                        </el-form-item>
                    </el-form>
                </div>

                <div class="chart-card mt-16">
                    <div class="chart-title">端口启停</div>
                    <el-form :inline="true" :model="toggleForm">
                        <el-form-item label="交换机">
                            <el-select v-model="toggleForm.switch_id" placeholder="选择交换机" filterable style="width:220px">
                                <el-option v-for="sw in switchOptions" :key="sw.id"
                                           :label="`${sw.name} (${sw.ip})`" :value="sw.id"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="端口">
                            <el-select v-model="toggleForm.port_name" placeholder="选择端口" filterable allow-create
                                       default-first-option style="width:180px" @visible-change="loadTogglePortNames">
                                <el-option v-for="p in togglePortNames" :key="p" :label="p" :value="p"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="动作">
                            <el-radio-group v-model="toggleForm.enabled">
                                <el-radio-button :value="true">启用</el-radio-button>
                                <el-radio-button :value="false">关闭</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                        <el-form-item>
                            <el-button type="warning" :loading="executing" :disabled="!auth.canWrite" @click="togglePort">
                                下发
                            </el-button>
                        </el-form-item>
                    </el-form>
                    <div class="text-muted">端口名可直接输入 (如 port1、gi1/0/1)，下拉来自最近一次采集的端口表</div>
                </div>
                <ExecResult :result="portResult"/>
            </el-tab-pane>

            <!-- ==================== 原始命令 ==================== -->
            <el-tab-pane label="原始命令" name="raw">
                <div class="chart-card">
                    <div class="chart-title">自定义 CLI 下发</div>
                    <el-form :model="rawForm" label-width="90px">
                        <el-form-item label="交换机">
                            <el-select v-model="rawForm.switch_id" placeholder="选择交换机" filterable style="width:320px">
                                <el-option v-for="sw in switchOptions" :key="sw.id"
                                           :label="`${sw.name} (${sw.ip})`" :value="sw.id"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="命令列表">
                            <el-input v-model="rawCommandsText" type="textarea" :rows="8"
                                      placeholder="每行一条配置命令，系统自动进入配置模式并在结束时保存"/>
                        </el-form-item>
                        <el-form-item>
                            <el-button type="danger" :loading="executing" :disabled="!auth.canWrite" @click="sendRaw">
                                执行下发
                            </el-button>
                        </el-form-item>
                    </el-form>
                    <div class="text-muted">
                        原始命令绕过模板校验，直接写入设备配置，请确认命令正确后再执行；所有下发都会进入审计日志
                    </div>
                </div>
                <ExecResult :result="rawResult"/>
            </el-tab-pane>

            <!-- ==================== 审计日志 ==================== -->
            <el-tab-pane label="审计日志" name="audit">
                <div class="toolbar">
                    <el-select v-model="auditSwitchId" placeholder="筛选交换机" clearable style="width:240px"
                               @change="onAuditFilterChange">
                        <el-option v-for="sw in switchOptions" :key="sw.id" :label="sw.name" :value="sw.id"/>
                    </el-select>
                    <el-button @click="fetchAuditLogs">刷新</el-button>
                </div>
                <el-table :data="auditLogs" size="small" v-loading="loading.audit">
                    <el-table-column prop="executed_at" label="时间" width="170"/>
                    <el-table-column prop="switch_name" label="交换机" width="150" show-overflow-tooltip/>
                    <el-table-column prop="switch_ip" label="IP" width="130"/>
                    <el-table-column prop="operation_type" label="类型" width="90">
                        <template #default="{ row }"><el-tag size="small">{{ row.operation_type }}</el-tag></template>
                    </el-table-column>
                    <el-table-column prop="operation_desc" label="描述" min-width="200" show-overflow-tooltip/>
                    <el-table-column prop="operator" label="操作人" width="100"/>
                    <el-table-column label="结果" width="90">
                        <template #default="{ row }">
                            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
                                {{ row.status }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column label="耗时" width="90">
                        <template #default="{ row }">{{ row.duration_ms }}ms</template>
                    </el-table-column>
                    <el-table-column label="操作" width="80" align="center">
                        <template #default="{ row }">
                            <el-button link type="primary" @click="openAudit(row)">明细</el-button>
                        </template>
                    </el-table-column>
                </el-table>
                <el-pagination class="mt-8" style="justify-content:flex-end" layout="total, prev, pager, next"
                               :total="auditTotal" :page-size="auditPageSize" :current-page="auditPage"
                               @current-change="(p) => { auditPage = p; fetchAuditLogs(); }"/>
            </el-tab-pane>
        </el-tabs>

        <!-- 审计明细 -->
        <el-dialog v-model="auditDialog" title="下发明细" width="760px" top="6vh">
            <el-descriptions :column="2" border size="small" class="mb-8">
                <el-descriptions-item label="交换机">{{ auditDetail.switch_name }}</el-descriptions-item>
                <el-descriptions-item label="IP">{{ auditDetail.switch_ip }}</el-descriptions-item>
                <el-descriptions-item label="时间">{{ auditDetail.executed_at }}</el-descriptions-item>
                <el-descriptions-item label="操作人">{{ auditDetail.operator }}</el-descriptions-item>
                <el-descriptions-item label="类型">{{ auditDetail.operation_type }}</el-descriptions-item>
                <el-descriptions-item label="结果">{{ auditDetail.status }}</el-descriptions-item>
                <el-descriptions-item label="描述" :span="2">{{ auditDetail.operation_desc }}</el-descriptions-item>
            </el-descriptions>
            <div class="drawer-section-title">下发命令</div>
            <div class="output-block">{{ auditDetail.commands_sent || '(无)' }}</div>
            <div class="drawer-section-title">设备回显</div>
            <div class="output-block">{{ auditDetail.commands_output || '(无)' }}</div>
            <template v-if="auditDetail.error_message">
                <div class="drawer-section-title">错误信息</div>
                <div class="output-block">{{ auditDetail.error_message }}</div>
            </template>
            <div class="drawer-section-title">参数</div>
            <div class="output-block">{{ JSON.stringify(auditDetail.parameters || {}, null, 2) }}</div>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * 自动化配置页: 迁移自 legacy frontend/js/automation.js, 补齐迁移前只有接口没有界面的
 * 端口启停 / 原始命令 / 审计明细三项. 下发是同步等待设备回显的长耗时操作, 按钮一律带 loading.
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import ExecResult from '../../components/ExecResult.vue';
import * as automationApi from '../../api/automation';
import * as switchesApi from '../../api/switches';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();

const tab = ref('vlan');
const executing = ref(false);
const loading = reactive({ audit: false });

const switchOptions = ref([]);
const vlanPlan = ref([]);

const vlanForm = ref({ switch_id: '', vlan_id: 10, vlan_name: '', description: '', operator: '' });
const batchSwitchId = ref('');

const portForm = ref({ switch_id: '', port_name: '', vlan_id: 1, allowed_vlans_str: '' });
const portMode = ref('access');
const portNames = ref([]);

const toggleForm = ref({ switch_id: '', port_name: '', enabled: true });
const togglePortNames = ref([]);

const rawForm = ref({ switch_id: '' });
const rawCommandsText = ref('');

const vlanResult = ref(null);
const portResult = ref(null);
const rawResult = ref(null);

const auditLogs = ref([]);
const auditPage = ref(1);
const auditPageSize = 50;
const auditTotal = ref(0);
const auditSwitchId = ref('');
const auditDialog = ref(false);
const auditDetail = ref({});

function requireSwitch(switchId) {
    if (!switchId) {
        ElMessage.warning('请选择交换机');
        return false;
    }
    return true;
}

async function fetchSwitches() {
    try {
        const data = await switchesApi.list({ page: 1, pageSize: 500 });
        switchOptions.value = data.items || [];
    } catch (e) { /* ignore */ }
}

async function fetchVlanPlan() {
    try {
        const data = await automationApi.vlanPlan();
        vlanPlan.value = data.vlan_plan || [];
    } catch (e) { /* ignore */ }
}

async function createVlan() {
    if (!requireSwitch(vlanForm.value.switch_id)) return;
    executing.value = true;
    try {
        vlanResult.value = await automationApi.vlanCreate({ ...vlanForm.value });
        reportResult(vlanResult.value, 'VLAN 创建成功');
    } finally {
        executing.value = false;
    }
}

async function deleteVlan() {
    if (!requireSwitch(vlanForm.value.switch_id)) return;
    const f = vlanForm.value;
    try {
        await ElMessageBox.confirm(`确定在设备上删除 VLAN ${f.vlan_id}?`, '危险操作', { type: 'warning' });
    } catch (e) {
        return;
    }
    executing.value = true;
    try {
        vlanResult.value = await automationApi.vlanDelete({
            switch_id: f.switch_id, vlan_id: f.vlan_id, operator: f.operator,
        });
        reportResult(vlanResult.value, 'VLAN 已删除');
    } finally {
        executing.value = false;
    }
}

async function batchCreateVlans() {
    if (!requireSwitch(batchSwitchId.value)) return;
    executing.value = true;
    try {
        vlanResult.value = await automationApi.vlanBatch({
            switch_id: batchSwitchId.value, use_vlan_plan: true, operator: vlanForm.value.operator,
        });
        reportResult(vlanResult.value, 'VLAN 规划下发成功');
    } finally {
        executing.value = false;
    }
}

async function setPortMode() {
    const f = portForm.value;
    if (!requireSwitch(f.switch_id) || !f.port_name) return;
    executing.value = true;
    try {
        if (portMode.value === 'access') {
            portResult.value = await automationApi.portAccess({
                switch_id: f.switch_id, port_name: f.port_name, vlan_id: f.vlan_id,
            });
        } else {
            portResult.value = await automationApi.portTrunk({
                switch_id: f.switch_id, port_name: f.port_name, native_vlan: 1,
                allowed_vlans: f.allowed_vlans_str.split(',')
                    .map((n) => Number(n.trim())).filter((n) => n > 0),
            });
        }
        reportResult(portResult.value, '端口配置成功');
    } finally {
        executing.value = false;
    }
}

async function togglePort() {
    const f = toggleForm.value;
    if (!requireSwitch(f.switch_id) || !f.port_name) return;
    executing.value = true;
    try {
        portResult.value = await automationApi.portToggle({
            switch_id: f.switch_id, port_name: f.port_name, enabled: f.enabled,
        });
        reportResult(portResult.value, `端口已${f.enabled ? '启用' : '关闭'}`);
    } finally {
        executing.value = false;
    }
}

async function sendRaw() {
    const commands = rawCommandsText.value.split('\n').map((s) => s.trim()).filter((s) => s);
    if (!requireSwitch(rawForm.value.switch_id)) return;
    if (!commands.length) return ElMessage.warning('请输入至少一条命令');
    try {
        await ElMessageBox.confirm(`将向设备下发 ${commands.length} 条自定义命令，确认继续?`, '危险操作',
            { type: 'warning' });
    } catch (e) {
        return;
    }
    executing.value = true;
    try {
        rawResult.value = await automationApi.rawCommand({
            switch_id: rawForm.value.switch_id, commands,
        });
        reportResult(rawResult.value, '命令执行完成');
    } finally {
        executing.value = false;
    }
}

function reportResult(r, okText) {
    if (!r) return;
    if (r.status === 'success') ElMessage.success(okText);
    else if (r.status === 'error') ElMessage.error(r.message || r.error || '设备不存在');
    else ElMessage.error(r.error || '执行失败');
}

/** 端口下拉复用采集层写入的端口表, 未采集时留空由用户手输 */
async function loadPortNames(visible) {
    if (!visible || portNames.value.length || !portForm.value.switch_id) return;
    try {
        const data = await switchesApi.ports(portForm.value.switch_id);
        portNames.value = (data.ports || []).map((p) => p.port_name).filter(Boolean);
    } catch (e) { /* ignore */ }
}

async function loadTogglePortNames(visible) {
    if (!visible || togglePortNames.value.length || !toggleForm.value.switch_id) return;
    try {
        const data = await switchesApi.ports(toggleForm.value.switch_id);
        togglePortNames.value = (data.ports || []).map((p) => p.port_name).filter(Boolean);
    } catch (e) { /* ignore */ }
}

function onAuditFilterChange() {
    auditPage.value = 1;
    fetchAuditLogs();
}

async function fetchAuditLogs() {
    loading.audit = true;
    try {
        const data = await automationApi.auditLogs({
            switchId: auditSwitchId.value, page: auditPage.value, pageSize: auditPageSize,
        });
        auditLogs.value = data.items || [];
        auditTotal.value = data.total || 0;
    } catch (e) { /* ignore */ } finally {
        loading.audit = false;
    }
}

async function openAudit(row) {
    auditDetail.value = await automationApi.auditLogDetail(row.id);
    auditDialog.value = true;
}

onMounted(() => {
    vlanForm.value.operator = auth.username;
    fetchSwitches();
    fetchVlanPlan();
    fetchAuditLogs();
});
</script>
