# MOXA 主动运维监控系统 — 技术规格说明书 (SPEC)

> 版本: 1.0.0 ｜ 更新: 2026-10-17
> 面向 MOXA 工业交换机与服务器的**主动运维**平台：采集 → 动态基线 → 异常检测 → 健康评分 → 实时推送 → 告警/点检。

---

## 1. 系统概述

### 1.1 目标
- 对工业现场的 MOXA 交换机（PT / EDS / RKS 系列）与服务器进行统一监控。
- 从"被动响应"升级为"**主动运维**"：自学习正常基线、早期发现劣化、量化设备健康、按优先级排序处理。
- 保证**实时性**：秒级状态感知（事件驱动 + SSE 推送）、采集完成即评估告警。

### 1.2 关键指标
| 指标 | 目标 |
|---|---|
| 设备规模 | 100+ 交换机，可扩展 |
| 端口状态数据新鲜度 | ≤ 采集单轮耗时（秒级~数十秒） |
| 告警时延 | 一轮采集内 / Trap 亚秒级 |
| 前端刷新 | SSE 实时推送（断线自动重连，30s 轮询兜底） |

### 1.3 非目标
- 不做历史全量时序存储引擎（当前 SQLite + 保留策略足够）。
- 不做分布式/多实例部署（单进程 uvicorn；未来如需再引入共享总线）。

---

## 2. 技术栈

| 层 | 技术 |
|---|---|
| Web 框架 | FastAPI (≥0.115) + Uvicorn (标准版) |
| 数据库 | SQLite (WAL) + SQLAlchemy 2.x async + aiosqlite |
| SNMP | puresnmp (≥2.0.1) + pyasn1 |
| SSH 自动化 | netmiko + paramiko |
| 服务器采集 | psutil / wmi(Windows) / agent / ssh |
| 调度 | APScheduler (AsyncIOScheduler，Asia/Shanghai) |
| 前端 | Vue 3 (CDN) + Element Plus 2.9.1 + ECharts 5.5 + Axios |
| 实时通道 | Server-Sent Events (SSE, 原生 Text/Event Stream) |
| 配置 | PyYAML (settings.yaml / devices.yaml) |
| Python | 3.14 (base 环境 `D:\AI\Miniconda\python.exe`) |

---

## 3. 系统架构

### 3.1 分层
```
┌────────────────────────────  前端 (Vue3 SPA)  ────────────────────────────┐
│  总览 / 交换机 / 服务器 / 主动运维 / 自动化 / 告警 / 点检   +  实时推送客户端 │
│  realtime.js(SSE)  ── EventSource /api/stream ──┐                         │
└───────────────────────────────┬─────────────────┼─────────────────────────┘
                                │ REST (axios)    │ SSE (text/event-stream)
┌───────────────────────────────▼─────────────────▼─────────────────────────┐
│                          FastAPI 应用 (backend/main.py)                    │
│  API 路由: switches / servers / automation / alerts / system /             │
│            inspection / proactive / stream                                 │
│  生命周期: init_db → 设备发现 → 静态同步 → 引导实时态 → 调度器 → Trap 接收  │
└───┬───────────────┬───────────────┬───────────────┬───────────────┬───────┘
    │               │               │               │               │
┌───▼────┐   ┌──────▼─────┐   ┌─────▼──────┐   ┌────▼──────┐  ┌─────▼──────┐
│ 调度器 │   │  采集层    │   │  告警引擎  │   │ 主动运维  │  │ 实时层     │
│APSched.│   │ SNMP/服务器│   │规则/抑制/  │   │基线/异常/ │  │LiveState + │
│        │   │ 进程/发现  │   │根因/通知   │   │健康/窗口  │  │EventBus    │
└───┬────┘   └──────┬─────┘   └─────┬──────┘   └────┬──────┘  └─────┬──────┘
    │               │               │               │               │
    └───────────────┴───────┬───────┴───────────────┴───────────────┘
                            │  SQLAlchemy async
                    ┌───────▼────────┐        ┌──────────────┐
                    │  SQLite (WAL)  │        │ SNMP Trap    │
                    │ data/*.db      │        │ UDP 接收器   │
                    └────────────────┘        └──────────────┘
```

