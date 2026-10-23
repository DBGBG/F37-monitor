<template>
    <div class="standards-page">
        <div class="page-title">点检标准</div>

        <el-row :gutter="16" class="mb-16">
            <el-col v-for="c in statCards" :key="c.key" :xs="12" :sm="8" :md="6">
                <StatCard :label="c.label" :value="c.value" :icon="c.icon" :type="c.type"/>
            </el-col>
        </el-row>

        <div class="toolbar">
            <div class="search-bar" style="margin:0">
                <el-input v-model="keyword" placeholder="搜索名称 / 判定值 / 处置措施" style="width:240px" clearable
                          @keyup.enter="fetchList"/>
                <el-select v-model="category" style="width:130px" @change="fetchList">
                    <el-option label="全部对象" value="all"/>
                    <el-option label="交换机" value="switch"/>
                    <el-option label="服务器" value="server"/>
                    <el-option label="通用环境" value="general"/>
                </el-select>
                <el-select v-model="enabled" style="width:120px" @change="fetchList">
                    <el-option label="全部状态" value=""/>
                    <el-option label="已启用" value="1"/>
                    <el-option label="已停用" value="0"/>
                </el-select>
                <el-button @click="fetchList">查询</el-button>
            </div>
            <el-button v-if="auth.canWrite" type="primary" @click="openDialog()">新增标准</el-button>
        </div>

        <el-table :data="list" size="small" v-loading="loading" border stripe>
            <el-table-column type="expand">
                <template #default="{ row }">
                    <div style="padding:8px 16px">
                        <div class="factor-row"><span>异常处置措施</span><span>{{ row.handle_action || '—' }}</span></div>
                        <div class="factor-row"><span>来源</span><span>{{ row.source === 'preset' ? '内置标准' : '自定义' }}</span></div>
                        <div class="factor-row"><span>创建 / 更新</span><span>{{ row.created_at }} / {{ row.updated_at }}</span></div>
                    </div>
                </template>
            </el-table-column>
            <el-table-column label="适用" width="90">
                <template #default="{ row }">
                    <el-tag size="small" :type="CATEGORY_TYPE[row.category] || 'info'">
                        {{ CATEGORY_LABEL[row.category] || row.category }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="name" label="标准名称" min-width="160" show-overflow-tooltip/>
            <el-table-column label="类型" width="70">
                <template #default="{ row }">{{ CHECK_TYPE_LABEL[row.check_type] || row.check_type }}</template>
            </el-table-column>
            <el-table-column label="判定标准" width="150">
                <template #default="{ row }">
                    {{ row.standard || '—' }}<span v-if="row.unit" class="text-muted"> {{ row.unit }}</span>
                </template>
            </el-table-column>
            <el-table-column prop="method" label="点检方法" width="140" show-overflow-tooltip/>
            <el-table-column label="建议周期" width="90">
                <template #default="{ row }">{{ CYCLE_LABEL[row.check_cycle] || row.check_cycle }}</template>
            </el-table-column>
            <el-table-column label="重要度" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="LEVEL_TYPE[row.level] || 'info'" effect="plain">
                        {{ LEVEL_LABEL[row.level] || row.level }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="引用" width="90" align="center">
                <template #default="{ row }">
                    <span v-if="row.ref_count > 0">{{ row.ref_count }} 个计划</span>
                    <span v-else class="text-muted">未引用</span>
                </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                        {{ row.enabled ? '启用' : '停用' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column v-if="auth.canWrite" label="操作" width="170" align="center">
                <template #default="{ row }">
                    <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
                    <el-button link @click="toggleRow(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
                    <el-button link type="danger" @click="deleteRow(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <div class="stat-label mt-8">
            标准库独立于点检计划维护：计划里的点检项是创建时的快照，改标准不会回写已生成的任务；
            新建计划时可对照本表逐条填写，同名标准会显示被多少个计划引用。
        </div>

        <el-dialog v-model="dialogVisible" :title="form.id ? '编辑点检标准' : '新增点检标准'" width="640px">
            <el-form :model="form" label-width="100px">
                <el-form-item label="适用对象" required>
                    <el-select v-model="form.category" style="width:100%">
                        <el-option label="交换机" value="switch"/>
                        <el-option label="服务器" value="server"/>
                        <el-option label="通用环境" value="general"/>
                    </el-select>
                </el-form-item>
                <el-form-item label="标准名称" required>
                    <el-input v-model="form.name" placeholder="如: 设备温度"/>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="检查类型">
                            <el-select v-model="form.check_type" style="width:100%">
                                <el-option label="判断 (正常/异常)" value="bool"/>
                                <el-option label="数值 (对照参考值)" value="value"/>
                                <el-option label="记录 (文字描述)" value="text"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="判定标准">
                            <el-input v-model="form.standard" placeholder="如: &lt; 60 或 绿灯常亮"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="单位">
                            <el-input v-model="form.unit" placeholder="°C / Mbps 等, 可留空"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="点检方法">
                            <el-input v-model="form.method" placeholder="目视 / 手感 / 仪器 / 系统查询"/>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="建议周期">
                            <el-select v-model="form.check_cycle" style="width:100%">
                                <el-option label="每日" value="daily"/>
                                <el-option label="每周" value="weekly"/>
                                <el-option label="每月" value="monthly"/>
                                <el-option label="每季" value="quarterly"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="重要度">
                            <el-select v-model="form.level" style="width:100%">
                                <el-option label="关键" value="key"/>
                                <el-option label="一般" value="normal"/>
                                <el-option label="可选" value="optional"/>
                            </el-select>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="处置措施">
                    <el-input v-model="form.handle_action" type="textarea" :rows="3"
                              placeholder="判定异常时的现场处置动作, 会显示在标准详情里"/>
                </el-form-item>
                <el-row :gutter="12">
                    <el-col :span="12">
                        <el-form-item label="排序">
                            <el-input-number v-model="form.sort_order" :min="0" :max="999" controls-position="right"/>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
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
 * 点检标准页: 判定标准从"计划内嵌项"抽出来单独成库.
 * 后端 /api/inspection/standards, 读放行所有角色, 写需 OPERATOR 及以上 (与点检计划同权限).
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import StatCard from '@/components/StatCard.vue';
import * as standardsApi from '@/api/standards';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();

const CATEGORY_LABEL = { switch: '交换机', server: '服务器', general: '通用' };
const CATEGORY_TYPE = { switch: '', server: 'success', general: 'info' };
const CHECK_TYPE_LABEL = { bool: '判断', value: '数值', text: '记录' };
const CYCLE_LABEL = { daily: '每日', weekly: '每周', monthly: '每月', quarterly: '每季' };
const LEVEL_LABEL = { key: '关键', normal: '一般', optional: '可选' };
const LEVEL_TYPE = { key: 'danger', normal: 'warning', optional: 'info' };

const list = ref([]);
const loading = ref(false);
const keyword = ref('');
const category = ref('all');
const enabled = ref('');

const statCards = computed(() => {
    const rows = list.value;
    return [
        { key: 'total', label: '标准总数', value: rows.length, icon: 'Memo', type: 'primary' },
        { key: 'enabled', label: '已启用', value: rows.filter((r) => r.enabled).length, icon: 'CircleCheck', type: 'success' },
        { key: 'key', label: '关键项', value: rows.filter((r) => r.level === 'key').length, icon: 'Warning', type: 'warning' },
        {
            key: 'ref',
            label: '被计划引用',
            value: rows.filter((r) => r.ref_count > 0).length,
            icon: 'Connection',
            type: '',
        },
    ];
});

async function fetchList() {
    loading.value = true;
    try {
        const data = await standardsApi.standards({
            keyword: keyword.value, category: category.value, enabled: enabled.value,
        });
        list.value = data.items || [];
    } catch (e) {
        /* 拦截器已提示 */
    } finally {
        loading.value = false;
    }
}

const dialogVisible = ref(false);
const saving = ref(false);
const emptyForm = () => ({
    id: null, category: 'switch', name: '', check_type: 'bool', standard: '', unit: '',
    method: '目视', check_cycle: 'daily', level: 'normal', handle_action: '',
    sort_order: 0, enabled: true,
});
const form = ref(emptyForm());

function openDialog(row) {
    form.value = row
        ? {
            id: row.id, category: row.category, name: row.name, check_type: row.check_type,
            standard: row.standard || '', unit: row.unit || '', method: row.method || '',
            check_cycle: row.check_cycle || 'daily', level: row.level || 'normal',
            handle_action: row.handle_action || '', sort_order: row.sort_order || 0,
            enabled: row.enabled !== false,
        }
        : emptyForm();
    dialogVisible.value = true;
}

async function save() {
    const f = form.value;
    if (!f.name) return ElMessage.warning('请填写标准名称');
    const payload = {
        category: f.category, name: f.name, check_type: f.check_type, standard: f.standard,
        unit: f.unit, method: f.method, check_cycle: f.check_cycle, level: f.level,
        handle_action: f.handle_action, sort_order: f.sort_order, enabled: f.enabled,
    };
    saving.value = true;
    try {
        if (f.id) await standardsApi.updateStandard(f.id, payload);
        else await standardsApi.createStandard(payload);
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
        await standardsApi.toggleStandard(row.id);
        fetchList();
    } catch (e) {
        /* 拦截器已提示 */
    }
}

async function deleteRow(row) {
    try {
        await ElMessageBox.confirm(
            `确定删除标准「${row.name}」? 已生成的点检计划不会受影响。`, '提示', { type: 'warning' });
    } catch (e) {
        return;
    }
    try {
        await standardsApi.deleteStandard(row.id);
        ElMessage.success('已删除');
        fetchList();
    } catch (e) {
        /* 拦截器已提示 */
    }
}

onMounted(fetchList);
</script>

<style scoped>
.standards-page .factor-row span:first-child {
    color: #909399;
}
</style>
