<template>
    <div class="duties-page">
        <div class="page-title">职责分工</div>

        <!-- 预留界面: 菜单与路由已接入, 数据模型与接口尚未定义 -->
        <el-empty description="职责分工功能尚未开通">
            <div class="stat-label" style="max-width:520px;text-align:left">
                计划用于维护"人 — 设备/区域 — 职责"的对应关系：谁负责哪些交换机与服务器、
                谁执行哪一档点检、异常时向谁升级。当前可先在设备点检的计划里填写负责人，
                待分工模型确认后再接入本页。
            </div>
        </el-empty>
    </div>
</template>

<script setup>
/**
 * 职责分工页: 本期只挂菜单与空白界面, 不调用任何接口。
 * 需求确认后再补 agent 侧的表结构与 /api/duties 接口。
 */
</script>

<style scoped>
.duties-page :deep(.el-empty__description) {
    margin-bottom: 8px;
}
</style>
