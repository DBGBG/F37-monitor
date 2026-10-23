import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

/**
 * 展示层构建配置.
 * 开发模式: 只代理 /api 到业务层 (Spring Boot 8080), 前端不再直连采集层;
 * 生产模式: dist/ 产物由 ops-server 的 maven-resources-plugin 复制进 jar 的 static/,
 *           由 Spring Boot 托管 (含 SPA 回退), 因此 base 用相对路径以兼容任意部署前缀.
 */
export default defineConfig({
    base: './',
    plugins: [vue()],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    server: {
        host: '127.0.0.1',
        port: 5173,
        proxy: {
            // SSE 走同一条 /api 前缀, http-proxy 默认流式透传, 不需要额外缓冲配置
            '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
            },
        },
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        chunkSizeWarningLimit: 1500,
    },
});
