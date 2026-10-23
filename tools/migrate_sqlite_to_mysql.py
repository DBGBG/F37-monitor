#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
历史数据迁移: SQLite (data/ops_monitor.db) -> MySQL (monitor)

用途
----
三层架构改造把存储从 SQLite 换成 MySQL, 本脚本把旧库中的采集与业务历史数据原样搬到
新库, 保持主键、时间值与行内容不变, 使迁移前后的统计数字与图表完全一致。

依赖
----
    pip install pymysql

用法
----
    # 1) 只看行数对比, 不写库
    python tools/migrate_sqlite_to_mysql.py --dry-run

    # 2) 全量迁移 (目标表须已由 Flyway 建好)
    python tools/migrate_sqlite_to_mysql.py

    # 3) 只迁指定表, 并在写入前清空目标表
    python tools/migrate_sqlite_to_mysql.py --tables port_traffic_stats,hardware_health --truncate

    # 4) 迁完做行数校验
    python tools/migrate_sqlite_to_mysql.py --verify

连接参数
--------
与采集层保持同一真相: 优先读环境变量 OPS_MYSQL_*, 其次读
ops-collector/config/collector.yaml 的 mysql 段, 最后才是内置默认值:
    OPS_MYSQL_HOST / OPS_MYSQL_PORT / OPS_MYSQL_USER / OPS_MYSQL_PASSWORD / OPS_MYSQL_DB
