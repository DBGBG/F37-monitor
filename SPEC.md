# MOXA 主动运维监控系统 - 技术规格说明书

## 📋 文档信息

- **版本**: v1.0.0
- **最后更新**: 2026-10-21
- **维护者**: 运维开发团队
- **适用范围**: 全体开发人员、运维人员

---

## 1. 系统概述

### 1.1 项目定位

MOXA 主动运维监控系统是一个面向工业网络环境的**主动式运维监控平台**，不仅限于 MOXA 品牌设备，支持多厂商网络设备和服务器的统一监控与管理。

**核心理念**：
- **主动发现**：自动发现网络中的设备，无需手动配置
- **主动预警**：基于 EWMA 动态基线的异常检测，在故障发生前预警
- **主动推送**：实时状态变化通过 SSE 主动推送到前端
- **智能分析**：健康评分、趋势预测、根因分析

### 1.2 系统目标

1. **秒级感知**：设备状态变化在 60 秒内推送至前端
2. **零配置**：自动发现网络中的 SNMP 设备
3. **智能告警**：基于动态基线的异常检测，减少误报
4. **可视化运维**：实时仪表盘、告警管理、点检管理
5. **高可扩展**：模块化设计，支持自定义采集器和告警规则

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    前端层 (Vue 3 + Element Plus)          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 仪表盘   │ │ 交换机   │ │ 服务器   │ │ 告警管理 │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└─────────────────────────────────────────────────────────┘
                            ↕ SSE / REST API
┌─────────────────────────────────────────────────────────┐
│                 API 网关层 (FastAPI)                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 设备 API │ │ 告警 API │ │ 点检 API │ │ 系统 API │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│                   业务逻辑层                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ 采集调度器   │ │ 告警引擎     │ │ 主动运维引擎 │    │
│  │ (APScheduler)│ │ (规则匹配)   │ │ (EWMA基线)   │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ SNMP 采集器  │ │ 服务器采集器 │ │ 进程监控器   │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│                   数据存储层                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ SQLite (WAL) │ │ 内存实时态   │ │ 事件总线     │    │
│  │ (持久化)     │ │ (LiveState)  │ │ (EventBus)   │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│                   设备通信层                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ SNMP v2c/v3  │ │ SSH 自动化   │ │ Agent 采集   │    │
│  │ (puresnmp)   │ │ (asyncssh)   │ │ (HTTP/WS)    │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 模块划分

