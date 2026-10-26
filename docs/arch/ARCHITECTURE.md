# 主动运维系统 (Proactive Operations & Maintenance System) — 三层架构说明

> 命名口径: 对外可见的系统名统一为**主动运维系统**（英文 Proactive Operations & Maintenance System），
> 覆盖交换机 / 服务器 / 进程 / 服务的主动监控与点检；文中出现的 MOXA 只表示被监控设备的厂商/型号事实。
> 代码标识的现状: 目录名 ops-collector / ops-server / ops-web、环境变量 OPS_*、自定义配置前缀 ops.* 与线程名前缀 ops- 均已改用 ops 词根；
> 仍保留的 MOXA 只有三类——Java 包名 com.moxa.monitor 与主类名、jar/npm 产物名 (moxa-server.jar、moxa-web)、以及被监控设备的厂商事实 (SNMP 企业号 8691 的 PT/EDS/NOS 私有 OID 与品牌判定)。

> 版本 v2.0.0（2026-10-22）。本文描述改造后的最终形态：数据采集 / 后端业务 / 前端展示三层分离，
> 以及分层边界、表所有权、接口清单、启动顺序与部署脚本。

---

## 1. 总体结构

```
d:\程序开发\主动运维系统\
├── ops-collector\      采集层  Python 3.10+   只采集 + 写库 + Trap 接收, 不监听任何 HTTP 端口
├── ops-server\       业务层  Spring Boot 3.3.5 / JDK 17   读库 + 业务逻辑 + 鉴权 + SSE + 托管前端产物
├── ops-web\          展示层  Vue 3.4 + Vite 5 + Element Plus 2.9 + ECharts 5.5   只调 /api/**
├── sql\               建库脚本 create_database.sql
├── tools\             历史数据迁移 migrate_sqlite_to_mysql.py
├── docs\              本文档
└── logs\              运行时日志 (实际写入 ops-collector\logs)
```

### 数据流

```
 交换机 (SNMP/Trap)                服务器 (Agent 9100 / SSH / WMI)
        │                                   │
        └──────────┬────────────────────────┘
                   ▼
        ┌─────────────────────┐   写入采集表 + 心跳
        │ ops-collector (Py)   │ ─────────────────┐
        │ 采集层 · 唯一写入方 │                    │
        └─────────────────────┘                   ▼
                   ▲          ┌──────────────────────────────┐
        读取扫描范围/采集周期   │        MySQL 8.0             │
        读取跨层指令            │  27 张表 · 表所有权见 §3     │
                                └──────────────┬───────────────┘
                   │                           │ 只读采集表 / 读写业务表
        ┌──────────┴──────────┐                ▼
        │ collector_commands  │ ◄──── ┌──────────────────────┐
        │ (Java → Python)     │       │ ops-server (Java)   │
        └─────────────────────┘       │ 业务层 · REST+SSE    │
                                      └──────────┬───────────┘
                                                 │ /api/**  (JSON, snake_case)
                                      ┌──────────▼───────────┐
                                      │ ops-web (Vue3)      │
                                      │ 展示层 · 浏览器      │
                                      └──────────────────────┘
```

### 三条原则性约束

1. **Python 不返回任何给前端的数据** — FastAPI 路由、静态托管、SSE 总线全部移除，
   历史实现保留在 `ops-collector/legacy_reference/` 供对照。
2. **Java 不触碰 SNMP/WMI 采集逻辑** — 设备 CLI 配置下发（JSch）除外，这是业务动作而非采集。
3. **Vue3 只访问 `/api/**`** — 开发期由 Vite 代理到 `http://127.0.0.1:8080`，
   生产期由 Spring Boot 直接托管 `dist/` 产物（含 SPA fallback）。

---

## 2. 各层职责

| 层 | 工程 | 入口 | 职责 | 明确不做 |
|----|------|------|------|----------|
| 采集 | `ops-collector` | `run.py` → `backend/daemon.py` | 设备发现、SNMP 状态/流量/硬件采集、服务器指标与进程采集、Trap 接收后定向重采、写心跳、执行跨层指令 | 告警判定、健康评分、点检、通知、HTTP 服务 |
| 业务 | `ops-server` | `com.moxa.monitor.MoxaServerApplication` | REST 接口、JWT+RBAC、告警引擎、通知、EWMA 基线与异常、健康评分、点检生成与执行、SSH 配置下发与审计、数据保留清理、SSE 推送、托管前端 | 直接读写设备 |
| 展示 | `ops-web` | `src/main.js` | 页面渲染、轮询(30s/60s)、SSE 订阅、交互与权限控制 | 直连采集层、自行拼 SQL、缓存业务规则 |