### 3.2 数据流（实时路径）
1. 调度器/`Trap` 触发 `SNMPCollector.poll_*` → puresnmp 在**独立线程池**执行（隔离阻塞）。
2. 采集结果批量落库（SQLite，WAL）并**更新内存实时态** `live`。
3. 一轮端口状态采集结束 → `live.publish_overview()` + `bus.publish("cycle")` → **立即**调用 `AlertEngine.check_all()`。
4. 告警创建/恢复 → `bus.publish("alert")`；告警统计变化 → `bus.publish("alerts")`。
5. SSE 端点把上述事件推送给所有前端订阅者；前端按事件类型局部刷新。

---

## 4. 目录结构

```
moxa-monitor/
├─ run.py                      # 入口: uvicorn backend.main:app
├─ requirements.txt
├─ SPEC.md / PLAN.md           # 本文档
├─ config/
│  ├─ settings.yaml            # 全局配置 (采集/告警/主动运维/保留...)
│  └─ devices.yaml             # 设备清单 (IP 段/服务器/VLAN 规划)
├─ data/                       # SQLite 数据库 (WAL)
├─ logs/                       # 守护进程/服务日志 (supervisor.py 运行后生成)
├─ scripts/
│  ├─ supervisor.py            # 守护进程: 崩溃自动重启 + 退避 + 心跳
│  ├─ start_service.cmd        # 以守护方式启动服务
│  ├─ setup_autostart.ps1      # 注册开机自启计划任务 (需管理员)
│  └─ remove_autostart.ps1
├─ backend/
│  ├─ main.py                  # FastAPI 应用 + 生命周期 + 设备发现/同步
│  ├─ config.py                # 配置加载 (单例)
│  ├─ database.py              # 引擎/会话/低延迟 PRAGMA/索引迁移
│  ├─ realtime.py              # LiveState 内存态 + EventBus 事件总线
│  ├─ api/                     # 各模块 REST 路由
│  ├─ collectors/              # snmp / server_agent / process_monitor / device_discovery
│  ├─ alerts/                  # alert_engine / notifiers
│  ├─ proactive/               # baseline / windows / health / engine
│  ├─ inspection/              # service
│  ├─ maintenance/             # cleanup (数据保留)
│  ├─ automation/              # ssh_executor / vlan_manager
│  ├─ traps/                   # receiver (SNMP Trap)
│  ├─ scheduler/               # task_scheduler
│  └─ models/                  # ORM 模型 (17 张表)
└─ frontend/
   ├─ index.html               # 单页 + 菜单 + 组件挂载
   ├─ css/style.css
   └─ js/  dashboard / switches / servers / proactive / automation / alerts /
           inspection / realtime / app
```

---

## 5. 数据模型 (17 张表)

| 表 | 模型 | 说明 | 关键字段 |
|---|---|---|---|
| `switch_devices` | SwitchDevice | 交换机基础信息 | ip(unique)、model、cpu_usage、is_online、snmp_config、ssh_config、enabled |
| `server_devices` | ServerDevice | 服务器基础信息 | ip(unique)、os_type、monitoring_config、processes_config、is_online |
| `port_status` | PortStatus | 端口**最新**状态(快照, DELETE+INSERT) | switch_id、port_index、admin/oper_status、speed_bps、vlan_id、port_mode |
| `port_traffic_stats` | PortTrafficStats | 端口流量**时序** | in/out_octets、in/out_unicast、in/out_errors、in/out_discards、*_rate_bps |
| `hardware_health` | HardwareHealth | 硬件健康**时序** | power1/2_status、power1/2_voltage、temperature、temperature_alarm、fan_*、overall_status |
| `server_metrics` | ServerMetrics | 服务器指标**时序** | cpu/memory/disk/net/load/uptime |
| `process_status` | ProcessStatus | 进程/服务状态 | process_name、is_running、is_critical、port_open |
| `alert_records` | AlertRecord | 告警记录 | alert_id(unique)、rule_name、severity、status、triggered_at、notified |
| `config_audit_logs` | ConfigAuditLog | 自动化配置审计 | 操作、目标、命令、结果 |
| `inspection_plans` | InspectionPlan | 点检计划 | schedule_type、cron_expr、target_scope、enabled |
| `inspection_items` | InspectionItem | 点检项模板 | check_type、standard、required |
| `inspection_tasks` | InspectionTask | 点检任务实例 | planned_time、due_time、status、result、abnormal_count |
| `inspection_records` | InspectionRecord | 点检执行明细 | item_name、value、result |
| `metric_baselines` | MetricBaseline | 动态基线(EWMA) | device_id+metric(unique)、mean、variance、sample_count |
| `anomaly_events` | AnomalyEvent | 主动运维异常事件 | metric、severity、value、baseline、deviation、score、status |
| `device_health` | DeviceHealth | 设备健康评分 | device_id(pk)、score、grade、factors、summary |
| `maintenance_windows` | MaintenanceWindow | 维护窗口 | start/end_time、device_type、device_ids、enabled |

