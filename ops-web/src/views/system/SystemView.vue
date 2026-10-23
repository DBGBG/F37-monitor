<template>
    <div class="system">
        <div class="page-title">系统管理</div>

        <el-tabs v-model="tab" @tab-change="onTabChange">
            <!-- ==================== 系统健康 ==================== -->
            <el-tab-pane label="系统健康" name="health">
                <el-row :gutter="16" class="mb-16">
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="交换机在线" :value="`${online.switches} / ${total.switches}`" icon="Refresh"
                                  type="primary"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="服务器在线" :value="`${online.servers} / ${total.servers}`" icon="Monitor"
                                  type="success"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="活跃告警" :value="health.alerts?.active || 0" icon="Warning" type="danger"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="库体积" :value="health.database?.total_size_mb ?? 0" suffix="MB"
                                  icon="Files" type="warning"/>
                    </el-col>
                </el-row>

                <div class="chart-card mb-16">
                    <div class="chart-title">数据新鲜度（各时间列最近写入）</div>
                    <el-row :gutter="12">
                        <el-col v-for="(v, k) in health.freshness || {}" :key="k" :xs="12" :sm="8" :md="6">
                            <div class="factor-row">
                                <span class="text-muted">{{ freshnessLabel(k) }}</span>
                                <span>{{ v || '—' }}</span>
                            </div>
                        </el-col>
                    </el-row>
                </div>

                <el-row :gutter="16">
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">核心表行数</div>
                            <el-table :data="toRows(health.table_rows || {})" size="small">
                                <el-table-column prop="k" label="表名"/>
                                <el-table-column prop="v" label="行数" align="right"/>
                            </el-table>
                        </div>
                    </el-col>
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">时序表跨度与保留策略</div>
                            <el-table :data="maintenanceRows" size="small">
                                <el-table-column prop="table" label="表名" width="160"/>
                                <el-table-column prop="rows" label="行数" width="100" align="right"/>
                                <el-table-column prop="oldest" label="最早" show-overflow-tooltip/>
                                <el-table-column prop="newest" label="最新" show-overflow-tooltip/>
                            </el-table>
                            <div class="text-muted mt-8">
                                保留策略：指标 {{ retention.metrics_days }} 天 / 告警 {{ retention.alerts_days }} 天
                                / 异常 {{ retention.anomalies_days }} 天 / 审计 {{ retention.audit_days }} 天
                                / 点检 {{ retention.inspection_days <= 0 ? '永久' : retention.inspection_days + ' 天' }}
                            </div>
                        </div>
                    </el-col>
                </el-row>

                <div v-if="auth.isAdmin" class="chart-card mt-16">
                    <div class="chart-title">手动清理历史数据</div>
                    <el-form :inline="true">
                        <el-form-item label="指标保留天数">
                            <el-input-number v-model="cleanupForm.metrics_days" :min="0" :max="3650" placeholder="留空按策略"/>
                        </el-form-item>
                        <el-form-item label="告警保留天数">
                            <el-input-number v-model="cleanupForm.alerts_days" :min="0" :max="3650" placeholder="留空按策略"/>
                        </el-form-item>
                        <el-form-item>
                            <el-button type="danger" :loading="cleaning" @click="runCleanup">立即清理</el-button>
                        </el-form-item>
                    </el-form>
                    <div class="text-muted">
                        0 表示不限制（永久保留）；留空则使用 sys_config.retention_policy 中的策略。清理只删除已闭环的告警与异常
                    </div>
                    <div v-if="cleanupSummary" class="output-block mt-8">{{ JSON.stringify(cleanupSummary, null, 2) }}</div>
                </div>
            </el-tab-pane>

            <!-- ==================== 采集层 ==================== -->
            <el-tab-pane label="采集层" name="collector">
                <div class="toolbar">
                    <div class="stat-label">
                        采集层是独立的 Python 进程，只写库不对外服务；业务层通过心跳表判断其存活，通过指令表下发重采 / 发现 / 重载
                    </div>
                    <div>
                        <el-button v-if="auth.canWrite" :loading="commanding" @click="doSync">同步设备台账</el-button>
                        <el-button v-if="auth.canWrite" :loading="commanding" @click="doRediscover">立即发现设备</el-button>
                        <el-button @click="fetchCollector">刷新</el-button>
                    </div>
                </div>

                <el-row :gutter="16" class="mb-16">
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="采集层状态" :value="heartbeat.alive ? '正常' : '异常'"
                                  :type="heartbeat.alive ? 'success' : 'danger'" icon="CircleCheck"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="最近心跳" :value="heartbeat.last_beat_at || '—'" icon="Clock"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="最近阶段" :value="heartbeat.last_phase || '—'" icon="DataLine"/>
                    </el-col>
                    <el-col :xs="12" :sm="8" :md="6">
                        <StatCard label="最近耗时" :value="heartbeat.last_duration_ms ?? 0" suffix="ms" icon="Timer"/>
                    </el-col>
                </el-row>

                <div class="chart-card mb-16">
                    <div class="chart-title">各阶段最新一轮采集</div>
                    <el-table :data="heartbeat.phases || []" size="small">
                        <el-table-column prop="component" label="组件" width="150"/>
                        <el-table-column prop="phase" label="阶段" width="180"/>
                        <el-table-column prop="total" label="目标数" width="90" align="right"/>
                        <el-table-column prop="success" label="成功" width="80" align="right"/>
                        <el-table-column label="失败" width="80" align="right">
                            <template #default="{ row }">
                                <span :class="row.errors ? 'text-danger' : ''">{{ row.errors }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="耗时" width="100" align="right">
                            <template #default="{ row }">{{ row.duration_ms }}ms</template>
                        </el-table-column>
                        <el-table-column prop="last_beat_at" label="时间" width="170"/>
                        <el-table-column label="明细" min-width="160">
                            <template #default="{ row }">
                                <span class="text-muted">{{ JSON.stringify(row.detail || {}) }}</span>
                            </template>
                        </el-table-column>
                    </el-table>
                </div>

                <el-row :gutter="16">
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">最近心跳记录</div>
                            <el-table :data="beats" size="small" height="300">
                                <el-table-column prop="last_beat_at" label="时间" width="170"/>
                                <el-table-column prop="phase" label="阶段" show-overflow-tooltip/>
                                <el-table-column prop="total" label="总数" width="70" align="right"/>
                                <el-table-column prop="errors" label="失败" width="70" align="right"/>
                                <el-table-column label="耗时" width="90" align="right">
                                    <template #default="{ row }">{{ row.duration_ms }}ms</template>
                                </el-table-column>
                            </el-table>
                        </div>
                    </el-col>
                    <el-col :xs="24" :md="12">
                        <div class="chart-card">
                            <div class="chart-title">
                                跨层指令
                                <el-tag v-if="commandsPending" type="warning" size="small" style="margin-left:8px">
                                    有指令待采集层拉取
                                </el-tag>
                            </div>
                            <el-table :data="commands" size="small" height="300">
                                <el-table-column prop="created_at" label="下发时间" width="170"/>
                                <el-table-column prop="cmd" label="指令" width="140"/>
                                <el-table-column label="状态" width="90">
                                    <template #default="{ row }">
                                        <el-tag size="small" :type="cmdStatusType(row.status)">{{ row.status }}</el-tag>
                                    </template>
                                </el-table-column>
                                <el-table-column prop="created_by" label="来源" width="100"/>
                                <el-table-column prop="result" label="结果" show-overflow-tooltip/>
                            </el-table>
                        </div>
                    </el-col>
                </el-row>
            </el-tab-pane>

            <!-- ==================== 采集任务 ==================== -->
            <el-tab-pane v-if="auth.isAdmin" label="采集任务" name="jobs">
                <div class="toolbar">
                    <div class="stat-label">周期与开关写入 collector_jobs，采集层每 5 分钟热加载；保存后额外下发重载指令立即生效</div>
                    <el-button @click="fetchJobs">刷新</el-button>
                </div>
                <el-table :data="jobs" size="small" v-loading="loading.jobs">
                    <el-table-column prop="job_id" label="任务标识" width="200"/>
                    <el-table-column prop="name" label="名称" min-width="160"/>
                    <el-table-column label="周期(秒)" width="140">
                        <template #default="{ row }">
                            <el-input-number v-model="row.interval_seconds" size="small" :min="10" :max="86400"
                                             controls-position="right" style="width:120px"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="超时(秒)" width="130">
                        <template #default="{ row }">
                            <el-input-number v-model="row.timeout_seconds" size="small" :min="5" :max="3600"
                                             controls-position="right" style="width:110px"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="并发" width="110">
                        <template #default="{ row }">
                            <el-input-number v-model="row.max_instances" size="small" :min="1" :max="4"
                                             controls-position="right" style="width:90px"/>
                        </template>
                    </el-table-column>
                    <el-table-column label="启用" width="80">
                        <template #default="{ row }"><el-switch v-model="row.enabled"/></template>
                    </el-table-column>
                    <el-table-column prop="updated_at" label="更新时间" width="170"/>
                    <el-table-column label="操作" width="90" align="center">
                        <template #default="{ row }">
                            <el-button link type="primary" :loading="savingJob === row.job_id" @click="saveJob(row)">
                                保存
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </el-tab-pane>

            <!-- ==================== 扫描网段 ==================== -->
            <el-tab-pane v-if="auth.isAdmin" label="扫描网段" name="groups">
                <div class="toolbar">
                    <div class="stat-label">设备自动发现范围（取代迁移前的 devices.yaml switch_groups），SNMP 团体字与 SSH 口令保存后以掩码回显</div>
                    <el-button type="primary" @click="openGroup()">新增网段</el-button>
                </div>
                <el-table :data="groups" size="small" v-loading="loading.groups">
                    <el-table-column prop="group_name" label="分组" width="160"/>
                    <el-table-column prop="ip_range" label="IP 范围" width="220"/>
                    <el-table-column prop="model" label="型号" width="120"/>
                    <el-table-column prop="location_prefix" label="位置前缀" width="130"/>
                    <el-table-column label="凭据" min-width="180">
                        <template #default="{ row }">
                            <span class="text-muted">
                                SNMP {{ row.snmp_config?.community || '—' }} · SSH
                                {{ row.ssh_config?.username || '—' }}
                            </span>
                        </template>
                    </el-table-column>
                    <el-table-column label="启用" width="80">
                        <template #default="{ row }">
                            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                                {{ row.enabled ? '启用' : '停用' }}
                            </el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column prop="updated_at" label="更新时间" width="170"/>
                    <el-table-column label="操作" width="180" align="center">
                        <template #default="{ row }">
                            <el-button link type="primary" @click="openGroup(row)">编辑</el-button>
                            <el-button link @click="toggleGroup(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                            <el-button link type="danger" @click="deleteGroup(row)">删除</el-button>
                        </template>
                    </el-table-column>
                </el-table>

                <el-dialog v-model="groupDialog" :title="groupForm.id ? '编辑扫描网段' : '新增扫描网段'" width="620px">
                    <el-form :model="groupForm" label-width="100px">
                        <el-form-item label="分组名称" required>
                            <el-input v-model="groupForm.group_name" placeholder="如: 车间接入交换机"/>
                        </el-form-item>
                        <el-form-item label="IP 范围" required>
                            <el-input v-model="groupForm.ip_range" placeholder="192.168.1.1-200 / 192.168.1.0/24"/>
                        </el-form-item>
                        <el-row :gutter="12">
                            <el-col :span="12">
                                <el-form-item label="默认型号">
                                    <el-input v-model="groupForm.model" placeholder="EDR-GP334 等"/>
                                </el-form-item>
                            </el-col>
                            <el-col :span="12">
                                <el-form-item label="位置前缀">
                                    <el-input v-model="groupForm.location_prefix" placeholder="如: 一号厂房"/>
                                </el-form-item>
                            </el-col>
                        </el-row>
                        <el-divider content-position="left">SNMP</el-divider>
                        <el-row :gutter="12">
                            <el-col :span="8">
                                <el-form-item label="版本">
                                    <el-select v-model="groupForm.snmp_version" style="width:100%">
                                        <el-option label="v2c" value="v2c"/>
                                        <el-option label="v3" value="v3"/>
                                    </el-select>
                                </el-form-item>
                            </el-col>
                            <el-col :span="16">
                                <el-form-item label="团体字">
                                    <el-input v-model="groupForm.snmp_community" placeholder="留空或保持掩码表示不改动"/>
                                </el-form-item>
                            </el-col>
                        </el-row>
                        <el-divider content-position="left">SSH</el-divider>
                        <el-row :gutter="12">
                            <el-col :span="12">
                                <el-form-item label="用户名">
                                    <el-input v-model="groupForm.ssh_username"/>
                                </el-form-item>
                            </el-col>
                            <el-col :span="12">
                                <el-form-item label="端口">
                                    <el-input-number v-model="groupForm.ssh_port" :min="1" :max="65535"/>
                                </el-form-item>
                            </el-col>
                        </el-row>
                        <el-form-item label="口令">
                            <el-input v-model="groupForm.ssh_password" type="password" show-password
                                      placeholder="留空或保持掩码表示不改动"/>
                        </el-form-item>
                        <el-form-item label="启用">
                            <el-switch v-model="groupForm.enabled"/>
                        </el-form-item>
                    </el-form>
                    <template #footer>
                        <el-button @click="groupDialog = false">取消</el-button>
                        <el-button type="primary" :loading="savingGroup" @click="saveGroup">保存</el-button>
                    </template>
                </el-dialog>
            </el-tab-pane>

            <!-- ==================== 用户管理 ==================== -->
            <el-tab-pane v-if="auth.isAdmin" label="用户管理" name="users">
                <div class="toolbar">
                    <div class="stat-label">
                        角色：ADMIN 全量 / OPERATOR 可点检、确认告警与下发配置 / VIEWER 只读监控
                    </div>
                    <el-button type="primary" @click="openUser()">新增用户</el-button>
                </div>
                <el-row :gutter="16">
                    <el-col :xs="24" :md="16">
                        <el-table :data="users" size="small" v-loading="loading.users">
                            <el-table-column prop="username" label="账号" width="140"/>
                            <el-table-column prop="display_name" label="姓名" width="140"/>
                            <el-table-column label="角色" width="110">
                                <template #default="{ row }">
                                    <el-tag :type="roleType(row.role_code)" size="small">{{ row.role_code }}</el-tag>
                                </template>
                            </el-table-column>
                            <el-table-column label="状态" width="80">
                                <template #default="{ row }">
                                    <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                                        {{ row.enabled ? '启用' : '停用' }}
                                    </el-tag>
                                </template>
                            </el-table-column>
                            <el-table-column prop="last_login_at" label="最近登录" width="170"/>
                            <el-table-column label="操作" width="170" align="center">
                                <template #default="{ row }">
                                    <el-button link type="primary" @click="openUser(row)">编辑</el-button>
                                    <el-button link type="danger" @click="deleteUser(row)">删除</el-button>
                                </template>
                            </el-table-column>
                        </el-table>
                    </el-col>
                    <el-col :xs="24" :md="8">
                        <div class="chart-card">
                            <div class="chart-title">角色与权限</div>
                            <div v-for="r in roles" :key="r.code" class="mb-8">
                                <div class="factor-row">
                                    <span><b>{{ r.name }}</b> ({{ r.code }})</span>
                                    <span class="text-muted">{{ r.user_count }} 人</span>
                                </div>
                                <div class="text-muted" style="font-size:12px">{{ (r.permissions || []).join(' · ') }}</div>
                            </div>
                        </div>
                    </el-col>
                </el-row>

                <el-dialog v-model="userDialog" :title="userForm.id ? '编辑用户' : '新增用户'" width="460px">
                    <el-form :model="userForm" label-width="80px">
                        <el-form-item label="账号" required>
                            <el-input v-model="userForm.username" :disabled="!!userForm.id"/>
                        </el-form-item>
                        <el-form-item label="姓名">
                            <el-input v-model="userForm.display_name"/>
                        </el-form-item>
                        <el-form-item label="角色">
                            <el-select v-model="userForm.role_code" style="width:100%">
                                <el-option v-for="r in roles" :key="r.code" :label="`${r.name} (${r.code})`"
                                           :value="r.code"/>
                            </el-select>
                        </el-form-item>
                        <el-form-item label="口令">
                            <el-input v-model="userForm.password" type="password" show-password
                                      :placeholder="userForm.id ? '留空表示不改动' : '不少于 6 位'"/>
                        </el-form-item>
                        <el-form-item label="启用">
                            <el-switch v-model="userForm.enabled"/>
                        </el-form-item>
                    </el-form>
                    <template #footer>
                        <el-button @click="userDialog = false">取消</el-button>
                        <el-button type="primary" :loading="savingUser" @click="saveUser">保存</el-button>
                    </template>
                </el-dialog>
            </el-tab-pane>

            <!-- ==================== 运行参数 ==================== -->
            <el-tab-pane v-if="auth.isAdmin" label="运行参数" name="configs">
                <div class="toolbar">
                    <div class="stat-label">
                        业务参数存于 sys_config（JSON），保存后业务层缓存立即失效，下一轮告警 / 分析即采用新值
                    </div>
                    <el-button @click="fetchConfigs">刷新</el-button>
                </div>
                <el-table :data="configs" size="small" v-loading="loading.configs">
                    <el-table-column type="expand">
                        <template #default="{ row }">
                            <div style="padding:8px 16px">
                                <el-input v-model="row.editText" type="textarea" :rows="12"
                                          style="font-family:Consolas,monospace"/>
                                <div class="mt-8">
                                    <el-button type="primary" size="small" :loading="savingKey === row.config_key"
                                               @click="saveConfig(row)">保存 {{ row.config_key }}</el-button>
                                    <span class="text-muted" style="margin-left:8px">必须是合法 JSON</span>
                                </div>
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column prop="config_key" label="配置键" width="200"/>
                    <el-table-column label="当前值" min-width="260">
                        <template #default="{ row }">
                            <span class="text-muted" style="font-size:12px">{{ preview(row.value) }}</span>
                        </template>
                    </el-table-column>
                    <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/>
                    <el-table-column prop="updated_at" label="更新时间" width="170"/>
                </el-table>
            </el-tab-pane>
        </el-tabs>
    </div>
</template>

<script setup>
/**
 * 系统管理页: 迁移前只有 /api/system/health 没有对应界面, 改造后统一为
 * 系统健康 / 采集层 / 采集任务 / 扫描网段 / 用户管理 / 运行参数 六个页签.
 * 只有系统健康与采集层对 OPERATOR 开放, 其余页签路由与接口都是 ADMIN 专属.
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatCard from '../../components/StatCard.vue';
import * as systemApi from '../../api/system';
import * as authApi from '../../api/auth';
import { useAuthStore } from '../../stores/auth';
import { useRealtimeStore } from '../../stores/realtime';

const auth = useAuthStore();
const realtime = useRealtimeStore();

const tab = ref('health');
const loading = reactive({ jobs: false, groups: false, users: false, configs: false });
const commanding = ref(false);
const cleaning = ref(false);

// ---------------- 系统健康 ----------------
const health = ref({});
const maintenance = ref({});
const cleanupForm = reactive({ metrics_days: undefined, alerts_days: undefined });
const cleanupSummary = ref(null);

const total = computed(() => ({
    switches: health.value.devices?.switches?.total || 0,
    servers: health.value.devices?.servers?.total || 0,
}));
const online = computed(() => ({
    switches: health.value.devices?.switches?.online || 0,
    servers: health.value.devices?.servers?.online || 0,
}));
const retention = computed(() => maintenance.value.retention || {});

const FRESHNESS_LABELS = {
    switch_last_poll: '交换机最近采集',
    server_last_poll: '服务器最近采集',
    traffic_last: '流量样本',
    hardware_last: '硬件样本',
    server_metrics_last: '服务器指标',
    process_last: '进程状态',
    alert_last: '最近告警',
};
const freshnessLabel = (k) => FRESHNESS_LABELS[k] || k;

const SERIES_TABLES = ['port_traffic_stats', 'hardware_health', 'server_metrics', 'process_status'];
const maintenanceRows = computed(() => SERIES_TABLES
    .filter((t) => maintenance.value[t])
    .map((t) => ({ table: t, ...maintenance.value[t] })));

const toRows = (obj) => Object.keys(obj).map((k) => ({ k, v: obj[k] }));

async function fetchHealth() {
    try {
        health.value = await systemApi.health();
    } catch (e) { /* 拦截器已提示 */ }
    try {
        maintenance.value = await systemApi.maintenanceStats();
    } catch (e) { /* ignore */ }
}

