"""按顺序执行 Flyway 迁移 SQL (无 mysql 客户端、或想在业务层启动前先把表建好时使用)。

用法:
    python tools/apply_flyway_sql.py                       # 用 collector.yaml 的连接参数, 跑 V1 + V2
    python tools/apply_flyway_sql.py --db monitor --file ops-server/src/main/resources/db/migration/V1__baseline.sql
    python tools/apply_flyway_sql.py --create-database      # 目标库不存在时先建库 (utf8mb4_general_ci)
    python tools/apply_flyway_sql.py --recreate              # 危险: 先 DROP 库再重建 (仅开发环境)

与采集层/业务层保持同一套连接参数: 优先读环境变量 OPS_MYSQL_*, 其次 ops-collector/config/collector.yaml。
"""

import argparse
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pymysql  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COLLECTOR_YAML = os.path.join(ROOT, "ops-collector", "config", "collector.yaml")
MIGRATION_DIR = os.path.join(ROOT, "ops-server", "src", "main", "resources", "db", "migration")
DEFAULT_FILES = ["V1__baseline.sql", "V2__seed.sql"]


def load_mysql_settings():
    """环境变量优先, 其次 collector.yaml (与采集层口径完全一致)"""
    mysql = {}
    if os.path.exists(COLLECTOR_YAML):
        try:
            import yaml
            with io.open(COLLECTOR_YAML, encoding="utf-8") as f:
                mysql = (yaml.safe_load(f) or {}).get("mysql") or {}
        except Exception as exc:
            print("[WARN] 解析 collector.yaml 失败: %s" % exc)
    return {
        "host": os.environ.get("OPS_MYSQL_HOST") or mysql.get("host") or "127.0.0.1",
        "port": int(os.environ.get("OPS_MYSQL_PORT") or mysql.get("port") or 3306),
        "user": os.environ.get("OPS_MYSQL_USER") or mysql.get("user") or "root",
        "password": os.environ.get("OPS_MYSQL_PASSWORD") or str(mysql.get("password") or ""),
        "database": os.environ.get("OPS_MYSQL_DB") or mysql.get("database") or "monitor",
    }