```
moxa-monitor/
├── backend/              # 后端核心模块
│   ├── api/             # REST API 接口层
│   │   ├── switches.py      # 交换机管理 API
│   │   ├── servers.py       # 服务器管理 API
│   │   ├── alerts.py        # 告警管理 API
│   │   ├── inspection.py    # 点检管理 API
│   │   ├── proactive.py     # 主动运维 API
│   │   ├── automation.py    # 自动化配置 API
│   │   ├── stream.py        # SSE 实时推送 API
│   │   └── system.py        # 系统管理 API
│   │
│   ├── collectors/      # 数据采集器
│   │   ├── snmp_collector.py    # SNMP 交换机采集
│   │   ├── server_agent.py      # 服务器资源采集
│   │   ├── process_monitor.py   # 进程/服务监控
│   │   └── device_discovery.py  # 设备自动发现
│   │
│   ├── alerts/          # 告警处理
│   │   ├── alert_engine.py      # 告警规则引擎
│   │   └── notifiers.py         # 通知管理器
│   │
│   ├── proactive/       # 主动运维
│   │   ├── engine.py            # 主动分析引擎
│   │   ├── baseline.py          # EWMA 动态基线
│   │   ├── health.py            # 健康评分算法
│   │   └── windows.py           # 维护窗口管理
│   │
│   ├── inspection/      # 点检管理
│   │   └── service.py           # 点检任务服务
│   │
│   ├── automation/      # 自动化配置
│   │   ├── ssh_executor.py      # SSH 命令执行器
│   │   └── vlan_manager.py      # VLAN 管理器
│   │
│   ├── scheduler/       # 任务调度
│   │   └── task_scheduler.py    # APScheduler 调度器
│   │
│   ├── traps/           # SNMP Trap
│   │   └── receiver.py          # Trap 接收器
│   │
│   ├── models/          # 数据库模型
│   │   ├── device.py            # 设备表
│   │   ├── port_status.py       # 端口状态表
│   │   ├── hardware_health.py   # 硬件健康表
│   │   ├── server_metrics.py    # 服务器指标表
│   │   ├── alert.py             # 告警记录表
│   │   ├── inspection.py        # 点检任务表
│   │   ├── proactive.py         # 主动运维表
│   │   └── config_audit.py      # 配置审计表
│   │
│   ├── maintenance/     # 数据维护
│   │   └── cleanup.py           # 数据清理服务
│   │
│   ├── config.py        # 配置管理器
│   ├── database.py      # 数据库初始化
│   ├── realtime.py      # 实时状态管理
│   └── main.py          # FastAPI 主入口
│
├── frontend/            # 前端静态资源
│   ├── index.html       # 单页应用入口
│   ├── css/
│   │   └── style.css    # 全局样式
│   ├── js/
│   │   ├── app.js       # Vue 应用主模块
│   │   ├── dashboard.js # 仪表盘页面
│   │   ├── switches.js  # 交换机管理
│   │   ├── servers.js   # 服务器管理
│   │   ├── alerts.js    # 告警管理
│   │   ├── inspection.js # 点检管理
│   │   ├── proactive.js # 主动运维
│   │   ├── automation.js # 自动化配置
│   │   └── realtime.js  # SSE 客户端
│   └── components/      # Vue 组件
│
├── config/              # 配置文件
│   ├── settings.yaml    # 全局配置
│   └── devices.yaml     # 设备清单
│
├── data/                # 数据目录
│   └── moxa_monitor.db  # SQLite 数据库
│
├── logs/                # 日志目录
│   ├── service.out.log  # 标准输出
│   ├── service.err.log  # 错误日志
│   └── supervisor.log   # 守护进程日志
│
├── scripts/             # 运维脚本
│   ├── setup_autostart.ps1    # 开机自启配置
│   └── remove_autostart.ps1   # 移除自启
│
└── run.py               # 启动脚本
```

### 2.3 技术栈

#### 后端技术栈

| 组件 | 技术选型 | 版本 | 用途 |
|-----|---------|------|------|
| **Web 框架** | FastAPI | 0.104+ | 异步 REST API |
| **ASGI 服务器** | Uvicorn | 0.24+ | 高性能 HTTP 服务 |
| **ORM** | SQLAlchemy | 2.0+ | 异步数据库操作 |
| **数据库** | SQLite | 3.35+ | 轻量级持久化 |
| **任务调度** | APScheduler | 3.10+ | 定时任务管理 |
| **SNMP** | puresnmp | 2.0+ | SNMP 协议通信 |
| **SSH** | asyncssh | 2.14+ | SSH 自动化 |
| **HTTP 客户端** | httpx | 0.25+ | Agent 通信 |
| **配置管理** | PyYAML | 6.0+ | YAML 配置解析 |

#### 前端技术栈

| 组件 | 技术选型 | 版本 | 用途 |
|-----|---------|------|------|
| **框架** | Vue.js | 3.3+ | 响应式 UI |
| **UI 库** | Element Plus | 2.4+ | 企业级组件库 |
| **图表** | ECharts | 5.4+ | 数据可视化 |
| **HTTP** | Axios | 1.6+ | REST API 调用 |
| **实时通信** | EventSource | - | SSE 客户端 |
| **构建工具** | 无（原生 ES Module） | - | 零配置部署 |

#### 运行环境

| 组件 | 要求 |
|-----|------|
| **Python** | 3.10+ |
| **操作系统** | Windows 10/11, Windows Server 2019+ |
| **内存** | ≥ 4GB（推荐 8GB） |
| **磁盘** | ≥ 10GB 可用空间 |
| **网络** | 支持 SNMP (UDP 161/162) |

