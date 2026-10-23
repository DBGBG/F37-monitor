/**
 * 自动化配置组件 - Automation Page
 * VLAN 配置、端口管理、审计日志
 */
const AutomationPage = {
    template: `
    <div class="automation-page">
        <div class="page-title">自动化配置</div>

        <el-tabs v-model="activeTab">
            <!-- VLAN 配置 -->
            <el-tab-pane label="VLAN 管理" name="vlan">
                <el-row :gutter="16">
                    <el-col :span="12">
                        <div class="chart-card">
                            <div class="chart-title">创建 VLAN</div>
                            <el-form :model="vlanForm" label-width="100px" style="max-width:400px">
                                <el-form-item label="交换机">
                                    <el-select v-model="vlanForm.switch_id" placeholder="选择交换机" filterable>
                                        <el-option v-for="sw in switchOptions" :key="sw.id"
                                                   :label="sw.name + ' (' + sw.ip + ')'" :value="sw.id" />
                                    </el-select>
                                </el-form-item>
                                <el-form-item label="VLAN ID">
                                    <el-input-number v-model="vlanForm.vlan_id" :min="1" :max="4094" />
                                </el-form-item>
                                <el-form-item label="VLAN 名称">
                                    <el-input v-model="vlanForm.vlan_name" placeholder="例: Management" />
                                </el-form-item>
                                <el-form-item label="描述">
                                    <el-input v-model="vlanForm.description" placeholder="可选" />
                                </el-form-item>
                                <el-form-item label="操作人">
                                    <el-input v-model="vlanForm.operator" placeholder="admin" />
                                </el-form-item>
                                <el-form-item>
                                    <el-button type="primary" @click="createVlan" :loading="executing">
                                        创建 VLAN
                                    </el-button>
                                </el-form-item>
                            </el-form>
                        </div>
                    </el-col>
                    <el-col :span="12">
                        <div class="chart-card">
                            <div class="chart-title">VLAN 规划 (配置文件)</div>
                            <el-table :data="vlanPlan" stripe size="small">
                                <el-table-column prop="vlan_id" label="VLAN ID" width="100" />
                                <el-table-column prop="name" label="名称" width="140" />
                                <el-table-column prop="description" label="描述" />
                                <el-table-column prop="subnet" label="子网" width="160" />
                            </el-table>
                            <div style="margin-top:12px">
                                <el-select v-model="batchSwitchId" placeholder="选择交换机" filterable style="width:240px">
                                    <el-option v-for="sw in switchOptions" :key="sw.id"
                                               :label="sw.name + ' (' + sw.ip + ')'" :value="sw.id" />
                                </el-select>
                                <el-button type="success" @click="batchCreateVlans" :loading="executing"
                                           style="margin-left:8px" :disabled="!batchSwitchId">
                                    一键下发 VLAN 规划
                                </el-button>
                            </div>
                        </div>
                    </el-col>
                </el-row>
            </el-tab-pane>

            <!-- 端口配置 -->
            <el-tab-pane label="端口管理" name="port">
                <div class="chart-card">
                    <div class="chart-title">端口模式配置</div>
                    <el-form :inline="true" :model="portForm">
                        <el-form-item label="交换机">
                            <el-select v-model="portForm.switch_id" placeholder="选择交换机" filterable>
                                <el-option v-for="sw in switchOptions" :key="sw.id"
                                           :label="sw.name + ' (' + sw.ip + ')'" :value="sw.id" />
                            </el-select>
                        </el-form-item>
                        <el-form-item label="端口">
                            <el-input v-model="portForm.port_name" placeholder="例: port1" style="width:120px" />
                        </el-form-item>
                        <el-form-item label="模式">
                            <el-radio-group v-model="portMode">
                                <el-radio-button label="access">Access</el-radio-button>
                                <el-radio-button label="trunk">Trunk</el-radio-button>
                            </el-radio-group>
                        </el-form-item>
                        <el-form-item v-if="portMode === 'access'" label="VLAN ID">
                            <el-input-number v-model="portForm.vlan_id" :min="1" :max="4094" />
                        </el-form-item>
                        <el-form-item v-if="portMode === 'trunk'" label="允许 VLAN">
                            <el-input v-model="portForm.allowed_vlans_str" placeholder="10,20,30" style="width:200px" />
                        </el-form-item>
                        <el-form-item>
                            <el-button type="primary" @click="setPortMode" :loading="executing">执行</el-button>
                        </el-form-item>
                    </el-form>
                </div>
            </el-tab-pane>

            <!-- 审计日志 -->
            <el-tab-pane label="审计日志" name="audit">
                <div class="toolbar">
                    <el-select v-model="auditSwitchId" placeholder="筛选交换机" clearable style="width:240px"
                               @change="fetchAuditLogs">
                        <el-option v-for="sw in switchOptions" :key="sw.id"
                                   :label="sw.name" :value="sw.id" />
                    </el-select>
                    <el-button @click="fetchAuditLogs">刷新</el-button>
                </div>
                <el-table :data="auditLogs" stripe border v-loading="auditLoading">
                    <el-table-column prop="executed_at" label="时间" width="170" />
                    <el-table-column prop="switch_name" label="交换机" width="150" />
                    <el-table-column prop="switch_ip" label="IP" width="130" />
                    <el-table-column prop="operation_type" label="类型" width="80">
                        <template #default="{ row }">
                            <el-tag size="small">{{ row.operation_type }}</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="operation_desc" label="描述" min-width="200" show-overflow-tooltip />
                    <el-table-column prop="operator" label="操作人" width="90" />
                    <el-table-column label="结果" width="80">
                        <template #default="{ row }">
                            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
                                {{ row.status }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="duration_ms" label="耗时" width="80">
                        <template #default="{ row }">{{ row.duration_ms }}ms</template>
                    </el-table-column>
                </el-table>
                <div style="margin-top:16px;display:flex;justify-content:flex-end">
                    <el-pagination
                        v-model:current-page="auditPage"
                        :page-size="50"
                        :total="auditTotal"
                        layout="prev, pager, next"
                        @current-change="fetchAuditLogs"
                    />
                </div>
            </el-tab-pane>
        </el-tabs>
    </div>
    `,
    setup() {
        const activeTab = Vue.ref('vlan');
        const switchOptions = Vue.ref([]);
        const executing = Vue.ref(false);

        // VLAN 表单
        const vlanForm = Vue.ref({
            switch_id: '', vlan_id: 10, vlan_name: '', description: '', operator: 'admin'
        });

        // 端口表单
        const portForm = Vue.ref({
            switch_id: '', port_name: '', vlan_id: 1, allowed_vlans_str: ''
        });
        const portMode = Vue.ref('access');

        // VLAN 规划
        const vlanPlan = Vue.ref([]);
        const batchSwitchId = Vue.ref('');

        // 审计日志
        const auditLogs = Vue.ref([]);
        const auditLoading = Vue.ref(false);
        const auditPage = Vue.ref(1);
        const auditTotal = Vue.ref(0);
        const auditSwitchId = Vue.ref('');

        const fetchSwitches = async () => {
            try {
                const resp = await axios.get('/api/switches/list', { params: { page: 1, page_size: 500 } });
                switchOptions.value = resp.data.items || [];
            } catch (e) { /* ignore */ }
        };

        const fetchVlanPlan = async () => {
            try {
                const resp = await axios.get('/api/automation/vlan-plan');
                vlanPlan.value = resp.data.vlan_plan || [];
            } catch (e) { /* ignore */ }
        };

        const createVlan = async () => {
            if (!vlanForm.value.switch_id) return ElementPlus.ElMessage.warning('请选择交换机');
            executing.value = true;
            try {
                const resp = await axios.post('/api/automation/vlan/create', vlanForm.value);
                if (resp.data.status === 'success') {
                    ElementPlus.ElMessage.success('VLAN 创建成功');
                } else {
                    ElementPlus.ElMessage.error('执行失败: ' + (resp.data.error || resp.data.message || ''));
                }
            } catch (e) {
                ElementPlus.ElMessage.error('请求失败');
            } finally {
                executing.value = false;
            }
        };

        const batchCreateVlans = async () => {
            executing.value = true;
            try {
                const resp = await axios.post('/api/automation/vlan/batch', {
                    switch_id: batchSwitchId.value, use_vlan_plan: true, operator: 'admin'
                });
                if (resp.data.status === 'success') {
                    ElementPlus.ElMessage.success('VLAN 规划下发成功');
                } else {
                    ElementPlus.ElMessage.error('执行失败: ' + (resp.data.error || ''));
                }
            } catch (e) {
                ElementPlus.ElMessage.error('请求失败');
            } finally {
                executing.value = false;
            }
        };

        const setPortMode = async () => {
            if (!portForm.value.switch_id || !portForm.value.port_name) {
                return ElementPlus.ElMessage.warning('请选择交换机并输入端口名');
            }
            executing.value = true;
            try {
                let url, body;
                if (portMode.value === 'access') {
                    url = '/api/automation/port/access';
                    body = {
                        switch_id: portForm.value.switch_id,
                        port_name: portForm.value.port_name,
                        vlan_id: portForm.value.vlan_id,
                        operator: 'admin'
                    };
                } else {
                    url = '/api/automation/port/trunk';
                    body = {
                        switch_id: portForm.value.switch_id,
                        port_name: portForm.value.port_name,
                        allowed_vlans: portForm.value.allowed_vlans_str.split(',').map(Number).filter(n => n > 0),
                        native_vlan: 1,
                        operator: 'admin'
                    };
                }
                const resp = await axios.post(url, body);
                if (resp.data.status === 'success') {
                    ElementPlus.ElMessage.success('端口配置成功');
                } else {
                    ElementPlus.ElMessage.error('执行失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('请求失败');
            } finally {
                executing.value = false;
            }
        };

        const fetchAuditLogs = async () => {
            auditLoading.value = true;
            try {
                const resp = await axios.get('/api/automation/audit-logs', {
                    params: { switch_id: auditSwitchId.value || undefined, page: auditPage.value }
                });
                auditLogs.value = resp.data.items || [];
                auditTotal.value = resp.data.total || 0;
            } catch (e) { /* ignore */ }
            finally { auditLoading.value = false; }
        };

        Vue.onMounted(() => {
            fetchSwitches();
            fetchVlanPlan();
            fetchAuditLogs();
        });

        return {
            activeTab, switchOptions, executing,
            vlanForm, portForm, portMode,
            vlanPlan, batchSwitchId,
            auditLogs, auditLoading, auditPage, auditTotal, auditSwitchId,
            createVlan, batchCreateVlans, setPortMode, fetchAuditLogs,
        };
    }
};
