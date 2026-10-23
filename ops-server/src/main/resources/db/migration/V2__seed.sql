-- ============================================================
-- V2__seed.sql - 主动运维系统 种子数据
-- 取值来源: ops-collector/config/settings.yaml 与 ops-collector/config/devices.yaml
-- 说明: sys_user 不在此写入, BCrypt 口令由 Java UserBootstrapInitializer 首启动时生成
--       (默认账号 admin/admin123, oper/oper123, viewer/viewer123)
-- ============================================================

-- ---------- 角色 ----------
INSERT INTO sys_role (code, name, permissions_json) VALUES
    ('ADMIN', '系统管理员', '["*"]'),
    ('OPERATOR', '运维工程师', '["device:read","device:write","alert:read","alert:ack","proactive:read","proactive:ack","inspection:read","inspection:execute","automation:exec","system:read"]'),
    ('VIEWER', '只读用户', '["device:read","alert:read","proactive:read","inspection:read","system:read"]');

-- ---------- 采集任务周期 (Python 每 5 分钟热加载) ----------
INSERT INTO collector_jobs (job_id, name, interval_seconds, timeout_seconds, max_instances, enabled) VALUES
    ('snmp_poll_status', '交换机端口状态采集', 120, 120, 1, 1),
    ('snmp_poll_traffic', '交换机流量采集', 300, 180, 1, 1),
    ('snmp_poll_hardware', '交换机硬件健康采集', 600, 300, 1, 1),
    ('server_poll_all', '服务器指标采集', 30, 120, 1, 1),
    ('process_monitor_all', '服务器进程监控', 60, 120, 1, 1),
    ('device_rediscovery', '设备发现与台账同步', 1800, 600, 1, 1);

-- ---------- 设备发现范围 (取代 devices.yaml 的 switch_groups) ----------
INSERT INTO discovery_groups (group_name, ip_range, model, location_prefix, snmp_config, ssh_config, enabled) VALUES
    ('A网段交换机', '10.51.200.1-10.51.200.200', 'MOXA', 'A网段',
     '{"version":"v2c","community":"public","port":161}',
     '{"username":"admin","password":"admin123","port":22}', 1),
    ('B网段交换机', '10.50.200.1-10.50.200.200', 'MOXA', 'B网段',
     '{"version":"v2c","community":"public","port":161}',
     '{"username":"admin","password":"admin123","port":22}', 1);

-- ---------- 服务器台账 (取自 devices.yaml servers) ----------
INSERT INTO server_devices (id, name, ip, os_type, location, monitoring_config, processes_config, enabled) VALUES
    ('srv-001', 'SCADA 数据采集服务器', '192.168.1.100', 'windows', '中控室-服务器机柜',
     '{"method":"agent","ssh":{"username":"","password":"","port":22},"agent_port":9100}',
     '[{"name":"SCADAService","port":8080,"critical":true},{"name":"sqlservr","port":1433,"critical":true}]', 1),
    ('srv-002', '历史数据服务器', '192.168.1.101', 'linux', '中控室-服务器机柜',
     '{"method":"ssh","ssh":{"username":"monitor","password":"monitor123","port":22},"agent_port":9100}',
     '[{"name":"mysqld","port":3306,"critical":true},{"name":"redis-server","port":6379,"critical":false}]', 1);

-- ---------- 业务参数 ----------
-- 告警规则 (8 条, 与 alert_engine.py 逐条对应)
INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('alert_rules',
     '{"switch_offline":{"severity":"CRITICAL","description":"交换机离线(SNMP 不可达)"},
       "switch_port_down":{"severity":"WARNING","description":"交换机端口 Down"},
       "switch_power_failure":{"severity":"CRITICAL","description":"电源故障"},
       "switch_cpu_high":{"threshold":90,"severity":"WARNING","description":"交换机 CPU 使用率过高"},
       "server_cpu_high":{"threshold":90,"severity":"WARNING","description":"服务器 CPU 使用率过高"},
       "server_memory_high":{"threshold":90,"severity":"WARNING","description":"服务器内存使用率过高"},
       "server_disk_high":{"threshold":90,"severity":"WARNING","description":"磁盘空间不足"},
       "server_process_down":{"severity":"CRITICAL","description":"关键进程异常退出"}}',
     '告警规则阈值与级别'),

    ('alert_settings',
     '{"enabled":true,"check_interval":30,"dedup_window":300}',
     '告警引擎运行参数'),

    ('proactive_params',
     '{"enabled":true,"analyze_interval":300,"ewma_alpha":0.15,"min_samples":20,"anomaly_k":4.0,
       "outlier_damping":0.1,"dedup_minutes":30,"escalate_severity":"CRITICAL","port_error_window_hours":2,
       "metrics":{
         "temperature":{"min_deviation":8.0,"direction":"up"},
         "cpu":{"min_deviation":20.0,"direction":"up"},
         "memory":{"min_deviation":15.0,"direction":"up"},
         "disk":{"min_deviation":10.0,"direction":"up"},
         "port_error":{"min_deviation":5.0,"direction":"up"}},
       "health_weights":{"online":30,"power":15,"temperature":15,"anomalies":20,"alerts":20}}',
     '主动运维分析参数(动态基线/异常检测/健康评分)'),

    ('notification_config',
     '{"email":{"enabled":false,"smtp_host":"smtp.example.com","smtp_port":465,"smtp_ssl":true,
        "username":"","password":"","sender":"monitor@example.com","recipients":["admin@example.com"]},
       "dingtalk":{"enabled":false,"webhook_url":"","secret":""}}',
     '告警通知渠道配置'),

    ('retention_policy',
     '{"metrics_days":30,"alerts_days":90,"anomalies_days":180,"audit_days":365,"inspection_days":0}',
     '数据保留策略(0=永久保留)'),

    ('inspection_defaults',
     '{"task_generation_interval":60,"default_grace_hours":12}',
     '点检默认参数'),

    ('ssh_defaults',
     '{"port":22,"timeout":30,"max_concurrent":5,"task_queue_size":100}',
     'SSH 配置下发默认参数'),

    ('vlan_plan',
     '[{"vlan_id":10,"name":"Management","description":"管理网络","subnet":"192.168.10.0/24"},
       {"vlan_id":20,"name":"Production","description":"生产网络","subnet":"192.168.20.0/24"},
       {"vlan_id":30,"name":"Monitoring","description":"监控网络","subnet":"192.168.30.0/24"},
       {"vlan_id":100,"name":"SCADA","description":"SCADA 专用网络","subnet":"192.168.100.0/24"}]',
     'VLAN 规划表(自动化配置批量下发用)');
