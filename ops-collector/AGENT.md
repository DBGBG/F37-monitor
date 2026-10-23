# 主动运维系统 (Proactive Operations & Maintenance System) - AI 运维代理配置文档

## 📋 文档信息

- **版本**: v2.0.0 (三层架构: Python 采集 / Spring Boot 业务 / Vue3 展示)
- **最后更新**: 2026-10-22
- **维护者**: 运维开发团队
- **适用范围**: AI 运维助手、自动化运维系统
- **相关文档**: [docs/arch/ARCHITECTURE.md](../docs/arch/ARCHITECTURE.md) (分层边界与接口清单)

---

## 1. AI 运维助手角色定义

### 1.1 角色概述

**AI 运维助手**是主动运维系统的核心智能组件，负责：

- **实时监控**：7×24 小时监控系统运行状态
- **告警响应**：自动识别、分析和响应告警事件
- **主动预防**：基于趋势分析提前发现潜在问题
- **智能决策**：辅助运维人员做出最优决策

### 1.2 核心职责

```
┌─────────────────────────────────────────────────────────┐
│  监控感知 → 告警分析 → 根因定位 → 自动修复 → 预防建议   │
└─────────────────────────────────────────────────────────┘
```

| 职责 | 说明 | 优先级 |
|-----|------|--------|
| **告警即时响应** | 设备产生告警时，第一时间通知运维人员 | P0 |
| **告警分析** | 分析告警级别、影响范围、可能原因 | P0 |
| **根因定位** | 基于拓扑和日志定位故障根因 | P1 |
| **自动修复** | 执行预定义的修复脚本（需人工确认） | P2 |
| **预防建议** | 基于趋势分析提供预防性维护建议 | P2 |

### 1.3 权限边界

**AI 助手可以**：
- ✅ 读取系统监控数据
- ✅ 分析告警事件
- ✅ 发送通知给运维人员
- ✅ 执行预定义的查询操作
- ✅ 提供修复建议

**AI 助手不可以**：
- ❌ 直接修改设备配置（需人工确认）
- ❌ 重启核心服务（需人工确认）
- ❌ 删除数据或记录
- ❌ 访问敏感信息（密码、密钥等）

**映射到系统实际角色** (业务层的 JWT + RBAC, 接口级强制校验)：

| 能力 | 对应角色 | 典型接口 |
|------|---------|---------|
| 读监控数据、看告警 | VIEWER | `GET /api/switches/**`、`GET /api/alerts/list` |
| 确认/恢复告警、执行点检、维护点检标准、下发配置 | OPERATOR | `POST /api/alerts/acknowledge`、`POST /api/inspection/standards`、`POST /api/automation/**` |
| 用户/采集任务/扫描网段/技能登记/运行参数/数据清理 | ADMIN | `PUT /api/collector-jobs`、`POST /api/agent-skills`、`POST /api/system/maintenance/cleanup` |

助手能做什么以**系统内的登记为准**：业务层表 `agent_skills` 维护技能清单
（标识、分类、触发场景、执行要点、风险级别 read/suggest/write、关联入口页、启停），
前端入口是左侧「Skill 管理」页（`/skills`，ADMIN 才能增删改）。该表**只用于登记与对外声明**，
没有任何调度器据此自动执行；`risk_level=write` 的技能仍须由人在自动化配置页确认下发。
点检该用什么标准同理登记在 `inspection_standards`（前端「点检标准」页 `/standards`，OPERATOR 可维护，
内置 18 条可在页面上启停与改处置措施）。

自动修复脚本不再直接登录设备执行, 而是走业务层的 SSH 下发通道
(`POST /api/automation/raw-command` 等), 每次执行都会写入 `config_audit_logs` 审计，
因此天然满足“需人工确认 + 可追溯”。设备凭据只存在 `switch_devices.ssh_config`,
且不会出现在任何 API 响应中。

---

## 2. 告警响应流程

### 2.1 告警分级

| 级别 | 颜色 | 响应时间 | 处理方式 |
|-----|------|---------|---------|
| **CRITICAL** | 🔴 红色 | < 5 分钟 | 立即通知，自动分析根因 |
| **WARNING** | 🟡 黄色 | < 30 分钟 | 通知运维人员，提供建议 |
| **INFO** | 🔵 蓝色 | < 2 小时 | 记录日志，定期汇总 |