---

## 3. 表所有权矩阵（27 张）

写入方：**C** = 采集层(Python)，**B** = 业务层(Java)。同一张表只有一个写入方，避免双写冲突。

| 表 | 写入 | 读取 | 说明 |
|----|:----:|:----:|------|
| `switch_devices` | **C + B（分列）** | 双方 | C 写 `ip/model/snmp_config 生效值/is_online/last_poll_time/cpu/memory/total_ports/uptime/firmware/serial`；B 只定向 UPDATE `name/location/enabled/snmp_config/ssh_config` |
| `server_devices` | **C + B（分列）** | 双方 | 同上；B 负责增删改与凭据维护 |
| `port_status` | C | B | 端口 admin/oper 状态、速率、错误计数 |
| `port_traffic_stats` | C | B | 端口 in/out bps、ucast/bcast |
| `hardware_health` | C | B | 温度、电源、风扇 |
| `server_metrics` | C | B | CPU/内存/磁盘/网络 |
| `process_status` | C | B | 关键进程存活与资源占用 |
| `collector_heartbeats` | C | B | 每轮采集一行：组件/阶段/总数/成功/错误/耗时 |
| `collector_jobs` | B | C | 6 个采集任务的周期、超时、并发、开关；C 每 5 分钟热加载 |
| `collector_commands` | B | C | 跨层指令：`rediscover` / `reload_config` / `collect_now`；C 每 10 秒拉取并回写 |
| `discovery_groups` | B | C | 扫描网段与 SNMP/SSH 凭据模板（取代 `devices.yaml` 的 `switch_groups`） |
| `alert_records` | B | B | 8 条规则产出的告警 |
| `metric_baselines` | B | B | EWMA 基线 |
| `anomaly_events` | B | B | 异常事件（含自动恢复） |
| `device_health` | B | B | 健康评分快照 |
| `maintenance_windows` | B | B | 维护窗口（告警抑制） |
| `inspection_plans` | B | B | 点检计划 |
| `inspection_items` | B | B | 计划点检项 |
| `inspection_tasks` | B | B | 生成的点检任务 |
| `inspection_records` | B | B | 点检执行履历 |
| `inspection_standards` | B | B | 点检标准库（V5 内置 18 条 + 手工新增），生成计划时的候选项 |
| `agent_skills` | B | B | 智能体技能登记（V5 内置 8 条），只做清单与启停配置，不触发任何自动执行 |
| `config_audit_logs` | B | B | 每一次 SSH 下发的命令与回显 |
| `sys_user` | B | B | BCrypt 口令，首启动由 `UserBootstrapInitializer` 生成 |
| `sys_role` / `sys_user_role` | B | B | ADMIN / OPERATOR / VIEWER |
| `sys_config` | B | B | 8 个业务参数键，取代 `settings.yaml` 的业务部分 |

> ⚠️ 共写表的技术约束：Java 侧对 `switch_devices` / `server_devices` **一律使用
> `@Modifying` 定向 UPDATE 指定列**，禁止整行 `save(entity)`，否则会把采集字段冲回 null。

---

## 4. 接口规范

| 项 | 约定 |
|----|------|
| 路径 | `/api/**`，与迁移前完全一致（不引入 `/v1`） |
| 信封 | `{"code":0,"message":"success","data":{...},"timestamp":"yyyy-MM-dd HH:mm:ss"}` |
| code | `0` 成功；`400xx` 参数/业务错误；`401xx` 未认证；`403xx` 无权限；`404xx` 不存在；`500xx` 服务端错误 |
| 分页 | 请求 `page`(从 1 起)、`page_size`(1..200)；响应 `data = {"total","page","page_size","items"}` |
| 字段命名 | Jackson 全局 `SNAKE_CASE`，与迁移前 Python 响应键逐一对齐 |
| 时间 | 序列化为 `yyyy-MM-dd HH:mm:ss`（Asia/Shanghai）；入参同时接受该格式与 ISO8601 |
| 鉴权 | `Authorization: Bearer <JWT>`；仅 `POST /api/auth/login` 公开 |
| 敏感字段 | `snmp_config.community`、`ssh_config.password`、`sys_user.password_hash` 永不出现在响应中 |