> 时间统一为**本地朴素时间**（Asia/Shanghai，无 DST）。

---

## 6. 采集层

### 6.1 SNMP 采集 (`collectors/snmp_collector.py`)
- 使用 puresnmp，`V2C(community)`。
- **阻塞隔离**：所有 SNMP 调用经 `run_in_executor` 在**独立线程池**（每线程自带事件循环）执行，避免阻塞主事件循环。
- 批量：`multiget` 多 OID 单 PDU；`bulkwalk(bulk_size)`。
- 三类采集，**阶段互斥**（`phase_lock`，交换机 SNMP 处理能力有限）：
  - `poll_all_status`：端口状态 + 系统信息（含在线判定）
  - `poll_all_traffic`：端口流量时序（含速率/错包率计算）
  - `poll_all_hardware`：温度/电源/风扇/告警
- 在线判定：能取到 `sys_name/sys_descr/sys_object_id` 任一即视为在线（避免 puresnmp 吞异常导致 `is_online` 恒真）。
- 并发：`max_concurrent`(默认 128) 线程池；`batch_size`(默认 128) 每批设备数（一批覆盖全部设备以压缩单轮耗时）。
- **OID 自适应**：PT 系列(8691.7.x)、EDS 系列(8691.2.6.x)、NOS 系列(8691.600/602, RKS-G4000) 分别适配。

| 指标 | OID 位置（相对产品根） |
|---|---|
| 型号 / 固件 / 插槽 | `.1.2.0` / `.1.4.0` / `.1.5.0` |
| 电源1/2 | `.1.10.1.0` / `.1.10.2.0` |
| 温度 | `.1.11.1.6.0` |
| 告警子树 | `.1.11.2.N.0` |
| NOS 温度 | `8691.602.1.3.2.3.0` |

### 6.2 服务器采集 (`collectors/server_agent.py`)
- 方式：agent(9100) / wmi(Windows) / ssh(Linux)。
- 采集 CPU/内存/磁盘/网络/负载/运行时长。

### 6.3 进程监控 (`collectors/process_monitor.py`)
- 按 `devices.yaml` 每台服务器配置的关键进程/端口做可达性与运行状态检查。

### 6.4 设备发现 (`collectors/device_discovery.py`)
- 按 `devices.yaml.switch_groups` 的 IP 段扫描 SNMP 可达设备，入库/更新。
- 启动时执行，运行中每 5 分钟重新发现。

### 6.5 SNMP Trap (`traps/receiver.py`)
- 监听 UDP（默认 162），收到任意 Trap 取**源 IP** → 定向刷新该设备状态 + 立即告警评估。
- 无需解析具体 OID，靠源 IP 定位；相同设备 `min_interval`(默认 5s) 抑制风暴。
- 需在 MOXA 设备上把 Trap 目的地址配置为本机 IP。

---

## 7. 实时层 (`backend/realtime.py` + `api/stream.py`)