async function runCleanup() {
    try {
        await ElMessageBox.confirm('将按保留策略删除历史数据，删除后不可恢复，确认继续?', '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    cleaning.value = true;
    try {
        const payload = {};
        if (cleanupForm.metrics_days) payload.metrics_days = cleanupForm.metrics_days;
        if (cleanupForm.alerts_days) payload.alerts_days = cleanupForm.alerts_days;
        const data = await systemApi.cleanup(payload);
        cleanupSummary.value = data.summary;
        ElMessage.success('清理完成');
        fetchHealth();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        cleaning.value = false;
    }
}

// ---------------- 采集层 ----------------
const heartbeat = ref({});
const beats = ref([]);
const commands = ref([]);
const commandsPending = ref(false);

async function fetchCollector() {
    try {
        heartbeat.value = await systemApi.collectorHeartbeat();
    } catch (e) { /* ignore */ }
    try {
        beats.value = (await systemApi.collectorBeats()).items || [];
    } catch (e) { /* ignore */ }
    try {
        const data = await systemApi.collectorCommands();
        commands.value = data.items || [];
        commandsPending.value = !!data.pending;
    } catch (e) { /* ignore */ }
}

async function doSync() {
    commanding.value = true;
    try {
        const data = await systemApi.syncDevices();
        ElMessage.success(data.message || '指令已下发');
        fetchCollector();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        commanding.value = false;
    }
}

async function doRediscover() {
    commanding.value = true;
    try {
        const data = await systemApi.rediscover();
        ElMessage.success(data.message || '指令已下发');
        fetchCollector();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        commanding.value = false;
    }
}

const cmdStatusType = (s) => ({ pending: 'warning', running: 'warning', done: 'success', failed: 'danger' }[s] || 'info');

// ---------------- 采集任务 ----------------
const jobs = ref([]);
const savingJob = ref('');

async function fetchJobs() {
    loading.jobs = true;
    try {
        jobs.value = (await systemApi.collectorJobs()).items || [];
    } catch (e) { /* ignore */ } finally {
        loading.jobs = false;
    }
}

async function saveJob(row) {
    savingJob.value = row.job_id;
    try {
        await systemApi.updateCollectorJob(row.job_id, {
            interval_seconds: row.interval_seconds,
            timeout_seconds: row.timeout_seconds,
            max_instances: row.max_instances,
            enabled: row.enabled,
        });
        ElMessage.success(`已保存 ${row.name}，采集层将在秒级重载`);
        fetchJobs();
        fetchCollector();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        savingJob.value = '';
    }
}

// ---------------- 扫描网段 ----------------
const groups = ref([]);
const groupDialog = ref(false);
const savingGroup = ref(false);
const emptyGroup = () => ({
    id: null, group_name: '', ip_range: '', model: '', location_prefix: '', enabled: true,
    snmp_version: 'v2c', snmp_community: '', ssh_username: '', ssh_port: 22, ssh_password: '',
});
const groupForm = ref(emptyGroup());

async function fetchGroups() {
    loading.groups = true;
    try {
        groups.value = (await systemApi.discoveryGroups()).items || [];
    } catch (e) { /* ignore */ } finally {
        loading.groups = false;
    }
}

function openGroup(row) {
    if (!row) {
        groupForm.value = emptyGroup();
        groupDialog.value = true;
        return;
    }
    const snmp = row.snmp_config || {};
    const ssh = row.ssh_config || {};
    groupForm.value = {
        id: row.id, group_name: row.group_name, ip_range: row.ip_range,
        model: row.model || '', location_prefix: row.location_prefix || '', enabled: row.enabled !== false,
        snmp_version: snmp.version || 'v2c', snmp_community: snmp.community || '',
        ssh_username: ssh.username || '', ssh_port: ssh.port || 22, ssh_password: ssh.password || '',
    };
    groupDialog.value = true;
}

async function saveGroup() {
    const f = groupForm.value;
    if (!f.group_name || !f.ip_range) return ElMessage.warning('分组名称与 IP 范围必填');
    const payload = {
        group_name: f.group_name, ip_range: f.ip_range, model: f.model,
        location_prefix: f.location_prefix, enabled: f.enabled,
        snmp_config: { version: f.snmp_version, community: f.snmp_community },
        ssh_config: { username: f.ssh_username, port: f.ssh_port, password: f.ssh_password },
    };
    savingGroup.value = true;
    try {
        if (f.id) await systemApi.updateDiscoveryGroup(f.id, payload);
        else await systemApi.createDiscoveryGroup(payload);
        ElMessage.success('已保存');
        groupDialog.value = false;
        fetchGroups();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        savingGroup.value = false;
    }
}

async function toggleGroup(row) {
    await systemApi.toggleDiscoveryGroup(row.id);
    fetchGroups();
}

async function deleteGroup(row) {
    try {
        await ElMessageBox.confirm(`确定删除扫描网段「${row.group_name}」(${row.ip_range})?`, '提示',
            { type: 'warning' });
    } catch (e) {
        return;
    }
    await systemApi.deleteDiscoveryGroup(row.id);
    ElMessage.success('已删除');
    fetchGroups();
}

// ---------------- 用户管理 ----------------
const users = ref([]);
const roles = ref([]);
const userDialog = ref(false);
const savingUser = ref(false);
const emptyUser = () => ({ id: null, username: '', display_name: '', role_code: 'VIEWER', password: '', enabled: true });
const userForm = ref(emptyUser());

async function fetchUsers() {
    loading.users = true;
    try {
        users.value = (await authApi.listUsers()).items || [];
        roles.value = (await authApi.listRoles()).items || [];
    } catch (e) { /* ignore */ } finally {
        loading.users = false;
    }
}

const roleType = (code) => ({ ADMIN: 'danger', OPERATOR: 'warning', VIEWER: 'info' }[code] || 'info');

function openUser(row) {
    userForm.value = row
        ? { id: row.id, username: row.username, display_name: row.display_name, role_code: row.role_code, password: '', enabled: row.enabled !== false }
        : emptyUser();
    userDialog.value = true;
}

async function saveUser() {
    const f = userForm.value;
    if (!f.username) return ElMessage.warning('请填写账号');
    savingUser.value = true;
    try {
        if (f.id) {
            await authApi.updateUser(f.id, {
                display_name: f.display_name, role_code: f.role_code, enabled: f.enabled, password: f.password,
            });
        } else {
            await authApi.createUser({
                username: f.username, password: f.password, display_name: f.display_name,
                role_code: f.role_code, enabled: f.enabled,
            });
        }
        ElMessage.success('已保存');
        userDialog.value = false;
        fetchUsers();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        savingUser.value = false;
    }
}

async function deleteUser(row) {
    try {
        await ElMessageBox.confirm(`确定删除用户「${row.username}」?`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    await authApi.deleteUser(row.id);
    ElMessage.success('已删除');
    fetchUsers();
}

// ---------------- 运行参数 ----------------
const configs = ref([]);
const savingKey = ref('');

async function fetchConfigs() {
    loading.configs = true;
    try {
        const items = (await systemApi.configs()).items || [];
        configs.value = items.map((c) => ({ ...c, editText: JSON.stringify(c.value, null, 2) }));
    } catch (e) { /* ignore */ } finally {
        loading.configs = false;
    }
}

function preview(value) {
    const text = JSON.stringify(value);
    return text && text.length > 160 ? `${text.slice(0, 160)}…` : text;
}

async function saveConfig(row) {
    let parsed;
    try {
        parsed = JSON.parse(row.editText);
    } catch (e) {
        return ElMessage.error('JSON 解析失败, 请检查格式');
    }
    savingKey.value = row.config_key;
    try {
        await systemApi.updateConfig(row.config_key, parsed, row.description);
        ElMessage.success(`${row.config_key} 已更新`);
        fetchConfigs();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        savingKey.value = '';
    }
}

// ---------------- 页签调度 ----------------
function onTabChange(name) {
    if (name === 'collector') fetchCollector();
    else if (name === 'jobs') fetchJobs();
    else if (name === 'groups') fetchGroups();
    else if (name === 'users') fetchUsers();
    else if (name === 'configs') fetchConfigs();
    else fetchHealth();
}

let timer = null;
let unsub = null;

onMounted(() => {
    fetchHealth();
    fetchCollector();
    // 心跳与指令状态跟随采集节奏刷新
    timer = setInterval(fetchCollector, 60000);
    unsub = realtime.on('cycle', () => {
        if (tab.value === 'collector' || tab.value === 'health') fetchCollector();
    });
});

onUnmounted(() => {
    if (timer) clearInterval(timer);
    if (unsub) unsub();
});
</script>

<style scoped>
.text-danger {
    color: #F56C6C;
}
</style>