### 2.2 告警响应矩阵

#### 交换机告警

| 告警类型 | 级别 | 响应动作 | 通知渠道 |
|---------|------|---------|---------|
| **设备离线** | CRITICAL | 1. 检查网络连通性<br>2. 检查 SNMP 配置<br>3. 检查设备电源 | 钉钉 + 邮件 + 短信 |
| **端口 Down** | WARNING | 1. 检查端口配置<br>2. 检查对端设备<br>3. 检查网线连接 | 钉钉 + 邮件 |
| **电源故障** | CRITICAL | 1. 检查电源模块<br>2. 检查供电线路<br>3. 准备备用电源 | 钉钉 + 邮件 + 短信 |
| **温度过高** | CRITICAL | 1. 检查机房空调<br>2. 检查设备风扇<br>3. 降低设备负载 | 钉钉 + 邮件 + 短信 |
| **CPU 使用率高** | WARNING | 1. 检查流量异常<br>2. 检查异常进程<br>3. 优化配置 | 钉钉 + 邮件 |

#### 服务器告警

| 告警类型 | 级别 | 响应动作 | 通知渠道 |
|---------|------|---------|---------|
| **CPU 使用率 > 90%** | WARNING | 1. 检查进程列表<br>2. 识别高 CPU 进程<br>3. 优化或重启服务 | 钉钉 + 邮件 |
| **内存使用率 > 90%** | WARNING | 1. 检查内存泄漏<br>2. 清理缓存<br>3. 增加内存 | 钉钉 + 邮件 |
| **磁盘使用率 > 90%** | WARNING | 1. 清理日志文件<br>2. 删除临时文件<br>3. 扩容磁盘 | 钉钉 + 邮件 |
| **关键进程异常** | CRITICAL | 1. 检查进程状态<br>2. 查看错误日志<br>3. 重启服务 | 钉钉 + 邮件 + 短信 |
| **服务不可达** | CRITICAL | 1. 检查网络连通性<br>2. 检查服务状态<br>3. 重启服务 | 钉钉 + 邮件 + 短信 |

### 2.3 告警响应流程

```
1. 告警触发
   ↓
2. 告警引擎检查规则
   ↓
3. 生成 AlertRecord
   ↓
4. 检查维护窗口（是否抑制）
   ↓
5. 告警去重（同一设备+规则）
   ↓
6. 发送通知（邮件/钉钉/短信）
   ↓
7. AI 助手分析告警
   ├─ 判断告警级别
   ├─ 分析影响范围
   ├─ 定位可能原因
   └─ 生成修复建议
   ↓
8. 推送给运维人员
   ↓
9. 运维人员确认（acknowledged）
   ↓
10. 执行修复（人工/自动）
    ↓
11. 告警恢复（resolved）
    ↓
12. 记录处理过程
```

### 2.4 告警聚合规则

为避免告警风暴，AI 助手会自动聚合相关告警：

**规则 1：同一网段设备批量离线**
```
触发条件：同一网段 > 5 台设备同时离线
聚合动作：合并为一条 CRITICAL 告警
告警内容："[网段] 发生批量设备离线，影响 X 台设备"
```

**规则 2：核心交换机离线**
```
触发条件：核心交换机离线
聚合动作：抑制其下联设备的告警
告警内容："[核心交换机] 离线，下联 X 台设备可能受影响"
```

**规则 3：级联故障**
```
触发条件：多个设备在短时间内（< 5 分钟）连续告警
聚合动作：合并为一条告警，标注为"级联故障"
告警内容："[级联故障] 检测到 X 台设备连续告警，可能为级联故障"
```

---

## 3. 主动运维策略

### 3.1 异常检测策略

#### EWMA 动态基线

**算法原理**：
```python
# 基线更新
baseline[t] = α * x[t] + (1 - α) * baseline[t-1]
variance[t] = α * (x[t] - baseline[t])² + (1 - α) * variance[t-1]

# 异常判定
z_score = |x[t] - baseline[t]| / sqrt(variance[t])
is_anomaly = (z_score > k) AND (|x[t] - baseline[t]| > min_deviation)
```

