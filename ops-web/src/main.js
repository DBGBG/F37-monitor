import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIcons from '@element-plus/icons-vue';
import 'element-plus/dist/index.css';
import '@/styles/index.css';
import App from './App.vue';
import router from './router';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });

// 菜单/按钮图标全量注册, 视图里直接 <el-icon><HomeFilled /></el-icon>
Object.entries(ElementPlusIcons).forEach(([name, component]) => {
    app.component(name, component);
});

app.mount('#app');
