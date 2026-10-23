<template>
    <div v-if="result" class="chart-card mt-16">
        <div class="chart-title">
            最近一次下发：
            <el-tag :type="result.status === 'success' ? 'success' : 'danger'" size="small">
                {{ result.status }}
            </el-tag>
            <span class="text-muted" style="margin-left:8px">{{ result.duration_ms || 0 }}ms</span>
        </div>
        <div v-if="result.error || result.message" class="output-block exec-error">
            {{ result.error || result.message }}
        </div>
        <div v-if="result.commands && result.commands.length" class="output-block mt-8">
            {{ result.commands.join('\n') }}
        </div>
        <div v-if="result.output" class="output-block mt-8">{{ result.output }}</div>
    </div>
</template>

<script setup>
/** 设备 CLI 下发结果回显: 状态 / 耗时 / 命令 / 回显, 失败时把错误置顶 */
defineProps({
    result: { type: Object, default: null },
});
</script>

<style scoped>
.exec-error {
    color: #f48771;
}
</style>