**参数配置**：
```yaml
proactive:
  ewma_alpha: 0.15        # 平滑系数（0~1）
  min_samples: 20         # 建立基线所需最少样本
  anomaly_k: 4.0          # z 分数阈值
  outlier_damping: 0.1    # 异常样本学习率
  dedup_minutes: 30       # 去重窗口（分钟）
```

**适用场景**：
- 温度缓慢上升（预测过热风险）
- CPU 使用率异常波动（识别异常进程）
- 流量突增（识别网络攻击或故障）
- 内存泄漏检测（趋势分析）

#### 阈值检测

**固定阈值**：
```yaml
alerts:
  rules:
    server_cpu_high:
      threshold: 90
      severity: "WARNING"
    
    server_memory_high:
      threshold: 90
      severity: "WARNING"
    
    server_disk_high:
      threshold: 90
      severity: "WARNING"
```

**动态阈值**：
```python
# 基于历史数据计算阈值
threshold = baseline + k * sqrt(variance)
```

### 3.2 健康评分策略

#### 评分维度

| 维度 | 权重 | 扣分规则 |
|-----|------|---------|
| **在线状态** | 30 分 | 离线扣 30 分 |
| **电源状态** | 15 分 | 电源故障扣 15 分 |
| **温度状态** | 15 分 | 温度异常扣 15 分 |
| **异常事件** | 20 分 | 每个未处理异常扣 20 分 |
| **活动告警** | 20 分 | 每个活动告警扣 20 分 |

#### 评分计算

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

#### 健康等级

| 分数范围 | 等级 | 颜色 | 处理建议 |
|---------|------|------|---------|
| 90-100 | 优秀 | 🟢 绿色 | 正常运行 |
| 70-89 | 良好 | 🟡 黄色 | 关注趋势 |
| 50-69 | 一般 | 🟠 橙色 | 需要优化 |
| 0-49 | 差 | 🔴 红色 | 立即处理 |

### 3.3 趋势预测策略

#### 温度趋势预测

**预测模型**：
```python
# 线性回归预测
def predict_temperature(history_data, hours_ahead=24):
    x = [t.timestamp for t in history_data]
    y = [t.temperature for t in history_data]
    
    # 拟合线性模型
    slope, intercept = linear_regression(x, y)
    
    # 预测未来温度
    future_time = now() + timedelta(hours=hours_ahead)
    predicted_temp = slope * future_time + intercept
    
    return predicted_temp
```

**预警规则**：
- 预测温度 > 阈值 - 5°C → 提前 24 小时预警
- 预测温度 > 阈值 → 立即预警

#### 容量趋势预测

**磁盘容量预测**：
```python
def predict_disk_usage(history_data, days_ahead=30):
    # 计算日均增长率
    growth_rate = (history_data[-1] - history_data[0]) / len(history_data)
    
    # 预测未来容量
    predicted_usage = history_data[-1] + growth_rate * days_ahead
    
    # 计算达到 90% 的时间
    if growth_rate > 0:
        days_to_90 = (90 - history_data[-1]) / growth_rate
        return predicted_usage, days_to_90
    
    return predicted_usage, None
```

**预警规则**：
- 预测 30 天内达到 90% → WARNING
- 预测 7 天内达到 90% → CRITICAL

---

## 4. 自动化任务说明

### 4.1 定时任务列表

三层架构下定时任务分属两个进程, 周期与开关的存放位置也不同。

**采集层 (Python, 周期与开关存在 `collector_jobs` 表, 每 5 分钟热加载, 可在「系统管理 → 采集任务」页调整)**

| job_id | 默认间隔 | 默认超时 | 说明 |
|--------|---------|---------|------|
| `snmp_poll_status` | 120s | 120s | 采集交换机端口状态 → `port_status` |
| `snmp_poll_traffic` | 300s | 180s | 采集端口流量统计 → `port_traffic_stats` |
| `snmp_poll_hardware` | 600s | 300s | 采集温度、电源、风扇 → `hardware_health` |
| `server_poll_all` | 30s | 120s | 采集服务器 CPU/内存/磁盘 → `server_metrics` |
| `process_monitor_all` | 60s | 120s | 监控关键进程状态 → `process_status` |
| `device_rediscovery` | 1800s | 600s | 扫描 `discovery_groups` 定义的网段, 新增/更新设备 |

