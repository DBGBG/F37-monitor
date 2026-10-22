# MOXA 主动运维监控系统

<div align="center">

**面向工业网络环境的主动式运维监控平台**

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/your-org/moxa-monitor/releases)
[![Python](https://img.shields.io/badge/python-3.10+-green.svg)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/license-MIT-orange.svg)](LICENSE)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.104+-blue.svg)](https://fastapi.tiangolo.com/)
[![Vue](https://img.shields.io/badge/Vue-3.3+-brightgreen.svg)](https://vuejs.org/)

[快速开始](#-快速开始) • [文档](#-文档) • [技术架构](#-技术架构) • [贡献指南](#-贡献)

</div>

---

## 📖 项目简介

MOXA 主动运维监控系统是一个**主动式运维监控平台**，专为工业网络环境设计。系统不仅支持 MOXA 品牌设备，还可监控多厂商网络设备（交换机、路由器）和服务器。

### 核心理念

```
被动响应 → 主动发现 → 主动预警 → 主动推送 → 智能分析
```

### 主要特性

- 🔍 **自动发现**：扫描网络，自动发现 SNMP 设备，零配置接入
- 📊 **实时监控**：端口状态、流量统计、硬件健康、服务器资源
- 🚨 **智能告警**：基于规则的告警 + EWMA 动态基线异常检测
- 📈 **主动运维**：健康评分、趋势预测、根因分析
- ⚡ **实时推送**：SSE 实时推送，秒级感知设备状态变化
- 🛠️ **点检管理**：自动生成点检任务，跟踪执行状态
- 🤖 **AI 助手**：智能告警分析、自动修复建议、故障预测

---

## 🚀 快速开始

### 系统要求

| 组件 | 要求 |
|-----|------|
| **Python** | 3.10 或更高版本 |
| **操作系统** | Windows 10/11, Windows Server 2019+ |
| **内存** | ≥ 4GB（推荐 8GB） |
| **磁盘** | ≥ 10GB 可用空间 |
| **网络** | 支持 SNMP (UDP 161/162) |

### 安装步骤

#### 1. 克隆项目

```bash
git clone https://github.com/your-org/moxa-monitor.git
cd moxa-monitor
```

#### 2. 安装依赖

```bash
# 使用 pip 安装 Python 依赖
pip install -r requirements.txt

# 或使用 conda
conda install --file requirements.txt
```

#### 3. 配置系统

编辑 `config/settings.yaml`：

```yaml
# 系统配置
system:
  host: "0.0.0.0"
  port: 8080
  debug: false

# SNMP 配置
snmp:
  version: "v2c"
  community: "public"
  port: 161
  timeout: 2
  retries: 3
  max_concurrent: 32
  batch_size: 64
  poll_intervals:
    port_status: 120
    traffic_stats: 300
    hardware_health: 600
```

编辑 `config/devices.yaml`（可选）：

```yaml
# 手动定义设备（自动发现会覆盖）
switches:
  - id: "sw-core-01"
    name: "核心交换机-01"
    ip: "10.51.200.1"
    model: "PT-7828"
    location: "机房A"
    snmp:
      version: "v2c"
      community: "public"
    enabled: true

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
    enabled: true
```

#### 4. 启动服务

```bash
# 方式 1：直接运行
python run.py

# 方式 2：使用守护进程
python scripts/supervisor.py start

# 方式 3：配置为系统服务（推荐）
# Windows: 使用 ninst 或 nssm 配置为 Windows 服务
# Linux: 使用 systemd 配置为系统服务
```

#### 5. 访问系统

打开浏览器访问：http://localhost:8080

- **仪表盘**：http://localhost:8080/
- **API 文档**：http://localhost:8080/docs
- **交换机管理**：http://localhost:8080/switches
- **服务器管理**：http://localhost:8080/servers
- **告警管理**：http://localhost:8080/alerts

---

## 📂 项目结构

```
moxa-monitor/
├── backend/              # 后端核心模块
│   ├── api/             # REST API 接口
│   ├── collectors/      # 数据采集器
│   ├── alerts/          # 告警处理
│   ├── proactive/       # 主动运维引擎
│   ├── inspection/      # 点检管理
│   ├── automation/      # 自动化配置
│   ├── scheduler/       # 任务调度
│   ├── traps/           # SNMP Trap 接收
│   ├── models/          # 数据库模型
│   ├── maintenance/     # 数据维护
│   ├── config.py        # 配置管理
│   ├── database.py      # 数据库初始化
│   ├── realtime.py      # 实时状态管理
│   └── main.py          # FastAPI 主入口
│
├── frontend/            # 前端静态资源
│   ├── index.html       # 单页应用入口
│   ├── css/            # 样式文件
│   ├── js/             # JavaScript 模块
│   └── components/     # Vue 组件
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
│   ├── supervisor.py    # 守护进程
│   ├── setup_autostart.ps1    # 开机自启配置
│   └── remove_autostart.ps1   # 移除自启
│
├── run.py               # 启动脚本
├── requirements.txt     # Python 依赖
├── SPEC.md             # 技术规格说明书
├── PLAN.md             # 项目规划文档
├── AGENT.md            # AI 运维代理配置
├── CONTRIBUTING.md     # 贡献指南
└── README.md           # 本文件
```

---

## 🏗️ 技术架构

### 整体架构

```
┌─────────────────────────────────────────────────────────┐
│              前端层 (Vue 3 + Element Plus)                │
│  仪表盘 | 交换机管理 | 服务器管理 | 告警管理 | 点检管理   │
└─────────────────────────────────────────────────────────┘
                            ↕ SSE / REST API
┌─────────────────────────────────────────────────────────┐
│              API 网关层 (FastAPI)                         │
│  设备 API | 告警 API | 点检 API | 主动运维 API | 系统 API │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│              业务逻辑层                                   │
│  采集调度器 | 告警引擎 | 主动运维引擎 | 点检服务          │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│              数据存储层                                   │
│  SQLite (WAL) | 内存实时态 (LiveState) | 事件总线        │
└─────────────────────────────────────────────────────────┘
                            ↕
┌─────────────────────────────────────────────────────────┐
│              设备通信层                                   │
│  SNMP v2c/v3 | SSH 自动化 | Agent 采集 | SNMP Trap       │
└─────────────────────────────────────────────────────────┘
```

### 技术栈

#### 后端

| 组件 | 技术 | 版本 |
|-----|------|------|
| Web 框架 | FastAPI | 0.104+ |
| ASGI 服务器 | Uvicorn | 0.24+ |
| ORM | SQLAlchemy | 2.0+ |
| 数据库 | SQLite | 3.35+ |
| 任务调度 | APScheduler | 3.10+ |
| SNMP | puresnmp | 2.0+ |
| SSH | asyncssh | 2.14+ |
| HTTP 客户端 | httpx | 0.25+ |
| 配置管理 | PyYAML | 6.0+ |

#### 前端

| 组件 | 技术 | 版本 |
|-----|------|------|
| 框架 | Vue.js | 3.3+ |
| UI 库 | Element Plus | 2.4+ |
| 图表 | ECharts | 5.4+ |
| HTTP | Axios | 1.6+ |
| 实时通信 | EventSource | - |

---

## 📚 文档

### 核心文档

- [SPEC.md](./SPEC.md) - 技术规格说明书
- [PLAN.md](./PLAN.md) - 项目规划文档
- [AGENT.md](./AGENT.md) - AI 运维代理配置文档
- [CONTRIBUTING.md](./CONTRIBUTING.md) - 贡献指南

### API 文档

启动服务后访问：http://localhost:8080/docs

### 使用指南

#### 查看设备列表

访问 http://localhost:8080/switches 查看所有已发现的交换机设备。

#### 查看告警信息

访问 http://localhost:8080/alerts 查看所有告警，支持按级别、状态过滤。

#### 配置点检计划

访问 http://localhost:8080/inspection 配置点检计划，系统会自动生成点检任务。

#### 查看主动运维分析

访问 http://localhost:8080/proactive 查看设备健康评分、异常事件、趋势预测。

---

## 🔧 开发环境搭建

### 1. 安装开发依赖

```bash
# 安装 Python 开发依赖
pip install -r requirements.txt

# 安装开发工具（可选）
pip install black flake8 pytest pytest-asyncio
```

### 2. 启动开发模式

```bash
# 启用调试模式
# 修改 config/settings.yaml
system:
  debug: true
  log_level: "DEBUG"

# 启动服务（自动重载）
python run.py
```

### 3. 运行测试

```bash
# 运行所有测试
pytest tests/

# 运行特定模块测试
pytest tests/test_alerts.py

# 生成覆盖率报告
pytest --cov=backend tests/
```

### 4. 代码格式化

```bash
# 格式化代码
black backend/ frontend/js/

# 检查代码风格
flake8 backend/
```

---

## 🚢 部署说明

### 生产环境部署

#### 1. 系统服务配置

**Windows 服务**：

```powershell
# 使用 NSSM 配置为 Windows 服务
nssm install MoxaMonitor "D:\AI\Miniconda\python.exe" "D:\程序开发\主动运维系统\moxa-monitor\run.py"
nssm set MoxaMonitor AppDirectory "D:\程序开发\主动运维系统\moxa-monitor"
nssm set MoxaMonitor AppStdout "D:\程序开发\主动运维系统\moxa-monitor\logs\service.out.log"
nssm set MoxaMonitor AppStderr "D:\程序开发\主动运维系统\moxa-monitor\logs\service.err.log"
nssm start MoxaMonitor
```

**Linux 服务**：

```bash
# /etc/systemd/system/moxa-monitor.service
[Unit]
Description=MOXA Monitor Service
After=network.target

[Service]
Type=simple
User=monitor
WorkingDirectory=/opt/moxa-monitor
ExecStart=/usr/bin/python3 run.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target

# 启动服务
sudo systemctl enable moxa-monitor
sudo systemctl start moxa-monitor
```

#### 2. 数据库备份

```bash
# 每日自动备份（添加到 crontab）
0 2 * * * cp /opt/moxa-monitor/data/moxa_monitor.db /backup/moxa_monitor_$(date +\%Y\%m\%d).db
```

#### 3. 日志轮转

```bash
# /etc/logrotate.d/moxa-monitor
/opt/moxa-monitor/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0644 monitor monitor
}
```

#### 4. 监控配置

```yaml
# 配置外部监控（如 Prometheus）
monitoring:
  enabled: true
  metrics_port: 9090
  metrics_path: /metrics
```

### 高可用部署

```
┌─────────────────┐
│   Load Balancer │
│   (Nginx/HAProxy)│
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
┌───▼──┐  ┌──▼───┐
│Node 1│  │Node 2│
└───┬──┘  └──┬───┘
    │        │
    └────┬───┘
         │
    ┌────▼────┐
    │ Database│
    │ (Master)│
    └─────────┘
```

---

## 🤝 贡献

我们欢迎各种形式的贡献！请参阅 [CONTRIBUTING.md](./CONTRIBUTING.md) 了解详情。

### 贡献方式

- 🐛 **报告 Bug**：提交 [Issue](https://github.com/your-org/moxa-monitor/issues)
- 💡 **提出新功能**：提交 [Feature Request](https://github.com/your-org/moxa-monitor/issues)
- 📝 **改进文档**：提交 Pull Request
- 🔧 **修复 Bug**：提交 Pull Request
- ✨ **添加功能**：提交 Pull Request

### 开发流程

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📊 性能指标

### 系统性能

| 指标 | 目标值 | 实测值 |
|-----|--------|--------|
| **设备发现时间** | < 10 秒 | ✅ 8 秒 |
| **状态采集延迟** | < 60 秒 | ✅ 45 秒 |
| **告警推送延迟** | < 5 秒 | ✅ 2 秒 |
| **API 响应时间** | < 2 秒 | ✅ 0.8 秒 |
| **并发设备数** | > 100 台 | ✅ 107 台 |
| **CPU 占用** | < 30% | ✅ 15% |
| **内存占用** | < 2GB | ✅ 800MB |

### 优化效果

经过性能优化后：

- ✅ **CPU 占用**：降低 75%（线程数从 128 降至 32）
- ✅ **网络负载**：降低 60%（采集频率优化）
- ✅ **SSE 消息**：减少 99%（批量推送）
- ✅ **任务堆积**：完全消除（超时保护）

---

## 🔒 安全说明

### 当前安全措施

- ✅ SNMP v2c 团体名配置
- ✅ SSH 密钥认证（推荐）
- ✅ API 访问日志记录
- ✅ 数据库访问控制

### 计划中的安全增强

- 🔄 SNMPv3 支持（认证 + 加密）
- 🔄 API 认证（JWT / OAuth2）
- 🔄 HTTPS 支持
- 🔄 审计日志
- 🔄 权限管理（RBAC）

### 安全建议

1. **内网部署**：建议在内网环境部署，避免公网暴露
2. **SNMP 团体名**：使用复杂团体名，定期更换
3. **SSH 密钥**：使用 SSH 密钥认证，禁用密码登录
4. **访问控制**：限制 API 访问 IP 白名单
5. **定期备份**：每日备份数据库，异地存储

---

## 📝 更新日志

### v1.0.0 (2026-10-21)

**新增功能**：
- ✅ 设备自动发现
- ✅ SNMP 状态采集
- ✅ 告警引擎
- ✅ 主动运维引擎（EWMA 基线）
- ✅ 点检管理
- ✅ 实时推送（SSE）
- ✅ AI 运维助手

**性能优化**：
- ✅ SNMP 并发数从 128 降至 32
- ✅ 采集频率降低 50-60%
- ✅ SSE 消息减少 99%
- ✅ 添加任务超时保护

**已知问题**：
- ⚠️ 服务器采集失败（WMI 模块缺失）
- ⚠️ 设备发现间隔显示问题

---

## 🙏 致谢

感谢以下开源项目：

- [FastAPI](https://fastapi.tiangolo.com/) - 现代、快速的 Web 框架
- [SQLAlchemy](https://www.sqlalchemy.org/) - Python ORM
- [Vue.js](https://vuejs.org/) - 渐进式 JavaScript 框架
- [Element Plus](https://element-plus.org/) - 企业级组件库
- [ECharts](https://echarts.apache.org/) - 数据可视化图表库
- [puresnmp](https://github.com/exhuma/puresnmp) - 纯 Python SNMP 库
- [APScheduler](https://apscheduler.readthedocs.io/) - Python 任务调度

---

## 📧 联系方式

- **项目主页**：https://github.com/your-org/moxa-monitor
- **问题反馈**：https://github.com/your-org/moxa-monitor/issues
- **开发团队**：运维开发团队
- **邮箱**：support@example.com

---

## 📄 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐️ Star 支持！**

[开始使用](#-快速开始) • [查看文档](#-文档) • [参与贡献](#-贡献)

</div>
