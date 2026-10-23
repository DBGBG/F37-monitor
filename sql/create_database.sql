-- ============================================================
-- 主动运维系统 (Proactive Operations & Maintenance System) - MySQL 建库与授权脚本
-- 执行方式: mysql -h <host> -u root -p < sql/create_database.sql
-- 说明: 表结构 DDL 与种子数据由 Flyway 在 Spring Boot 启动时自动创建/校验
--       (ops-server/src/main/resources/db/migration/V1__baseline.sql,
--        V2__seed.sql 种子, V3__tune_collector_jobs.sql 采集周期)
--       本脚本只负责建库与账号授权, 与以下两处配置保持一致:
--         ops-server/src/main/resources/application.yml
--         ops-collector/config/collector.yaml
-- ============================================================

CREATE DATABASE IF NOT EXISTS monitor
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

-- 应用账号 (Python 采集层与 Spring Boot 业务层共用同一账号, 生产环境请按需拆分)
-- 当前部署 (10.51.190.70) 复用已有账号 test/1234, 该账号已具备 monitor 库全部权限,
-- 如换机器需要重新授权, 取消下面注释即可; 改密码需同步 application.yml 与 collector.yaml。
-- CREATE USER IF NOT EXISTS 'test'@'%' IDENTIFIED BY '1234';
-- CREATE USER IF NOT EXISTS 'test'@'localhost' IDENTIFIED BY '1234';
--
-- GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
--     ON monitor.* TO 'test'@'%';
-- GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
--     ON monitor.* TO 'test'@'localhost';

-- Flyway 需要建表与查询权限, 上述 CREATE/ALTER 已覆盖
FLUSH PRIVILEGES;

-- 校验: 建库后应能看到 25 张业务表 (Flyway 执行 V1 之后)
SHOW TABLES FROM monitor;