### 7.1 组件
- **LiveState**：内存中设备/告警"最新态"（`switches`/`servers`/`alerts`/`last_cycle`），提供 `overview()` 与 `snapshot()`，避免仪表盘每次扫库。
- **EventBus**：进程内发布订阅，每订阅者一个 `asyncio.Queue`，慢消费自动丢弃最旧事件。

### 7.2 SSE 端点 `GET /api/stream`
| 事件 | 触发 | 载荷 |
|---|---|---|
| `snapshot` | 连接建立 | 全量首屏（overview + devices + last_cycle） |
| `overview` | 设备/告警统计变化 | overview 对象 |
| `device` | 单台设备状态更新 | 设备摘要 |
| `alert` | 告警触发/恢复 | action/device/rule/severity/title |
| `alerts` | 活跃/严重计数变化 | {active, critical} |
| `cycle` | 一次采集周期完成 | phase/success/errors/duration |
| `trap` | 收到 Trap | source/size |

每 15s 发送 `: ping` 心跳；前端 `realtime.js` 断线指数退避重连（2s→30s）。

---

## 8. 告警引擎 (`alerts/alert_engine.py`)

### 8.1 规则（`settings.yaml -> alerts.rules`）
| 规则 | 级别 | 条件 |
|---|---|---|
| `switch_offline` | CRITICAL | 交换机 SNMP 不可达 |
| `switch_port_down` | WARNING | 端口 **up→down 跳变**（避免未接线端口误报） |
| `switch_power_failure` | CRITICAL | 电源1/2 = 故障(2) |
| `switch_cpu_high` | WARNING | CPU > 阈值 |
| `server_cpu_high` / `server_memory_high` / `server_disk_high` | WARNING | 指标 > 阈值 |
| `server_process_down` | CRITICAL | 关键进程未运行 |

### 8.2 机制
- **唯一告警 ID**：`md5("{device_id}:{rule_name}")[:16]`；同规则 upsert（避免唯一约束冲突）。
- **自动恢复**：条件消失 → `_resolve_rule` 置 `resolved`。
- **根因吸收**：设备离线时抑制其端口子告警；在线时自动恢复离线告警。
- **维护窗口抑制**：窗口内不产生告警。
- **防重入**：`check_all` 加锁，定时检查与"采集后即时检查"互斥。
- **事件驱动**：端口状态采集完成即评估；同时保留 30s 定时兜底。
- **通知**：邮件/钉钉（`notifiers.py`，可扩展），仅未通知的活动告警发送。

---

## 9. 主动运维 (`proactive/`)

### 9.1 组件
- `baseline.py`：EWMA 动态基线 + `evaluate`（z 分数判定）。
- `windows.py`：`MaintenanceService` 维护窗口判定（30s TTL 缓存）。
- `health.py`：`compute_health` 0–100 分 + 等级（优/良/中/差）。
- `engine.py`：`ProactiveEngine.analyze()` 主流程 + `build_recommendations`。

### 9.2 算法要点
- **EWMA 更新**：正常样本按 `ewma_alpha` 快速跟随；**异常样本按 `outlier_damping`(0.1) 阻尼**，防止故障值把基线带偏导致持续故障停报。
- **异常判定**：`z = |value-mean|/std`，需同时满足 `z ≥ anomaly_k` 且 `|value-mean| ≥ min_deviation`，且方向符合 `direction`(up)。
- **样本不足不判定**：`sample_count ≥ min_samples` 才判定（预热）。
- 监控指标：`temperature / cpu / memory / disk / port_error`（端口误码由累计计数器增量得出）。

### 9.3 健康评分
按 `health_weights` 从 100 扣分：online(30) / power(15) / temperature(15) / anomalies(20) / alerts(20)。

### 9.4 维护窗口
窗口内抑制主动异常与告警；用于计划停电/割接/升级。

### 9.5 参数（`settings.yaml -> proactive`）
`enabled` / `analyze_interval(180)` / `ewma_alpha(0.15)` / `min_samples(20)` / `anomaly_k(4.0)` / `outlier_damping(0.1)` / `dedup_minutes(30)` / `escalate_severity(CRITICAL)` / `port_error_window_hours(2)` / `metrics{}` / `health_weights{}`。