---

## 3. 核心功能模块

### 3.1 SNMP 采集器 (snmp_collector.py)

#### 功能概述

通过 SNMP 协议采集网络交换机的端口状态、流量统计、硬件健康等数据。

#### 核心特性

- **自动适配**：支持 MOXA PT/EDS/IKS/ICS/TN 系列、NOS v4.x 系列
- **并发控制**：Semaphore + ThreadPoolExecutor，避免网络拥塞
- **批量采集**：按批次并行处理，压缩单轮采集时间
- **超时保护**：每类采集任务独立超时控制
- **状态推送**：采集完成后批量推送设备更新事件

#### 采集类型

| 采集类型 | 间隔 | 超时 | OID 示例 |
|---------|------|------|---------|
| **端口状态** | 120s | 120s | `1.3.6.1.2.1.2.2.1.8` (ifOperStatus) |
| **流量统计** | 300s | 180s | `1.3.6.1.2.1.2.2.1.10` (ifInOctets) |
| **硬件健康** | 600s | 300s | `1.3.6.1.4.1.8691.7.15.1.11.1.6.0` (温度) |
| **系统信息** | 120s | 120s | `1.3.6.1.2.1.1.5.0` (sysName) |

#### 设备发现流程

```
1. 扫描 IP 范围 (ping 阶段, 并发 100)
   ↓
2. 存活 IP 进行 SNMP 探测 (并发 50)
   ↓
3. 获取 sysDescr/sysName/sysLocation
   ↓
4. 判断是否为 MOXA 设备
   ↓
5. 入库并同步到实时态
```

### 3.2 告警引擎 (alert_engine.py)

#### 功能概述

基于规则的告警系统，支持告警去重、抑制、自动恢复。

#### 告警级别

| 级别 | 颜色 | 说明 | 示例 |
|-----|------|------|------|
| **INFO** | 蓝色 | 信息提示 | 设备重启、配置变更 |
| **WARNING** | 黄色 | 警告 | 端口 Down、CPU 使用率 > 80% |
| **CRITICAL** | 红色 | 严重 | 设备离线、电源故障、温度过高 |

#### 告警规则

```yaml
# 交换机告警规则
switch_offline:
  severity: "CRITICAL"
  description: "交换机离线(SNMP 不可达)"

switch_port_down:
  severity: "WARNING"
  description: "交换机端口 Down"

switch_power_failure:
  severity: "CRITICAL"
  description: "电源故障"

# 服务器告警规则
server_cpu_high:
  threshold: 90
  severity: "WARNING"
  description: "服务器 CPU 使用率过高"

server_memory_high:
  threshold: 90
  severity: "WARNING"
  description: "服务器内存使用率过高"
```

#### 告警生命周期

```
1. 触发条件满足
   ↓
2. 检查维护窗口（是否抑制）
   ↓
3. 生成/更新告警记录（去重）
   ↓
4. 发送通知（邮件/钉钉/短信）
   ↓
5. 前端实时推送
   ↓
6. 人工确认（acknowledged）
   ↓
7. 条件恢复 → 自动标记为 resolved
```

### 3.3 主动运维引擎 (proactive/engine.py)

#### 功能概述

基于 EWMA（指数加权移动平均）的动态基线异常检测系统。

#### 核心算法

**EWMA 基线更新**：
```
baseline[t] = α * x[t] + (1 - α) * baseline[t-1]
variance[t] = α * (x[t] - baseline[t])² + (1 - α) * variance[t-1]
```

其中：
- `α` = 平滑系数（默认 0.15）
- `x[t]` = 当前样本值
- `baseline[t]` = 当前基线
- `variance[t]` = 当前方差

**异常判定**：
```
z_score = |x[t] - baseline[t]| / sqrt(variance[t])
is_anomaly = (z_score > k) AND (|x[t] - baseline[t]| > min_deviation)
```

其中：
- `k` = z 分数阈值（默认 4.0）
- `min_deviation` = 最小绝对偏离（防止噪音触发）