`ops-web/src/api/http.js` 的拦截器负责：注入 token → `code!==0` 时 `ElMessage.error(message)` 并 reject →
成功时自动解包 `data`（业务代码拿到的就是 data 本体）→ 401 清 token 跳 `/login`。

---

## 5. 接口清单（110 个）

### 5.1 交换机 `SwitchController`（10）

| 方法 路径 | 权限 | 说明 |
|-----------|------|------|
| GET `/api/switches/summary` | VIEWER | 在线数、总数、告警概览 |
| GET `/api/switches/list` | VIEWER | 分页 + `keyword`/`online_only`，含最新温度 |
| GET `/api/switches/{id}/detail` | VIEWER | 单台详情 |
| GET `/api/switches/{id}/ports` | VIEWER | 端口状态 |
| GET `/api/switches/{id}/traffic` | VIEWER | `port_index`+`hours` 流量曲线 |
| GET `/api/switches/{id}/hardware` | VIEWER | `hours` 硬件曲线 |
| GET `/api/switches/{id}/edit` | VIEWER | 取可编辑字段（含凭据掩码） |
| PUT `/api/switches/{id}` | ADMIN | 改名称/位置/启停/SNMP/SSH 凭据 |
| DELETE `/api/switches/{id}` | ADMIN | 移除设备 |
| POST `/api/switches/{id}/refresh` | OPERATOR | 下发 `collect_now` 指令 |

### 5.2 服务器 `ServerController`（9）

`/api/servers`：GET `/summary`、`/list`、`/{id}/detail`、`/{id}/metrics`、`/{id}/processes`、`/{id}/edit`（VIEWER）；
POST ``、PUT `/{id}`、DELETE `/{id}`（ADMIN）。

### 5.3 告警 `AlertController`（4）

GET `/api/alerts/summary`、`/list?status&severity&page&page_size`（VIEWER）；
POST `/api/alerts/acknowledge`、`/resolve`（OPERATOR，`acknowledged_by`/`resolved_by` 缺省取当前登录用户）。

### 5.4 主动运维 `ProactiveController`（14）

GET `/overview`、`/health`、`/health/{device_id}`、`/anomalies`、`/recommendations`、`/baselines`、
`/maintenance/windows`（VIEWER）；
POST `/anomalies/{id}/acknowledge`、`/anomalies/{id}/resolve`、`/maintenance/windows`、
PUT `/maintenance/windows/{id}`、POST `/maintenance/windows/{id}/toggle`、DELETE `/maintenance/windows/{id}`（OPERATOR）；
POST `/analyze`（ADMIN，同步执行一轮全量分析）。

### 5.5 点检 `InspectionController`（16）

GET `/templates`、`/devices`、`/plans`、`/plans/{id}`、`/tasks`、`/tasks/{id}`、`/history`、`/stats`（VIEWER）；
POST `/plans`、PUT `/plans/{id}`、DELETE `/plans/{id}`、POST `/plans/{id}/toggle`、`/plans/{id}/run-now`、
POST `/tasks/{id}/start`、`/tasks/{id}/execute`、`/tasks/{id}/skip`（OPERATOR）。

### 5.6 自动化 `AutomationController`（10）

POST `/api/automation/vlan/create`、`vlan/delete`、`vlan/batch`、`port/access`、`port/trunk`、`port/toggle`、
`raw-command`（OPERATOR，均写 `config_audit_logs`）；
GET `/api/automation/vlan-plan`、`/audit-logs`、`/audit-logs/{id}`（VIEWER）。

### 5.7 系统 `SystemController`（13）

