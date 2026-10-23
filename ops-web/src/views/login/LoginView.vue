<template>
    <div class="login-page">
        <div class="login-card">
            <div class="login-title">主动运维系统</div>
            <div class="login-subtitle">覆盖交换机 / 服务器 / 进程 / 服务的主动监控与点检</div>
            <el-form :model="form" label-position="top" @keyup.enter="submit">
                <el-form-item label="用户名">
                    <el-input v-model="form.username" placeholder="用户名" size="large" clearable>
                        <template #prefix><el-icon><User/></el-icon></template>
                    </el-input>
                </el-form-item>
                <el-form-item label="口令">
                    <el-input v-model="form.password" type="password" placeholder="口令" size="large" show-password>
                        <template #prefix><el-icon><Lock/></el-icon></template>
                    </el-input>
                </el-form-item>
                <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="submit">
                    登录
                </el-button>
            </el-form>
            <div class="login-footer">
                采集层 (Python) 写入 MySQL · 业务层 (Spring Boot) 提供接口 · 展示层只访问 /api
            </div>
        </div>
    </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useRealtimeStore } from '@/stores/realtime';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const realtime = useRealtimeStore();

const form = reactive({ username: '', password: '' });
const loading = ref(false);

const submit = async () => {
    if (!form.username || !form.password) {
        ElMessage.warning('请输入用户名与口令');
        return;
    }
    loading.value = true;
    try {
        await auth.login(form.username.trim(), form.password);
        // 登录成功后立刻建立 SSE 通道, 顶栏"实时"标签与告警计数才是秒级更新
        realtime.connect();
        ElMessage.success('登录成功');
        const redirect = route.query.redirect;
        router.replace(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/dashboard');
    } catch (e) {
        /* 失败原因由 http 拦截器统一提示 */
    } finally {
        loading.value = false;
    }
};
</script>

<style scoped>
.login-btn {
    width: 100%;
}
</style>