#### 健康评分算法

```python
def compute_health(device_state):
    score = 100
    
    # 离线扣分
    if not device_state.is_online:
        score -= 30
    
    # 电源故障扣分
    if device_state.power_status != "normal":
        score -= 15
    
    # 温度异常扣分
    if device_state.temperature > threshold:
        score -= 15
    
    # 未处理异常扣分
    score -= 20 * len(open_anomalies)
    
    # 活动告警扣分
    score -= 20 * len(active_alerts)
    
    return max(0, score)
```

#### 异常事件生命周期

```
1. 检测到指标偏离基线
   ↓
2. 创建 AnomalyEvent (status=open)
   ↓
3. 同类异常合并（dedup_minutes 窗口内）
   ↓
4. 严重度达到阈值 → 升级为 AlertRecord
   ↓
5. 人工确认 (status=acknowledged)
   ↓
6. 指标恢复正常 → 自动关闭 (status=resolved)
```

### 3.4 点检管理 (inspection/service.py)

#### 功能概述

自动生成点检任务、跟踪执行状态、逾期提醒。

#### 点检计划

```yaml
# 每日点检
- name: "交换机日常巡检"
  schedule: "0 9 * * *"  # 每天 9:00
  devices: "all_switches"
  items:
    - "检查设备在线状态"
    - "检查端口指示灯"
    - "检查温度告警"

# 每周点检
- name: "服务器深度巡检"
  schedule: "0 10 * * 1"  # 每周一 10:00
  devices: "all_servers"
  items:
    - "检查 CPU/内存/磁盘"
    - "检查系统日志"
    - "检查服务运行状态"
```

#### 任务状态

| 状态 | 说明 |
|-----|------|
| **pending** | 待执行 |
| **in_progress** | 执行中 |
| **completed** | 已完成 |
| **overdue** | 逾期 |
| **skipped** | 跳过 |

### 3.5 实时推送 (realtime.py + stream.py)

#### 功能概述

基于 SSE (Server-Sent Events) 的实时状态推送系统。

#### 事件类型

| 事件 | 说明 | 触发时机 |
|-----|------|---------|
| **snapshot** | 首屏全量快照 | SSE 连接建立时 |
| **device** | 单台设备更新 | 采集完成后（已优化为批量） |
| **devices_batch** | 批量设备更新 | 一批采集完成后 |
| **overview** | 统计信息更新 | 每次采集周期结束 |
| **alert** | 告警事件 | 告警触发/恢复时 |
| **cycle** | 采集周期完成 | 一轮采集结束后 |

#### 推送流程

```
1. SNMP 采集器获取设备状态
   ↓
2. 更新内存实时态 (LiveState)
   ↓
3. 发布事件到事件总线 (EventBus)
   ↓
4. SSE 推送到所有订阅客户端
   ↓
5. 前端实时更新 UI（无需刷新）
```

---

## 4. 数据库设计

### 4.1 核心表结构

#### switch_devices (交换机设备表)

| 字段 | 类型 | 说明 |
|-----|------|------|
| `id` | VARCHAR(50) PK | 设备 ID（如 sw-10-51-200-57） |
| `name` | VARCHAR(200) | 设备名称 |
| `ip` | VARCHAR(50) UNIQUE | IP 地址 |
| `model` | VARCHAR(100) | 设备型号 |
| `location` | VARCHAR(200) | 物理位置 |
| `firmware_version` | VARCHAR(100) | 固件版本 |
| `serial_number` | VARCHAR(100) | 序列号 |
| `total_ports` | INTEGER | 端口总数 |
| `uptime_seconds` | INTEGER | 运行时长（秒） |
| `cpu_usage` | FLOAT | CPU 使用率 |
| `memory_usage` | FLOAT | 内存使用率 |
| `is_online` | BOOLEAN | 是否在线 |
| `last_poll_time` | DATETIME | 最后采集时间 |
| `snmp_config` | JSON | SNMP 配置 |
| `ssh_config` | JSON | SSH 配置 |
| `enabled` | BOOLEAN | 是否启用 |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |

#### port_status (端口状态表)

| 字段 | 类型 | 说明 |
|-----|------|------|
| `id` | INTEGER PK | 自增主键 |
| `switch_id` | VARCHAR(50) FK | 交换机 ID |
| `port_index` | INTEGER | 端口索引 |
| `port_name` | VARCHAR(100) | 端口名称 |
| `admin_status` | INTEGER | 管理状态（1=up, 2=down） |
| `oper_status` | INTEGER | 运行状态（1=up, 2=down） |
| `speed_bps` | BIGINT | 端口速率（bps） |
| `mac_address` | VARCHAR(50) | MAC 地址 |
| `vlan_id` | INTEGER | VLAN ID |
| `port_mode` | VARCHAR(20) | 端口模式（access/trunk/hybrid） |
| `last_change` | DATETIME | 状态最后变更时间 |
| `updated_at` | DATETIME | 更新时间 |

**索引**：
- `ix_port_status_switch_id` ON `port_status(switch_id)`

#### port_traffic_stats (端口流量统计表)

| 字段 | 类型 | 说明 |
|-----|------|------|
| `id` | INTEGER PK | 自增主键 |
| `switch_id` | VARCHAR(50) FK | 交换机 ID |
| `port_index` | INTEGER | 端口索引 |
| `timestamp` | DATETIME | 采集时间 |
| `in_octets` | BIGINT | 入方向字节数 |
| `out_octets` | BIGINT | 出方向字节数 |
| `in_unicast_pkts` | BIGINT | 入方向单播包数 |
| `out_unicast_pkts` | BIGINT | 出方向单播包数 |
| `in_errors` | BIGINT | 入方向错误包数 |
| `out_errors` | BIGINT | 出方向错误包数 |
| `in_discards` | BIGINT | 入方向丢包数 |
| `out_discards` | BIGINT | 出方向丢包数 |
| `in_rate_bps` | FLOAT | 入方向速率（bps） |
| `out_rate_bps` | FLOAT | 出方向速率（bps） |
| `in_error_rate` | FLOAT | 入方向错包率 |
| `out_error_rate` | FLOAT | 出方向错包率 |

**索引**：
- `ix_port_traffic_switch_port_ts` ON `port_traffic_stats(switch_id, port_index, timestamp)`

#### alert_records (告警记录表)

| 字段 | 类型 | 说明 |
|-----|------|------|
| `id` | INTEGER PK | 自增主键 |
| `alert_id` | VARCHAR(100) UNIQUE | 告警唯一 ID |
| `device_id` | VARCHAR(50) | 设备 ID |
| `device_name` | VARCHAR(200) | 设备名称 |
| `device_type` | VARCHAR(20) | 设备类型（switch/server） |
| `rule_name` | VARCHAR(100) | 规则名称 |
| `severity` | VARCHAR(20) | 严重级别（INFO/WARNING/CRITICAL） |
| `title` | VARCHAR(500) | 告警标题 |
| `description` | TEXT | 告警描述 |
| `metric_value` | VARCHAR(100) | 触发指标值 |
| `threshold` | VARCHAR(100) | 告警阈值 |
| `status` | VARCHAR(20) | 状态（active/acknowledged/resolved） |
| `acknowledged_by` | VARCHAR(100) | 确认人 |
| `resolved_at` | DATETIME | 恢复时间 |
| `resolved_by` | VARCHAR(100) | 恢复操作人 |
| `notified` | BOOLEAN | 是否已通知 |
| `notify_channels` | VARCHAR(200) | 通知渠道 |
| `triggered_at` | DATETIME | 触发时间 |
| `updated_at` | DATETIME | 更新时间 |

**索引**：
- `ix_alert_records_status_triggered` ON `alert_records(status, triggered_at)`

### 4.2 数据库优化

#### WAL 模式配置