| 路径 | 权限 | 说明 |
|------|------|------|
| GET `/api/system/health` | VIEWER | 库体积/表行数/数据新鲜度/设备与告警计数/采集层状态 |
| GET `/api/system/status` | VIEWER | 轻量状态（layer、collector_alive） |
| GET `/api/system/maintenance/stats` | VIEWER | 4 张时序表的行数与时间跨度 + 保留策略 |
| POST `/api/system/maintenance/cleanup` | ADMIN | 手动清理，可覆盖 `metrics_days`/`alerts_days` |
| POST `/api/system/sync-devices` | OPERATOR | 下发 `reload_config` 指令（采集层重读配置并同步台账） |
| POST `/api/system/rediscover` | OPERATOR | 下发 `rediscover` 指令 |
| GET `/api/system/collector/heartbeat` | VIEWER | 采集层是否存活 + 各阶段最近一轮 |
| GET `/api/system/collector/heartbeat/recent` | VIEWER | 心跳历史列表 |
| GET `/api/system/collector/commands` | VIEWER | 跨层指令队列与执行结果 |
| GET `/api/system/configs`、`/configs/values`、`/configs/keys` | VIEWER | 运行参数 |
| PUT `/api/system/configs/{key}` | ADMIN | 仅允许 8 个业务键白名单 |

### 5.8 实时 `StreamController`（2）+ 总览 `DashboardController`（1）

GET `/api/stream`（SSE：`overview` / `devices` / `alerts` / `anomalies` / `heartbeat` 事件）、
GET `/api/realtime/status`（`subscribers`、`last_beat_at`、`collector_alive`）、
GET `/api/dashboard/overview`。

### 5.9 认证与配置管理（31）

- `AuthController`（4）：POST `/api/auth/login`（公开）、GET `/api/auth/me`、POST `/api/auth/logout`、
  POST `/api/auth/change-password`。
- `UserController`（5，ADMIN）：GET `/api/users`、GET `/api/users/roles`、POST `/api/users`、
  PUT `/api/users/{id}`、DELETE `/api/users/{id}`；约束：口令≥6 位、不能停用/降级当前账号、必须保留至少 1 个 ADMIN。
- `DiscoveryGroupController`（6）：GET `/api/discovery-groups`、`/{id}`（VIEWER）；POST、PUT、
  POST `/{id}/toggle`、DELETE（ADMIN）。`ip_range` 支持 `单IP`、`ip-ip`、`ip-后缀`、`CIDR`。
- `CollectorJobController`（2）：GET `/api/collector-jobs`（VIEWER）、PUT `/api/collector-jobs/{job_id}`（ADMIN，
  周期 10..86400s、超时 5..3600s）。

### 5.10 点检标准库 `InspectionStandardController`（7）

| 方法 路径 | 权限 | 说明 |
|-----------|------|------|
| GET `/api/inspection/standards` | VIEWER | `keyword`/`category`/`enabled` 过滤，返回 `{total, enabled_count, items[]}`，item 含 `ref_count`（按名称匹配 `inspection_items` 的引用次数） |
| GET `/api/inspection/standards/options` | VIEWER | 适用对象/判定类型/周期/重要度字典 |
| GET `/api/inspection/standards/{id}` | VIEWER | 单条详情 |
| POST `/api/inspection/standards` | OPERATOR | 新增，`source=manual`；同一 `category` 下重名返回 400xx |
| PUT `/api/inspection/standards/{id}` | OPERATOR | 修改（不改 `source`） |
| POST `/api/inspection/standards/{id}/toggle` | OPERATOR | 启停 |
| DELETE `/api/inspection/standards/{id}` | OPERATOR | 删除；已生成的点检计划不受影响 |

字段命名注意：周期列叫 `check_cycle`、处置列叫 `handle_action`，都是为避开 MySQL 关键字 `CYCLE`/`ACTION`。

### 5.11 智能体技能 `AgentSkillController`（7）

GET `""`（`{total, enabled_count, write_count, categories, items[]}`）、GET `/options`、GET `/{id}`（VIEWER）；
POST `""`、PUT `/{id}`、POST `/{id}/toggle`、DELETE `/{id}`（**均 ADMIN**，技能登记会改变助手对外声明的能力，比点检标准更高一档）。
`skill_key` 须匹配 `^[a-z][a-z0-9_]{1,49}$` 且唯一；`risk_level` ∈ read/suggest/write；
`entry_path` 只能是已存在的一级路由。本表**没有任何调度器读取**，write 类技能仍需在自动化配置页由人确认下发并留 `config_audit_logs`。

