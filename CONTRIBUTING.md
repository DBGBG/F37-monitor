# 主动运维监控系统 - 协作指南

<div align="center">

**欢迎参与主动运维监控系统的开发与维护！**

[代码规范](#-代码规范) • [提交流程](#-提交流程) • [分支管理](#-分支管理) • [测试要求](#-测试要求)

</div>

---

## 📋 文档信息

- **版本**: v1.0.0
- **最后更新**: 2026-10-22
- **维护者**: 运维开发团队
- **适用范围**: 所有参与本项目开发与维护的人员

---

## 🙋 如何参与

### 参与方式

我们欢迎以下形式的贡献：

| 方式 | 说明 | 难度 |
|-----|------|------|
| 🐛 **报告 Bug** | 在 Issues 中提交 Bug 报告 | ⭐ |
| 💡 **提出新功能** | 在 Issues 中提交功能需求 | ⭐ |
| 📝 **改进文档** | 修正错误、补充遗漏 | ⭐⭐ |
| 🔧 **修复 Bug** | 提交修复 PR | ⭐⭐⭐ |
| ✨ **添加功能** | 提交新功能 PR | ⭐⭐⭐⭐ |
| 🏗️ **架构优化** | 提出并实施架构改进 | ⭐⭐⭐⭐⭐ |

### 新手入门

如果你是第一次参与本项目，建议按以下顺序熟悉：

1. **阅读文档**：阅读 [README.md](./README.md) 了解项目概况
2. **了解架构**：阅读 [SPEC.md](./SPEC.md) 了解技术架构
3. **运行系统**：按快速开始指南搭建开发环境
4. **查看待办**：阅读 [PLAN.md](./PLAN.md) 了解当前开发计划
5. **选择任务**：从 `good first issue` 标签的 Issue 开始

---

## 📏 代码规范

### Python 代码规范

#### 基本原则

- **Python 版本**：≥ 3.10
- **编码风格**：遵循 [PEP 8](https://peps.python.org/pep-0008/)
- **类型提示**：所有公共函数和方法必须添加类型提示
- **文档字符串**：所有模块、类、公共函数必须有 docstring

#### 命名规范

```python
# 类名：PascalCase
class SnmpCollector:
    pass

class AlertRecord(Base):
    pass

# 函数和变量：snake_case
def poll_all_status():
    pass

device_count = 0
active_alerts = []

# 常量：UPPER_SNAKE_CASE
MAX_CONCURRENT = 32
OID_SYS_NAME = "1.3.6.1.2.1.1.5.0"

# 私有成员：单下划线前缀
def _internal_method():
    pass

_private_var = "internal"

# 模块级私有：单下划线前缀
_module_cache = {}
```

#### 导入顺序

```python
# 1. 标准库
import asyncio
import logging
from datetime import datetime
from typing import Optional

# 2. 第三方库
from fastapi import APIRouter
from sqlalchemy import select, update
from puresnmp import Client, V2C

# 3. 本项目模块
from backend.config import config
from backend.database import async_session
from backend.models.device import SwitchDevice
```

#### 函数规范

```python
# ✅ 好的写法
async def poll_switch_status(
    self,
    switch: dict,
    *,
    timeout: float = 5.0,
    retries: int = 3,
) -> dict:
    """采集单台交换机的端口状态。

    Args:
        switch: 交换机设备信息字典，需包含 id、ip、community 等字段。
        timeout: 单次 SNMP 请求超时时间（秒）。
        retries: 失败重试次数。

    Returns:
        包含端口状态信息的字典，格式如下：
        {
            "ports": [...],
            "timestamp": "2026-10-22T10:00:00"
        }

    Raises:
        SnmpTimeoutError: SNMP 请求超时且重试耗尽。
        SnmpAuthError: SNMP 认证失败。
    """
    ...

# ❌ 不好的写法
def poll_status(sw, t=5, r=3):  # 缺少类型提示、命名不清晰
    ...
```

#### 异常处理

```python
# ✅ 好的写法 - 精确捕获，记录日志
try:
    result = await asyncio.wait_for(
        self._collect_device(switch),
        timeout=120
    )
except asyncio.TimeoutError:
    logger.error("设备 %s 采集超时(120s)，跳过本轮", switch["ip"])
    return None
except SnmpAuthError as e:
    logger.warning("设备 %s SNMP 认证失败: %s", switch["ip"], e)
    return None
except Exception as e:
    logger.exception("设备 %s 采集异常: %s", switch["ip"], e)
    return None

# ❌ 不好的写法 - 吞掉异常
try:
    result = await self._collect_device(switch)
except:
    pass  # 不要这样做！
```

#### 日志规范

```python
import logging
logger = logging.getLogger(__name__)

# 日志级别使用规范
logger.debug("详细调试信息，如 SNMP OID 值")
logger.info("关键业务流程，如采集完成、告警触发")
logger.warning("可恢复的异常，如超时重试、认证失败")
logger.error("不可恢复的错误，如采集失败、数据库异常")
logger.critical("系统级严重错误，如数据库无法连接")

# ✅ 好的写法 - 使用 %s 占位符
logger.info("发现 %d 台设备，在线 %d 台", total, online)
logger.error("设备 %s 采集失败: %s", device_ip, error_msg)

# ❌ 不好的写法 - 字符串拼接
logger.info(f"发现 {total} 台设备")  # 即使日志级别不匹配也会执行拼接
```

#### 异步编程规范

```python
# ✅ 异步函数中使用异步 IO
async def collect_all():
    async with asyncio.TaskGroup() as tg:
        for device in devices:
            tg.create_task(collect_device(device))

# ✅ 使用信号量控制并发
sem = asyncio.Semaphore(32)

async def collect_with_limit(device):
    async with sem:
        return await collect_device(device)

# ✅ 使用锁保护共享资源
db_lock = asyncio.Lock()

async def safe_db_write():
    async with db_lock:
        await db.commit()

# ❌ 不要在异步函数中阻塞
async def bad_example():
    time.sleep(1)  # 会阻塞整个事件循环！
    requests.get("http://...")  # 同步 HTTP 请求！
```

### 前端代码规范

#### JavaScript / Vue 规范

```javascript
// ✅ 使用 const / let，禁止 var
const MAX_RETRIES = 3;
let currentDevice = null;

// ✅ 使用箭头函数
const formatTime = (timestamp) => {
    return new Date(timestamp).toLocaleString('zh-CN');
};

// ✅ 使用模板字符串
const message = `设备 ${deviceName} 当前状态: ${status}`;

// ✅ 使用解构赋值
const { id, name, ip } = device;
const [first, ...rest] = items;

// ✅ 使用可选链和空值合并
const modelName = device?.info?.model ?? '未知型号';

// ✅ Vue 3 组合式 API 风格
import { ref, computed, onMounted } from 'vue';

export default {
    setup() {
        const devices = ref([]);
        const loading = ref(false);

        const onlineCount = computed(() =>
            devices.value.filter(d => d.is_online).length
        );

        onMounted(async () => {
            loading.value = true;
            try {
                devices.value = await fetchDevices();
            } finally {
                loading.value = false;
            }
        });

        return { devices, loading, onlineCount };
    }
};
```

#### 前端命名规范

```javascript
// 变量和函数：camelCase
const deviceList = [];
function formatAlertCount() {}

// 常量：UPPER_SNAKE_CASE
const MAX_RETRY_COUNT = 3;
const API_BASE_URL = '/api';

// 组件名：PascalCase
const DeviceCard = { ... };
const AlertPanel = { ... };

// CSS 类名：kebab-case（BEM 命名）
// .alert-panel
// .alert-panel__header
// .alert-panel__item--critical
```

### 配置文件规范

```yaml
# ✅ YAML 配置规范
# 使用 2 空格缩进
# 字符串使用双引号
# 布尔值使用 true / false
# 添加注释说明每个配置项

system:
  name: "主动运维监控系统"
  host: "0.0.0.0"
  port: 8080
  debug: false

# SNMP 采集配置
snmp:
  version: "v2c"           # 支持 v2c / v3
  community: "public"       # SNMP 团体名
  port: 161                 # SNMP 端口
  timeout: 2                # 超时时间（秒）
  retries: 3                # 重试次数
  max_concurrent: 32        # 最大并发数
  batch_size: 64            # 每批设备数
```

### SQL / 数据库规范

```python
# ✅ SQLAlchemy 模型规范
class AlertRecord(Base):
    """告警记录表 - 存储系统产生的所有告警"""
    __tablename__ = "alert_records"

    # 主键
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)

    # 业务字段（带注释）
    alert_id: Mapped[str] = mapped_column(
        String(100), unique=True,
        comment="告警唯一ID"
    )
    device_id: Mapped[str] = mapped_column(
        String(50), index=True,
        comment="设备ID"
    )

    # 时间字段
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now,
        comment="创建时间"
    )
```

---

## 📝 提交流程

### 1. 提交 Issue

#### Bug 报告

提交 Bug 时请包含以下信息：

```markdown
**Bug 描述**
简明扼要地描述遇到的问题。

**复现步骤**
1. 启动系统 `python run.py`
2. 访问 http://localhost:8080/switches
3. 等待 5 分钟
4. 页面卡住无响应

**预期行为**
页面应正常显示设备列表。

**实际行为**
页面卡住，浏览器控制台显示大量 SSE 消息。

**环境信息**
- 操作系统: Windows 11 26H2
- Python 版本: 3.12
- 浏览器: Chrome 120
- 设备数量: 107 台

**日志信息**
```
2026-10-22 10:00:00 [ERROR] 任务堆积: 端口状态采集 超时
```

**截图**
（如果适用）
```

#### 功能需求

```markdown
**需求描述**
希望能增加设备分组功能，按物理位置分组管理设备。

**使用场景**
工厂有多个车间，每个车间有若干交换机，希望能按车间查看设备状态。

**建议方案**
在设备列表中增加"分组"列，支持按分组过滤和查看。

**补充信息**
- 优先级：中
- 预计影响范围：前端 + 后端 API + 数据库
```

### 2. 提交 Pull Request

#### PR 流程

```
1. Fork 项目
   ↓
2. 创建功能分支
   git checkout -b feature/add-device-group
   ↓
3. 开发 & 测试
   编写代码，本地测试通过
   ↓
4. 提交代码
   git commit -m "feat: 添加设备分组功能"
   ↓
5. 推送分支
   git push origin feature/add-device-group
   ↓
6. 创建 PR
   填写 PR 模板，关联 Issue
   ↓
7. 代码审查
   至少 1 人审查通过
   ↓
8. CI 检查
   自动化测试通过
   ↓
9. 合并代码
   使用 Squash Merge 合并
```

#### PR 模板

```markdown
## 变更说明

### 变更类型
- [ ] 新功能 (feat)
- [ ] Bug 修复 (fix)
- [ ] 文档更新 (docs)
- [ ] 代码重构 (refactor)
- [ ] 性能优化 (perf)
- [ ] 测试相关 (test)
- [ ] 构建/工具 (chore)

### 变更内容
简要描述本次 PR 的变更内容。

### 关联 Issue
Closes #123

### 变更影响
- [ ] 无破坏性变更
- [ ] 需要更新配置文件
- [ ] 需要数据库迁移
- [ ] 需要更新文档

### 测试情况
- [ ] 已添加单元测试
- [ ] 所有测试通过
- [ ] 已手动测试

### 截图
（如涉及 UI 变更，请附截图）

### 检查清单
- [ ] 代码符合项目规范
- [ ] 已添加必要的注释
- [ ] 已更新相关文档
- [ ] 无新增 lint 警告
```

#### Commit 消息规范

本项目使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<类型>(<范围>): <描述>

[可选的正文]

[可选的脚注]
```

**类型列表**：

| 类型 | 说明 | 示例 |
|-----|------|------|
| `feat` | 新功能 | `feat(alerts): 添加告警聚合规则` |
| `fix` | Bug 修复 | `fix(snmp): 修复超时后任务堆积` |
| `docs` | 文档更新 | `docs(readme): 更新快速开始指南` |
| `style` | 代码格式 | `style: 统一日志格式` |
| `refactor` | 代码重构 | `refactor(collector): 重构批量采集逻辑` |
| `perf` | 性能优化 | `perf(snmp): 降低并发数减少CPU占用` |
| `test` | 测试相关 | `test(alerts): 添加告警引擎单元测试` |
| `chore` | 构建/工具 | `chore(deps): 升级 fastapi 到 0.104` |
| `ci` | CI/CD | `ci: 添加 GitHub Actions 工作流` |
| `revert` | 回滚 | `revert: 回滚 feat: 添加设备分组` |

**范围说明**：

| 范围 | 对应模块 |
|-----|---------|
| `snmp` | SNMP 采集器 |
| `alerts` | 告警引擎 |
| `proactive` | 主动运维引擎 |
| `api` | REST API |
| `frontend` | 前端 |
| `db` | 数据库 |
| `config` | 配置管理 |
| `scheduler` | 任务调度 |
| `ui` | 用户界面 |

**好的 Commit 示例**：

```
perf(snmp): 降低并发数并添加超时保护

- 将 max_concurrent 从 128 降至 32
- 为 poll_all_status 添加 120s 超时控制
- 为 poll_all_traffic 添加 180s 超时控制
- 批量推送设备更新事件，减少 SSE 消息量

Closes #42
```

```
fix(alerts): 修复告警去重逻辑导致告警风暴

告警去重窗口内相同设备+规则的告警应被抑制，
但原代码中 dedup_minutes 参数未正确传递，
导致同一设备产生大量重复告警。

修复方案：将 dedup_minutes 参数正确传递给告警引擎。

Fixes #56
```

---

## 🌿 分支管理

### 分支模型

本项目采用简化的 Git Flow 分支模型：

```
main          ●───────●───────────────────●──── 稳定发布
             /       /                   /
release/1.1  ───────●─────────────────── 发布准备
             /       /
develop    ●───●───●───●───●───●───●───● 开发主线
           /       /           /
feature/  ●───●───●  功能分支
           \
fix/        ●───●  修复分支
```

### 分支说明

| 分支 | 用途 | 生命周期 | 合并目标 |
|-----|------|---------|---------|
| **`main`** | 生产环境稳定版本 | 永久 | - |
| **`develop`** | 开发主线，集成所有功能 | 永久 | - |
| **`feature/*`** | 新功能开发 | 临时 | → `develop` |
| **`fix/*`** | Bug 修复 | 临时 | → `develop` |
| **`hotfix/*`** | 生产环境紧急修复 | 临时 | → `main` + `develop` |
| **`release/*`** | 发布准备与测试 | 临时 | → `main` + `develop` |

### 分支命名

```bash
# 功能分支
feature/add-device-group
feature/snmp-v3-support
feature/alert-aggregation

# 修复分支
fix/snmp-timeout-issue
fix/alert-dedup-bug
fix/memory-leak

# 紧急修复
hotfix/critical-security-patch
hotfix/database-corruption

# 发布分支
release/v1.1.0
release/v1.2.0
```

### 分支规则

1. **`main` 分支**：
   - 受保护，不允许直接推送
   - 只能通过 PR 合并
   - 每次合并必须打 Tag
   - 始终保持可部署状态

2. **`develop` 分支**：
   - 受保护，只能通过 PR 合并
   - 集成所有已完成的功能
   - 保持可测试状态

3. **功能分支**：
   - 从 `develop` 创建
   - 完成后合并回 `develop`
   - 合并后删除分支
   - 定期 rebase 保持同步

4. **紧急修复**：
   - 从 `main` 创建
   - 修复后同时合并到 `main` 和 `develop`
   - 合并后打 Tag

### Tag 规范

```bash
# 版本号格式
v<主版本>.<次版本>.<修订号>

# 示例
v1.0.0    # 首次正式发布
v1.1.0    # 新增功能
v1.1.1    # Bug 修复

# 创建 Tag
git tag -a v1.1.0 -m "release: v1.1.0 新增设备分组功能"
git push origin v1.1.0
```

---

## 🧪 测试要求

### 测试策略

```
┌─────────────────────────────────────────┐
│          E2E 测试 (端到端)                │  ← 少量关键路径
├─────────────────────────────────────────┤
│        集成测试 (模块间交互)              │  ← 核心业务流程
├─────────────────────────────────────────┤
│        单元测试 (函数/方法级别)           │  ← 大量覆盖
└─────────────────────────────────────────┘
```

### 测试覆盖率要求

| 模块 | 最低覆盖率 | 说明 |
|-----|----------|------|
| **告警引擎** | 90% | 核心业务逻辑 |
| **主动运维引擎** | 85% | EWMA 算法、健康评分 |
| **SNMP 采集器** | 80% | 数据采集核心 |
| **API 接口** | 75% | 参数校验、业务逻辑 |
| **工具函数** | 80% | 通用工具 |
| **整体项目** | 70% | 最低要求 |

### 单元测试

#### 编写规范

```python
# tests/test_alert_engine.py

import pytest
from datetime import datetime
from backend.alerts.alert_engine import AlertEngine
from backend.models.alert import AlertRule


class TestAlertEngine:
    """告警引擎单元测试"""

    @pytest.fixture
    def engine(self):
        """测试夹具：创建告警引擎实例"""
        return AlertEngine()

    @pytest.fixture
    def sample_device(self):
        """测试夹具：示例设备数据"""
        return {
            "id": "sw-test-01",
            "name": "测试交换机",
            "ip": "10.0.0.1",
            "is_online": True,
        }

    # ---- 告警触发测试 ----

    async def test_trigger_alert_when_rule_matched(
        self, engine, sample_device
    ):
        """测试：规则匹配时应触发告警"""
        rule = AlertRule(
            name="device_offline",
            condition=lambda d: not d["is_online"],
            severity="CRITICAL",
        )

        # 模拟设备离线
        sample_device["is_online"] = False
        alerts = await engine.check_rules(sample_device, [rule])

        assert len(alerts) == 1
        assert alerts[0].severity == "CRITICAL"
        assert alerts[0].device_id == "sw-test-01"

    async def test_no_alert_when_rule_not_matched(
        self, engine, sample_device
    ):
        """测试：规则不匹配时不应触发告警"""
        rule = AlertRule(
            name="device_offline",
            condition=lambda d: not d["is_online"],
            severity="CRITICAL",
        )

        # 设备在线，不应触发
        alerts = await engine.check_rules(sample_device, [rule])
        assert len(alerts) == 0

    # ---- 告警去重测试 ----

    async def test_dedup_within_window(self, engine, sample_device):
        """测试：去重窗口内相同告警应被抑制"""
        engine.dedup_minutes = 30

        alert1 = engine.create_alert(sample_device, "device_offline")
        alert2 = engine.create_alert(sample_device, "device_offline")

        # 第二次应被去重
        assert alert1 is not None
        assert alert2 is None

    async def test_no_dedup_after_window(self, engine, sample_device):
        """测试：去重窗口后应重新触发告警"""
        engine.dedup_minutes = 30

        alert1 = engine.create_alert(sample_device, "device_offline")

        # 模拟时间推移
        engine._dedup_cache.clear()

        alert2 = engine.create_alert(sample_device, "device_offline")
        assert alert1 is not None
        assert alert2 is not None
```

#### 运行测试

```bash
# 运行所有测试
pytest tests/

# 运行特定模块
pytest tests/test_alert_engine.py

# 运行特定测试
pytest tests/test_alert_engine.py::TestAlertEngine::test_trigger_alert

# 带覆盖率
pytest --cov=backend tests/

# 带详细输出
pytest -v --tb=short tests/

# 并行执行（加速）
pytest -n auto tests/
```

### 集成测试

```python
# tests/integration/test_collection_pipeline.py

import pytest
from httpx import AsyncClient
from backend.main import app


class TestCollectionPipeline:
    """采集流程集成测试"""

    @pytest.fixture
    async def client(self):
        """创建测试客户端"""
        async with AsyncClient(app=app, base_url="http://test") as ac:
            yield ac

    async def test_full_collection_cycle(self, client):
        """测试：完整采集周期"""
        # 1. 触发采集
        response = await client.post("/api/system/collect/now")
        assert response.status_code == 200

        # 2. 检查设备状态更新
        response = await client.get("/api/switches")
        assert response.status_code == 200
        devices = response.json()
        assert len(devices) > 0

        # 3. 检查端口状态记录
        response = await client.get("/api/switches/sw-test-01/ports")
        assert response.status_code == 200

    async def test_alert_to_notification(self, client):
        """测试：告警触发到通知发送"""
        # 1. 模拟设备离线
        await self._simulate_device_offline("sw-test-01")

        # 2. 等待告警引擎处理
        await asyncio.sleep(5)

        # 3. 检查告警记录
        response = await client.get("/api/alerts?device_id=sw-test-01")
        alerts = response.json()
        assert any(a["rule_name"] == "device_offline" for a in alerts)
```

### 性能测试

```python
# tests/performance/test_snmp_performance.py

import pytest
import time
from backend.collectors.snmp_collector import SnmpCollector


class TestSnmpPerformance:
    """SNMP 采集性能测试"""

    async def test_collection_time_under_limit(self):
        """测试：107台设备采集应在 120 秒内完成"""
        collector = SnmpCollector()

        start = time.monotonic()
        result = await collector.poll_all_status()
        elapsed = time.monotonic() - start

        assert elapsed < 120, f"采集耗时 {elapsed:.1f}s，超过 120s 限制"
        assert result["success_count"] > 0

    async def test_memory_usage_stable(self):
        """测试：连续采集 10 轮内存不应持续增长"""
        import psutil
        import os

        process = psutil.Process(os.getpid())
        initial_memory = process.memory_info().rss

        collector = SnmpCollector()
        for _ in range(10):
            await collector.poll_all_status()

        final_memory = process.memory_info().rss
        growth = (final_memory - initial_memory) / initial_memory

        assert growth < 0.1, f"内存增长 {growth:.1%}，可能存在内存泄漏"
```

### 前端测试

```javascript
// frontend/tests/dashboard.test.js

import { mount } from '@vue/test-utils';
import Dashboard from '@/js/dashboard.js';

describe('Dashboard', () => {
    it('渲染设备统计卡片', () => {
        const wrapper = mount(Dashboard, {
            props: {
                stats: {
                    total_devices: 107,
                    online_devices: 106,
                    offline_devices: 1,
                    active_alerts: 5,
                },
            },
        });

        expect(wrapper.find('.stat-card').exists()).toBe(true);
        expect(wrapper.text()).toContain('107');
    });

    it('点击刷新按钮触发数据更新', async () => {
        const wrapper = mount(Dashboard);
        const refreshBtn = wrapper.find('.refresh-btn');

        await refreshBtn.trigger('click');

        expect(wrapper.emitted('refresh')).toBeTruthy();
    });
});
```

### 测试环境配置

```yaml
# config/test-settings.yaml
# 测试环境专用配置

system:
  debug: true
  log_level: "DEBUG"

snmp:
  max_concurrent: 4       # 测试环境降低并发
  batch_size: 8
  timeout: 1
  retries: 1

database:
  url: "sqlite+aiosqlite:///data/test.db"

scheduler:
  enabled: false           # 测试时禁用定时任务
```

---

## 🔍 代码审查

### 审查清单

审查者在 Code Review 时需要关注以下方面：

#### 功能正确性
- [ ] 代码逻辑是否正确实现了需求？
- [ ] 边界条件是否处理？
- [ ] 异常路径是否覆盖？

#### 代码质量
- [ ] 命名是否清晰、符合规范？
- [ ] 函数是否过长（> 100 行）需要拆分？
- [ ] 是否有重复代码可以抽取？
- [ ] 注释是否充分、有价值？

#### 性能考量
- [ ] 是否有不必要的同步阻塞操作？
- [ ] 数据库查询是否高效（无 N+1 查询）？
- [ ] 并发控制是否合理？
- [ ] 是否有内存泄漏风险？

#### 安全性
- [ ] 用户输入是否经过校验？
- [ ] 是否有 SQL 注入风险？
- [ ] 敏感信息是否泄露？
- [ ] SNMP 团体名 / SSH 密钥是否安全处理？

#### 可维护性
- [ ] 是否易于理解和修改？
- [ ] 是否遵循现有架构模式？
- [ ] 配置项是否可通过配置文件调整？
- [ ] 日志是否充分便于排查？

#### 测试
- [ ] 是否添加了必要的测试？
- [ ] 测试是否覆盖了主要路径？
- [ ] 测试是否可靠（无随机失败）？

### 审查流程

```
1. 提交者创建 PR
   ↓
2. 指定审查者（至少 1 人）
   ↓
3. 审查者阅读代码，留下评论
   ↓
4. 提交者回复评论，修改代码
   ↓
5. 审查者确认修改，Approve
   ↓
6. CI 检查通过
   ↓
7. 合并代码
```

### 审查礼仪

- **尊重**：评论代码，不评论人
- **建设性**：提出改进建议，不仅指出问题
- **具体**：引用具体代码行，说明原因
- **及时**：收到审查请求后 24 小时内响应
- **学习**：把审查当作互相学习的机会

```
# ✅ 好的评论
"这里使用 `asyncio.wait_for` 添加超时控制很好 👍
不过建议把超时时间提取为配置项，方便不同环境调整。"

# ❌ 不好的评论
"这样写不对。"
```

---

## 📦 发布流程

### 发布检查清单

发布新版本前需要确认：

- [ ] 所有计划功能已合并到 `develop`
- [ ] 所有测试通过
- [ ] 代码审查完成
- [ ] 文档已更新（CHANGELOG、README 等）
- [ ] 性能测试达标
- [ ] 数据库迁移脚本已测试
- [ ] 配置文件变更已记录
- [ ] 已知问题已记录

### 发布步骤

```bash
# 1. 创建发布分支
git checkout develop
git pull origin develop
git checkout -b release/v1.1.0

# 2. 更新版本号
# 修改 run.py 或 __init__.py 中的版本号

# 3. 更新 CHANGELOG
# 整理本次变更的功能和修复

# 4. 最终测试
pytest tests/

# 5. 合并到 main
git checkout main
git merge --no-ff release/v1.1.0

# 6. 打 Tag
git tag -a v1.1.0 -m "release: v1.1.0"
git push origin main --tags

# 7. 合并回 develop
git checkout develop
git merge --no-ff release/v1.1.0
git push origin develop

# 8. 删除发布分支
git branch -d release/v1.1.0
```

---

## 🛠️ 开发工具推荐

| 工具 | 用途 | 安装 |
|-----|------|------|
| **Black** | Python 代码格式化 | `pip install black` |
| **Flake8** | Python 代码检查 | `pip install flake8` |
| **isort** | 导入排序 | `pip install isort` |
| **mypy** | 类型检查 | `pip install mypy` |
| **pytest** | 单元测试 | `pip install pytest` |
| **pre-commit** | Git 钩子 | `pip install pre-commit` |

### 配置 pre-commit 钩子

```yaml
# .pre-commit-config.yaml
repos:
  - repo: https://github.com/psf/black
    rev: 23.10.0
    hooks:
      - id: black
        language_version: python3.10

  - repo: https://github.com/pycqa/isort
    rev: 5.12.0
    hooks:
      - id: isort
        args: ["--profile", "black"]

  - repo: https://github.com/pycqa/flake8
    rev: 6.1.0
    hooks:
      - id: flake8
        args: ["--max-line-length=120"]
```

```bash
# 安装 pre-commit 钩子
pre-commit install

# 手动运行所有检查
pre-commit run --all-files
```

---

## 📞 获取帮助

- **项目文档**：[README.md](./README.md)、[SPEC.md](./SPEC.md)
- **开发计划**：[PLAN.md](./PLAN.md)
- **问题反馈**：提交 [GitHub Issue](https://github.com/your-org/moxa-monitor/issues)
- **团队联系**：运维开发团队

---

<div align="center">

**感谢每一位贡献者！你们的付出让这个项目变得更好。**

[回到顶部](#-如何参与)

</div>