---

## 10. 设备点检 (`inspection/`)

- 计划 = 目标设备（全部/指定）+ 周期（daily/weekly/monthly/cron，统一转 5 段 cron）+ 检查项模板。
- 任务实例：按计划周期为每台设备生成（唯一约束 plan+device+planned_time），支持 run-now。
- 执行：逐项记录结果，统计异常项；**异常项仅点检页标记，不产生告警**。
- 履历与统计：按计划/设备/时间段查询。

---

## 11. 自动化配置 (`automation/`)

- 基于 netmiko SSH：VLAN 创建/删除、端口 access/trunk、端口 toggle、批量 VLAN 规划下发、原始命令。
- 所有操作写入 `config_audit_logs` 审计。

---

## 12. API 清单

> 完整交互式文档：`GET /docs`

### 系统 / 总览
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/system/status` | 系统状态 + 调度任务 |
| GET | `/api/system/health` | 自监控（DB 体积/WAL/表行数/新鲜度） |
| GET | `/api/system/maintenance/stats` | 维护统计 |
| POST | `/api/system/maintenance/cleanup` | 手动触发数据清理 |
| POST | `/api/system/sync-devices` | 重新加载配置并同步 |
| POST | `/api/system/rediscover` | 手动设备发现 |
| GET | `/api/dashboard/overview` | 仪表盘总览 |
| GET | `/api/realtime/status` | 实时子系统状态 |
| GET | `/api/stream` | **SSE 实时事件流** |

### 交换机 `/api/switches`
`GET /summary`、`GET /list`、`GET /{id}/detail`、`GET /{id}/ports`、`GET /{id}/traffic`、`GET /{id}/hardware`

### 服务器 `/api/servers`
`GET /summary`、`GET /list`、`GET /{id}/detail`、`GET /{id}/metrics`、`GET /{id}/processes`

### 告警 `/api/alerts`
`GET /summary`、`GET /list`、`POST /acknowledge`、`POST /resolve`

### 自动化 `/api/automation`
`POST /vlan/create`、`POST /vlan/delete`、`POST /port/access`、`POST /port/trunk`、`POST /port/toggle`、`POST /vlan/batch`、`POST /raw-command`、`GET /audit-logs`、`GET /vlan-plan`

### 主动运维 `/api/proactive`
`GET /overview`、`GET /health`、`GET /health/{id}`、`GET /anomalies`、`POST /anomalies/{id}/acknowledge`、`POST /anomalies/{id}/resolve`、`GET /recommendations`、`GET /baselines`、`POST /analyze`、`GET/POST /maintenance/windows`、`PUT /maintenance/windows/{id}`、`POST /maintenance/windows/{id}/toggle`、`DELETE /maintenance/windows/{id}`

### 点检 `/api/inspection`
`GET /templates`、`GET /devices`、`GET /plans`、`GET /plans/{id}`、`POST /plans`、`PUT /plans/{id}`、`DELETE /plans/{id}`、`POST /plans/{id}/toggle`、`POST /plans/{id}/run-now`、`GET /tasks`、`GET /tasks/{id}`、`POST /tasks/{id}/start`、`POST /tasks/{id}/execute`、`POST /tasks/{id}/skip`、`GET /history`、`GET /stats`

---

## 13. 配置参考 (`config/settings.yaml`)

| 段 | 关键项 | 默认 |
|---|---|---|
| `system` | host/port/debug/log_level | 0.0.0.0/8080/false/INFO |
| `database` | url/echo | sqlite+aiosqlite/data/moxa_monitor.db |
| `snmp` | timeout/retries/bulk_size/max_concurrent/batch_size | 2/3/50/128/128 |
| `snmp.poll_intervals` | port_status/traffic_stats/hardware_health | 60/120/300 |
| `snmp_trap` | enabled/port/min_interval | true/162/5 |
| `server_monitor` | poll_interval/agent_port/wmi_timeout | 30/9100/10 |
| `process_monitor` | poll_interval | 60 |
| `alerts` | enabled/check_interval/dedup_window/rules{} | true/30/300 |
| `proactive` | 见 §9.5 | analyze_interval 180 |
| `inspection` | task_generation_interval/default_grace_hours | 60/12 |
| `notifications` | email/dingtalk | 均 disabled |
| `retention` | metrics_days/alerts_days/anomalies_days/audit_days/inspection_days | 30/90/180/365/0 |

`config/devices.yaml`：`switch_groups[]`(IP 段)、`switches[]`、`servers[]`、`vlan_plan[]`。

---

## 14. 调度任务 (`scheduler/task_scheduler.py`)

| ID | 名称 | 触发 |
|---|---|---|
| `snmp_poll_status` | SNMP 端口状态/系统信息 | interval 60s（max_instances=1） |
| `snmp_poll_traffic` | SNMP 端口流量 | interval 120s |
| `snmp_poll_hardware` | SNMP 硬件健康 | interval 300s |
| `server_poll_all` | 服务器资源 | interval 30s |
| `process_monitor_all` | 进程/服务 | interval 60s |
| `alert_check` | 告警规则检查 | interval 30s（+ 采集后即时触发） |
| `device_rediscovery` | 设备重新发现 | interval 5min |
| `data_cleanup` | 数据保留清理 | cron 每日 03:30 |
| `inspection_generate` | 点检任务生成 | interval 60s |
| `proactive_analyze` | 主动运维分析 | interval 180s |

---

## 15. 数据保留 (`maintenance/cleanup.py`)

- 按 `retention.*` 天数删除过期：`port_traffic_stats`、`hardware_health`、`server_metrics`、`process_status`、`alert_records`、`anomaly_events`（仅删已恢复）、`config_audit_logs`、点检履历。
- 每日 03:30 自动执行，可手动触发。

---

## 16. 部署与运维

### 16.1 启动
```
scripts\start_service.cmd        # 推荐: 经守护进程启动
# 或直接: python run.py
```

### 16.2 守护 (`scripts/supervisor.py`)
- 子进程异常退出自动拉起（5→10→…→60s 退避，稳定 300s 后重置）。
- 日志：`logs/supervisor.log`、`logs/service.out.log`、`logs/service.err.log`。
- 心跳：`logs/supervisor_heartbeat.json`。

### 16.3 开机自启
```
# 需管理员: 注册 SYSTEM 计划任务 (AtStartup + 失败重启)
powershell -ExecutionPolicy Bypass -File scripts\setup_autostart.ps1
```

### 16.4 数据库调优
每个连接应用：`journal_mode=WAL`、`synchronous=NORMAL`、`temp_store=MEMORY`、`cache_size=-65536`(64MB)、`mmap_size=268435456`(256MB)。

---

## 17. 前端

- 单页应用，菜单项 → `currentPage`，组件为全局 `const XxxPage = { template, setup }`。
- 页面：`dashboard` / `switches` / `servers` / `proactive` / `automation` / `alerts` / `inspection`。
- `realtime.js`：单例 `EventSource`，`Realtime.on(event, fn)` 订阅；顶部显示实时通道状态。
- 告警页对"告警风暴"做去抖刷新。

---

## 18. 非功能需求

| 维度 | 设计 |
|---|---|
| 性能 | SNMP 并发 128 + 批量 128；采集隔离线程池；SQLite 低延迟 PRAGMA |
| 实时性 | Trap(亚秒) + 事件驱动告警 + SSE 推送 |
| 可靠性 | 守护进程自动重启；WAL；告警防重入；Trap 风暴抑制；离线不覆盖端口状态 |
| 可观测 | `/api/system/health`、`/api/realtime/status`、心跳文件、结构化日志 |
| 可维护 | 配置外置；幂等索引迁移；保留策略；审计日志 |
| 安全 | SSH/SNMP 凭据存于 YAML（生产建议改为密文/环境变量）；API 暂无鉴权（内网部署） |
