"""
主动运维系统 - 采集层启动脚本
用法: python run.py

三层架构下本脚本只启动 Python 采集守护进程 (不监听 HTTP 端口):
  采集层 -> MySQL <- 业务层 (Spring Boot, 默认 8080) -> 浏览器
业务层与前端分别由 scripts/start_all.py / supervisor.py 一起拉起。
"""

import os
import sys

# 将项目根目录加入 Python 路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backend.config import config
from backend.daemon import main as daemon_main


def main():
    c = config.mysql
    jobs = config.snmp.get("poll_intervals", {})
    print("""
    +===============================================+
    |   主动运维系统 - 数据采集层                   |
    |   Collector Daemon (SNMP / Server / Process)   |
    +===============================================+
      MySQL   : {host}:{port}/{db}
      采集周期: 状态 {st}s / 流量 {tr}s / 硬件 {hw}s
                服务器 {sv}s / 进程 {pc}s
      Trap    : {trap}
      提示    : 本层不提供 HTTP 接口, 页面访问请启动 Spring Boot 业务层
    +===============================================+
    """.format(
        host=c["host"], port=c["port"], db=c["database"],
        st=jobs.get("port_status", 120), tr=jobs.get("traffic_stats", 300),
        hw=jobs.get("hardware_health", 600),
        sv=config.server_monitor.get("poll_interval", 30),
        pc=config.process_monitor.get("poll_interval", 60),
        trap=("UDP 162 已启用" if config.snmp_trap.get("enabled") else "未启用"),
    ))

    return daemon_main()


if __name__ == "__main__":
    sys.exit(main() or 0)
