"""
一键启动 (采集层 + 业务层) - 三层架构唯一启动入口
------------------------------------------------------------
用法:
    python scripts/start_all.py                 前台启动, Ctrl+C 停止两层
    pythonw scripts/start_all.py                后台启动 (开机计划任务用这个)
    python scripts/start_all.py --java=<java.exe>  指定业务层 JVM (计划任务账户没有 JAVA_HOME 时用)

做三件事:
    1. 预检 config/collector.yaml 是否可读, 打印 MySQL 目标与采集周期
    2. 预检 MySQL 端口是否可达 (给数据库留出启动窗口, 最多等 OPS_STARTUP_WAIT_SECONDS 秒)
    3. 交给 supervisor.run() 拉起并守护两个进程
       采集层  python ops-collector/run.py
       业务层  java -jar ops-server/target/moxa-server.jar
"""

import os
import socket
import sys
import time
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MONITOR_DIR = os.path.dirname(SCRIPT_DIR)
WORKSPACE_DIR = os.path.dirname(MONITOR_DIR)

sys.path.insert(0, SCRIPT_DIR)
sys.path.insert(0, MONITOR_DIR)

import supervisor  # noqa: E402  (同目录脚本, 提供 run() 与路径常量)

COLLECTOR_YAML = supervisor.COLLECTOR_YAML
WAIT_SECONDS = int(os.environ.get("OPS_STARTUP_WAIT_SECONDS") or 120)
PROBE_INTERVAL = 5


def _load_mysql_settings():
    """读取 collector.yaml 的 mysql 段; 环境变量优先, 与采集层口径一致"""
    mysql = {}
    try:
        import yaml
        with open(COLLECTOR_YAML, "r", encoding="utf-8") as f:
            cfg = yaml.safe_load(f) or {}
        mysql = cfg.get("mysql") or {}
        jobs = (cfg.get("snmp") or {}).get("poll_intervals") or {}
        print("  采集周期: 状态 {}s / 流量 {}s / 硬件 {}s".format(
            jobs.get("port_status", 120), jobs.get("traffic_stats", 300),
            jobs.get("hardware_health", 600)))
    except Exception as exc:
        print("  [WARN] 读取 config/collector.yaml 失败: %s" % exc)
    return {
        "host": os.environ.get("OPS_MYSQL_HOST") or mysql.get("host") or "127.0.0.1",
        "port": int(os.environ.get("OPS_MYSQL_PORT") or mysql.get("port") or 3306),
        "database": os.environ.get("OPS_MYSQL_DB") or mysql.get("database") or "monitor",
    }


def wait_for_mysql(host, port, timeout=WAIT_SECONDS):
    """TCP 探测 MySQL; 超时后只告警不退出 (数据库稍后起来时采集层会自动重连)"""
    deadline = time.time() + timeout
    attempt = 0
    while True:
        attempt += 1
        try:
            with socket.create_connection((host, port), timeout=3):
                print("  [OK] MySQL %s:%s 可达 (第 %d 次探测)" % (host, port, attempt))
                return True
        except OSError as exc:
            if time.time() >= deadline:
                print("  [WARN] MySQL %s:%s 仍不可达 (%s), 继续启动, 采集层会自行重试"
                      % (host, port, exc))
                return False
            print("  [WAIT] MySQL %s:%s 未就绪 (%s), %ds 后重试" % (host, port, exc, PROBE_INTERVAL))
            time.sleep(PROBE_INTERVAL)


def _apply_argv_env():
    """把命令行参数转成环境变量; 计划任务以 SYSTEM 运行时读不到用户态的 JAVA_HOME"""
    for arg in sys.argv[1:]:
        if arg.startswith("--java="):
            os.environ["OPS_JAVA"] = arg.split("=", 1)[1].strip('"')


def main():
    _apply_argv_env()
    print("=" * 60)
    print(" 主动运维系统 - 一键启动  %s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 60)
    print("  工作区   : %s" % WORKSPACE_DIR)
    print("  采集层   : %s" % os.path.join(MONITOR_DIR, "run.py"))
    print("  业务层   : %s" % os.path.join(supervisor.SERVER_DIR, "target", "moxa-server.jar"))
    print("  JVM      : %s" % (os.environ.get("OPS_JAVA") or "(JAVA_HOME / PATH 上的 java)"))

    mysql = _load_mysql_settings()
    print("  目标数据库: %s:%s/%s" % (mysql["host"], mysql["port"], mysql["database"]))
    wait_for_mysql(mysql["host"], mysql["port"])

    if supervisor.find_jar():
        print("  [OK] 业务层产物已就绪")
    else:
        print("  [NOTE] 未找到 ops-server 产物, 先只守护采集层; 构建命令:")
        print("         mvn -f ops-server\\pom.xml clean package")
        print("         (产物补齐后守护进程会在 60s 内自动纳入业务层)")
    print("  页面地址 : http://127.0.0.1:8080")
    print("  日志目录 : %s" % supervisor.LOG_DIR)
    print("-" * 60)

    supervisor.run()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n收到 Ctrl+C, 已退出")
        sys.exit(0)