```python
# database.py
PRAGMA journal_mode=WAL        # 启用 WAL 模式，支持并发读写
PRAGMA synchronous=NORMAL      # 降低 fsync 频率
PRAGMA temp_store=MEMORY       # 临时表进内存
PRAGMA cache_size=-65536       # 64MB 页缓存
PRAGMA mmap_size=268435456     # 256MB 内存映射
```

#### 索引策略

- **高频查询字段**：`switch_id`, `device_id`, `timestamp`
- **复合索引**：`(switch_id, port_index, timestamp)` 支持多条件过滤
- **状态索引**：`(status, triggered_at)` 支持告警列表排序

#### 数据保留策略

```yaml
retention:
  metrics_days: 30        # 指标数据保留 30 天
  alerts_days: 90         # 告警记录保留 90 天
  anomalies_days: 180     # 异常事件保留 180 天
  audit_days: 365         # 审计日志保留 365 天
```

---

## 5. API 接口规范

### 5.1 通用规范

#### 请求格式

- **协议**：HTTP/HTTPS
- **方法**：GET / POST / PUT / DELETE
- **Content-Type**：`application/json`
- **认证**：暂无（内网环境）

#### 响应格式

**成功响应**：
```json
{
  "status": "success",
  "data": { ... },
  "message": "操作成功"
}
```

**错误响应**：
```json
{
  "status": "error",
  "code": "ERROR_CODE",
  "message": "错误描述"
}
```

**分页响应**：
```json
{
  "status": "success",
  "data": {
    "items": [ ... ],
    "total": 100,
    "page": 1,
    "page_size": 50
  }
}
```

### 5.2 核心接口

#### 设备管理

**GET /api/switches/list**
- 描述：获取交换机列表
- 参数：
  - `page` (int): 页码，默认 1
  - `page_size` (int): 每页数量，默认 50
  - `keyword` (str): 搜索关键词
  - `online_only` (bool): 仅显示在线设备

**GET /api/switches/{device_id}/ports**
- 描述：获取交换机端口列表
- 参数：
  - `device_id` (str): 设备 ID

**POST /api/system/rediscover**
- 描述：手动触发设备重新发现

#### 告警管理

**GET /api/alerts/list**
- 描述：获取告警列表
- 参数：
  - `status` (str): 告警状态（active/acknowledged/resolved）
  - `severity` (str): 严重级别（INFO/WARNING/CRITICAL）
  - `page` (int): 页码
  - `page_size` (int): 每页数量

**POST /api/alerts/{alert_id}/acknowledge**
- 描述：确认告警
- 参数：
  - `alert_id` (str): 告警 ID
  - `acknowledged_by` (str): 确认人

**GET /api/alerts/summary**
- 描述：获取告警统计

#### 实时推送

**GET /api/stream**
- 描述：SSE 实时事件流
- 响应：`text/event-stream`
- 事件类型：snapshot / device / overview / alert / cycle

**GET /api/realtime/status**
- 描述：获取实时子系统状态

#### 仪表盘

**GET /api/dashboard/overview**
- 描述：获取仪表盘总览数据
- 响应：
```json
{
  "switches": { "total": 107, "online": 106, "offline": 1 },
  "servers": { "total": 2, "online": 0, "offline": 2 },
  "alerts": { "active": 0, "critical": 0 },
  "total_devices": 109,
  "online_devices": 106,
  "online_rate": 97.2
}
```

---

## 6. 配置项说明

### 6.1 全局配置 (settings.yaml)

#### 系统配置

```yaml
system:
  name: "MOXA 主动运维监控系统"
  host: "0.0.0.0"
  port: 8080
  debug: false
  log_level: "INFO"
```

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `name` | str | "MOXA 主动运维监控系统" | 系统名称 |
| `host` | str | "0.0.0.0" | 监听地址 |
| `port` | int | 8080 | 监听端口 |
| `debug` | bool | false | 调试模式 |
| `log_level` | str | "INFO" | 日志级别 |

#### SNMP 配置

