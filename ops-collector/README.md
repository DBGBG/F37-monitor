# 主动运维系统 (Proactive Operations & Maintenance System) — 数据采集层

> 本工程是三层架构中的**采集层**。系统全貌与接口清单见 **[docs/arch/ARCHITECTURE.md](../docs/arch/ARCHITECTURE.md)**。

```
ops-collector (Python 采集)  →  MySQL 8  →  ops-server (Spring Boot 业务)  →  ops-web (Vue3 展示)
```

采集层**只做四件事**：SNMP 采集、服务器/进程采集、SNMP Trap 接收、写数据库。
它**不监听任何 HTTP 端口**，不产生告警，不做告警判定/健康分析/点检/通知/配置下发——这些都由业务层负责。

---

## 1. 本层职责

| 能力 | 实现 | 落库表 |
|------|------|--------|
| 设备发现（扫描网段、识别 MOXA 交换机） | `backend/collectors/device_discovery.py` | `switch_devices` |
| 端口状态采集（admin/oper、速率、错误计数） | `backend/collectors/snmp_collector.py` | `port_status` |
| 端口流量采集（in/out bps、单播/广播） | 同上 | `port_traffic_stats` |
| 硬件健康采集（温度、电源、风扇） | 同上 | `hardware_health` |
| 服务器指标采集（Agent 9100 / SSH / WMI） | `backend/collectors/server_agent.py` | `server_metrics` |
| 关键进程监控 | `backend/collectors/process_monitor.py` | `process_status` |
| Trap 接收 → 按源 IP 定向重采一次 | `backend/traps/receiver.py` | 上述采集表 |
| 每轮采集心跳 | `backend/scheduler/task_scheduler.py` | `collector_heartbeats` |
| 执行业务层下发的跨层指令 | 同上（每 10s 拉取） | `collector_commands` |

采集任务清单（周期与开关存在 `collector_jobs` 表，每 5 分钟热加载，可在页面「系统管理 → 采集任务」调整）：

| job_id | 默认周期 | 默认超时 |
|--------|---------|---------|
| `snmp_poll_status` | 120s | 120s |
| `snmp_poll_traffic` | 300s | 180s |
| `snmp_poll_hardware` | 600s | 300s |
| `server_poll_all` | 30s | 120s |
| `process_monitor_all` | 60s | 120s |
| `device_rediscovery` | 1800s | 600s |

---

## 2. 目录结构

```
ops-collector/
├── backend/
│   ├── collectors/        # SNMP / 服务器 / 进程 / 设备发现
│   ├── traps/             # SNMP Trap 接收 (UDP 162)
│   ├── scheduler/         # 采集调度 + 心跳 + 跨层指令执行
│   ├── models/            # SQLAlchemy 2.0 映射 (表结构由 Flyway 建, 本层不建表)
│   ├── config.py          # 读 config/collector.yaml, 支持环境变量覆盖
│   ├── database.py        # aiomysql 异步引擎 (pool_size=10, pool_recycle=1800, pool_pre_ping)
│   └── daemon.py          # 采集层生命周期入口
├── config/
│   ├── collector.yaml     # 唯一有效配置 (MySQL / SNMP / Trap / 服务器采集 / 调度 / 日志)
│   ├── settings.yaml      # 遗留, 已退役, 仅供对照
│   └── devices.yaml       # 遗留, 设备清单已改为 discovery_groups / *_devices 表
├── scripts/
│   ├── start_all.py       # 一键启动入口 (预检 MySQL → 守护两层)
│   ├── supervisor.py      # 守护采集层 + 业务层两个进程
│   ├── start_service.cmd  # 前台启动快捷方式
│   ├── setup_autostart.ps1    # 注册开机计划任务 (ProactiveOpsMonitor)
│   └── remove_autostart.ps1   # 移除计划任务
├── legacy_reference/      # 迁移前的 FastAPI/告警/主动运维/点检/自动化实现 (只读对照)
├── logs/                  # 运行日志
├── data/                  # 遗留 SQLite 文件 (迁移前)
├── run.py                 # 采集层启动脚本
└── requirements.txt       # Python 依赖 (无 fastapi/uvicorn/netmiko/jinja2)
```

---

## 3. 快速开始

### 3.1 前置条件

- Python 3.10+（开发环境为 3.14）
- MySQL 8.0+（业务层的主动分析需要窗口函数）
- 业务层与前端已按 [docs/arch/ARCHITECTURE.md §7](../docs/arch/ARCHITECTURE.md) 构建完成

### 3.2 安装依赖

```powershell
D:\AI\Miniconda\python.exe -m pip install -r requirements.txt
```

关键依赖：`sqlalchemy[asyncio]` + `aiomysql` + `cryptography`（MySQL 8 `caching_sha2_password`）、
`puresnmp`、`paramiko`、`psutil`、`wmi`、`apscheduler`、`httpx`、`pyyaml`、`pyasn1`。

### 3.3 配置

编辑 [`config/collector.yaml`](config/collector.yaml)：

```yaml
mysql:
  host: "10.51.190.70"        # 与 ops-server/application.yml 保持一致
  port: 3306
  user: "test"
  password: "1234"
  database: "monitor"

snmp:
  version: "v2c"
  community: "public"          # 设备行 snmp_config 优先
  timeout: 2                   # 单次请求超时 (秒)
  retries: 1                   # 不可达设备快速失败, 避免超时风暴
  max_concurrent: 8            # 实测上限: 32/64 路并发下 bulkwalk 会集体超时
  batch_size: 16               # 每批设备数 (批内并行, 批间让步)
  discovery_interval_minutes: 30
  offline_backoff:
    enabled: true
    probe_after: 300           # 离线超过该秒数先轻量探活
  poll_intervals:              # 兜底周期, 以 collector_jobs 表为准 (见 V3/V4 迁移)
    port_status: 600
    traffic_stats: 900
    hardware_health: 1800

snmp_trap:
  enabled: true
  port: 162                    # 需在各设备上把 Trap 目的地址配为本机 IP
```

