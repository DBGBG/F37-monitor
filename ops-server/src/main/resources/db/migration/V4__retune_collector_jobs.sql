-- ============================================================
-- V4: 按实测定型采集周期 (在 V3 基础上再放宽)
--
-- 实测 (107 台交换机, 约 99 台 SNMP 可达, max_concurrent=8):
--   状态轮  ~215s   流量轮  ~268s   硬件轮  ~120s
-- 三类采集共用 phase_lock (交换机 SNMP 处理能力有限, 阶段间必须互斥),
-- V3 的 240/600/900 会让三类周期之和超过时间预算 (约 148%), 表现为
-- 任务被 max_instances=1 合并、实际间隔一路漂移。
-- 这里取 600/900/1800, 占用率约 73%, 留出突发余量:
--   端口状态 10 分钟一档 (前端实时标签), 流量 15 分钟一个点, 硬件 30 分钟一次。
-- 幂等: 只 UPDATE 已存在的 job_id。
-- ============================================================

UPDATE collector_jobs
SET interval_seconds = 600, timeout_seconds = 420
WHERE job_id = 'snmp_poll_status';

UPDATE collector_jobs
SET interval_seconds = 900, timeout_seconds = 420
WHERE job_id = 'snmp_poll_traffic';

UPDATE collector_jobs
SET interval_seconds = 1800, timeout_seconds = 420
WHERE job_id = 'snmp_poll_hardware';
