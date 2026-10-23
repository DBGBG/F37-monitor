-- ============================================================
-- V3: 按实测 SNMP 吞吐调整采集作业周期
--
-- 背景: 现场 107 台交换机 (RKS-G4000/MOXA NOS 等) 的 bulkwalk 在 32 路并发下
--       几乎全部 2s 超时 (实测 64 台并发只有 2 台取到端口), 表现为
--       "作业报成功但时序表没有新行". 采集层已把 max_concurrent 降到 8,
--       单轮完整采集约需 130~160s, 因此把周期与超时同步放宽, 避免
--       上一轮没跑完就被 max_instances=1 挤掉.
-- 幂等: 只 UPDATE 已存在的 job_id, 不改表结构, 可重复执行.
-- ============================================================

UPDATE collector_jobs
SET interval_seconds = 240, timeout_seconds = 300
WHERE job_id = 'snmp_poll_status';

UPDATE collector_jobs
SET interval_seconds = 600, timeout_seconds = 420
WHERE job_id = 'snmp_poll_traffic';

UPDATE collector_jobs
SET interval_seconds = 900, timeout_seconds = 420
WHERE job_id = 'snmp_poll_hardware';