> `max_concurrent` / `batch_size` 不是越大越好: 工业交换机的 SNMP 处理能力有限,
> 并发过高只会让 bulkwalk 集体 2s 超时, 表现为"作业报成功但时序表没有新行"。
> 采集日志与 `collector_heartbeats.detail` 会区分 `collected/skipped/empty` 三个口径。

敏感项建议用环境变量覆盖，不留明文在文件里：
`OPS_MYSQL_HOST` / `OPS_MYSQL_PORT` / `OPS_MYSQL_USER` / `OPS_MYSQL_PASSWORD` / `OPS_MYSQL_DB`。

**设备清单不再写在 YAML 里**：

- 交换机扫描范围与凭据模板 → 页面「系统管理 → 扫描网段」（表 `discovery_groups`）
- 服务器清单与凭据 → 页面「服务器管理」新增（表 `server_devices`）

### 3.4 启动

```powershell
# 只启动采集层 (前台, Ctrl+C 停止)
python run.py

# 启动采集层 + 业务层 (推荐, 带崩溃自动重启)
python scripts\start_all.py

# 开机自启 (计划任务, SYSTEM 账户)
powershell -ExecutionPolicy Bypass -File scripts\setup_autostart.ps1
```

启动横幅会打印 MySQL 目标、各采集周期与 Trap 状态；本层不打印 HTTP 地址。

### 3.5 验证

```sql
-- 1. 设备已入库
SELECT id, ip, is_online, last_poll_time FROM switch_devices;

-- 2. 采集在跑 (最新一轮心跳)
SELECT component, phase, total, success, errors, duration_ms, last_beat_at
  FROM collector_heartbeats ORDER BY id DESC LIMIT 10;

-- 3. 跨层指令被执行
SELECT cmd, status, result, created_at, executed_at FROM collector_commands ORDER BY id DESC LIMIT 5;
```

页面侧：登录后进入「系统管理 → 采集层」，`采集层状态` 应为**在线**，各阶段显示最近一轮的成功/错误数。

---

## 4. 与业务层的边界

| | 采集层（本层） | 业务层 |
|---|---|---|
| 写 `switch_devices` / `server_devices` | 新增设备 + 采集字段 | 只改 `name` / `location` / `enabled` / `snmp_config` / `ssh_config` |
| 写时序表 | 是（唯一写入方） | 只读 |
| 写 `collector_heartbeats` | 是 | 只读 |
| 读 `collector_jobs` / `discovery_groups` | 只读 | 是（唯一写入方） |
| 读 `collector_commands` | 拉取并回写状态 | 写入指令 |
| 告警 / 基线 / 健康分 / 点检 / 通知 / 下发 / 清理 | **不做** | 全部在此 |

> 本层启动**不会建表**。表结构与种子数据由业务层的 Flyway 迁移脚本维护
> （`ops-server/src/main/resources/db/migration/V1__baseline.sql`、`V2__seed.sql`），
> 因此首次运行请先启动一次业务层。

---

## 5. 故障排查

| 现象 | 排查 |
|------|------|
| 启动即报连接错误 | MySQL 未启动或账号无权限；先执行 `sql/create_database.sql`，或用 `start_all.py` 的等待探测输出定位 |
| 页面显示采集层离线 | 看 `logs/collector.err.log`；确认 `collector_heartbeats` 是否在推进（超过 180s 无新记录即判定离线） |
| 交换机全部离线 | 核对 `discovery_groups` 的网段与 `snmp_config.community`；用 `snmpget -v2c -c public <ip> 1.3.6.1.2.1.1.1.0` 手工验证 |
| 服务器指标缺失 | 确认 Agent(9100) 或 SSH/WMI 凭据；连续失败 3 次会进入 300s 冷却，页面「服务器管理」可看方式配置 |
| Trap 不触发刷新 | 设备侧 Trap 目的地址需指向本机 UDP 162，并放行防火墙 |
| 改了周期不生效 | 周期由 `collector_jobs` 表热加载（最长 5 分钟）；页面「采集任务」保存时会顺带下发 `reload_config` |

日志位置：`logs/collector.out.log`、`logs/collector.err.log`、`logs/supervisor.log`、`logs/supervisor_heartbeat.json`。

---

## 6. 历史数据迁移

`data/ops_monitor.db`（旧 SQLite）中的历史数据可回灌 MySQL：

```powershell
# 先校验各表行数一致
D:\AI\Miniconda\python.exe ..\tools\migrate_sqlite_to_mysql.py --dry-run

# 正式导入 (保留原主键与时间值, 每批 1000 行)
D:\AI\Miniconda\python.exe ..\tools\migrate_sqlite_to_mysql.py

# 只导部分表 / 先清空目标表
D:\AI\Miniconda\python.exe ..\tools\migrate_sqlite_to_mysql.py --tables port_status,port_traffic_stats --truncate
```

> 必须在业务层首次启动（Flyway 建表完成）之后执行。

---

## 7. 相关文档

- [docs/arch/ARCHITECTURE.md](../docs/arch/ARCHITECTURE.md) — 分层边界、表所有权矩阵、110 个接口清单、部署与验收
- [AGENT.md](AGENT.md) — 运维策略：告警分级与响应、主动运维参数、定时任务、故障恢复流程
- [SPEC.md](SPEC.md) / [PLAN.md](PLAN.md) — 迁移前的规格与规划（历史文档）
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献指南
- [legacy_reference/](legacy_reference/) — 迁移前实现对照（不再维护）