"""
from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from typing import Any, Dict, List, Optional, Sequence, Tuple

try:
    import pymysql
except ImportError:  # --dry-run 不需要 pymysql, 真正写库前会再次校验
    pymysql = None  # type: ignore[assignment]

# SQLite 文件相对本脚本的位置
SQLITE_PATH_DEFAULT = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ops-collector", "data", "ops_monitor.db")
)

COLLECTOR_YAML = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ops-collector", "config", "collector.yaml")
)

# 迁移顺序: 父表在前, 子表在后 (外键依赖方向)
TABLE_ORDER: List[str] = [
    "switch_devices",
    "server_devices",
    "port_status",
    "port_traffic_stats",
    "hardware_health",
    "server_metrics",
    "process_status",
    "alert_records",
    "metric_baselines",
    "anomaly_events",
    "device_health",
    "maintenance_windows",
    "inspection_plans",
    "inspection_items",
    "inspection_tasks",
    "inspection_records",
    "config_audit_logs",
]

BATCH_SIZE = 1000

# 源列名 -> 目标列名: MySQL 8 里 LAST_VALUE 是保留字, 建表时把该列改名为 latest_value
COLUMN_RENAME: Dict[str, Dict[str, str]] = {
    "metric_baselines": {"last_value": "latest_value"},
}


def _yaml_mysql() -> Dict[str, Any]:
    """读 collector.yaml 的 mysql 段 (采集层与业务层的连接真相), 读不到则返回空"""
    try:
        import io
        import yaml
        with io.open(COLLECTOR_YAML, encoding="utf-8") as f:
            return (yaml.safe_load(f) or {}).get("mysql") or {}
    except Exception:
        return {}


def mysql_config() -> Dict[str, Any]:
    y = _yaml_mysql()
    return {
        "host": os.getenv("OPS_MYSQL_HOST") or y.get("host") or "127.0.0.1",
        "port": int(os.getenv("OPS_MYSQL_PORT") or y.get("port") or 3306),
        "user": os.getenv("OPS_MYSQL_USER") or y.get("user") or "root",
        "password": os.getenv("OPS_MYSQL_PASSWORD") or str(y.get("password") or ""),
        "database": os.getenv("OPS_MYSQL_DB") or y.get("database") or "monitor",
        "charset": "utf8mb4",
    }


def sqlite_columns(cur: sqlite3.Cursor, table: str) -> List[str]:
    cur.execute("PRAGMA table_info('%s')" % table)
    return [row[1] for row in cur.fetchall()]


def mysql_columns(cur: pymysql.cursors.Cursor, db: str, table: str) -> Dict[str, str]:
    """返回 {列名: 数据类型(小写)}, 目标表不存在时返回空字典"""
    cur.execute(
        "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s",
        (db, table),
    )
    return {r[0]: r[1].lower() for r in cur.fetchall()}


def mysql_table_exists(cur: pymysql.cursors.Cursor, db: str, table: str) -> bool:
    cur.execute(
        "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s LIMIT 1",
        (db, table),
    )
    return cur.fetchone() is not None


def normalize(value: Any, col_type: str) -> Any:
    """按目标列类型修正 SQLite 取出的值"""
    if value is None:
        return None
    if isinstance(value, bytes):
        return value
    if col_type in ("datetime", "timestamp"):
        s = str(value).strip()
        if not s:
            return None
        # SQLite 可能带微秒或 ISO 的 'T' 分隔符, MySQL DATETIME(0) 需截断
        s = s.replace("T", " ", 1) if "T" in s[:11] else s
        if "." in s:
            s = s.split(".", 1)[0]
        if len(s) == 19:
            return s
        return s[:19] if len(s) > 19 else s
    if col_type == "date":
        return str(value).strip()[:10]
    if col_type == "json":
        # 空串不是合法 JSON, 一律按 NULL 处理
        if isinstance(value, str) and not value.strip():
            return None
        return value
    if col_type in ("tinyint", "smallint", "int", "bigint", "mediumint"):
        if isinstance(value, bool):
            return int(value)
        if isinstance(value, str):
            s = value.strip()
            if not s:
                return None
            try:
                return int(float(s))
            except ValueError:
                return None
        if isinstance(value, float):
            return int(value)
        return value
    if col_type in ("float", "double", "decimal"):
        if isinstance(value, str):
            s = value.strip()
            if not s:
                return None
            try:
                return float(s)
            except ValueError:
                return None
        return value
    if isinstance(value, bool):
        return int(value)
    return value


def count_sqlite(scur: sqlite3.Cursor, table: str) -> int:
    scur.execute("SELECT COUNT(*) FROM '%s'" % table)
    return scur.fetchone()[0]


def count_mysql(mcur: pymysql.cursors.Cursor, table: str) -> int:
    mcur.execute("SELECT COUNT(*) FROM `%s`" % table)
    return mcur.fetchone()[0]


def migrate_table(
    sconn: sqlite3.Connection,
    mconn: pymysql.Connection,
    table: str,
    db_name: str,
    truncate: bool,
) -> Tuple[int, int]:
    """返回 (源行数, 写入行数)"""
    scur = sconn.cursor()
    mcur = mconn.cursor()

    if not mysql_table_exists(mcur, db_name, table):
        print("  %-22s 目标表不存在, 跳过 (请先启动 Spring Boot 让 Flyway 建表)" % table)
        return 0, 0

    dst_cols = mysql_columns(mcur, db_name, table)
    rename = COLUMN_RENAME.get(table, {})
    pairs: List[Tuple[str, str]] = []
    dropped: List[str] = []
    for c in sqlite_columns(scur, table):
        dst = rename.get(c, c)
        if dst in dst_cols:
            pairs.append((c, dst))
        else:
            dropped.append(c)
    common = [dst for _src, dst in pairs]
    if not common:
        print("  %-22s 无公共列, 跳过" % table)
        return 0, 0

    total = count_sqlite(scur, table)
    print("  %-22s 源 %8d 行, 迁移列 %d%s" % (
        table, total, len(common),
        (" (忽略列: %s)" % ",".join(dropped)) if dropped else ""))

    if total == 0:
        return total, 0

    if truncate:
        mcur.execute("TRUNCATE TABLE `%s`" % table)
        print("  %-22s 已清空目标表" % table)

    placeholders = ",".join(["%s"] * len(common))
    col_list = ",".join("`%s`" % c for c in common)
    sql = "INSERT INTO `%s` (%s) VALUES (%s)" % (table, col_list, placeholders)
    types = [dst_cols[c] for c in common]

    scur.execute("SELECT %s FROM `%s`" % (
        ",".join('"%s"' % src for src, _dst in pairs), table))

    written = 0
    batch: List[Sequence[Any]] = []
    while True:
        rows = scur.fetchmany(BATCH_SIZE)
        if not rows:
            break
        batch = [[normalize(v, t) for v, t in zip(row, types)] for row in rows]
        mcur.executemany(sql, batch)
        written += len(batch)
        mconn.commit()
        done = min(written, total)
        sys.stdout.write("\r  %-22s 已写入 %d/%d" % (table, done, total))
        sys.stdout.flush()
    print("")
    return total, written


def main() -> int:
    ap = argparse.ArgumentParser(description="SQLite -> MySQL 历史数据迁移")
    ap.add_argument("--sqlite", default=SQLITE_PATH_DEFAULT, help="SQLite 文件路径")
    ap.add_argument("--tables", default="", help="只迁移这些表, 逗号分隔")
    ap.add_argument("--truncate", action="store_true", help="写入前清空目标表")
    ap.add_argument("--dry-run", action="store_true", help="只统计源库行数, 不连 MySQL 不写库")
    ap.add_argument("--verify", action="store_true", help="迁移后逐表校验行数")
    args = ap.parse_args()

    if not os.path.exists(args.sqlite):
        sys.stderr.write("SQLite 文件不存在: %s\n" % args.sqlite)
        return 2

    selected = [t.strip() for t in args.tables.split(",") if t.strip()]
    unknown = [t for t in selected if t not in TABLE_ORDER]
    if unknown:
        sys.stderr.write("不支持的表名: %s\n可选: %s\n" % (",".join(unknown), ",".join(TABLE_ORDER)))
        return 2
    tables = selected or TABLE_ORDER

    cfg = mysql_config()
    print("源库: %s" % args.sqlite)
    print("目标: mysql://%s@%s:%s/%s" % (cfg["user"], cfg["host"], cfg["port"], cfg["database"]))
    print("模式: %s%s\n" % (
        "DRY-RUN " if args.dry_run else "",
        "TRUNCATE " if args.truncate else ""))

    sconn = sqlite3.connect(args.sqlite)
    scur = sconn.cursor()
    scur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    existing = {r[0] for r in scur.fetchall()}

    if args.dry_run:
        # 只读源库, 便于在尚未装好 MySQL 时先评估数据量
        print("\n汇总:")
        total = 0
        for table in tables:
            if table not in existing:
                print("  %-22s 源库无此表" % table)
                continue
            n = count_sqlite(scur, table)
            total += n
            print("  %-22s 源 %8d 行" % (table, n))
        print("  %-22s 源 %8d 行" % ("合计", total))
        sconn.close()
        return 0

    if pymysql is None:
        sys.stderr.write("缺少依赖 pymysql, 请执行: pip install pymysql\n")
        return 2

    mconn = pymysql.connect(**cfg)
    mcur = mconn.cursor()
    mcur.execute("SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION'")
    # 全量导入期间关闭外键与唯一性检查, 避免子表先于父表写入导致失败
    mcur.execute("SET FOREIGN_KEY_CHECKS=0")
    mcur.execute("SET SESSION unique_checks=0")

    summary: Dict[str, Tuple[int, int]] = {}
    try:
        for table in tables:
            if table not in existing:
                print("  %-22s 源库无此表, 跳过" % table)
                continue
            summary[table] = migrate_table(
                sconn, mconn, table, cfg["database"], args.truncate)

        mcur.execute("SET FOREIGN_KEY_CHECKS=1")
        mcur.execute("SET SESSION unique_checks=1")
        print("\n已恢复外键检查, 执行 ANALYZE TABLE 更新统计信息:")
        for table in summary:
            try:
                mcur.execute("ANALYZE TABLE `%s`" % table)
                mcur.fetchall()
                print("  %s" % table)
            except Exception as e:  # 单表统计失败不影响整体
                print("  %-22s ANALYZE 失败: %s" % (table, e))
    finally:
        sconn.close()
        mconn.commit()
        mconn.close()

    print("\n汇总:")
    src_total = dst_total = 0
    for table, (src, written) in summary.items():
        src_total += src
        dst_total += written
        print("  %-22s 源 %8d 行, 写入 %8d 行" % (table, src, written))
    print("  %-22s 源 %8d 行, 写入 %8d 行" % ("合计", src_total, dst_total))

    if args.verify:
        print("\n行数校验 (源 vs 目标):")
        sconn2 = sqlite3.connect(args.sqlite)
        mconn2 = pymysql.connect(**cfg)
        scur2 = sconn2.cursor()
        mcur2 = mconn2.cursor()
        bad = 0
        for table in summary:
            src = count_sqlite(scur2, table)
            dst = count_mysql(mcur2, table)
            flag = "OK" if dst >= src else "差异!"
            if dst < src:
                bad += 1
            print("  %-22s 源 %8d / 目标 %8d  %s" % (table, src, dst, flag))
        sconn2.close()
        mconn2.close()
        if bad:
            print("有 %d 张表目标行数少于源表, 请检查上面的告警" % bad)
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