### 5.12 前端一级菜单（11 个, 顺序由 `MainLayout.vue` 的 `MENU_ORDER` 决定）

```
/dashboard 系统总览 · /switches 交换机管理 · /servers 服务器监控 · /proactive 主动运维
/automation 自动化配置 · /alerts 告警管理 · /inspection 设备点检
/standards 点检标准(Memo) · /skills Skill 管理(MagicStick) · /duties 职责分工(Avatar, 空白占位页)
/system 系统管理
```

---

## 6. 关键机制

### 6.1 实时推送

`SnapshotPoller` 每 2 秒读库构建内存快照并做差量比对，经 `SseEmitter` 广播；
心跳前进时立即触发一轮告警检查，`AlertEngineService` / `ProactiveEngineService` 产出新告警或异常时
直接调用 `StreamService.publish()`，实现秒级推送（不必等 2 秒轮询）。

两处与 SSE 相关的实现约束（都是现场踩出来的）：

- `EventSource` 不能自定义请求头，token 走 `?token=`，因此 `JwtAuthFilter` 必须
  `shouldNotFilterAsyncDispatch()=false`：默认的"ASYNC 派发不再过过滤器"会让 SSE 完成的那次
  派发没有登录态而被判 AccessDenied，而此时响应流已提交，异常无法转成信封，只在容器里刷 500 堆栈。
- 客户端关页/断网时 Tomcat 抛的 `IOException` 消息会随系统语言变化（中文 Windows 是
  "你的主机中的软件中止了一个已建立的连接。"），`GlobalExceptionHandler` 按异常链上的关键字识别为
  "对端已断开"，降为 DEBUG 且不再写信封，避免日志被无关 ERROR 淹没。

### 6.2 跨层控制（Java → Python）

Python 不暴露 HTTP，Java 通过 `collector_commands` 表下发指令：

```
Java: INSERT collector_commands(cmd, payload, status='pending', created_by)
Python: 每 10s SELECT ... WHERE status='pending' → 执行 → UPDATE status='done', result=..., executed_at=now
```

指令类型（共 3 种，与 `backend/scheduler/task_scheduler.py` 的实现一致）：

| cmd | payload | 采集层行为 |
|-----|---------|-----------|
| `rediscover` | — | 立即执行一轮网段扫描与设备入库 |
| `reload_config` | — | 重读 `collector.yaml` + 重建采集任务（`/api/system/sync-devices` 用这个） |
| `collect_now` | `{"device_id": "..."}` | 定向重采一台设备（`/api/switches/{id}/refresh` 用这个） |

`POST /api/proactive/analyze` 不走这张表（分析在 Java 内同步执行）。

### 6.3 采集层自监控

每个采集任务每轮结束写 `collector_heartbeats`（component/phase/total/success/errors/duration_ms/detail_json）。
`success` 的口径是**本轮真正拿到样本并落库的设备数**（`detail_json` 里另有 `collected/skipped/empty` 三个计数）：
早期版本只把"抛异常"算失败，导致 SNMP 集体超时的轮次报"全部成功"却零写入，心跳与数据面互相矛盾。
`ops.alert.collector-dead-seconds=180`：心跳超过 180 秒未前进即判定采集层离线，
前端实时标签转灰，`/api/system/health` 的 `collector.alive=false`。

### 6.4 定时任务归属

| 层 | 任务 | 周期 | 配置位置 |
|----|------|------|----------|
| Python | 6 个采集任务（status/traffic/hardware/server/process/rediscovery） | 600/900/1800/30/60/1800s | `collector_jobs` 表，每 5 分钟热加载（周期见 V4 迁移，依据是 8 路并发下的实测单轮耗时） |
| Python | 跨层指令拉取 | 10s | `collector.yaml: scheduler.command_poll_seconds` |
| Java | 快照轮询 + SSE | 2s | `SnapshotPoller` |
| Java | 告警引擎 | 30s | `AlertEngineService` |
| Java | 点检生成与逾期标记 | 60s | `InspectionSchedulerService` |
| Java | 主动运维分析 | 300s | `ProactiveEngineService` |
| Java | 数据保留清理 | 每日 03:30 | `RetentionCleanupService`，策略在 `sys_config.retention_policy` |

---

## 7. 部署与启动