def split_statements(sql):
    """按分号切分语句, 忽略字符串字面量与 -- / `` /* */ `` 注释中的分号"""
    statements = []
    buf = []
    i = 0
    n = len(sql)
    while i < n:
        c = sql[i]
        nxt = sql[i + 1] if i + 1 < n else ""
        if c == "-" and nxt == "-":
            while i < n and sql[i] != "\n":
                i += 1
            continue
        if c == "#":
            while i < n and sql[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n - 1 and not (sql[i] == "*" and sql[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if c in ("'", '"', "`"):
            quote = c
            buf.append(c)
            i += 1
            while i < n:
                if sql[i] == "\\":
                    buf.append(sql[i:i + 2])
                    i += 2
                    continue
                if sql[i] == quote:
                    if i + 1 < n and sql[i + 1] == quote:  # '' 转义
                        buf.append(quote * 2)
                        i += 2
                        continue
                    buf.append(quote)
                    i += 1
                    break
                buf.append(sql[i])
                i += 1
            continue
        if c == ";":
            text = "".join(buf).strip()
            if text:
                statements.append(re.sub(r"\s+", " ", text))
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        statements.append(re.sub(r"\s+", " ", tail))
    return statements


def ensure_database(args, cfg):
    conn = pymysql.connect(host=cfg["host"], port=cfg["port"], user=cfg["user"],
                           password=cfg["password"], charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            if args.recreate:
                print("DROP DATABASE %s ..." % cfg["database"])
                cur.execute("DROP DATABASE `%s`" % cfg["database"])
            if args.create_database or args.recreate:
                cur.execute(
                    "CREATE DATABASE IF NOT EXISTS `%s` "
                    "DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci" % cfg["database"])
                print("库 %s 已就绪 (utf8mb4 / utf8mb4_general_ci)" % cfg["database"])
            cur.execute("SHOW DATABASES LIKE %s", (cfg["database"],))
            if not cur.fetchall():
                raise SystemExit("[FAIL] 目标库 %s 不存在且账号无建库权限, 请给一个可用库名" % cfg["database"])
    finally:
        conn.close()


def apply_files(args, cfg):
    conn = pymysql.connect(host=cfg["host"], port=cfg["port"], user=cfg["user"],
                           password=cfg["password"], database=cfg["database"],
                           charset="utf8mb4", autocommit=not args.one_transaction)
    total = 0
    try:
        for path in args.resolved_files:
            with io.open(path, encoding="utf-8") as f:
                sql = f.read()
            statements = split_statements(sql)
            print("执行 %s: %d 条语句" % (os.path.basename(path), len(statements)))
            with conn.cursor() as cur:
                for idx, stmt in enumerate(statements, 1):
                    try:
                        cur.execute(stmt)
                    except Exception as exc:
                        print("[FAIL] %s 第 %d 条: %s" % (os.path.basename(path), idx, exc))
                        print("       SQL: %s" % stmt[:200])
                        raise
            total += len(statements)
        if args.one_transaction:
            conn.commit()
        print("全部执行完成, 共 %d 条语句" % total)
    finally:
        conn.close()


def verify(cfg):
    conn = pymysql.connect(host=cfg["host"], port=cfg["port"], user=cfg["user"],
                           password=cfg["password"], database=cfg["database"],
                           charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s",
                        (cfg["database"],))
            tables = cur.fetchone()[0]
            cur.execute("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                        "WHERE TABLE_SCHEMA=%s", (cfg["database"],))
            indexes = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                        "WHERE TABLE_SCHEMA=%s AND REFERENCED_TABLE_NAME IS NOT NULL", (cfg["database"],))
            fks = cur.fetchone()[0]
            print("表数量=%d 索引数=%d 外键数=%d" % (tables, indexes, fks))
            cur.execute("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s "
                        "ORDER BY TABLE_NAME", (cfg["database"],))
            names = [r[0] for r in cur.fetchall()]
            print("表清单: %s" % ", ".join(names))
            for table in ("sys_role", "sys_config", "collector_jobs", "discovery_groups", "server_devices"):
                if table in names:
                    with conn.cursor() as cur2:
                        cur2.execute("SELECT COUNT(*) FROM `%s`" % table)
                        print("  种子 %-18s %d 行" % (table, cur2.fetchone()[0]))
    finally:
        conn.close()
    return tables


def parse_args():
    ap = argparse.ArgumentParser(description="执行 Flyway 迁移 SQL 并校验结果")
    ap.add_argument("--host"); ap.add_argument("--port"); ap.add_argument("--user")
    ap.add_argument("--password"); ap.add_argument("--db")
    ap.add_argument("--file", action="append", default=[], help="指定要执行的 SQL 文件, 可重复")
    ap.add_argument("--create-database", action="store_true", help="执行前先 CREATE DATABASE IF NOT EXISTS")
    ap.add_argument("--recreate", action="store_true", help="执行前 DROP 目标库 (危险, 仅开发环境)")
    ap.add_argument("--one-transaction", action="store_true", help="全部语句在一个事务内提交")
    ap.add_argument("--skip-verify", action="store_true")
    args = ap.parse_args()

    cfg = load_mysql_settings()
    if args.host:
        cfg["host"] = args.host
    if args.port:
        cfg["port"] = int(args.port)
    if args.user:
        cfg["user"] = args.user
    if args.password:
        cfg["password"] = args.password
    if args.db:
        cfg["database"] = args.db

    files = args.file or [os.path.join(MIGRATION_DIR, name) for name in DEFAULT_FILES]
    resolved = []
    for path in files:
        full = path if os.path.isabs(path) else os.path.join(ROOT, path)
        if not os.path.exists(full):
            raise SystemExit("[FAIL] SQL 文件不存在: %s" % full)
        resolved.append(full)
    args.resolved_files = resolved
    return args, cfg


def main():
    args, cfg = parse_args()
    print("目标: %(user)s@%(host)s:%(port)s/%(database)s" % cfg)
    ensure_database(args, cfg)
    apply_files(args, cfg)
    if not args.skip_verify:
        verify(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
