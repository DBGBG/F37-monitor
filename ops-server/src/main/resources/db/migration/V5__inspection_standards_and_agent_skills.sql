-- ============================================================
-- V5: 点检标准库 + 智能体 Skill 清单
--
-- 两张新表均为 [B] (业务层 Java 写, 采集层 Python 不读不写), 与点检/告警链路解耦:
--   inspection_standards  标准库: 判定标准从"计划内嵌"抽出来单独维护, 计划里的点检项仍是快照
--   agent_skills          AI 运维代理的技能清单 (对应 ops-collector/AGENT.md 的能力矩阵),
--                         仅作为配置与说明载体, 业务层不会自动执行技能, 写操作仍需 OPERATOR 确认
--
-- 幂等: CREATE TABLE IF NOT EXISTS + INSERT IGNORE (依赖唯一键), 重复执行不产生脏数据。
-- ============================================================

-- [B] 点检标准库
CREATE TABLE IF NOT EXISTS inspection_standards (
    id            INT          NOT NULL AUTO_INCREMENT,
    category      VARCHAR(20)  NOT NULL DEFAULT 'general' COMMENT '适用对象: switch/server/general',
    name          VARCHAR(200) NOT NULL COMMENT '标准项名称',
    check_type    VARCHAR(20)  NOT NULL DEFAULT 'bool' COMMENT 'bool/value/text',
    standard      VARCHAR(200) NOT NULL DEFAULT '' COMMENT '判定标准/参考值',
    unit          VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '单位',
    method        VARCHAR(100) NOT NULL DEFAULT '' COMMENT '点检方法: 目视/手感/仪器/系统查询',
    check_cycle   VARCHAR(20)  NOT NULL DEFAULT 'daily' COMMENT '建议周期: daily/weekly/monthly/quarterly',
    level         VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT '重要度: key/normal/optional',
    handle_action VARCHAR(500) NOT NULL DEFAULT '' COMMENT '异常处置措施',
    source        VARCHAR(20)  NOT NULL DEFAULT 'manual' COMMENT '来源: preset(内置)/manual(自定义)',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    sort_order    INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_standard_cat_name (category, name),
    KEY ix_inspection_standards_category (category, enabled),
    KEY ix_inspection_standards_cycle (check_cycle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='点检标准库表';

-- [B] 智能体技能清单
CREATE TABLE IF NOT EXISTS agent_skills (
    id            INT          NOT NULL AUTO_INCREMENT,
    skill_key     VARCHAR(50)  NOT NULL COMMENT '技能标识, 智能体按此调用',
    name          VARCHAR(100) NOT NULL COMMENT '技能名称',
    category      VARCHAR(50)  NOT NULL DEFAULT '分析' COMMENT '分类: 采集诊断/告警分析/主动预防/点检辅助/配置自动化/报告汇总',
    description   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '技能说明',
    trigger_scene VARCHAR(300) NOT NULL DEFAULT '' COMMENT '触发场景 / 何时调用',
    steps         TEXT         NULL COMMENT '执行要点, 每行一条',
    entry_path    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '关联页面路径, 如 /inspection',
    risk_level    VARCHAR(20)  NOT NULL DEFAULT 'read' COMMENT 'read(只读)/suggest(给结论)/write(需人工确认后下发)',
    version       VARCHAR(20)  NOT NULL DEFAULT '1.0' COMMENT '技能版本',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_skill_key (skill_key),
    KEY ix_agent_skills_category (category, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能体技能清单表';

-- ---------- 种子: 点检标准 (取自 InspectionSchedulerService 内置模板, 补齐方法/周期/处置) ----------
INSERT IGNORE INTO inspection_standards
    (category, name, check_type, standard, unit, method, check_cycle, level, handle_action, source, enabled, sort_order) VALUES
    ('switch', '电源指示灯', 'bool', '绿灯常亮', '', '目视', 'daily', 'key', '检查供电回路与电源模块在位情况, 必要时切换备用电源并上报', 'preset', 1, 1),
    ('switch', '系统/告警指示灯', 'bool', '无红灯告警', '', '目视', 'daily', 'key', '登录设备查看告警日志, 按级别确认或上报', 'preset', 1, 2),
    ('switch', '风扇运转', 'bool', '运转正常无异响', '', '目视/耳听', 'daily', 'key', '确认风扇模块在位, 记录异响槽位并申请更换', 'preset', 1, 3),
    ('switch', '设备温度', 'value', '< 60', '°C', '红外测温仪/系统查询', 'daily', 'key', '检查机柜通风与空调, 清理进出风口, 持续偏高需降载', 'preset', 1, 4),
    ('switch', '端口指示灯', 'bool', '与业务一致', '', '目视', 'daily', 'normal', '对照端口表核查 link 状态, down 端口排查对端设备与跳线', 'preset', 1, 5),
    ('switch', '网线/光纤连接', 'bool', '连接牢固无松动', '', '手感/目视', 'weekly', 'normal', '重新插拔并补齐标签, 光纤操作需先断电', 'preset', 1, 6),
    ('switch', '设备清洁除尘', 'bool', '无积尘', '', '目视', 'monthly', 'optional', '使用防静电工具除尘, 严禁带电擦拭', 'preset', 1, 7),
    ('switch', '接地与安装固定', 'bool', '接地良好、固定牢靠', '', '目视/扳手', 'monthly', 'optional', '复紧接地线与导轨螺丝, 必要时测量接地电阻', 'preset', 1, 8),
    ('server', '电源/运行指示灯', 'bool', '正常', '', '目视', 'daily', 'key', '双电源逐一核查, 单电源故障立即报修', 'preset', 1, 1),
    ('server', '风扇/散热', 'bool', '运转正常', '', '耳听/目视', 'daily', 'key', '记录异响风扇编号, 申请热插拔更换', 'preset', 1, 2),
    ('server', '机房环境温度', 'value', '18~27', '°C', '温湿度计', 'daily', 'key', '核对空调运行与送风通道, 超范围先降负载再排查', 'preset', 1, 3),
    ('server', '硬盘告警指示灯', 'bool', '无红灯', '', '目视', 'daily', 'key', '立即备份数据, 按 RAID 手册更换冗余盘并建单', 'preset', 1, 4),
    ('server', '线缆连接', 'bool', '连接正常', '', '手感', 'weekly', 'normal', '重新插拔并整理走线, 补齐两端标签', 'preset', 1, 5),
    ('server', '设备清洁除尘', 'bool', '无积尘', '', '目视', 'monthly', 'optional', '机房专用设备清洁, 严禁在设备运行面使用湿布', 'preset', 1, 6),
    ('general', 'UPS 与供电状态', 'bool', '市电供电、无电池告警', '', '目视', 'daily', 'key', '切换旁路前通知业务方, 电池告警立即联系供电维护', 'preset', 1, 1),
    ('general', '机柜门锁与门禁记录', 'bool', '关闭上锁、无未授权进入', '', '目视/比对门禁记录', 'weekly', 'optional', '追溯进入记录并上报, 补齐门锁钥匙管理台账', 'preset', 1, 2),
    ('general', '标签与台账一致性', 'bool', '标签清晰、与台账一致', '', '目视/比对台账', 'monthly', 'normal', '现场补打标签并在系统内更正设备名称与位置', 'preset', 1, 3),
    ('general', '备件库存核对', 'text', '关键备件齐全', '', '清点', 'quarterly', 'optional', '列出缺口清单提交采购, 优先补齐风扇与电源模块', 'preset', 1, 4);

-- ---------- 种子: 智能体技能 (能力项与现有接口一一对应) ----------
INSERT IGNORE INTO agent_skills
    (skill_key, name, category, description, trigger_scene, steps, entry_path, risk_level, version, enabled) VALUES
    ('alert_root_cause', '告警根因分析', '告警分析',
     '结合告警记录与端口/硬件/流量样本, 判断告警是设备本体、链路还是采集侧引起',
     '出现 CRITICAL 告警, 或同一设备 30 分钟内重复告警',
     '取告警明细与规则阈值
拉同设备同时段的 port_status / hardware_health / port_traffic_stats
对照主动运维基线判断是否偏离长期水平
输出: 结论 + 影响范围 + 建议动作, 需要下发时提示人工确认',
     '/alerts', 'suggest', '1.0', 1),
    ('collector_link_check', '采集链路体检', '采集诊断',
     '核查采集层心跳、各阶段成功率与数据新鲜度, 定位断采原因',
     '顶栏显示"采集异常", 或图表出现数据空洞',
     '读 collector_heartbeats 最近各阶段一轮
比对 collector_jobs 周期配置与实到间隔
检查设备 enabled 与 is_online、最近 last_poll_time
输出: 是采集侧问题还是设备侧问题, 并给出下一步指令',
     '/system', 'read', '1.0', 1),
    ('device_health_trend', '设备健康趋势评估', '主动预防',
     '基于 EWMA 基线与健康评分, 提前识别劣化设备并给出预防性建议',
     '每日巡检汇总, 或某台设备评分连续下滑',
     '读 device_health 最新评分与等级分布
比对 metric_baselines 判断劣化速度
结合 hardware_health 寿命类指标
输出: 需优先干预的设备清单与建议处置窗口',
     '/proactive', 'suggest', '1.0', 1),
    ('inspection_report', '点检日报生成', '报告汇总',
     '汇总当日点检任务完成情况、异常项与超期任务, 生成可交班的日报文本',
     '每日交接班前, 或点检任务超期未处理',
     '按日期取 inspection_tasks 与 inspection_records
统计完成率、异常项数、超期未执行任务
列出异常项的标准值与实测值
输出: 日报正文 + 待跟进事项清单',
     '/inspection', 'read', '1.0', 1),
    ('port_fault_diag', '端口故障诊断', '采集诊断',
     '针对单个端口 down/错误计数上升, 判断是本端口、对端设备还是线缆',
     '端口状态告警, 或业务方反馈链路中断',
     '取该端口 admin/oper 状态与错误计数历史
查同交换机其他端口是否同时异常
对照流量样本判断是否长期无流量
输出: 疑似故障点 + 现场确认步骤',
     '/switches', 'suggest', '1.0', 1),
    ('vlan_config_draft', 'VLAN 配置草案', '配置自动化',
     '按需求生成 VLAN/端口配置命令草案供人工核对, 不直接下发',
     '新增业务接入或调整 VLAN 划分',
     '确认目标交换机型号与当前 VLAN 表
按模板生成候选命令与回滚命令
标注影响端口与断口时长
输出: 草案文本, 下发由人在自动化配置页确认',
     '/automation', 'write', '1.0', 1),
    ('maintenance_window_advice', '维护窗口建议', '主动预防',
     '根据告警分布与业务高峰规律, 建议抑制告警的维护窗口时段',
     '计划停机、批量升级或除尘作业前',
     '统计目标设备近 7 天告警时段分布
找出流量低谷窗口
给出窗口起止时间与涉及规则
输出: 建议在主动运维页手工创建窗口, 不代为生效',
     '/proactive', 'suggest', '1.0', 1),
    ('retention_review', '数据保留与容量巡检', '采集诊断',
     '核对时序表行数与保留策略, 提示库容量风险, 不代为删除',
     '系统健康页库体积上升, 或月度容量盘点',
     '读 maintenance/stats 四张时序表行数与跨度
比对 sys_config.retention_policy
列出超期未清理的数据量估计
输出: 是否需要清理 + 建议保留天数, 清理由 ADMIN 执行',
     '/system', 'read', '1.0', 1);