### 7.1 环境要求

| 组件 | 版本 | 用途 |
|------|------|------|
| Python | 3.10+（开发用 3.14） | 采集层 |
| MySQL | **8.0+**（需要 `ROW_NUMBER()` 窗口函数） | 两层共用 |
| JDK | 17 | 业务层 |
| Maven | 3.9+ | 业务层构建 |
| Node | 24（18+ 即可） | 前端构建 |

### 7.2 首次启动顺序

```powershell
# ① 建库（库名 monitor, utf8mb4/utf8mb4_general_ci; 表与种子由 Flyway 建）
#    PowerShell 不支持 `<` 重定向, 用 mysql 的 source
mysql -h 10.51.190.70 -u test -p1234 -e "source sql/create_database.sql"

# ② 安装采集层依赖
D:\AI\Miniconda\python.exe -m pip install -r ops-collector\requirements.txt

# ③ 构建前端 (产物 ops-web\dist, 会被 maven-resources-plugin 复制进 jar 的 static/)
cd ops-web; npm install; npm run build; cd ..

# ④ 构建业务层 (Flyway 在启动时自动执行 V1__baseline.sql + V2__seed.sql + V3/V4 采集周期调优 + V5 点检标准与智能体技能)
#    没有系统 JDK/Maven 时, 指向任一 JDK17 环境 (本机用 conda 装的 D:\AI\moxabuild)
$env:JAVA_HOME = "D:\AI\moxabuild\Library"; $env:Path = "$env:JAVA_HOME\bin;D:\AI\moxabuild\Library\bin;$env:Path"
mvn -f ops-server\pom.xml clean package

# ⑤ 迁移历史数据 (可选, 只在需要保留旧 SQLite 数据时执行)
D:\AI\Miniconda\python.exe tools\migrate_sqlite_to_mysql.py --dry-run    # 先校验行数
D:\AI\Miniconda\python.exe tools\migrate_sqlite_to_mysql.py             # 正式导入

# ⑥ 一键启动两层 (采集 + 业务), 浏览器访问 http://127.0.0.1:8080
#    本机 JAVA_HOME 未写进系统环境变量 (java 装在 conda 环境 D:\AI\moxabuild),
#    所以后台拉起时必须显式传 --java, 否则 supervisor 找不到 java.exe
D:\AI\Miniconda\python.exe ops-collector\scripts\start_all.py --java=D:\AI\moxabuild\Library\bin\java.exe
```

> 顺序说明：⑤ 可跳过；Flyway 只建表与写种子数据，若先跑 ⑤ 会因表不存在而失败，
> 因此**首次必须先启动一次业务层**（或手工执行 `V1`/`V2`）再回灌历史数据。
> 稳妥做法：④ 之后先单独 `java -jar ops-server\target\moxa-server.jar` 启动一次让 Flyway 建表，
> Ctrl+C 停止 → 执行 ⑤ → 再执行 ⑥。
> 手工启动业务层时**工作目录必须是 `ops-collector`**（`application.yml` 的日志路径是相对路径 `../logs/server.log`）：
> `Start-Process java -ArgumentList '-jar','..\ops-server\target\moxa-server.jar' -WorkingDirectory ops-collector`。

### 7.3 开机自启

```powershell
# 注册计划任务 (任务名 ProactiveOpsMonitor, AtStartup, SYSTEM 账户, 开机即拉起 start_all.py)
powershell -ExecutionPolicy Bypass -File ops-collector\scripts\setup_autostart.ps1

# 立即启动一次
Start-ScheduledTask -TaskName ProactiveOpsMonitor

# 移除自启
powershell -ExecutionPolicy Bypass -File ops-collector\scripts\remove_autostart.ps1
```

三个脚本的分工：

| 脚本 | 作用 |
|------|------|
| `scripts/start_all.py` | **唯一启动入口**。预检 collector.yaml → TCP 探测 MySQL（最多等 `OPS_STARTUP_WAIT_SECONDS`，默认 120s）→ 探测 jar 产物 → 调用 supervisor；支持 `--java=<java.exe>` |
| `scripts/supervisor.py` | 守护两个子进程：`python -X utf8 -u run.py`（采集，固定用 `python.exe`，见 7.6）与 `java -jar moxa-server.jar`（业务）。指数退避重启（5→60s，稳定 300s 后重置），优雅停止宽限 15s；把 `collector.yaml` 的 mysql 段导出为 `OPS_MYSQL_*` 供业务层共用；jar 或 java.exe 缺失时只守护采集层并每 60s 重新探测；心跳写 `logs/supervisor_heartbeat.json` |
| `scripts/start_service.cmd` | 前台手工启动的快捷封装（等价于 `python -u scripts\start_all.py`），可用 `--java=` 透传 JVM 路径 |