```yaml
snmp:
  version: "v2c"
  community: "public"
  port: 161
  timeout: 2
  retries: 3
  bulk_size: 50
  max_concurrent: 32
  batch_size: 64
  poll_intervals:
    port_status: 120
    traffic_stats: 300
    hardware_health: 600
```

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `version` | str | "v2c" | SNMP 版本 |
| `community` | str | "public" | 团体名 |
| `port` | int | 161 | SNMP 端口 |
| `timeout` | int | 2 | 单次请求超时（秒） |
| `retries` | int | 3 | 重试次数 |
| `bulk_size` | int | 50 | GETBULK 最大行数 |
| `max_concurrent` | int | 32 | 最大并发数 |
| `batch_size` | int | 64 | 每批设备数 |
| `poll_intervals.port_status` | int | 120 | 端口状态采集间隔（秒） |
| `poll_intervals.traffic_stats` | int | 300 | 流量统计采集间隔（秒） |
| `poll_intervals.hardware_health` | int | 600 | 硬件健康采集间隔（秒） |

#### 主动运维配置

```yaml
proactive:
  enabled: true
  analyze_interval: 300
  ewma_alpha: 0.15
  min_samples: 20
  anomaly_k: 4.0
  outlier_damping: 0.1
  dedup_minutes: 30
  escalate_severity: "CRITICAL"
```

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| `enabled` | bool | true | 是否启用 |
| `analyze_interval` | int | 300 | 分析间隔（秒） |
| `ewma_alpha` | float | 0.15 | EWMA 平滑系数 |
| `min_samples` | int | 20 | 建立基线所需最少样本 |
| `anomaly_k` | float | 4.0 | z 分数阈值 |
| `outlier_damping` | float | 0.1 | 异常样本学习率 |
| `dedup_minutes` | int | 30 | 去重窗口（分钟） |
| `escalate_severity` | str | "CRITICAL" | 自动升级告警级别 |

### 6.2 设备配置 (devices.yaml)

#### 交换机配置

```yaml
switches:
  - id: "sw-core-01"
    name: "核心交换机-01"
    ip: "10.51.200.1"
    model: "PT-7828"
    location: "机房A"
    snmp:
      version: "v2c"
      community: "public"
    ssh:
      username: "admin"
      password: "password"
    enabled: true
```

#### 服务器配置

```yaml
servers:
  - id: "srv-web-01"
    name: "Web服务器-01"
    ip: "192.168.1.100"
    os_type: "windows"
    location: "机房A"
    monitoring:
      cpu: true
      memory: true
      disk: true
    processes:
      - name: "nginx"
        port: 80
    enabled: true
```

---

## 7. 性能优化建议

### 7.1 并发控制

**问题**：SNMP 线程池过高导致 CPU 占用飙升

**解决方案**：
```python
# snmp_collector.py
self.executor = ThreadPoolExecutor(
    max_workers=min(self.snmp_config.get("max_concurrent", 128), 32),
    thread_name_prefix="snmp",
)
```

**预期效果**：CPU 占用降低 75%

### 7.2 采集频率优化

**问题**：采集频率过高导致网络和设备负担

**解决方案**：
```yaml
# settings.yaml
poll_intervals:
  port_status: 120      # 从 60s 降至 120s
  traffic_stats: 300    # 从 120s 降至 300s
  hardware_health: 600  # 从 300s 降至 600s
```

**预期效果**：网络负载降低 50-60%

### 7.3 批量推送

**问题**：单台设备单独推送导致 SSE 消息风暴

**解决方案**：
```python
# snmp_collector.py - _run_batches()
# 采集完成后批量推送
if updated_device_ids and label == "状态":
    devices_data = [live.switches[did] for did in updated_device_ids]
    bus.publish("devices_batch", devices_data)
```

**预期效果**：SSE 消息数量减少 99%

### 7.4 超时保护

**问题**：采集任务堆积导致事件循环阻塞

**解决方案**：
```python
# snmp_collector.py
async def poll_all_status(self):
    try:
        stats = await asyncio.wait_for(
            self._run_batches(self.poll_switch_status, "状态"),
            timeout=120
        )
        await self._after_cycle(stats, alert=True)
    except asyncio.TimeoutError:
        logger.error("采集任务超时，跳过本轮")
```