每轮采集结束写一行 `collector_heartbeats`; 业务层据此判断「采集层是否活着」(超过 180s 无心跳视为离线)。

**业务层 (Spring Boot, 固定 `@Scheduled`, 周期由代码常量维护)**

| 任务 | 间隔 | 说明 |
|------|------|------|
| 快照轮询 + SSE 广播 | 2s | 读库构建实时快照, 心跳前进时立即触发告警检查 |
| 告警引擎 | 30s | 8 条规则检查 + 通知发送 + 维护窗口抑制 |
| 点检任务生成 | 60s | 按计划 cron 生成任务并标记逾期 |
| 主动运维分析 | 300s | EWMA 基线 / 异常事件 / 健康评分 / 建议 |
| 数据保留清理 | 每日 03:30 | 按 `sys_config.retention_policy` 分批删除 |
| 跨层指令拉取 | 10s | 读 `collector_commands` 通知采集层重发现/重采 |

### 4.2 任务调度策略

#### 优先级调度

```python
# 采集层任务优先级 (数值小者优先)
TASK_PRIORITY = {
    "服务器资源采集": 1,
    "SNMP 端口状态采集": 2,
    "SNMP 流量统计采集": 3,
    "SNMP 硬件健康采集": 4,
    "进程/服务监控": 5,
    "设备重新发现": 6,
}
```

#### 并发控制

```python
# 同类任务最大并发数 (collector_jobs.max_instances)
MAX_CONCURRENT = {
    "SNMP 端口状态采集": 1,
    "SNMP 流量统计采集": 1,
    "SNMP 硬件健康采集": 1,
    "服务器资源采集": 1,
}
```

#### 超时保护

```python
# 任务超时时间 (collector_jobs.timeout_seconds)
TASK_TIMEOUT = {
    "SNMP 端口状态采集": 120,
    "SNMP 流量统计采集": 180,
    "SNMP 硬件健康采集": 300,
}
```

### 4.3 自动修复脚本

#### 脚本 1：重启 SNMP 服务

**触发条件**：设备 SNMP 不可达
**执行步骤**：
```bash
#!/bin/bash
# restart_snmp.sh

DEVICE_IP=$1

# 1. 检查网络连通性
ping -c 3 $DEVICE_IP
if [ $? -ne 0 ]; then
    echo "网络不通，无法修复"
    exit 1
fi

# 2. SSH 登录设备
ssh admin@$DEVICE_IP << 'EOF'
# 3. 重启 SNMP 服务
no snmp-server
snmp-server
write memory

# 4. 检查 SNMP 状态
show snmp
EOF

# 5. 验证修复
snmpget -v2c -c public $DEVICE_IP 1.3.6.1.2.1.1.1.0
if [ $? -eq 0 ]; then
    echo "SNMP 服务已恢复"
else
    echo "SNMP 服务未恢复，需要人工介入"
fi
```

#### 脚本 2：清理磁盘空间

**触发条件**：磁盘使用率 > 90%
**执行步骤**：
```bash
#!/bin/bash
# cleanup_disk.sh

SERVER_IP=$1

# 1. SSH 登录服务器
ssh admin@$SERVER_IP << 'EOF'

# 2. 清理日志文件
find /var/log -name "*.log" -mtime +7 -delete

# 3. 清理临时文件
rm -rf /tmp/*

# 4. 清理缓存
sync
echo 3 > /proc/sys/vm/drop_caches

# 5. 检查磁盘使用率
df -h /
EOF

# 6. 验证修复
DISK_USAGE=$(ssh admin@$SERVER_IP "df -h / | tail -1 | awk '{print \$5}' | sed 's/%//'")
if [ $DISK_USAGE -lt 90 ]; then
    echo "磁盘空间已清理，当前使用率：$DISK_USAGE%"
else
    echo "磁盘空间未释放，需要人工介入"
fi
```

#### 脚本 3：重启异常服务

