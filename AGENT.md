# MOXA 主动运维监控系统 - AI 运维代理配置文档

## 📋 文档信息

- **版本**: v1.0.0
- **最后更新**: 2026-10-21
- **维护者**: 运维开发团队
- **适用范围**: AI 运维助手、自动化运维系统

---

## 1. AI 运维助手角色定义

### 1.1 角色概述

**AI 运维助手**是 MOXA 主动运维监控系统的核心智能组件，负责：

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

| 任务名称 | 间隔 | 超时 | 说明 |
|---------|------|------|------|
| **SNMP 端口状态采集** | 120s | 120s | 采集交换机端口状态 |
| **SNMP 流量统计采集** | 300s | 180s | 采集端口流量统计 |
| **SNMP 硬件健康采集** | 600s | 300s | 采集温度、电源状态 |
| **服务器资源采集** | 30s | - | 采集 CPU/内存/磁盘 |
| **进程/服务监控** | 60s | - | 监控关键进程状态 |
| **告警规则检查** | 30s | - | 检查告警触发条件 |
| **设备重新发现** | 30min | - | 扫描网络中的新设备 |
| **数据保留清理** | 每日 03:30 | - | 清理过期数据 |
| **点检任务生成** | 60s | - | 生成点检任务 |
| **主动运维分析** | 300s | - | EWMA 基线分析 |

### 4.2 任务调度策略

#### 优先级调度

```python
# 任务优先级
TASK_PRIORITY = {
    "告警规则检查": 1,      # 最高优先级
    "服务器资源采集": 2,
    "SNMP 端口状态采集": 3,
    "主动运维分析": 4,
    "点检任务生成": 5,
    "数据保留清理": 6,      # 最低优先级
}
```

#### 并发控制

```python
# 同类任务最大并发数
MAX_CONCURRENT = {
    "SNMP 端口状态采集": 1,
    "SNMP 流量统计采集": 1,
    "SNMP 硬件健康采集": 1,
    "服务器资源采集": 1,
}
```

#### 超时保护

```python
# 任务超时时间
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

```yaml
# settings.yaml
maintenance:
  windows:
    - name: "每日维护窗口"
      schedule: "0 2 * * *"  # 每天 2:00
      duration: 3600         # 1 小时
      devices: "all"
      
    - name: "周末维护窗口"
      schedule: "0 2 * * 6"  # 每周六 2:00
      duration: 14400        # 4 小时
      devices: "all"
      
    - name: "月度维护窗口"
      schedule: "0 2 1 * *"  # 每月 1 号 2:00
      duration: 28800        # 8 小时
      devices: "all"
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
  name: "MOXA AI 运维助手"
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
| **数据库连接数** | SQLite 连接数 | 60s | > 100 |
| **任务队列长度** | APScheduler 队列长度 | 60s | > 10 |

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

#### 场景 1：监控系统宕机

**恢复步骤**：
```bash
# 1. 检查服务状态
systemctl status moxa-monitor

# 2. 查看错误日志
tail -f logs/service.err.log

# 3. 重启服务
systemctl restart moxa-monitor

# 4. 验证服务状态
curl http://localhost:8080/api/system/health

# 5. 检查数据完整性
python scripts/check_data_integrity.py
```

#### 场景 2：数据库损坏

**恢复步骤**：
```bash
# 1. 停止服务
systemctl stop moxa-monitor

# 2. 备份当前数据库
cp data/moxa_monitor.db data/moxa_monitor.db.bak

# 3. 从备份恢复
cp backup/moxa_monitor.db data/moxa_monitor.db

# 4. 启动服务
systemctl start moxa-monitor

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
python scripts/check_device_status.py
```

### 8.3 数据恢复

#### 场景 1：历史数据丢失

**恢复步骤**：
```bash
# 1. 从备份恢复
cp backup/moxa_monitor.db data/moxa_monitor.db

# 2. 导入历史数据
python scripts/import_historical_data.py --file backup/historical_data.csv

# 3. 重建基线
python scripts/rebuild_baseline.py

# 4. 验证数据完整性
python scripts/check_data_integrity.py
```

---

## 9. 附录

### 9.1 常用命令

```bash
# 查看系统状态
curl http://localhost:8080/api/system/status

# 查看实时状态
curl http://localhost:8080/api/realtime/status

# 查看告警统计
curl http://localhost:8080/api/alerts/summary

# 手动触发设备发现
curl -X POST http://localhost:8080/api/system/rediscover

# 手动触发告警检查
curl -X POST http://localhost:8080/api/system/check-alerts

# 查看任务调度状态
curl http://localhost:8080/api/system/jobs
```

### 9.2 配置文件路径

```
# 主配置文件
config/settings.yaml

# 设备配置文件
config/devices.yaml

# 数据库文件
data/moxa_monitor.db

# 日志文件
logs/service.out.log
logs/service.err.log
logs/supervisor.log
```

### 9.3 联系方式

| 角色 | 姓名 | 电话 | 邮箱 |
|-----|------|------|------|
| **运维负责人** | 张三 | 13800138000 | zhangsan@example.com |
| **开发负责人** | 李四 | 13800138001 | lisi@example.com |
| **AI 助手支持** | 王五 | 13800138002 | wangwu@example.com |

---

**文档结束**
