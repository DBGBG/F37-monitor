# legacy_reference —— 改造前的业务层与前端源码（只读存档）

三层架构改造把业务逻辑（告警、主动运维、点检、SSH 配置下发、数据保留、REST 接口、SSE）
从 Python 迁到 Spring Boot（`../moxa-server`），前端重写为 Vue3 工程（`../moxa-web`）。
本目录是迁移前的原始实现，**采集层运行时不再引用这里的任何文件**，保留目的只有一个：
作为 Java/Vue 实现的逐字段对照依据（业务规则、响应结构、页面取数逻辑）。

> 命名说明：本目录内出现的旧系统名“MOXA 主动运维监控系统”（页面标题、FastAPI 文档标题、
> 通知模板抬头等）是迁移前的历史原文，保留不改写，以免破坏对照价值；对外可见文案已统一为“主动运维系统”。

| 内容 | 原位置 | 对应新实现 |
|---|---|---|
| `api/` 8 个路由文件、64 个接口 | `backend/api/` | `moxa-server/src/main/java/com/moxa/monitor/web/` |
| `main.py` FastAPI 入口 + 设备发现入库 | `backend/main.py` | `moxa-monitor/backend/daemon.py`（入库部分）+ Spring Boot |
| `realtime.py` 内存态与 SSE 总线 | `backend/realtime.py` | `moxa-server` 的 `SnapshotPoller` / `StreamService` |
| `alerts/` 告警引擎与通知 | `backend/alerts/` | `AlertEngineService` / `NotificationService` |
| `proactive/` 基线/异常/健康/维护窗口 | `backend/proactive/` | `BaselineService` / `AnomalyService` / `HealthScoreService` / `MaintenanceWindowService` |
| `inspection/` 点检计划与任务生成 | `backend/inspection/` | `InspectionSchedulerService` / `InspectionPlanService` |
| `automation/` SSH 下发 + VLAN + j2 模板 | `backend/automation/` | `MoxaSshClient` / `VlanService` / `CommandTemplateService` |
| `maintenance/cleanup.py` 数据保留 | `backend/maintenance/` | `RetentionCleanupService` |
| `frontend/` 免构建 CDN 版页面 | `frontend/` | `moxa-web/src/views/` |

验收通过（Java 侧全部接口与前端页面联调完成）后，本目录可以整体删除。