**触发条件**：关键进程异常退出
**执行步骤**：
```bash
#!/bin/bash
# restart_service.sh

SERVER_IP=$1
SERVICE_NAME=$2

# 1. SSH 登录服务器
ssh admin@$SERVER_IP << EOF

# 2. 检查服务状态
systemctl status $SERVICE_NAME

# 3. 重启服务
systemctl restart $SERVICE_NAME

# 4. 检查服务状态
systemctl status $SERVICE_NAME
EOF

# 5. 验证修复
ssh admin@$SERVER_IP "systemctl is-active $SERVICE_NAME"
if [ $? -eq 0 ]; then
    echo "服务 $SERVICE_NAME 已重启"
else
    echo "服务 $SERVICE_NAME 未恢复，需要人工介入"
fi
```

### 4.4 维护窗口管理

#### 维护窗口配置

维护窗口不再是 YAML 配置, 而是业务层数据库表 `maintenance_windows`, 通过
「主动运维 → 维护窗口」页面或 `POST /api/proactive/maintenance/windows` 维护
(需要 OPERATOR 及以上权限), 支持一次性窗口与每日/每周重复窗口。

```jsonc
// POST /api/proactive/maintenance/windows
{
  "name": "每日维护窗口",
  "device_type": "all",        // all / switch / server
  "device_ids": [],            // 空数组 = 该类型全部设备
  "start_time": "2026-10-22 02:00:00",
  "end_time": "2026-10-22 03:00:00",
  "reason": "例行巡检",
  "enabled": true
}
```

#### 维护窗口行为

**窗口内**：
- ✅ 告警抑制（不发送通知）
- ✅ 告警记录（但不推送）
- ✅ 采集继续（保持数据完整性）
- ✅ 自动修复暂停（避免干扰维护）

**窗口外**：
- ✅ 告警正常推送
- ✅ 自动修复启用
- ✅ 采集正常运行

---

## 5. 通知策略

### 5.1 通知渠道

| 渠道 | 适用场景 | 配置方式 |
|-----|---------|---------|
| **钉钉机器人** | 实时告警通知 | Webhook URL |
| **邮件** | 告警详情、日报、周报 | SMTP 配置 |
| **短信** | CRITICAL 级别告警 | 短信网关 API |
| **微信** | 告警通知（可选） | 企业微信 API |

### 5.2 通知规则

#### 告警通知

```yaml
notifications:
  dingtalk:
    enabled: true
    webhook_url: "https://oapi.dingtalk.com/robot/send?access_token=xxx"
    secret: "xxx"
    
  email:
    enabled: true
    smtp_host: "smtp.example.com"
    smtp_port: 465
    smtp_ssl: true
    username: "monitor@example.com"
    password: "xxx"
    sender: "monitor@example.com"
    recipients:
      - "admin@example.com"
      
  sms:
    enabled: true
    gateway_url: "https://sms.example.com/send"
    api_key: "xxx"
    recipients:
      - "13800138000"
```

#### 通知级别

| 告警级别 | 钉钉 | 邮件 | 短信 |
|---------|------|------|------|
| **CRITICAL** | ✅ | ✅ | ✅ |
| **WARNING** | ✅ | ✅ | ❌ |
| **INFO** | ✅ | ❌ | ❌ |

### 5.3 通知模板

#### 钉钉通知模板

```markdown
## 🔴 CRITICAL 告警

**设备**: {{ device_name }}
**IP**: {{ device_ip }}
**告警类型**: {{ rule_name }}
**触发时间**: {{ triggered_at }}

### 告警详情

{{ description }}

### 当前指标

- 指标值: {{ metric_value }}
- 阈值: {{ threshold }}

### 建议操作

1. {{ suggestion_1 }}
2. {{ suggestion_2 }}
3. {{ suggestion_3 }}

[查看详情](http://monitor.example.com/alerts/{{ alert_id }})
```

#### 邮件通知模板

