<template>
    <el-card class="stat-card" :class="type" shadow="hover" :body-style="{ padding: '20px' }">
        <div class="stat-value">{{ value }}<small v-if="suffix" class="stat-suffix">{{ suffix }}</small></div>
        <div class="stat-label">{{ label }}</div>
        <div v-if="icon" class="stat-icon">
            <el-icon :size="40"><component :is="icon"/></el-icon>
        </div>
        <div v-if="$slots.footer" class="stat-footer">
            <slot name="footer"/>
        </div>
    </el-card>
</template>

<script setup>
/** 总览/列表页顶部的统计卡片, 沿用迁移前 .stat-card 的视觉 */
defineProps({
    label: { type: String, required: true },
    value: { type: [String, Number], default: 0 },
    suffix: { type: String, default: '' },
    icon: { type: String, default: '' },
    type: { type: String, default: '' },
});
</script>

<style scoped>
.stat-suffix {
    font-size: 14px;
    font-weight: 500;
    margin-left: 2px;
}

.stat-footer {
    margin-top: 8px;
    font-size: 12px;
    color: #909399;
}
</style>