### 7.4 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OPS_MYSQL_HOST` / `PORT` / `USER` / `PASSWORD` / `DB` | 当前部署 `10.51.190.70` / `3306` / `test` / `1234` / `monitor` | 两层共用：采集层读 `collector.yaml` 的 mysql 段, 业务层读 `application.yml` 的 `${OPS_MYSQL_*}` 占位默认值; 两边默认值必须一致, 环境变量优先级最高; supervisor 会把 yaml 值注入 Java 子进程 |
| `OPS_JAVA` | — | java.exe 绝对路径；其次 `JAVA_HOME\bin\java.exe`，再次 `shutil.which("java")` 解析成绝对路径（裸 `java` 在计划任务/pythonw 下会 `[WinError 2]`）；都找不到则业务层标记 unavailable 并给出提示 |
| `OPS_SERVER_JAR` | — | 指定 jar 路径；缺省按 `ops-server\target\moxa-server.jar` 查找 |
| `OPS_PYTHON` | `D:\AI\Miniconda\python.exe` | 部署脚本使用 |
| `OPS_JWT_SECRET` / `OPS_JWT_EXPIRE_MINUTES` | 内置占位 / 720 | **生产必须覆盖**：`openssl rand -base64 32` |
| `OPS_MAIL_HOST/PORT/USER/PASSWORD` | `smtp.example.com` / 465 / 空 | 邮件通知 |
| `OPS_STARTUP_WAIT_SECONDS` | 120 | start_all.py 等待 MySQL 就绪的秒数 |

### 7.5 日志

| 文件 | 内容 |
|------|------|
| `ops-collector/logs/collector.out.log` / `collector.err.log` | 采集层 stdout / stderr |
| `ops-collector/logs/server.out.log` / `server.err.log` | 业务层 stdout / stderr |
| `ops-collector/logs/server.log` | Spring Boot 滚动日志（supervisor 传 `--logging.file.name=` 指定）；手工 `java -jar` 时走 `application.yml` 的 `../logs/server.log`，因工作目录是 `ops-collector`，实际落在仓库根的 `logs/server.log` |
| `ops-collector/logs/supervisor.log` | 守护进程事件（启动/退出/重启/退避） |
| `ops-collector/logs/supervisor_heartbeat.json` | 两个子进程 pid、运行状态、累计启动次数、最近退出码 |

### 7.6 现场已知限制

- **远端 MySQL 链路抖动**：现场库 `10.51.190.70` 与采集机同一网段，采集轮（尤其状态轮单轮 ~5 分钟）
  写入高峰期会出现成簇的 `SQLState 08S01/08003`、`Communications link failure`，对应接口偶发 500 与 >5s 慢响应
  （日志按分钟聚合过：07:58-08:04、08:29、09:07、09:24-09:25 各一簇，其余时段干净）。
  已做的兜底：Hikari `keepalive-time=30000` + JDBC `connectTimeout=5000/socketTimeout=60000`，剔除"看起来能用其实已断"的空闲连接；
  彻底解决需要 DBA/网络侧查 MySQL 与中间链路（`Aborted_clients` 在持续增长）。
- **工业交换机的 SNMP 吞吐上限**：并发 >8 路时 bulkwalk 集体 2s 超时（实测 64 路并发只有 2 台取到端口），
  所以 `collector.yaml` 的 `max_concurrent=8`、`batch_size=16`，周期按 V4 放宽；
  设备数或网段变化后用 `tools/snmp_concurrency_probe.py --levels 6,12,32` 复测再调参；
  不支持私有/子树 OID 的设备属于"可达但无该指标"，计入心跳的 `empty`，不算采集失败。