```html
<h2>🔴 CRITICAL 告警通知</h2>

<table border="1" cellpadding="5">
  <tr>
    <td><strong>设备名称</strong></td>
    <td>{{ device_name }}</td>
  </tr>
  <tr>
    <td><strong>设备 IP</strong></td>
    <td>{{ device_ip }}</td>
  </tr>
  <tr>
    <td><strong>告警类型</strong></td>
    <td>{{ rule_name }}</td>
  </tr>
  <tr>
    <td><strong>触发时间</strong></td>
    <td>{{ triggered_at }}</td>
  </tr>
  <tr>
    <td><strong>告警详情</strong></td>
    <td>{{ description }}</td>
  </tr>
</table>

<h3>建议操作</h3>
<ol>
  <li>{{ suggestion_1 }}</li>
  <li>{{ suggestion_2 }}</li>
  <li>{{ suggestion_3 }}</li>
</ol>

<p>
  <a href="http://monitor.example.com/alerts/{{ alert_id }}">查看详情</a>
</p>
```

---

## 6. AI 助手配置

### 6.1 AI 助手参数

```yaml
ai_assistant:
  enabled: true
  name: "主动运维 AI 助手"
  version: "1.0.0"
  
  # 告警分析参数
  alert_analysis:
    enabled: true
    root_cause_analysis: true
    impact_analysis: true
    suggestion_generation: true
    
  # 趋势预测参数
  trend_prediction:
    enabled: true
    prediction_horizon_hours: 24
    min_data_points: 20
    
  # 自动修复参数
  auto_remediation:
    enabled: false  # 默认关闭，需人工确认
    require_confirmation: true
    max_retries: 3
    retry_interval_seconds: 300
```

### 6.2 AI 助手规则库

#### 规则 1：设备离线根因分析

```yaml
rule:
  name: "设备离线根因分析"
  condition: "device.is_online == false"
  actions:
    - check_network_connectivity
    - check_snmp_configuration
    - check_device_power_status
    - check_recent_events
  suggestions:
    - "检查设备电源是否正常"
    - "检查网络连接是否正常"
    - "检查 SNMP 配置是否正确"
    - "检查设备是否被人为关闭"
```

#### 规则 2：温度异常根因分析

```yaml
rule:
  name: "温度异常根因分析"
  condition: "device.temperature > threshold"
  actions:
    - check_ambient_temperature
    - check_fan_status
    - check_airflow
    - check_device_load
  suggestions:
    - "检查机房空调是否正常运行"
    - "检查设备风扇是否正常工作"
    - "检查设备通风是否良好"
    - "检查设备负载是否过高"
```

#### 规则 3：CPU 使用率高根因分析

```yaml
rule:
  name: "CPU 使用率高根因分析"
  condition: "server.cpu_usage > 90"
  actions:
    - check_top_processes
    - check_system_load
    - check_recent_changes
    - check_resource_contention
  suggestions:
    - "检查是否有异常进程占用 CPU"
    - "检查系统负载是否过高"
    - "检查近期是否有配置变更"
    - "考虑优化应用或增加 CPU 资源"
```

### 6.3 AI 助手学习机制

#### 反馈学习

```python
# AI 助手从运维人员反馈中学习
def learn_from_feedback(alert_id, feedback):
    """
    feedback: {
        "action_taken": "重启了 SNMP 服务",
        "root_cause": "SNMP 服务卡死",
        "resolution_time": 300,  # 秒
        "effective": True
    }
    """
    # 1. 记录处理过程
    save_resolution_pattern(alert_id, feedback)
    
    # 2. 更新根因分析规则
    if feedback["effective"]:
        boost_rule_confidence(feedback["root_cause"])
    
    # 3. 优化建议生成
    update_suggestions(feedback["action_taken"])
```

#### 模式识别

```python
# 识别故障模式
def identify_fault_patterns():
    """
    识别常见故障模式：
    - 时间模式：每天 2:00 出现告警 → 可能与定时任务冲突
    - 空间模式：同一网段设备批量离线 → 可能是网络故障
    - 因果模式：温度升高 → CPU 使用率升高 → 可能是散热问题
    """
    patterns = []
    
    # 时间模式
    time_patterns = analyze_temporal_patterns(alert_history)
    patterns.extend(time_patterns)
    
    # 空间模式
    spatial_patterns = analyze_spatial_patterns(alert_history)
    patterns.extend(spatial_patterns)
    
    # 因果模式
    causal_patterns = analyze_causal_patterns(alert_history)
    patterns.extend(causal_patterns)
    
    return patterns
```

---

## 7. 监控指标

