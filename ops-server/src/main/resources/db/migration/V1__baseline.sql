-- ============================================================
-- V1__baseline.sql - 主动运维系统 全量表结构
-- 分层约定: 标注 [C] 的表由 Python 采集层写入, 标注 [B] 的表由 Spring Boot 业务层写入,
--           标注 [CB] 的表两层共写但列不重叠 (详见 docs/ARCHITECTURE.md 表所有权矩阵)
-- 字符集: utf8mb4 / 引擎: InnoDB / 时间: DATETIME 无时区, 全链路 Asia/Shanghai
-- ============================================================

-- ---------- 设备台账 ----------

-- [CB] 交换机: Python 写采集字段, Spring Boot 写台账字段
CREATE TABLE switch_devices (
    id                VARCHAR(50)  NOT NULL COMMENT '设备ID',
    name              VARCHAR(200) NOT NULL COMMENT '设备名称',
    ip                VARCHAR(50)  NOT NULL COMMENT 'IP地址',
    model             VARCHAR(100) NOT NULL DEFAULT '' COMMENT '设备型号',
    location          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '物理位置',
    firmware_version  VARCHAR(100) NOT NULL DEFAULT '' COMMENT '固件版本',
    serial_number     VARCHAR(100) NOT NULL DEFAULT '' COMMENT '序列号',
    total_ports       INT          NOT NULL DEFAULT 0 COMMENT '端口总数',
    uptime_seconds    INT          NOT NULL DEFAULT 0 COMMENT '运行时长(秒)',
    cpu_usage         FLOAT        NOT NULL DEFAULT 0 COMMENT 'CPU使用率(%)',
    memory_usage      FLOAT        NOT NULL DEFAULT 0 COMMENT '内存使用率(%)',
    is_online         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否在线',
    last_poll_time    DATETIME     NULL COMMENT '最后采集时间',
    snmp_config       JSON         NULL COMMENT 'SNMP配置',
    ssh_config        JSON         NULL COMMENT 'SSH配置',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_switch_ip (ip),
    KEY ix_switch_enabled (enabled),
    KEY ix_switch_online (is_online)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交换机设备表';

-- [CB] 服务器: Python 写采集字段, Spring Boot 写台账字段
CREATE TABLE server_devices (
    id                VARCHAR(50)  NOT NULL COMMENT '服务器ID',
    name              VARCHAR(200) NOT NULL COMMENT '服务器名称',
    ip                VARCHAR(50)  NOT NULL COMMENT 'IP地址',
    os_type           VARCHAR(20)  NOT NULL COMMENT '操作系统类型: windows/linux',
    location          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '物理位置',
    hostname          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '主机名',
    os_version        VARCHAR(200) NOT NULL DEFAULT '' COMMENT '系统版本',
    cpu_cores         INT          NOT NULL DEFAULT 0 COMMENT 'CPU核心数',
    total_memory_mb   INT          NOT NULL DEFAULT 0 COMMENT '总内存(MB)',
    is_online         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否在线',
    uptime_seconds    INT          NOT NULL DEFAULT 0 COMMENT '运行时长(秒)',
    last_poll_time    DATETIME     NULL COMMENT '最后采集时间',
    monitoring_config JSON         NULL COMMENT '监控配置(采集方式与凭据)',
    processes_config  JSON         NULL COMMENT '进程监控配置',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_ip (ip),
    KEY ix_server_enabled (enabled),
    KEY ix_server_online (is_online)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='服务器设备表';

-- ---------- 采集数据 (仅 Python 写入) ----------

-- [C] 端口最新状态
CREATE TABLE port_status (
    id            INT          NOT NULL AUTO_INCREMENT,
    switch_id     VARCHAR(50)  NOT NULL COMMENT '交换机ID',
    port_index    INT          NOT NULL COMMENT '端口索引',
    port_name     VARCHAR(100) NOT NULL DEFAULT '' COMMENT '端口名称',
    admin_status  INT          NOT NULL DEFAULT 1 COMMENT '管理状态: 1=up, 2=down',
    oper_status   INT          NOT NULL DEFAULT 2 COMMENT '运行状态: 1=up, 2=down',
    speed_bps     BIGINT       NOT NULL DEFAULT 0 COMMENT '端口速率(bps)',
    mac_address   VARCHAR(50)  NOT NULL DEFAULT '' COMMENT 'MAC地址',
    vlan_id       INT          NOT NULL DEFAULT 1 COMMENT '所属VLAN',
    port_mode     VARCHAR(20)  NOT NULL DEFAULT 'access' COMMENT '端口模式: access/trunk/hybrid',
    last_change   DATETIME     NULL COMMENT '状态最后变更时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY ix_port_status_switch_id (switch_id),
    KEY ix_port_status_switch_port (switch_id, port_index),
    CONSTRAINT fk_port_status_switch FOREIGN KEY (switch_id) REFERENCES switch_devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交换机端口状态表';

-- [C] 端口流量时序
CREATE TABLE port_traffic_stats (
    id                INT         NOT NULL AUTO_INCREMENT,
    switch_id         VARCHAR(50) NOT NULL COMMENT '交换机ID',
    port_index        INT         NOT NULL COMMENT '端口索引',
    timestamp         DATETIME    NOT NULL COMMENT '采集时间',
    in_octets         BIGINT      NOT NULL DEFAULT 0 COMMENT '入方向字节数',
    out_octets        BIGINT      NOT NULL DEFAULT 0 COMMENT '出方向字节数',
    in_unicast_pkts   BIGINT      NOT NULL DEFAULT 0 COMMENT '入方向单播包数',
    out_unicast_pkts  BIGINT      NOT NULL DEFAULT 0 COMMENT '出方向单播包数',
    in_errors         BIGINT      NOT NULL DEFAULT 0 COMMENT '入方向错误包数',
    out_errors        BIGINT      NOT NULL DEFAULT 0 COMMENT '出方向错误包数',
    in_discards       BIGINT      NOT NULL DEFAULT 0 COMMENT '入方向丢包数',
    out_discards      BIGINT      NOT NULL DEFAULT 0 COMMENT '出方向丢包数',
    in_rate_bps       FLOAT       NOT NULL DEFAULT 0 COMMENT '入方向速率(bps)',
    out_rate_bps      FLOAT       NOT NULL DEFAULT 0 COMMENT '出方向速率(bps)',
    in_error_rate     FLOAT       NOT NULL DEFAULT 0 COMMENT '入方向错包率',
    out_error_rate    FLOAT       NOT NULL DEFAULT 0 COMMENT '出方向错包率',
    PRIMARY KEY (id),
    KEY ix_port_traffic_switch_id (switch_id),
    KEY ix_port_traffic_switch_port_ts (switch_id, port_index, timestamp),
    KEY ix_port_traffic_timestamp (timestamp),
    CONSTRAINT fk_port_traffic_switch FOREIGN KEY (switch_id) REFERENCES switch_devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='端口流量统计表';

-- [C] 硬件健康时序
CREATE TABLE hardware_health (
    id                INT         NOT NULL AUTO_INCREMENT,
    switch_id         VARCHAR(50) NOT NULL COMMENT '交换机ID',
    timestamp         DATETIME    NOT NULL COMMENT '采集时间',
    power1_status     INT         NOT NULL DEFAULT 0 COMMENT '电源1状态: 0=未知,1=正常,2=故障',
    power2_status     INT         NOT NULL DEFAULT 0 COMMENT '电源2状态: 0=未知,1=正常,2=故障',
    power1_voltage    FLOAT       NOT NULL DEFAULT 0 COMMENT '电源1电压(V)',
    power2_voltage    FLOAT       NOT NULL DEFAULT 0 COMMENT '电源2电压(V)',
    temperature       FLOAT       NOT NULL DEFAULT 0 COMMENT '设备温度(°C)',
    temperature_alarm INT         NOT NULL DEFAULT 0 COMMENT '温度告警: 0=正常,1=告警',
    fan_status        INT         NOT NULL DEFAULT 0 COMMENT '风扇状态: 0=未知,1=正常,2=故障',
    fan_speed         INT         NOT NULL DEFAULT 0 COMMENT '风扇转速(RPM)',
    cpu_usage         FLOAT       NOT NULL DEFAULT 0 COMMENT 'CPU使用率(%)',
    memory_usage      FLOAT       NOT NULL DEFAULT 0 COMMENT '内存使用率(%)',
    memory_total_kb   INT         NOT NULL DEFAULT 0 COMMENT '总内存(KB)',
    memory_used_kb    INT         NOT NULL DEFAULT 0 COMMENT '已用内存(KB)',
    overall_status    VARCHAR(20) NOT NULL DEFAULT 'normal' COMMENT '综合状态: normal/warning/critical',
    PRIMARY KEY (id),
    KEY ix_hardware_health_switch_id (switch_id),
    KEY ix_hardware_health_switch_ts (switch_id, timestamp),
    KEY ix_hardware_health_timestamp (timestamp),
    CONSTRAINT fk_hardware_switch FOREIGN KEY (switch_id) REFERENCES switch_devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交换机硬件健康表';

-- [C] 服务器资源时序
CREATE TABLE server_metrics (
    id                   INT         NOT NULL AUTO_INCREMENT,
    server_id            VARCHAR(50) NOT NULL COMMENT '服务器ID',
    timestamp            DATETIME    NOT NULL COMMENT '采集时间',
    cpu_usage_percent    FLOAT       NOT NULL DEFAULT 0 COMMENT 'CPU使用率(%)',
    cpu_per_core         JSON        NULL COMMENT '每核CPU使用率',
    cpu_freq_mhz         FLOAT       NOT NULL DEFAULT 0 COMMENT 'CPU频率(MHz)',
    memory_total_mb      BIGINT      NOT NULL DEFAULT 0 COMMENT '总内存(MB)',
    memory_used_mb       BIGINT      NOT NULL DEFAULT 0 COMMENT '已用内存(MB)',
    memory_usage_percent FLOAT       NOT NULL DEFAULT 0 COMMENT '内存使用率(%)',
    memory_available_mb  BIGINT      NOT NULL DEFAULT 0 COMMENT '可用内存(MB)',
    disk_total_gb        FLOAT       NOT NULL DEFAULT 0 COMMENT '磁盘总空间(GB)',
    disk_used_gb         FLOAT       NOT NULL DEFAULT 0 COMMENT '磁盘已用空间(GB)',
    disk_usage_percent   FLOAT       NOT NULL DEFAULT 0 COMMENT '磁盘使用率(%)',
    disk_read_bps        FLOAT       NOT NULL DEFAULT 0 COMMENT '磁盘读速率(B/s)',
    disk_write_bps       FLOAT       NOT NULL DEFAULT 0 COMMENT '磁盘写速率(B/s)',
    net_bytes_sent       BIGINT      NOT NULL DEFAULT 0 COMMENT '网络发送字节数',
    net_bytes_recv       BIGINT      NOT NULL DEFAULT 0 COMMENT '网络接收字节数',
    net_send_rate_bps    FLOAT       NOT NULL DEFAULT 0 COMMENT '网络发送速率(bps)',
    net_recv_rate_bps    FLOAT       NOT NULL DEFAULT 0 COMMENT '网络接收速率(bps)',
    uptime_seconds       BIGINT      NOT NULL DEFAULT 0 COMMENT '运行时长(秒)',
    load_avg             JSON        NULL COMMENT '负载平均值 [1m,5m,15m]',
    process_count        INT         NOT NULL DEFAULT 0 COMMENT '进程总数',
    PRIMARY KEY (id),
    KEY ix_server_metrics_server_id (server_id),
    KEY ix_server_metrics_server_ts (server_id, timestamp),
    KEY ix_server_metrics_timestamp (timestamp),
    CONSTRAINT fk_server_metrics_server FOREIGN KEY (server_id) REFERENCES server_devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='服务器资源指标表';

-- [C] 进程/服务状态时序
CREATE TABLE process_status (
    id             INT          NOT NULL AUTO_INCREMENT,
    server_id      VARCHAR(50)  NOT NULL COMMENT '服务器ID',
    timestamp      DATETIME     NOT NULL COMMENT '采集时间',
    process_name   VARCHAR(200) NOT NULL COMMENT '进程名称',
    pid            INT          NOT NULL DEFAULT 0 COMMENT '进程PID',
    is_running     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否运行中',
    cpu_percent    FLOAT        NOT NULL DEFAULT 0 COMMENT 'CPU占用(%)',
    memory_mb      FLOAT        NOT NULL DEFAULT 0 COMMENT '内存占用(MB)',
    memory_percent FLOAT        NOT NULL DEFAULT 0 COMMENT '内存占比(%)',
    port           INT          NOT NULL DEFAULT 0 COMMENT '监听端口',
    port_open      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '端口是否可达',
    is_critical    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否关键进程',
    status_detail  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '状态详情',
    PRIMARY KEY (id),
    KEY ix_process_status_server_id (server_id),
    KEY ix_process_status_server_ts (server_id, timestamp),
    KEY ix_process_status_critical_running (is_critical, is_running),
    CONSTRAINT fk_process_status_server FOREIGN KEY (server_id) REFERENCES server_devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='进程/服务状态表';

-- ---------- 业务数据 (仅 Spring Boot 写入) ----------

-- [B] 告警记录
CREATE TABLE alert_records (
    id              INT          NOT NULL AUTO_INCREMENT,
    alert_id        VARCHAR(100) NOT NULL COMMENT '告警唯一ID(md5(device:rule))',
    device_id       VARCHAR(50)  NOT NULL COMMENT '设备ID',
    device_name     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '设备名称',
    device_type     VARCHAR(20)  NOT NULL COMMENT '设备类型: switch/server',
    rule_name       VARCHAR(100) NOT NULL COMMENT '规则名称',
    severity        VARCHAR(20)  NOT NULL COMMENT '严重级别: INFO/WARNING/CRITICAL',
    title           VARCHAR(500) NOT NULL COMMENT '告警标题',
    description     TEXT         NULL COMMENT '告警描述',
    metric_value    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '触发指标值',
    threshold       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '告警阈值',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '状态: active/acknowledged/resolved',
    acknowledged_by VARCHAR(100) NOT NULL DEFAULT '' COMMENT '确认人',
    resolved_at     DATETIME     NULL COMMENT '恢复时间',
    resolved_by     VARCHAR(100) NOT NULL DEFAULT '' COMMENT '恢复操作人',
    notified        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已通知',
    notify_channels VARCHAR(200) NOT NULL DEFAULT '' COMMENT '通知渠道',
    triggered_at    DATETIME     NOT NULL COMMENT '触发时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_alert_id (alert_id),
    KEY ix_alert_records_device_id (device_id),
    KEY ix_alert_records_severity (severity),
    KEY ix_alert_records_status (status),
    KEY ix_alert_records_triggered_at (triggered_at),
    KEY ix_alert_records_status_triggered (status, triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='告警记录表';

-- [B] 指标动态基线
CREATE TABLE metric_baselines (
    id           INT         NOT NULL AUTO_INCREMENT,
    device_id    VARCHAR(50) NOT NULL COMMENT '设备ID',
    device_type  VARCHAR(20) NOT NULL DEFAULT 'switch' COMMENT '设备类型',
    metric       VARCHAR(30) NOT NULL COMMENT '指标名',
    mean         FLOAT       NOT NULL DEFAULT 0 COMMENT 'EWMA 均值(基线)',
    variance     FLOAT       NOT NULL DEFAULT 0 COMMENT 'EWMA 方差',
    sample_count INT         NOT NULL DEFAULT 0 COMMENT '累计样本数',
    latest_value   FLOAT       NOT NULL DEFAULT 0 COMMENT '最近一次取值 (避开 MySQL 8 保留字 LAST_VALUE)',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_baseline_device_metric (device_id, metric),
    KEY ix_metric_baselines_device_id (device_id),
    KEY ix_metric_baselines_metric (metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标动态基线表';

-- [B] 主动运维异常事件
CREATE TABLE anomaly_events (
    id              INT          NOT NULL AUTO_INCREMENT,
    device_id       VARCHAR(50)  NOT NULL COMMENT '设备ID',
    device_name     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '设备名称',
    device_type     VARCHAR(20)  NOT NULL DEFAULT 'switch' COMMENT '设备类型',
    metric          VARCHAR(30)  NOT NULL COMMENT '异常指标',
    severity        VARCHAR(20)  NOT NULL DEFAULT 'WARNING' COMMENT '级别: WARNING/CRITICAL',
    title           VARCHAR(300) NOT NULL DEFAULT '' COMMENT '标题',
    description     TEXT         NULL COMMENT '描述',
    value           FLOAT        NOT NULL DEFAULT 0 COMMENT '异常值',
    baseline        FLOAT        NOT NULL DEFAULT 0 COMMENT '基线值',
    deviation       FLOAT        NOT NULL DEFAULT 0 COMMENT '绝对偏离',
    score           FLOAT        NOT NULL DEFAULT 0 COMMENT 'z 分数',
    unit            VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '单位',
    status          VARCHAR(20)  NOT NULL DEFAULT 'open' COMMENT 'open/acknowledged/resolved',
    occurred_at     DATETIME     NOT NULL COMMENT '首次发生时间',
    last_seen_at    DATETIME     NOT NULL COMMENT '最近一次出现时间',
    resolved_at     DATETIME     NULL COMMENT '恢复时间',
    acknowledged_by VARCHAR(100) NOT NULL DEFAULT '' COMMENT '确认人',
    escalated       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已升级为告警',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY ix_anomaly_events_device_id (device_id),
    KEY ix_anomaly_events_severity (severity),
    KEY ix_anomaly_events_status (status),
    KEY ix_anomaly_events_occurred_at (occurred_at),
    KEY ix_anomaly_events_status_occurred (status, occurred_at),
    KEY ix_anomaly_events_device_metric_status (device_id, metric, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='主动运维异常事件表';

-- [B] 设备健康评分
CREATE TABLE device_health (
    device_id   VARCHAR(50)  NOT NULL COMMENT '设备ID',
    device_name VARCHAR(200) NOT NULL DEFAULT '' COMMENT '设备名称',
    device_type VARCHAR(20)  NOT NULL DEFAULT 'switch' COMMENT '设备类型',
    score       INT          NOT NULL DEFAULT 100 COMMENT '健康分 0-100',
    grade       VARCHAR(10)  NOT NULL DEFAULT '优' COMMENT '等级: 优/良/中/差',
    factors     JSON         NULL COMMENT '扣分因子明细',
    summary     VARCHAR(300) NOT NULL DEFAULT '' COMMENT '健康摘要',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (device_id),
    KEY ix_device_health_device_type (device_type),
    KEY ix_device_health_type_score (device_type, score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备健康评分表';

-- [B] 计划维护窗口
CREATE TABLE maintenance_windows (
    id          INT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL COMMENT '窗口名称',
    device_type VARCHAR(20)  NOT NULL DEFAULT 'all' COMMENT '作用范围: all/switch/server',
    device_ids  JSON         NULL COMMENT '指定设备ID列表(空=全部)',
    start_time  DATETIME     NOT NULL COMMENT '开始时间',
    end_time    DATETIME     NOT NULL COMMENT '结束时间',
    reason      TEXT         NULL COMMENT '维护原因',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_by  VARCHAR(100) NOT NULL DEFAULT 'admin' COMMENT '创建人',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY ix_maintenance_windows_enabled_time (enabled, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='计划维护窗口表';

-- [B] 点检计划
CREATE TABLE inspection_plans (
    id                INT          NOT NULL AUTO_INCREMENT,
    name              VARCHAR(200) NOT NULL COMMENT '计划名称',
    description       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '说明',
    device_type       VARCHAR(20)  NOT NULL DEFAULT 'switch' COMMENT '设备类型: switch/server',
    target_scope      VARCHAR(20)  NOT NULL DEFAULT 'all' COMMENT '范围: all/devices',
    target_ids        JSON         NULL COMMENT '指定设备ID列表',
    schedule_type     VARCHAR(20)  NOT NULL DEFAULT 'daily' COMMENT 'daily/weekly/monthly/cron',
    schedule_value    JSON         NULL COMMENT '周期参数(时刻/星期/日期)',
    cron_expr         VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'cron 表达式',
    assignee          VARCHAR(100) NOT NULL DEFAULT '' COMMENT '默认执行人',
    grace_hours       INT          NOT NULL DEFAULT 12 COMMENT '完成时限(小时)',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    last_generated_at DATETIME     NULL COMMENT '上次生成任务时间',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点检计划表';

-- [B] 点检计划项
CREATE TABLE inspection_items (
    id         INT          NOT NULL AUTO_INCREMENT,
    plan_id    INT          NOT NULL COMMENT '所属计划',
    name       VARCHAR(200) NOT NULL COMMENT '检查项名称',
    check_type VARCHAR(20)  NOT NULL DEFAULT 'bool' COMMENT 'bool/value/text',
    standard   VARCHAR(200) NOT NULL DEFAULT '' COMMENT '标准/参考值',
    unit       VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '单位',
    required   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否必填',
    sort_order INT          NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (id),
    KEY ix_inspection_items_plan_id (plan_id),
    CONSTRAINT fk_inspection_item_plan FOREIGN KEY (plan_id) REFERENCES inspection_plans (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点检计划项表';

-- [B] 点检任务
CREATE TABLE inspection_tasks (
    id             INT          NOT NULL AUTO_INCREMENT,
    plan_id        INT          NOT NULL COMMENT '计划ID',
    plan_name      VARCHAR(200) NOT NULL DEFAULT '' COMMENT '计划名称(冗余)',
    device_id      VARCHAR(50)  NOT NULL COMMENT '设备ID',
    device_name    VARCHAR(200) NOT NULL DEFAULT '' COMMENT '设备名称',
    device_type    VARCHAR(20)  NOT NULL DEFAULT 'switch' COMMENT '设备类型',
    planned_time   DATETIME     NOT NULL COMMENT '计划执行时间',
    due_time       DATETIME     NOT NULL COMMENT '完成截止时间',
    items          JSON         NULL COMMENT '点检项快照',
    status         VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/in_progress/completed/skipped/overdue',
    result         VARCHAR(20)  NOT NULL DEFAULT '' COMMENT 'normal/abnormal',
    abnormal_count INT          NOT NULL DEFAULT 0 COMMENT '异常项数',
    assignee       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '执行人',
    operator       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '实际执行人',
    remark         VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    started_at     DATETIME     NULL COMMENT '开始时间',
    finished_at    DATETIME     NULL COMMENT '完成时间',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_inspection_task (plan_id, device_id, planned_time),
    KEY ix_inspection_tasks_plan_id (plan_id),
    KEY ix_inspection_tasks_device_id (device_id),
    KEY ix_inspection_tasks_planned_time (planned_time),
    KEY ix_inspection_tasks_status (status),
    KEY ix_inspection_tasks_device_planned (device_id, planned_time),
    KEY ix_inspection_tasks_status_due (status, due_time),
    CONSTRAINT fk_inspection_task_plan FOREIGN KEY (plan_id) REFERENCES inspection_plans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点检任务表';

-- [B] 点检执行明细
CREATE TABLE inspection_records (
    id         INT          NOT NULL AUTO_INCREMENT,
    task_id    INT          NOT NULL COMMENT '任务ID',
    item_name  VARCHAR(200) NOT NULL COMMENT '检查项名称',
    check_type VARCHAR(20)  NOT NULL DEFAULT 'bool' COMMENT '类型',
    standard   VARCHAR(200) NOT NULL DEFAULT '' COMMENT '标准/参考值',
    value      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '实测值/描述',
    result     VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT 'normal/abnormal/na',
    remark     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY ix_inspection_records_task_id (task_id),
    CONSTRAINT fk_inspection_record_task FOREIGN KEY (task_id) REFERENCES inspection_tasks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点检执行明细表';

-- [B] 配置变更审计日志
CREATE TABLE config_audit_logs (
    id              INT          NOT NULL AUTO_INCREMENT,
    switch_id       VARCHAR(50)  NOT NULL COMMENT '交换机ID',
    switch_name     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '交换机名称',
    switch_ip       VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '交换机IP',
    operation_type  VARCHAR(50)  NOT NULL COMMENT '操作类型: vlan/port/system/raw',
    operation_desc  VARCHAR(500) NOT NULL COMMENT '操作描述',
    operator        VARCHAR(100) NOT NULL DEFAULT 'system' COMMENT '操作人',
    commands_sent   MEDIUMTEXT   NULL COMMENT '下发的命令',
    commands_output MEDIUMTEXT   NULL COMMENT '命令输出',
    parameters      JSON         NULL COMMENT '操作参数',
    status          VARCHAR(20)  NOT NULL COMMENT 'success/failed/timeout',
    error_message   TEXT         NULL COMMENT '错误信息',
    duration_ms     INT          NOT NULL DEFAULT 0 COMMENT '执行耗时(ms)',
    executed_at     DATETIME     NOT NULL COMMENT '执行时间',
    PRIMARY KEY (id),
    KEY ix_config_audit_switch_id (switch_id),
    KEY ix_config_audit_executed_at (executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配置审计日志表';

-- ---------- 三层边界表 (跨层通信) ----------

-- [B写/C读] IP 段发现范围与凭据模板 (取代 devices.yaml 的 switch_groups)
CREATE TABLE discovery_groups (
    id              INT          NOT NULL AUTO_INCREMENT,
    group_name      VARCHAR(100) NOT NULL COMMENT '分组名称',
    ip_range        VARCHAR(100) NOT NULL COMMENT 'IP范围: a.b.c.1-200 / CIDR',
    model           VARCHAR(100) NOT NULL DEFAULT '' COMMENT '设备型号',
    location_prefix VARCHAR(100) NOT NULL DEFAULT '' COMMENT '位置前缀',
    snmp_config     JSON         NULL COMMENT 'SNMP凭据模板',
    ssh_config      JSON         NULL COMMENT 'SSH凭据模板',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY ix_discovery_groups_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备发现范围表';

-- [B写/C读] 采集任务周期与开关
CREATE TABLE collector_jobs (
    id               INT         NOT NULL AUTO_INCREMENT,
    job_id           VARCHAR(50) NOT NULL COMMENT '任务ID',
    name             VARCHAR(100) NOT NULL DEFAULT '' COMMENT '任务名称',
    interval_seconds INT         NOT NULL DEFAULT 60 COMMENT '采集周期(秒)',
    timeout_seconds  INT         NOT NULL DEFAULT 120 COMMENT '单轮超时(秒)',
    max_instances    INT         NOT NULL DEFAULT 1 COMMENT '最大实例数',
    enabled          TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否启用',
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_collector_job_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='采集任务配置表';

-- [C写/B读] 采集层心跳
CREATE TABLE collector_heartbeats (
    id           INT         NOT NULL AUTO_INCREMENT,
    component    VARCHAR(30) NOT NULL DEFAULT 'collector' COMMENT '组件: collector/trap',
    phase        VARCHAR(30) NOT NULL DEFAULT '' COMMENT '阶段: 状态/流量/硬件/服务器/进程/发现',
    total        INT         NOT NULL DEFAULT 0 COMMENT '本轮目标设备数',
    success      INT         NOT NULL DEFAULT 0 COMMENT '成功数',
    errors       INT         NOT NULL DEFAULT 0 COMMENT '失败数',
    duration_ms  INT         NOT NULL DEFAULT 0 COMMENT '本轮耗时(ms)',
    last_beat_at DATETIME    NOT NULL COMMENT '心跳时间',
    detail       JSON        NULL COMMENT '附加统计',
    PRIMARY KEY (id),
    KEY ix_heartbeat_component_beat (component, last_beat_at),
    KEY ix_heartbeat_beat_at (last_beat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='采集层心跳表';

-- [B写/C读] 业务层下发给采集层的指令
CREATE TABLE collector_commands (
    id          INT          NOT NULL AUTO_INCREMENT,
    cmd         VARCHAR(50)  NOT NULL COMMENT '指令: rediscover/reload_config/collect_now',
    payload     JSON         NULL COMMENT '指令参数',
    status      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/running/done/failed',
    result      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '执行结果',
    created_by  VARCHAR(100) NOT NULL DEFAULT 'system' COMMENT '发起人',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    executed_at DATETIME     NULL COMMENT '执行时间',
    PRIMARY KEY (id),
    KEY ix_collector_command_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='采集指令表';

-- ---------- 权限与配置 ----------

CREATE TABLE sys_user (
    id            INT          NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(120) NOT NULL COMMENT 'BCrypt 口令散列',
    display_name  VARCHAR(100) NOT NULL DEFAULT '' COMMENT '显示名',
    role_code     VARCHAR(20)  NOT NULL DEFAULT 'VIEWER' COMMENT '主角色: ADMIN/OPERATOR/VIEWER',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    last_login_at DATETIME     NULL COMMENT '最后登录时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统用户表';

CREATE TABLE sys_role (
    id               INT         NOT NULL AUTO_INCREMENT,
    code             VARCHAR(20) NOT NULL COMMENT '角色编码',
    name             VARCHAR(50) NOT NULL DEFAULT '' COMMENT '角色名称',
    permissions_json JSON        NULL COMMENT '权限清单',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='系统角色表';

CREATE TABLE sys_user_role (
    user_id INT NOT NULL,
    role_id INT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户角色关联表';

CREATE TABLE sys_config (
    id            INT          NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(50)  NOT NULL COMMENT '配置键',
    config_value  JSON         NOT NULL COMMENT '配置值(JSON)',
    description   VARCHAR(200) NOT NULL DEFAULT '' COMMENT '说明',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务参数配置表';
