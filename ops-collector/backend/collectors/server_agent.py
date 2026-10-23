"""
服务器资源采集器
- 支持 Linux (SSH + psutil) 和 Windows (WMI) 混合环境
- 也可通过轻量 Agent HTTP 接口采集
- 采集 CPU、内存、磁盘、网络等指标
"""

import asyncio
import logging
import socket
import time
from datetime import datetime
from typing import Optional

import httpx
import paramiko
import psutil
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import config
from backend.database import async_session
from backend.models.device import ServerDevice
from backend.models.server_metrics import ServerMetrics
from backend.collectors.state import device_state, load_enabled_servers, write_heartbeat

logger = logging.getLogger(__name__)


class ServerCollector:
    """服务器资源采集器"""

    def __init__(self):
        self.monitor_config = config.server_monitor
        # 上次采集的网络/磁盘计数器 (用于计算速率)
        self._last_net: dict = {}
        self._last_disk: dict = {}
        # 连续失败退避: 不可达服务器冷却期内跳过采集, 避免每 30s 重复超时挂线程
        _fb = self.monitor_config.get("fail_backoff", {})
        self._fail_threshold = _fb.get("threshold", 3)
        self._fail_cooldown = _fb.get("cooldown", 300)
        self._fail_counts: dict[str, int] = {}
        self._skip_until: dict[str, float] = {}

    async def collect_via_agent(self, server_cfg: dict) -> Optional[dict]:
        """通过轻量 Agent HTTP 接口采集 (推荐方式)"""
        ip = server_cfg["ip"]
        port = server_cfg.get("monitoring", {}).get("agent_port", 9100)
        url = f"http://{ip}:{port}/metrics"

        try:
            async with httpx.AsyncClient(timeout=5) as client:
                resp = await client.get(url)
                resp.raise_for_status()
                return resp.json()
        except Exception as e:
            logger.warning(f"Agent 采集失败 [{ip}:{port}]: {e}")
            return None

    async def collect_via_ssh_linux(self, server_cfg: dict) -> Optional[dict]:
        """通过 SSH 远程执行 psutil 采集脚本 (Linux 服务器)"""
        ip = server_cfg["ip"]
        ssh_cfg = server_cfg.get("monitoring", {}).get("ssh", {})
        username = ssh_cfg.get("username", "root")
        password = ssh_cfg.get("password", "")
        port = ssh_cfg.get("port", 22)

        # 远程执行的 Python 采集脚本
        remote_script = r'''
import json, psutil, time, platform

cpu_percent = psutil.cpu_percent(interval=1)
cpu_per_core = psutil.cpu_percent(interval=0, percpu=True)
mem = psutil.virtual_memory()
disk = psutil.disk_usage("/")
disk_io = psutil.disk_io_counters()
net = psutil.net_io_counters()
boot_time = psutil.boot_time()

result = {
    "cpu_usage_percent": cpu_percent,
    "cpu_per_core": cpu_per_core,
    "cpu_freq_mhz": getattr(psutil.cpu_freq(), "current", 0) if psutil.cpu_freq() else 0,
    "memory_total_mb": mem.total // (1024*1024),
    "memory_used_mb": mem.used // (1024*1024),
    "memory_usage_percent": mem.percent,
    "memory_available_mb": mem.available // (1024*1024),
    "disk_total_gb": round(disk.total / (1024**3), 2),
    "disk_used_gb": round(disk.used / (1024**3), 2),
    "disk_usage_percent": disk.percent,
    "disk_read_bytes": disk_io.read_bytes if disk_io else 0,
    "disk_write_bytes": disk_io.write_bytes if disk_io else 0,
    "net_bytes_sent": net.bytes_sent if net else 0,
    "net_bytes_recv": net.bytes_recv if net else 0,
    "uptime_seconds": int(time.time() - boot_time),
    "load_avg": list(psutil.getloadavg()) if hasattr(psutil, "getloadavg") else [0,0,0],
    "process_count": len(psutil.pids()),
    "hostname": platform.node(),
    "os_version": platform.platform(),
}
print(json.dumps(result))
'''

        try:
            client = paramiko.SSHClient()
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

            # 在线程中执行 SSH 操作 (连接/横幅/认证均硬超时 10s, 防不可达主机悬挂)
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(
                None,
                lambda: client.connect(
                    ip, port=port, username=username, password=password,
                    timeout=10, banner_timeout=10, auth_timeout=10,
                )
            )

            _, stdout, stderr = await loop.run_in_executor(
                None,
                lambda: client.exec_command(f"python3 -c '{remote_script}'", timeout=30)
            )

            output = await loop.run_in_executor(None, stdout.read)
            client.close()

            import json
            data = json.loads(output.decode().strip())
            return data

        except Exception as e:
            logger.error(f"SSH 采集失败 [{ip}]: {e}")
            return None

    async def collect_via_wmi_windows(self, server_cfg: dict) -> Optional[dict]:
        """通过 WMI 远程采集 Windows 服务器 (需要在 Windows 环境运行)"""
        ip = server_cfg["ip"]
        ssh_cfg = server_cfg.get("monitoring", {}).get("ssh", {})
        username = ssh_cfg.get("username", "")
        password = ssh_cfg.get("password", "")

        try:
            import wmi as wmi_lib

            # WMI 远程连接
            loop = asyncio.get_event_loop()
            conn = await loop.run_in_executor(
                None,
                lambda: wmi_lib.WMI(
                    computer=ip,
                    user=username,
                    password=password,
                )
            )

            # 采集 CPU
            cpu_data = await loop.run_in_executor(
                None, lambda: conn.Win32_Processor()[0]
            )
            cpu_usage = float(cpu_data.LoadPercentage or 0)

            # 采集内存
            os_data = await loop.run_in_executor(
                None, lambda: conn.Win32_OperatingSystem()[0]
            )
            total_mem_kb = int(os_data.TotalVisibleMemorySize or 0)
            free_mem_kb = int(os_data.FreePhysicalMemory or 0)
            used_mem_kb = total_mem_kb - free_mem_kb
            mem_percent = round(used_mem_kb / total_mem_kb * 100, 1) if total_mem_kb > 0 else 0

            # 采集磁盘
            disk_data = await loop.run_in_executor(
                None, lambda: conn.Win32_LogicalDisk(DeviceID="C:")[0]
            )
            disk_total = int(disk_data.Size or 0) / (1024**3)
            disk_free = int(disk_data.FreeSpace or 0) / (1024**3)
            disk_used = disk_total - disk_free
            disk_percent = round(disk_used / disk_total * 100, 1) if disk_total > 0 else 0

            # 系统信息
            hostname = str(os_data.CSName or "")
            os_version = str(os_data.Caption or "")
            uptime_str = str(os_data.LastBootUpTime or "")

            return {
                "cpu_usage_percent": cpu_usage,
                "cpu_per_core": [],
                "cpu_freq_mhz": 0,
                "memory_total_mb": total_mem_kb // 1024,
                "memory_used_mb": used_mem_kb // 1024,
                "memory_usage_percent": mem_percent,
                "memory_available_mb": free_mem_kb // 1024,
                "disk_total_gb": round(disk_total, 2),
                "disk_used_gb": round(disk_used, 2),
                "disk_usage_percent": disk_percent,
                "disk_read_bytes": 0,
                "disk_write_bytes": 0,
                "net_bytes_sent": 0,
                "net_bytes_recv": 0,
                "uptime_seconds": 0,
                "load_avg": [0, 0, 0],
                "process_count": 0,
                "hostname": hostname,
                "os_version": os_version,
            }

        except Exception as e:
            logger.error(f"WMI 采集失败 [{ip}]: {e}")
            return None

    async def poll_server(self, server_cfg: dict) -> bool:
        """采集一台服务器 (带连续失败退避), 返回本轮采集通道是否可达"""
        ip = server_cfg["ip"]
        skip_until = self._skip_until.get(ip, 0)
        if time.time() < skip_until:
            logger.debug(f"服务器连续失败退避中, 跳过采集 [{ip}]")
            return True
        try:
            await self._poll_server_once(server_cfg)
        except Exception:
            pass
        # 以离线标记作为失败信号 (内部各采集方式失败均不抛出, 只写 is_online=False)
        entry = device_state.servers.get(server_cfg["id"], {})
        reached = bool(entry.get("is_online", True))
        if reached:
            self._fail_counts.pop(ip, None)
            self._skip_until.pop(ip, None)
        else:
            self._fail_counts[ip] = self._fail_counts.get(ip, 0) + 1
            if self._fail_counts[ip] >= self._fail_threshold:
                self._skip_until[ip] = time.time() + self._fail_cooldown
                logger.warning(
                    f"服务器连续 {self._fail_counts[ip]} 次采集失败, "
                    f"退避 {self._fail_cooldown}s 后再试 [{ip}]"
                )
        return reached

    async def _poll_server_once(self, server_cfg: dict):
        """采集一台服务器的数据 (单次执行)"""
        ip = server_cfg["ip"]
        server_id = server_cfg["id"]
        method = server_cfg.get("monitoring", {}).get("method", "agent")

        data = None
        try:
            if method == "agent":
                data = await self.collect_via_agent(server_cfg)
                # Agent 不可用时回退到 SSH/WMI
                if data is None:
                    os_type = server_cfg.get("os_type", "linux")
                    if os_type == "linux":
                        data = await self.collect_via_ssh_linux(server_cfg)
                    else:
                        data = await self.collect_via_wmi_windows(server_cfg)
            elif method == "ssh":
                data = await self.collect_via_ssh_linux(server_cfg)
            elif method == "wmi":
                data = await self.collect_via_wmi_windows(server_cfg)

            if data is None:
                logger.warning(f"所有采集方式均失败: {ip}")
                device_state.update_server(server_id, name=server_cfg.get("name", ""), ip=ip, is_online=False)
                async with async_session() as session:
                    await session.execute(
                        update(ServerDevice)
                        .where(ServerDevice.id == server_id)
                        .values(is_online=False, last_poll_time=datetime.now())
                    )
                    await session.commit()
                return

            # 计算网络/磁盘速率
            now = datetime.now()
            last_net = self._last_net.get(ip)
            last_disk = self._last_disk.get(ip)

            net_send_rate, net_recv_rate = 0.0, 0.0
            disk_read_bps, disk_write_bps = 0.0, 0.0

            if last_net:
                dt = (now - last_net["time"]).total_seconds()
                if dt > 0:
                    net_send_rate = (data.get("net_bytes_sent", 0) - last_net["sent"]) * 8 / dt
                    net_recv_rate = (data.get("net_bytes_recv", 0) - last_net["recv"]) * 8 / dt

            if last_disk:
                dt = (now - last_disk["time"]).total_seconds()
                if dt > 0:
                    disk_read_bps = (data.get("disk_read_bytes", 0) - last_disk["read"]) / dt
                    disk_write_bps = (data.get("disk_write_bytes", 0) - last_disk["write"]) / dt

            self._last_net[ip] = {
                "time": now,
                "sent": data.get("net_bytes_sent", 0),
                "recv": data.get("net_bytes_recv", 0),
            }
            self._last_disk[ip] = {
                "time": now,
                "read": data.get("disk_read_bytes", 0),
                "write": data.get("disk_write_bytes", 0),
            }

            # 写入数据库
            async with async_session() as session:
                # 更新服务器基础信息
                update_vals = {
                    "is_online": True,
                    "last_poll_time": now,
                    "uptime_seconds": data.get("uptime_seconds", 0),
                }
                if data.get("hostname"):
                    update_vals["hostname"] = data["hostname"]
                if data.get("os_version"):
                    update_vals["os_version"] = data["os_version"]
                if data.get("memory_total_mb"):
                    update_vals["total_memory_mb"] = data["memory_total_mb"]

                await session.execute(
                    update(ServerDevice)
                    .where(ServerDevice.id == server_id)
                    .values(**update_vals)
                )

                # 插入指标数据
                metrics = ServerMetrics(
                    server_id=server_id,
                    timestamp=now,
                    cpu_usage_percent=data.get("cpu_usage_percent", 0),
                    cpu_per_core=data.get("cpu_per_core", []),
                    cpu_freq_mhz=data.get("cpu_freq_mhz", 0),
                    memory_total_mb=data.get("memory_total_mb", 0),
                    memory_used_mb=data.get("memory_used_mb", 0),
                    memory_usage_percent=data.get("memory_usage_percent", 0),
                    memory_available_mb=data.get("memory_available_mb", 0),
                    disk_total_gb=data.get("disk_total_gb", 0),
                    disk_used_gb=data.get("disk_used_gb", 0),
                    disk_usage_percent=data.get("disk_usage_percent", 0),
                    disk_read_bps=round(disk_read_bps, 2),
                    disk_write_bps=round(disk_write_bps, 2),
                    net_bytes_sent=data.get("net_bytes_sent", 0),
                    net_bytes_recv=data.get("net_bytes_recv", 0),
                    net_send_rate_bps=round(net_send_rate, 2),
                    net_recv_rate_bps=round(net_recv_rate, 2),
                    uptime_seconds=data.get("uptime_seconds", 0),
                    load_avg=data.get("load_avg", [0, 0, 0]),
                    process_count=data.get("process_count", 0),
                )
                session.add(metrics)
                await session.commit()

            device_state.update_server(server_id, name=server_cfg.get("name", ""), ip=ip, is_online=True)
            logger.debug(f"服务器采集完成: {ip} - CPU {data.get('cpu_usage_percent', 0)}%")

        except Exception as e:
            logger.error(f"服务器采集异常 [{ip}]: {e}", exc_info=True)

    async def poll_all_servers(self, timeout: int = 120):
        """批量采集所有服务器 (设备清单来自 server_devices 表)"""
        started = time.monotonic()
        try:
            servers = await load_enabled_servers()
        except Exception as e:
            logger.error(f"读取服务器设备清单失败: {e}")
            return
        if not servers:
            logger.warning("没有已启用的服务器设备")
            return

        logger.info(f"开始批量服务器采集: {len(servers)} 台")
        # 处于连续失败退避中的设备本轮会被 poll_server 直接跳过 (不抛异常),
        # 先拍快照统计数量, 否则心跳会把"一个样本都没采到"报成全部成功
        skipped = sum(1 for s in servers if time.time() < self._skip_until.get(s["ip"], 0))
        try:
            results = await asyncio.wait_for(
                asyncio.gather(*[self.poll_server(srv) for srv in servers], return_exceptions=True),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            logger.error(f"服务器采集任务超时({timeout}s)，跳过本轮")
            await write_heartbeat(
                phase="服务器", total=len(servers), success=0, errors=len(servers),
                duration_seconds=time.monotonic() - started, detail={"reason": "timeout"},
            )
            return

        # 失败 = 抛异常 或 采集通道不可达; 有样本 = 总数 - 失败 - 退避跳过
        errors = sum(
            1 for r in results if isinstance(r, Exception) or r is False
        )
        collected = max(0, len(servers) - errors - skipped)
        duration = round(time.monotonic() - started, 1)
        summary = f"{collected} 台有样本, {errors} 失败, {skipped} 退避跳过"
        if collected == 0:
            logger.warning(f"服务器采集完成但无数据: {summary} (耗时 {duration}s)")
        elif errors or skipped:
            logger.warning(f"服务器采集完成: {summary} (耗时 {duration}s)")
        else:
            logger.info(f"服务器采集完成: 全部 {collected} 台有样本 (耗时 {duration}s)")
        detail = device_state.cycle_summary()
        detail.update({"collected": collected, "skipped": skipped})
        await write_heartbeat(
            phase="服务器", total=len(servers), success=collected,
            errors=errors, duration_seconds=duration,
            detail=detail,
        )