### 7.1 系统监控指标

| 指标 | 说明 | 采集频率 | 告警阈值 |
|-----|------|---------|---------|
| **CPU 使用率** | 服务器 CPU 使用率 | 30s | > 90% |
| **内存使用率** | 服务器内存使用率 | 30s | > 90% |
| **磁盘使用率** | 服务器磁盘使用率 | 30s | > 90% |
| **网络流量** | 服务器网络流量 | 30s | > 80% 带宽 |
| **数据库体积** | MySQL `information_schema` 各表数据+索引体积 | 每日 | > 10GB |
| **采集层心跳** | `collector_heartbeats.last_beat_at` 距今时长 | 2s | > 180s 视为离线 |
| **任务堆积** | 采集任务单轮耗时超过其周期 | 60s | 连续 3 轮 |

### 7.2 业务监控指标

| 指标 | 说明 | 采集频率 | 告警阈值 |
|-----|------|---------|---------|
| **设备在线率** | 在线设备占比 | 120s | < 95% |
| **告警数量** | 每小时告警数量 | 3600s | > 50 |
| **告警响应时间** | 从告警触发到确认的时间 | 实时 | > 30 分钟 |
| **告警解决时间** | 从告警触发到解决的时间 | 实时 | > 2 小时 |
| **采集成功率** | 采集成功的设备占比 | 120s | < 90% |
| **推送延迟** | SSE 推送延迟 | 实时 | > 5 秒 |

### 7.3 AI 助手监控指标

| 指标 | 说明 | 采集频率 | 告警阈值 |
|-----|------|---------|---------|
| **告警分析准确率** | AI 分析告警的准确率 | 每日 | < 80% |
| **根因定位准确率** | 根因定位的准确率 | 每周 | < 70% |
| **自动修复成功率** | 自动修复的成功率 | 每月 | < 60% |
| **预测准确率** | 趋势预测的准确率 | 每月 | < 75% |
| **建议采纳率** | 运维人员采纳建议的比例 | 每月 | < 50% |

---

## 8. 故障恢复流程

### 8.1 系统故障恢复

#### 场景 1：主动运维系统宕机

**恢复步骤**：
```powershell
# 1. 看守护进程是否还在 (两个子进程: python 采集层 / java 业务层)
Get-Content ops-collector\logs\supervisor_heartbeat.json | ConvertFrom-Json | Format-List

# 2. 查看错误日志 (先采集层后业务层)
Get-Content ops-collector\logs\collector.err.log -Tail 50
Get-Content ops-collector\logs\server.err.log -Tail 50
Get-Content ops-collector\logs\server.log -Tail 50

# 3. 拉起两层 (前台方式, 异常退出会自动重启)
python ops-collector\scripts\start_all.py
#    或重新触发开机计划任务
Start-ScheduledTask -TaskName ProactiveOpsMonitor

# 4. 验证服务状态 (需先登录取 JWT)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/system/health

# 5. 确认采集层已写入新心跳
mysql -h 10.51.190.70 -u test -p monitor -e "SELECT component,phase,total,errors,last_beat_at FROM collector_heartbeats ORDER BY id DESC LIMIT 10;"
```

采集层与业务层任一崩溃都由 `supervisor.py` 按指数退避 (5→60s) 自动拉起, 通常无需人工重启。

#### 场景 2：数据库损坏

**恢复步骤**：
```bash
# 1. 停止服务
systemctl stop ops-collector

# 2. 备份当前数据库
cp data/ops_monitor.db data/ops_monitor.db.bak

# 3. 从备份恢复
cp backup/ops_monitor.db data/ops_monitor.db

# 4. 启动服务
systemctl start ops-collector

# 5. 验证数据
python scripts/verify_data.py
```

### 8.2 网络故障恢复

#### 场景 1：网络设备批量离线

**恢复步骤**：
```bash
# 1. 检查核心交换机
ping 10.51.200.1
snmpget -v2c -c public 10.51.200.1 1.3.6.1.2.1.1.1.0

# 2. 检查网络连通性
traceroute 10.51.200.1

# 3. 检查核心交换机配置
ssh admin@10.51.200.1
show running-config

# 4. 重启核心交换机 SNMP 服务
ssh admin@10.51.200.1
no snmp-server
snmp-server
write memory

# 5. 验证恢复
#    页面「交换机管理」看该设备是否回到在线, 或
#    GET /api/switches/summary (需 JWT)
```

