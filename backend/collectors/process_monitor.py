"""
自定义进程/服务监控
- 检测指定进程存活状态、CPU/内存占用
- 端口探活检测
- 支持 Linux (SSH) 和 Windows (WMI) 两种方式
"""

import asyncio
import json
import logging
import socket
from datetime import datetime
from typing import Optional

import paramiko
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import config
from backend.database import async_session
from backend.models.server_metrics import ProcessStatus

logger = logging.getLogger(__name__)


class ProcessMonitor:
    """进程/服务监控器"""

    def __init__(self):
        self.monitor_config = config.process_monitor
        self.default_processes = self.monitor_config.get("default_processes", [])

    def _check_port(self, host: str, port: int, timeout: float = 3.0) -> bool:
        """检测端口是否可达"""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            result = sock.connect_ex((host, port))
            sock.close()
            return result == 0
        except Exception:
            return False

    async def _check_port_async(self, host: str, port: int, timeout: float = 3.0) -> bool:
        """异步端口探活"""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._check_port, host, port, timeout)

    async def check_processes_linux(self, server_cfg: dict) -> list[dict]:
        """通过 SSH 检查 Linux 服务器上的进程"""
        ip = server_cfg["ip"]
        ssh_cfg = server_cfg.get("monitoring", {}).get("ssh", {})
        username = ssh_cfg.get("username", "root")
        password = ssh_cfg.get("password", "")
        port = ssh_cfg.get("port", 22)

        # 获取要监控的进程列表
        process_list = server_cfg.get("processes", self.default_processes)
        if not process_list:
            return []

        # 构建远程执行脚本
        process_names = [p["name"] for p in process_list]
        names_json = json.dumps(process_names)

        remote_script = f'''
import json, psutil, socket

target_names = {names_json}
results = []

for name in target_names:
    found = False
    for proc in psutil.process_iter(["pid", "name", "cpu_percent", "memory_info", "memory_percent", "status"]):
        try:
            if name.lower() in proc.info["name"].lower():
                mem_info = proc.info["memory_info"]
                mem_mb = mem_info.rss / (1024*1024) if mem_info else 0
                results.append({{
                    "process_name": name,
                    "pid": proc.info["pid"],
                    "is_running": proc.info["status"] != "zombie",
                    "cpu_percent": proc.info["cpu_percent"] or 0,
                    "memory_mb": round(mem_mb, 1),
                    "memory_percent": round(proc.info["memory_percent"] or 0, 2),
                    "status_detail": proc.info["status"],
                }})
                found = True
                break
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue

    if not found:
        results.append({{
            "process_name": name,
            "pid": 0,
            "is_running": False,
            "cpu_percent": 0,
            "memory_mb": 0,
            "memory_percent": 0,
            "status_detail": "not_found",
        }})

print(json.dumps(results))
'''

        try:
            client = paramiko.SSHClient()
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

            loop = asyncio.get_event_loop()
            await loop.run_in_executor(
                None,
                lambda: client.connect(ip, port=port, username=username, password=password, timeout=10)
            )

            _, stdout, _ = await loop.run_in_executor(
                None,
                lambda: client.exec_command(f"python3 -c '{remote_script}'", timeout=30)
            )

            output = await loop.run_in_executor(None, stdout.read)
            client.close()

            results = json.loads(output.decode().strip())

            # 端口探活
            for proc_info in process_list:
                proc_port = proc_info.get("port", 0)
                if proc_port > 0:
                    port_open = await self._check_port_async(ip, proc_port)
                    for r in results:
                        if r["process_name"] == proc_info["name"]:
                            r["port"] = proc_port
                            r["port_open"] = port_open
                            r["is_critical"] = proc_info.get("critical", False)

            return results

        except Exception as e:
            logger.error(f"Linux 进程检查失败 [{ip}]: {e}")
            # 回退为仅端口探活
            results = []
            for proc_info in process_list:
                proc_port = proc_info.get("port", 0)
                port_open = await self._check_port_async(ip, proc_port) if proc_port else False
                results.append({
                    "process_name": proc_info["name"],
                    "pid": 0,
                    "is_running": port_open,  # 端口可达则推断为运行中
                    "cpu_percent": 0,
                    "memory_mb": 0,
                    "memory_percent": 0,
                    "port": proc_port,
                    "port_open": port_open,
                    "is_critical": proc_info.get("critical", False),
                    "status_detail": f"ssh_failed_port_check: {'open' if port_open else 'closed'}",
                })
            return results

    async def check_processes_windows(self, server_cfg: dict) -> list[dict]:
        """通过 WMI 检查 Windows 服务器上的进程"""
        ip = server_cfg["ip"]
        process_list = server_cfg.get("processes", self.default_processes)
        if not process_list:
            return []

        results = []
        try:
            import wmi as wmi_lib
            ssh_cfg = server_cfg.get("monitoring", {}).get("ssh", {})

            loop = asyncio.get_event_loop()
            conn = await loop.run_in_executor(
                None,
                lambda: wmi_lib.WMI(
                    computer=ip,
                    user=ssh_cfg.get("username", ""),
                    password=ssh_cfg.get("password", ""),
                )
            )

            for proc_info in process_list:
                name = proc_info["name"]
                try:
                    procs = await loop.run_in_executor(
                        None,
                        lambda: conn.Win32_Process(Name=f"%{name}%")
                    )

                    if procs:
                        p = procs[0]
                        results.append({
                            "process_name": name,
                            "pid": int(p.ProcessId),
                            "is_running": True,
                            "cpu_percent": 0,  # WMI 获取 CPU 较复杂，简化处理
                            "memory_mb": int(p.WorkingSetSize or 0) / (1024 * 1024),
                            "memory_percent": 0,
                            "port": proc_info.get("port", 0),
                            "port_open": await self._check_port_async(ip, proc_info.get("port", 0)),
                            "is_critical": proc_info.get("critical", False),
                            "status_detail": "running",
                        })
                    else:
                        port_open = await self._check_port_async(ip, proc_info.get("port", 0))
                        results.append({
                            "process_name": name,
                            "pid": 0,
                            "is_running": False,
                            "cpu_percent": 0,
                            "memory_mb": 0,
                            "memory_percent": 0,
                            "port": proc_info.get("port", 0),
                            "port_open": port_open,
                            "is_critical": proc_info.get("critical", False),
                            "status_detail": "not_found",
                        })
                except Exception as e:
                    logger.warning(f"WMI 进程查询失败 [{ip}] {name}: {e}")
                    results.append({
                        "process_name": name,
                        "pid": 0,
                        "is_running": False,
                        "cpu_percent": 0,
                        "memory_mb": 0,
                        "memory_percent": 0,
                        "port": proc_info.get("port", 0),
                        "port_open": False,
                        "is_critical": proc_info.get("critical", False),
                        "status_detail": f"wmi_error: {str(e)}",
                    })

        except Exception as e:
            logger.error(f"Windows 进程检查失败 [{ip}]: {e}")
            # 回退为端口探活
            for proc_info in process_list:
                proc_port = proc_info.get("port", 0)
                port_open = await self._check_port_async(ip, proc_port) if proc_port else False
                results.append({
                    "process_name": proc_info["name"],
                    "pid": 0,
                    "is_running": port_open,
                    "cpu_percent": 0, "memory_mb": 0, "memory_percent": 0,
                    "port": proc_port, "port_open": port_open,
                    "is_critical": proc_info.get("critical", False),
                    "status_detail": f"wmi_failed_port_check: {'open' if port_open else 'closed'}",
                })

        return results

    async def poll_server_processes(self, server_cfg: dict):
        """采集一台服务器的进程状态"""
        ip = server_cfg["ip"]
        server_id = server_cfg["id"]
        os_type = server_cfg.get("os_type", "linux")

        try:
            if os_type == "linux":
                results = await self.check_processes_linux(server_cfg)
            else:
                results = await self.check_processes_windows(server_cfg)

            if not results:
                return

            # 写入数据库
            async with async_session() as session:
                now = datetime.now()
                for r in results:
                    status = ProcessStatus(
                        server_id=server_id,
                        timestamp=now,
                        process_name=r.get("process_name", ""),
                        pid=r.get("pid", 0),
                        is_running=r.get("is_running", False),
                        cpu_percent=r.get("cpu_percent", 0),
                        memory_mb=r.get("memory_mb", 0),
                        memory_percent=r.get("memory_percent", 0),
                        port=r.get("port", 0),
                        port_open=r.get("port_open", False),
                        is_critical=r.get("is_critical", False),
                        status_detail=r.get("status_detail", ""),
                    )
                    session.add(status)
                await session.commit()

            logger.debug(f"进程监控完成: {ip} - {len(results)} 个进程/服务")

        except Exception as e:
            logger.error(f"进程监控异常 [{ip}]: {e}", exc_info=True)

    async def poll_all_processes(self):
        """批量检查所有服务器的进程"""
        servers = config.get_enabled_servers()
        if not servers:
            return

        logger.info(f"开始批量进程监控: {len(servers)} 台服务器")
        tasks = [self.poll_server_processes(srv) for srv in servers]
        await asyncio.gather(*tasks, return_exceptions=True)
