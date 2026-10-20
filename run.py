"""
MOXA 主动运维监控系统 - 启动脚本
用法: python run.py
"""

import sys
import os
import uvicorn

# 将项目根目录加入 Python 路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from backend.config import config


def main():
    host = config.system.get("host", "0.0.0.0")
    port = config.system.get("port", 8080)
    debug = config.system.get("debug", False)

    print(f"""
    ╔═══════════════════════════════════════════════════╗
    ║     MOXA 主动运维监控系统                          ║
    ║     Industrial Proactive Monitoring System         ║
    ╠═══════════════════════════════════════════════════╣
    ║     地址: http://{host}:{port}                    ║
    ║     API文档: http://{host}:{port}/docs            ║
    ║     模式: {'调试' if debug else '生产'}                              ║
    ╚═══════════════════════════════════════════════════╝
    """)

    uvicorn.run(
        "backend.main:app",
        host=host,
        port=port,
        reload=debug,
        log_level="info",
    )


if __name__ == "__main__":
    main()