### 8.3 数据恢复

#### 场景 1：历史数据丢失

**恢复步骤**：
```powershell
# 1. 从 MySQL 备份恢复
mysql -h 10.51.190.70 -u test -p monitor -e "source D:/backup/monitor_full.sql"

# 2. 若只有旧 SQLite 文件, 用迁移脚本回灌历史数据
python tools\migrate_sqlite_to_mysql.py --truncate

# 3. 重建基线: 采集数据重新积累后 EWMA 会自动重算,
#    也可手动触发一次全量分析 (ADMIN): POST /api/proactive/analyze

# 4. 验证数据完整性: GET /api/system/health 看 table_rows 与 freshness
```

---

## 9. 附录

### 9.1 常用命令

除 `POST /api/auth/login` 外, 所有 `/api/**` 接口都需要 JWT:

```bash
# 0. 登录 (默认账号 admin/admin123 / oper/oper123 / viewer/viewer123, 首次登录后请改口令)
curl -s -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"admin123"}'
#    取响应中的 data.token, 后续请求带上
AUTH="Authorization: Bearer $TOKEN"

# 1. 系统健康 (含采集层心跳与各表新鲜度)
curl -s -H "$AUTH" http://localhost:8080/api/system/health

# 2. 实时推送状态 (subscribers / collector_alive)
curl -s -H "$AUTH" http://localhost:8080/api/realtime/status

# 3. 告警统计与列表
curl -s -H "$AUTH" http://localhost:8080/api/alerts/summary
curl -s -H "$AUTH" "http://localhost:8080/api/alerts/list?status=active&page=1&page_size=20"

# 4. 采集任务周期 (GET 查看, PUT 调整周期与开关, ADMIN)
curl -s -H "$AUTH" http://localhost:8080/api/collector-jobs

# 5. 采集心跳明细
curl -s -H "$AUTH" http://localhost:8080/api/system/collector/heartbeat

# 6. 手动触发设备发现 (写 collector_commands, 采集层 10s 内拉取执行)
curl -s -X POST -H "$AUTH" http://localhost:8080/api/system/rediscover

# 7. 手动触发一次主动运维分析 (ADMIN, 本层同步执行)
curl -s -X POST -H "$AUTH" http://localhost:8080/api/proactive/analyze

# 8. 手动数据清理 (ADMIN)
curl -s -X POST -H "$AUTH" http://localhost:8080/api/system/maintenance/cleanup
```

> 告警检查不需要手动触发: 业务层每 30s 一轮, 采集心跳前进时会立即执行一轮。

### 9.2 配置与日志路径

```
# 采集层配置 (只含 MySQL/SNMP/Trap/日志)
ops-collector/config/collector.yaml

# 业务层配置 (端口/数据源/JWT/邮件/CORS)
ops-server/src/main/resources/application.yml

# 数据库表结构与种子数据 (业务层首启动由 Flyway 自动执行)
ops-server/src/main/resources/db/migration/V1__baseline.sql
ops-server/src/main/resources/db/migration/V2__seed.sql

# 采集层日志
ops-collector/logs/collector.out.log
ops-collector/logs/collector.err.log

# 业务层日志
ops-collector/logs/server.log
ops-collector/logs/server.out.log
ops-collector/logs/server.err.log

# 守护进程日志与心跳
ops-collector/logs/supervisor.log
ops-collector/logs/supervisor_heartbeat.json

# 遗留文件 (已退役, 仅供对照)
ops-collector/config/settings.yaml
ops-collector/config/devices.yaml
ops-collector/data/ops_monitor.db
ops-collector/legacy_reference/
```

### 9.3 联系方式

| 角色 | 姓名 | 电话 | 邮箱 |
|-----|------|------|------|
| **运维负责人** | 张三 | 13800138000 | zhangsan@example.com |
| **开发负责人** | 李四 | 13800138001 | lisi@example.com |
| **AI 助手支持** | 王五 | 13800138002 | wangwu@example.com |

---

**文档结束**
