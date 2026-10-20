"""
设备自动发现模块
- 扫描 IP 范围，通过 SNMP 探测真实存在的 MOXA 交换机
- 先 ping 快速筛选，再 SNMP 精确确认
- 只将真实响应的设备入库监控
"""

import asyncio
import logging
import subprocess
import time
from datetime import datetime
from typing import Optional

from puresnmp import Client, V2C, ObjectIdentifier

from backend.config import config, expand_ip_range

logger = logging.getLogger(__name__)

# SNMP OID
OID_SYS_DESCR = ObjectIdentifier("1.3.6.1.2.1.1.1.0")
OID_SYS_NAME  = ObjectIdentifier("1.3.6.1.2.1.1.5.0")
OID_SYS_UPTIME = ObjectIdentifier("1.3.6.1.2.1.1.3.0")
OID_SYS_LOCATION = ObjectIdentifier("1.3.6.1.2.1.1.6.0")


def _decode_octetstring(val) -> str:
    """解码 puresnmp OctetString 为普通字符串"""
    try:
        if hasattr(val, 'value'):
            raw = val.value
        elif isinstance(val, bytes):
            raw = val
        else:
            raw = str(val)

        if isinstance(raw, bytes):
            # 尝试 UTF-8 解码 (中文), 失败则 latin-1
            try:
                return raw.decode('utf-8')
            except UnicodeDecodeError:
                return raw.decode('latin-1')
        return str(raw)
    except Exception:
        return str(val)


class DeviceDiscovery:
    """设备自动发现器"""

    def __init__(self):
        self.snmp_config = config.snmp
        # 并发控制: ping 阶段可以高并发, SNMP 阶段控制更严
        self.ping_semaphore = asyncio.Semaphore(100)
        self.snmp_semaphore = asyncio.Semaphore(50)
        self._discovered: dict[str, dict] = {}  # ip -> device_info

    async def _ping_check(self, ip: str, timeout_ms: int = 800) -> bool:
        """快速 ICMP ping 检测"""
        async with self.ping_semaphore:
            proc = None
            try:
                proc = await asyncio.create_subprocess_exec(
                    "ping", "-n", "1", "-w", str(timeout_ms), ip,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                await asyncio.wait_for(proc.wait(), timeout=timeout_ms / 1000 + 2)
                return proc.returncode == 0
            except (asyncio.TimeoutError, Exception):
                # 超时后杀死僵尸进程
                if proc and proc.returncode is None:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                return False

    async def _snmp_probe(self, ip: str, community: str = "public") -> Optional[dict]:
        """SNMP 探测 - 获取设备基本信息"""
        async with self.snmp_semaphore:
            client = Client(ip, V2C(community))
            client.configure(timeout=3, retries=1)  # 快速探测: 3秒超时, 1次尝试

            try:
                # 先获取 sysDescr 确认可达
                descr_raw = await client.get(OID_SYS_DESCR)
                descr = _decode_octetstring(descr_raw)

                # 获取 sysName
                name_raw = await client.get(OID_SYS_NAME)
                name = _decode_octetstring(name_raw)

                # 获取 sysLocation
                try:
                    loc_raw = await client.get(OID_SYS_LOCATION)
                    location = _decode_octetstring(loc_raw)
                except Exception:
                    location = ""

                # 获取 uptime
                try:
                    uptime_raw = await client.get(OID_SYS_UPTIME)
                    # TimeTicks 为 puresnmp 包装对象, 真实值在 .value (单位: 1/100 秒)
                    raw = uptime_raw.value if hasattr(uptime_raw, "value") else uptime_raw
                    uptime = int(raw) // 100  # TimeTicks -> seconds
                except Exception:
                    uptime = 0

                # 判断是否为 MOXA 设备
                is_moxa = "moxa" in descr.lower() or "eds-" in descr.lower() or "pt-" in descr.lower()

                return {
                    "ip": ip,
                    "sys_descr": descr,
                    "sys_name": name,
                    "sys_location": location,
                    "uptime": uptime,
                    "is_moxa": is_moxa,
                    "community": community,
                    "discovered_at": datetime.now(),
                }

            except Exception:
                return None

    async def scan_range(self, ip_range: str, community: str = "public") -> list[dict]:
        """扫描一个 IP 范围，返回发现的 SNMP 设备列表"""
        ips = expand_ip_range(ip_range)
        logger.info(f"开始扫描 IP 范围: {ip_range} ({len(ips)} 个地址)")

        # Phase 1: 并发 ping 筛选
        ping_tasks = [self._ping_check(ip) for ip in ips]
        ping_results = await asyncio.gather(*ping_tasks)
        alive_ips = [ip for ip, alive in zip(ips, ping_results) if alive]
        logger.info(f"  Ping 存活: {len(alive_ips)}/{len(ips)}")

        # Phase 2: 对存活 IP 进行 SNMP 探测
        snmp_tasks = [self._snmp_probe(ip, community) for ip in alive_ips]
        snmp_results = await asyncio.gather(*snmp_tasks)

        devices = []
        for result in snmp_results:
            if result is not None:
                devices.append(result)

        logger.info(f"  SNMP 发现: {len(devices)} 台设备 (其中 MOXA: {sum(1 for d in devices if d['is_moxa'])})")
        return devices

    async def discover_all(self) -> list[dict]:
        """扫描所有配置的 switch_groups，发现真实设备"""
        all_devices = []

        for group in config.switch_groups_raw:
            ip_range = group.get("ip_range", "")
            if not ip_range:
                continue

            community = group.get("snmp", {}).get("community", "public")
            group_name = group.get("group_name", "未命名组")

            devices = await self.scan_range(ip_range, community)

            # 为发现的设备添加组信息
            for d in devices:
                d["group_name"] = group_name
                d["location_prefix"] = group.get("location_prefix", group_name)
                d["model"] = group.get("model", "")
                d["ssh_config"] = group.get("ssh", {})

            all_devices.extend(devices)

        # 合并到已发现缓存
        for d in all_devices:
            self._discovered[d["ip"]] = d

        logger.info(f"设备发现完成: 共 {len(all_devices)} 台真实交换机")
        return all_devices

    @property
    def discovered_devices(self) -> dict:
        return self._discovered