- **采集周期内的指令下发延迟**：三类 SNMP 作业共用 `phase_lock`，单轮可达 300s，
  此间 `collect_now` / `reload_config` 等 10s 轮询作业会被 APScheduler 合并（日志中
  `skipped: maximum number of running instances reached`），表现为下发晚一个轮次。
- **不能用 pythonw.exe 跑采集层**：本机实测同一个 dist-info 元数据文件，`python.exe` 读到
  1181 字节正常文本，`pythonw.exe` 读到 5277 字节随机二进制，APS 在 import 时调
  `importlib.metadata.entry_points()` 扫全部已装包，于是 `UnicodeDecodeError: 0xe7 in position 3`
  直接把采集层打崩（运行 46~227s 后 exit=1，崩溃循环）。属解释器进程侧的读取异常，
  与本项目代码无关；supervisor 已固定用 `python.exe -X utf8` 起采集层（父进程是 pythonw 时
  用 `CREATE_NO_WINDOW` 避免弹控制台）。

---

## 8. 默认账号

| 账号 | 口令 | 角色 | 说明 |
|------|------|------|------|
| `admin` | `admin123` | ADMIN | 全部权限 |
| `oper` | `oper123` | OPERATOR | 告警确认、点检执行、配置下发 |
| `viewer` | `viewer123` | VIEWER | 只读 |

由 `UserBootstrapInitializer` 在首次启动时以 BCrypt 写入 `sys_user`；登录后可通过
`POST /api/auth/change-password` 或「系统管理 → 用户管理」改口令。

---

## 9. 与原始方案的实现偏差

| 方案条目 | 实际实现 | 原因 |
|----------|----------|------|
| §2.3 `sql/schema.sql` + `sql/seed.sql` | DDL/种子只存在于 `ops-server/src/main/resources/db/migration/V1__baseline.sql`、`V2__seed.sql`；`sql/` 目录仅保留 `create_database.sql` | Flyway 需要 classpath 下的脚本，两处存放会产生第二份真相 |
| §3.1 删除业务代码 | 旧实现整体 Move 到 `ops-collector/legacy_reference/` | 工作区非 git 仓库，删除后无法回溯迁移前的行为语义 |
| §4.4 64 + 12 = 76 个接口 | 实际 110 个 | 补齐了迁移前散落在 `main.py` 的路由，并增加了编辑表单回填（`/{id}/edit`）、审计明细、心跳历史、指令队列、角色列表、改口令等前端必需接口（点检标准库与智能体技能各 +7） |
| §4.5 保留策略 `OPTIMIZE TABLE` | 用 `ANALYZE TABLE` | InnoDB 下 `OPTIMIZE` 会长时间锁表，现场不可接受 |
| §5 `src/api/users.js` | 用户 CRUD 归入 `src/api/auth.js` | 与登录共用同一套认证 store，避免两个 api 文件互相引用 |
| §4.6 SSE | 2 秒轮询 + 心跳前进即时唤起告警检查 + 业务事件即时推送 | 见 §6.1，前端体验与迁移前等价 |

---

## 10. 端到端验收清单

1. 登录（admin/admin123）→ 右上角显示用户名与角色。
2. 总览页数字与迁移前一致（设备在线率、活跃/严重告警、待办点检、健康分布、最近 10 条告警）。
3. 交换机列表显示温度与在线状态；进入详情可看端口、流量曲线、硬件曲线。
4. 服务器页显示 CPU/内存/磁盘与关键进程。
5. 告警页确认 → 状态变 `acknowledged`；恢复 → 收到邮件/钉钉通知且 `notified=1`。
6. 点检：新建计划 → `run-now` 生成任务 → 提交履历 → 完成率统计变化。
7. 主动运维：`POST /api/proactive/analyze` 产出异常事件与健康分；维护窗口内新告警被抑制。
8. VLAN 下发 → 「自动化 → 审计日志」出现记录，含 `commands_sent` 与回显。
9. 拔掉网线使设备离线 → 30 秒内页面红色告警出现，SSE 实时标签同步变化。
10. 停止 `python run.py` → 180 秒后「系统管理 → 采集层」显示离线；恢复后自动转绿。
11. `POST /api/system/maintenance/cleanup` 返回各表删除行数。
12. 重启机器 → 计划任务 `ProactiveOpsMonitor` 自动拉起两层，浏览器直接可用。