**预期效果**：防止任务堆积，保证系统响应性

---

## 8. 故障排查指南

### 8.1 常见问题

#### 问题 1：设备全部离线

**可能原因**：
- 网络不通
- SNMP 配置错误
- 设备 SNMP 服务未启动

**排查步骤**：
```bash
# 1. 检查网络连通性
ping 10.51.200.1

# 2. 检查 SNMP 配置
snmpget -v2c -c public 10.51.200.1 1.3.6.1.2.1.1.1.0

# 3. 查看系统日志
tail -f logs/service.err.log
```

#### 问题 2：告警风暴

**可能原因**：
- 网络设备批量离线
- 告警去重窗口过短

**解决方案**：
```yaml
# settings.yaml
alerts:
  dedup_window: 300  # 增加去重窗口（秒）
```

#### 问题 3：前端页面卡顿

**可能原因**：
- SSE 消息频率过高
- 前端未做节流处理

**解决方案**：
- 已优化为批量推送
- 前端添加节流逻辑（每 5 秒最多处理一次同类事件）

### 8.2 日志分析

**日志位置**：
- `logs/service.out.log`：标准输出
- `logs/service.err.log`：错误日志
- `logs/supervisor.log`：守护进程日志

**关键日志模式**：
```
# 设备发现
[INFO] backend.collectors.device_discovery: SNMP 发现: 44 台设备

# 采集完成
[INFO] backend.collectors.snmp_collector: 批量采集[状态]完成: 全部 106 台成功

# 告警触发
[WARNING] backend.alerts.alert_engine: 告警触发: [CRITICAL] [设备名] 设备离线

# 任务超时
[ERROR] backend.collectors.snmp_collector: 端口状态采集任务超时(120s)，跳过本轮
```

---

## 9. 附录

### 9.1 SNMP OID 参考

#### 标准 MIB-II

| OID | 说明 |
|-----|------|
| `1.3.6.1.2.1.1.1.0` | sysDescr |
| `1.3.6.1.2.1.1.5.0` | sysName |
| `1.3.6.1.2.1.1.6.0` | sysLocation |
| `1.3.6.1.2.1.2.2.1.8` | ifOperStatus |
| `1.3.6.1.2.1.2.2.1.10` | ifInOctets |

#### MOXA PT 系列

| OID | 说明 |
|-----|------|
| `1.3.6.1.4.1.8691.7.15.1.2.0` | 型号名 |
| `1.3.6.1.4.1.8691.7.15.1.4.0` | 固件版本 |
| `1.3.6.1.4.1.8691.7.15.1.11.1.6.0` | 设备温度 |
| `1.3.6.1.4.1.8691.7.15.1.10.1.0` | 电源 1 状态 |
| `1.3.6.1.4.1.8691.7.15.1.10.2.0` | 电源 2 状态 |

### 9.2 术语表

| 术语 | 说明 |
|-----|------|
| **EWMA** | 指数加权移动平均（Exponentially Weighted Moving Average） |
| **SSE** | 服务器推送事件（Server-Sent Events） |
| **WAL** | 预写日志（Write-Ahead Logging） |
| **z-score** | 标准分数，用于衡量数据点与均值的距离 |
| **SNMP** | 简单网络管理协议（Simple Network Management Protocol） |

---

## 10. 版本历史

### v1.0.0 (2026-10-21)

**新增功能**：
- ✅ 设备自动发现
- ✅ SNMP 状态采集
- ✅ 告警引擎
- ✅ 主动运维引擎（EWMA 基线）
- ✅ 点检管理
- ✅ 实时推送（SSE）
- ✅ 性能优化（并发控制、批量推送、超时保护）

**性能优化**：
- SNMP 并发数从 128 降至 32
- 采集频率降低 50-60%
- SSE 消息减少 99%
- 添加任务超时保护

---

**文档结束**
