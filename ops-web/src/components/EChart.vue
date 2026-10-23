<template>
    <div ref="host" class="chart-container" :style="{ height: height }"></div>
</template>

<script setup>
/**
 * ECharts 容器: 负责实例创建、option 更新、窗口自适应与销毁.
 * 迁移前各页面自己写 window.addEventListener('resize', ...), 集中到这里统一处理.
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';

const props = defineProps({
    option: { type: Object, default: () => ({}) },
    height: { type: String, default: '320px' },
    /** true 时合并配置而不是整体替换 (折线图追加数据点用) */
    notMerge: { type: Boolean, default: true },
});

const host = ref(null);
let chart = null;
let observer = null;

const render = () => {
    if (!chart || !props.option) {
        return;
    }
    chart.setOption(props.option, props.notMerge);
};

const resize = () => {
    if (chart) {
        chart.resize();
    }
};

onMounted(() => {
    chart = echarts.getInstanceByDom(host.value) || echarts.init(host.value);
    render();
    window.addEventListener('resize', resize);
    // 侧边栏折叠/卡片显隐会改变容器宽度, 用尺寸监听兜住
    if (typeof ResizeObserver !== 'undefined') {
        observer = new ResizeObserver(resize);
        observer.observe(host.value);
    }
});

onBeforeUnmount(() => {
    window.removeEventListener('resize', resize);
    if (observer) {
        observer.disconnect();
        observer = null;
    }
    if (chart) {
        chart.dispose();
        chart = null;
    }
});

watch(() => props.option, render, { deep: true });
</script>
