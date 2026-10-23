<template>
    <div class="skills-page">
        <div class="page-title">Skill 管理</div>

        <el-row :gutter="16" class="mb-16">
            <el-col v-for="c in statCards" :key="c.key" :xs="12" :sm="8" :md="6">
                <StatCard :label="c.label" :value="c.value" :icon="c.icon" :type="c.type"/>
            </el-col>
        </el-row>

        <div class="toolbar">
            <div class="search-bar" style="margin:0">
                <el-input v-model="keyword" placeholder="搜索技能名 / 标识 / 说明" style="width:240px" clearable
                          @keyup.enter="fetchList"/>
                <el-select v-model="category" style="width:150px" @change="fetchList">
                    <el-option label="全部分类" value="all"/>
                    <el-option v-for="c in categories" :key="c" :label="c" :value="c"/>
                </el-select>
                <el-select v-model="enabled" style="width:120px" @change="fetchList">
                    <el-option label="全部状态" value=""/>
                    <el-option label="已启用" value="1"/>
                    <el-option label="已停用" value="0"/>
                </el-select>
                <el-button @click="fetchList">查询</el-button>
            </div>
            <el-button v-if="auth.isAdmin" type="primary" @click="openDialog()">新增技能</el-button>
        </div>

        <el-table :data="list" size="small" v-loading="loading" border stripe>
            <el-table-column type="expand">
                <template #default="{ row }">
                    <div style="padding:8px 16px">
                        <div class="drawer-section-title" style="margin-top:0">技能说明</div>
                        <div class="text-muted">{{ row.description || '—' }}</div>
                        <div class="drawer-section-title">触发场景</div>
                        <div class="text-muted">{{ row.trigger_scene || '—' }}</div>
                        <div class="drawer-section-title">执行要点</div>
                        <ol class="skill-steps">
                            <li v-for="(s, i) in stepList(row.steps)" :key="i">{{ s }}</li>
                        </ol>
                        <div class="text-muted mt-8" style="font-size:12px">
                            创建 {{ row.created_at }} · 更新 {{ row.updated_at }}
                        </div>
                    </div>
                </template>
            </el-table-column>
            <el-table-column label="技能" min-width="200">
                <template #default="{ row }">
                    <div>{{ row.name }}</div>
                    <div class="text-muted" style="font-size:12px">{{ row.skill_key }} · v{{ row.version }}</div>
                </template>
            </el-table-column>
            <el-table-column prop="category" label="分类" width="120"/>
            <el-table-column prop="trigger_scene" label="触发场景" min-width="200" show-overflow-tooltip/>
            <el-table-column label="风险级别" width="120">
                <template #default="{ row }">
                    <el-tag size="small" :type="RISK_TYPE[row.risk_level] || 'info'" effect="plain">
                        {{ RISK_LABEL[row.risk_level] || row.risk_level }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="关联入口" width="130">
                <template #default="{ row }">
                    <router-link v-if="row.entry_path" :to="row.entry_path" class="entry-link">{{ row.entry_path }}</router-link>
                    <span v-else class="text-muted">—</span>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                        {{ row.enabled ? '启用' : '停用' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column v-if="auth.isAdmin" label="操作" width="170" align="center">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
                    <el-button link @click="toggleRow(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                    <el-button link type="danger" @click="deleteRow(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div class="stat-label mt-8">
            这里登记的是 AI 运维代理可调用的技能清单（对应 AGENT.md 的能力矩阵）：停用只代表不可被调用，
            系统不会据此自动操作设备；标记"需人工确认"的技能，其命令仍需运维人员在自动化配置页下发并留审计记录。
        </div>

        <el-dialog v-model="dialogVisible" :title="form.id ? '编辑技能' : '新增技能'" width="680px">
            <el-form :model="form" label-width="100px">
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="技能标识" required>
                            <el-input v-model="form.skill_key" placeholder="小写字母/数字/下划线, 如 port_fault_diag"
                                      :disabled="!!form.id"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="技能名称" required>
                            <el-input v-model="form.name" placeholder="如: 端口故障诊断"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="分类">
                            <el-select v-model="form.category" style="width:100%" filterable allow-create>
                                <el-option v-for="c in categories" :key="c" :label="c" :value="c"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="风险级别">
                            <el-select v-model="form.risk_level" style="width:100%">
                                <el-option label="只读 (仅取数)" value="read"/>
                                <el-option label="给结论 (不改数据)" value="suggest"/>
                                <el-option label="需人工确认 (涉及下发)" value="write"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="技能说明">
                    <el-input v-model="form.description" type="textarea" :rows="2"
                              placeholder="这个技能做什么, 输出什么"/>
                </el-form-item>
                <el-form-item label="触发场景">
                    <el-input v-model="form.trigger_scene" placeholder="什么情况下调用该技能"/>
                </el-form-item>
                <el-form-item label="执行要点">
                    <el-input v-model="form.steps" type="textarea" :rows="5"
                              placeholder="每行一条步骤, 例如: 取该端口 admin/oper 状态与错误计数历史"/>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="关联入口">
                            <el-select v-model="form.entry_path" style="width:100%" clearable placeholder="可选">
                                <el-option v-for="p in entries" :key="p.path" :label="`${p.title} (${p.path})`"
                                           :value="p.path"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="6">
                        <el-form-item label="版本">
                            <el-input v-model="form.version" placeholder="1.0"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="6">
                        <el-form-item label="启用">
                            <el-switch v-model="form.enabled"/>
                        </el-form-item>
                    </el-col>
                </el-row>
            </el-form>
            <template #footer>
                <el-button @click="dialogVisible = false">取消</el-button>
                <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<script setup>
/**
 * Skill 管理页: 维护智能体技能清单 (agent_skills).
 * 后端 /api/agent-skills, 读放行所有角色, 增删改仅 ADMIN.
 * 技能标识创建后不可改 (改标识等于换调用名, 会让已有引用失效), 需要调整时删除重建.
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatCard from '@/components/StatCard.vue';
import * as skillsApi from '@/api/skills';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

const RISK_LABEL = { read: '只读', suggest: '给结论', write: '需人工确认' };
const RISK_TYPE = { read: 'info', suggest: 'warning', write: 'danger' };

const list = ref([]);
const categories = ref([]);
const loading = ref(false);
const keyword = ref('');
const category = ref('all');
const enabled = ref('');

/** 关联入口下拉: 与路由表保持一致, 只列一级页面 */
const entries = [
    { path: '/dashboard', title: '系统总览' },
    { path: '/switches', title: '交换机管理' },
    { path: '/servers', title: '服务器监控' },
    { path: '/proactive', title: '主动运维' },
    { path: '/automation', title: '自动化配置' },
    { path: '/alerts', title: '告警管理' },
    { path: '/inspection', title: '设备点检' },
    { path: '/standards', title: '点检标准' },
    { path: '/skills', title: 'Skill 管理' },
    { path: '/duties', title: '职责分工' },
    { path: '/system', title: '系统管理' },
];

const statCards = computed(() => {
    const rows = list.value;
    return [
        { key: 'total', label: '技能总数', value: rows.length, icon: 'MagicStick', type: 'primary' },
        { key: 'enabled', label: '已启用', value: rows.filter((r) => r.enabled).length, icon: 'CircleCheck', type: 'success' },
        {
            key: 'write',
            label: '需人工确认',
            value: rows.filter((r) => r.risk_level === 'write').length,
            icon: 'Warning',
            type: 'warning',
        },
        {
            key: 'category',
            label: '覆盖分类',
            value: new Set(rows.map((r) => r.category)).size,
            icon: 'Grid',
            type: '',
        },
    ];
});

const stepList = (steps) => (steps || '').split('\n').map((s) => s.trim()).filter((s) => s);

async function fetchList() {
    loading.value = true;
    try {
        const data = await skillsApi.skills({
            keyword: keyword.value, category: category.value, enabled: enabled.value,
        });
        list.value = data.items || [];
        // 分类字典: 后端固定清单 + 当前数据里出现的分类, 避免手工新增的分类筛不出来
        const dict = (await skillsApi.skillOptions()).categories || [];
        categories.value = [...new Set([...dict, ...list.value.map((r) => r.category)])];
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.value = false;
    }
}

const dialogVisible = ref(false);
const saving = ref(false);
const emptyForm = () => ({
    id: null, skill_key: '', name: '', category: '采集诊断', description: '', trigger_scene: '',
    steps: '', entry_path: '', risk_level: 'read', version: '1.0', enabled: true,
});
const form = ref(emptyForm());

function openDialog(row) {
    form.value = row
        ? {
            id: row.id, skill_key: row.skill_key, name: row.name, category: row.category,
            description: row.description || '', trigger_scene: row.trigger_scene || '',
            steps: row.steps || '', entry_path: row.entry_path || '',
            risk_level: row.risk_level || 'read', version: row.version || '1.0',
            enabled: row.enabled !== false,
        }
        : emptyForm();
    dialogVisible.value = true;
}

async function save() {
    const f = form.value;
    if (!f.skill_key || !f.name) return ElMessage.warning('技能标识与名称必填');
    const payload = {
        skill_key: f.skill_key, name: f.name, category: f.category, description: f.description,
        trigger_scene: f.trigger_scene, steps: f.steps, entry_path: f.entry_path,
        risk_level: f.risk_level, version: f.version, enabled: f.enabled,
    };
    saving.value = true;
    try {
        if (f.id) await skillsApi.updateSkill(f.id, payload);
        else await skillsApi.createSkill(payload);
        ElMessage.success('已保存');
        dialogVisible.value = false;
        fetchList();
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        saving.value = false;
    }
}

async function toggleRow(row) {
    try {
        await skillsApi.toggleSkill(row.id);
        fetchList();
    } catch (e) {
        /* 拦截器已提示 */
    }
}

async function deleteRow(row) {
    try {
        await ElMessageBox.confirm(
            `确定删除技能「${row.name}」(${row.skill_key})? 删除后智能体将不再调用该技能。`, '提示',
            { type: 'warning' });
    } catch (e) {
        return;
    }
    try {
        await skillsApi.deleteSkill(row.id);
        ElMessage.success('已删除');
        fetchList();
    } catch (e) {
        /* 拦截器已提示 */
    }
}

onMounted(fetchList);
</script>

<style scoped>
.skill-steps {
    margin: 0;
    padding-left: 20px;
    color: #606266;
    font-size: 13px;
    line-height: 1.8;
}

.entry-link {
    color: var(--primary-color);
    text-decoration: none;
}
</style>
