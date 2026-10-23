"""
SNMP 交换机状态采集器
- 使用 puresnmp 异步接口批量采集交换机状态 (通用 MIB + MOXA 私有 MIB)
- 支持端口状态、流量统计、硬件健康（温度/告警）
- 通过 asyncio.Semaphore 控制并发数，避免网络拥塞
- 自动适配 PT 系列 / EDS 系列 MOXA 设备
"""

import asyncio
import logging
import random
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from typing import Optional

from puresnmp import Client, V2C, ObjectIdentifier
from sqlalchemy import select, update, delete
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import config
from backend.database import async_session
from backend.models.device import SwitchDevice
from backend.models.port_status import PortStatus, PortTrafficStats
from backend.models.hardware_health import HardwareHealth
from backend.collectors.state import device_state, write_heartbeat

logger = logging.getLogger(__name__)

# ---- 标准 IF-MIB ----
OID_IF_NAME       = "1.3.6.1.2.1.31.1.1.1.1"    # 端口名称 (ifName)
OID_IF_SPEED      = "1.3.6.1.2.1.2.2.1.5"        # 端口速率 (ifSpeed, bps)
OID_IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"    # 高速率 (ifHighSpeed, Mbps)
OID_IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7"      # 管理状态
OID_IF_OPER_STATUS  = "1.3.6.1.2.1.2.2.1.8"      # 运行状态
OID_IF_IN_OCTETS    = "1.3.6.1.2.1.2.2.1.10"
OID_IF_IN_UCAST     = "1.3.6.1.2.1.2.2.1.11"
OID_IF_IN_DISCARDS  = "1.3.6.1.2.1.2.2.1.13"
OID_IF_IN_ERRORS    = "1.3.6.1.2.1.2.2.1.14"
OID_IF_OUT_OCTETS   = "1.3.6.1.2.1.2.2.1.16"
OID_IF_OUT_UCAST    = "1.3.6.1.2.1.2.2.1.17"
OID_IF_OUT_DISCARDS = "1.3.6.1.2.1.2.2.1.19"
OID_IF_OUT_ERRORS   = "1.3.6.1.2.1.2.2.1.20"
OID_IF_HC_IN_OCTETS  = "1.3.6.1.2.1.31.1.1.1.6"
OID_IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10"

# ---- 系统信息 (标准 MIB-II) ----
OID_SYS_NAME      = "1.3.6.1.2.1.1.5.0"
OID_SYS_DESCR     = "1.3.6.1.2.1.1.1.0"
OID_SYS_UPTIME    = "1.3.6.1.2.1.1.3.0"
OID_SYS_LOCATION  = "1.3.6.1.2.1.1.6.0"
OID_SYS_OBJECT_ID = "1.3.6.1.2.1.1.2.0"

# ---- MOXA PT 系列私有 OID ----
OID_MOXA_PT_MODEL     = "1.3.6.1.4.1.8691.7.15.1.2.0"    # 型号名
OID_MOXA_PT_FIRMWARE  = "1.3.6.1.4.1.8691.7.15.1.4.0"    # 固件版本
OID_MOXA_PT_SLOTS     = "1.3.6.1.4.1.8691.7.15.1.5.0"    # 插槽数
OID_MOXA_PT_TEMP      = "1.3.6.1.4.1.8691.7.15.1.11.1.6.0"  # 设备温度 (°C)
# 告警状态 (PT 系列): .11.2.1 ~ .11.2.7
OID_MOXA_PT_ALARM_BASE = "1.3.6.1.4.1.8691.7.15.1.11.2"  # 告警子树
# 电源状态 (PT 系列): .10.1.0 和 .10.2.0
OID_MOXA_PT_POWER1    = "1.3.6.1.4.1.8691.7.15.1.10.1.0"
OID_MOXA_PT_POWER2    = "1.3.6.1.4.1.8691.7.15.1.10.2.0"

# ---- MOXA 交换机私有 MIB 统一前缀 (各型号使用 8691.7.<型号号> 作为产品根, 内部布局一致) ----
MOXA_SWITCH_OID_PREFIX = "1.3.6.1.4.1.8691.7."
# 相对产品根的通用子 OID
REL_MODEL    = ".1.2.0"      # 型号名
REL_FIRMWARE = ".1.4.0"      # 固件版本
REL_SLOTS    = ".1.5.0"      # 插槽/端口模组数
REL_POWER1   = ".1.10.1.0"   # 电源1状态
REL_POWER2   = ".1.10.2.0"   # 电源2状态
REL_TEMP     = ".1.11.1.6.0" # 设备温度 (°C)

# ---- MOXA EDS 系列私有 OID (部分型号可能支持) ----
OID_MOXA_EDS_POWER1_STATUS  = "1.3.6.1.4.1.8691.2.6.1.2.1.0"
OID_MOXA_EDS_POWER2_STATUS  = "1.3.6.1.4.1.8691.2.6.1.2.2.0"
OID_MOXA_EDS_POWER1_VOLTAGE = "1.3.6.1.4.1.8691.2.6.1.2.3.0"
OID_MOXA_EDS_POWER2_VOLTAGE = "1.3.6.1.4.1.8691.2.6.1.2.4.0"
OID_MOXA_EDS_TEMPERATURE    = "1.3.6.1.4.1.8691.2.6.1.3.1.0"
OID_MOXA_EDS_TEMP_ALARM     = "1.3.6.1.4.1.8691.2.6.1.3.2.0"
OID_MOXA_EDS_FAN_STATUS     = "1.3.6.1.4.1.8691.2.6.1.4.1.0"

# ---- MOXA 下一代 OS (NOS v4.x) 系列 (RKS-G4000 / EDS-4000 / EDS-G4000) 私有 MIB ----
# sysObjectID 形如 1.3.6.1.4.1.8691.600.1.6.4 (RKS-G4028-4GS-2HV-T)
MOXA_NOS_OID_PREFIX = "1.3.6.1.4.1.8691.600."
# 产品信息 (位于 8691.600.2.2.*)
OID_MOXA_NOS_PORTS    = "1.3.6.1.4.1.8691.600.2.2.1.0"   # 端口总数
OID_MOXA_NOS_SERIAL   = "1.3.6.1.4.1.8691.600.2.2.3.0"   # 序列号
OID_MOXA_NOS_MODEL    = "1.3.6.1.4.1.8691.600.2.2.4.0"   # 型号名
# NOS 公共 MIB (位于 8691.602.*)
OID_MOXA_NOS_FIRMWARE = "1.3.6.1.4.1.8691.602.1.1.2.2.1.0"  # 固件版本
OID_MOXA_NOS_TEMP     = "1.3.6.1.4.1.8691.602.1.3.2.3.0"    # 设备温度 (°C)


def _run_device_work(ip: str, community: str, client_cfg: dict, work):
    """在工作线程的单事件循环内, 复用同一个 Client 完成一台设备的全部 SNMP 操作

    旧实现每发一个请求就新建 线程+事件循环+UDP socket, 一轮状态采集 (5 WALK
    + 2 multiget) 就要创建 700+ 个, 是本机 CPU 持续满载的主因。改为每设备一个
    会话, 线程/循环/socket 创建量下降约 7 倍。
    设备侧串行执行 (工业交换机的 SNMP 处理能力有限, 设各并行反而拖慢双方)。
    """
    async def main():
        client = Client(ip, V2C(community), port=client_cfg["port"])
        client.configure(timeout=client_cfg["timeout"], retries=client_cfg["retries"])
        return await work(client)

    return asyncio.run(main())


async def _walk_to_dict(client, base_oid: str, bulk_size: int) -> dict:
    """在给定 client 所属事件循环内 bulkwalk 一个 OID 子树"""
    result = {}
    try:
        async for binding in client.bulkwalk(
            [ObjectIdentifier(base_oid)], bulk_size=bulk_size
        ):
            oid_str = str(binding.oid)
            if not oid_str.startswith(base_oid):
                break
            result[oid_str] = binding.value
    except Exception as e:
        logger.debug(f"SNMP WALK 结束 OID={base_oid}: {e}")
    return result


async def _multiget(client, oids: list[str]) -> dict:
    """单次 PDU 批量获取多个 OID (multiget; 失败时回退逐条 GET)"""
    if not oids:
        return {}
    try:
        values = await client.multiget([ObjectIdentifier(o) for o in oids])
        return {oid: val for oid, val in zip(oids, values)}
    except Exception:
        result = {}
        for oid_str in oids:
            try:
                result[oid_str] = await client.get(ObjectIdentifier(oid_str))
            except Exception:
                pass
        return result


def _oid_suffix(oid: str, base: str) -> str:
    """提取 OID 相对于 base 的后缀"""
    if oid.startswith(base + "."):
        return oid[len(base) + 1:]
    return oid.split(".")[-1]


def _unwrap(val):
    """解包 puresnmp 类型 (Integer/TimeTicks/Gauge/Counter 等为包装对象, 真实值在 .value)"""
    return val.value if hasattr(val, "value") else val


def _moxa_product_base(sys_oid_val) -> str:
    """从 sysObjectID 推导 MOXA 交换机的产品 OID 根 (如 1.3.6.1.4.1.8691.7.15);
    非 MOXA 交换机 (或非 8691.7.x 系列) 返回空串。
    各型号仅产品根不同 (PT-7828=15, IKS-6728A=117, EDS-408A=7 ...), 内部子 OID 布局一致。"""
    s = _safe_str(sys_oid_val).strip().rstrip(".")
    if s.startswith(MOXA_SWITCH_OID_PREFIX):
        return s
    return ""


def _is_moxa_nos(sys_oid_val) -> bool:
    """判断是否为 MOXA 下一代 OS (NOS v4.x) 系列交换机 (RKS-G4000 / EDS-4000/G4000),
    sysObjectID 以 1.3.6.1.4.1.8691.600 开头。"""
    return _safe_str(sys_oid_val).strip().rstrip(".").startswith(MOXA_NOS_OID_PREFIX)


def _safe_int(val, default=0) -> int:
    """安全转换为整数 (自动解包 puresnmp 包装类型)"""
    try:
        return int(_unwrap(val))
    except (ValueError, TypeError):
        try:
            return int(str(_unwrap(val)).strip())
        except (ValueError, TypeError):
            return default


def _safe_float(val, default=0.0) -> float:
    """安全转换为浮点数 (自动解包 puresnmp 包装类型)"""
    try:
        return float(_unwrap(val))
    except (ValueError, TypeError):
        try:
            return float(str(_unwrap(val)).strip())
        except (ValueError, TypeError):
            return default


def _safe_str(val, default="") -> str:
    """安全转换为字符串 (支持 puresnmp OctetString)"""
    try:
        # puresnmp OctetString 对象
        if hasattr(val, 'value'):
            raw = val.value
        elif isinstance(val, bytes):
            raw = val
        else:
            return str(val)

        if isinstance(raw, bytes):
            try:
                return raw.decode('utf-8')
            except UnicodeDecodeError:
                return raw.decode('latin-1')
        return str(raw)
    except Exception:
        return default


class SNMPCollector:
    """SNMP 交换机数据采集器"""

    def __init__(self):
        self.snmp_config = config.snmp
        # SNMP 网络调用放到独立线程池执行 (每线程自带事件循环), 避免阻塞主事件循环
        self.executor = ThreadPoolExecutor(
            max_workers=self.snmp_config.get("max_concurrent", 64),
            thread_name_prefix="snmp",
        )
        # 设备级并发上限 (宽松): 仅用于避免同时构造过多客户端, 不构成瓶颈
        self.device_semaphore = asyncio.Semaphore(
            max(self.snmp_config.get("max_concurrent", 64), 128)
        )
        self.db_lock = asyncio.Lock()  # DB 写入串行锁
        # 三类采集阶段互斥: 交换机的 SNMP 处理能力有限, 各阶段并发会相互拖慢
        self.phase_lock = asyncio.Lock()
        self._last_counters: dict = {}
        # 离线退避: ip -> 下次允许轻量探活的时刻 (秒, time.monotonic), 用于采集节流
        self._next_probe_at: dict[str, float] = {}

    def _community(self, switch_cfg: dict) -> str:
        return switch_cfg.get("snmp", {}).get(
            "community", self.snmp_config.get("community", "public")
        )

    def _timeout(self) -> int:
        return self.snmp_config.get("timeout", 5)

    def _client_cfg(self) -> dict:
        """工作线程内构造 Client 所需的参数 (超时/重试/端口)"""
        return {
            "port": self.snmp_config.get("port", 161),
            "timeout": self._timeout(),
            "retries": self.snmp_config.get("retries", 2),
        }

    async def _device_session(self, ip: str, community: str, work, client_cfg: dict | None = None):
        """在工作线程(独立事件循环 + 复用单一 Client)中执行该设备的全部 SNMP 操作

        work: async def work(client) -> result
        client_cfg: 缺省取全局超时/重试; 探活等场景可传入更激进的参数
        """
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self.executor, _run_device_work, ip, community,
            client_cfg or self._client_cfg(), work
        )

    async def collect_port_status(self, ip: str, community: str) -> list[dict]:
        """采集交换机端口状态 (只采集物理端口，通过 ifName 过滤)"""
        async with self.device_semaphore:
            logger.debug(f"采集端口状态: {ip}")

            # 单会话内串行采集: 端口运行状态 + 名称 + 管理状态 + 速率
            bases = [
                OID_IF_OPER_STATUS, OID_IF_NAME, OID_IF_ADMIN_STATUS,
                OID_IF_SPEED, OID_IF_HIGH_SPEED,
            ]
            bulk_size = self.snmp_config.get("bulk_size", 50)

            async def work(client):
                return [await _walk_to_dict(client, b, bulk_size) for b in bases]

            oper_data, name_data, admin_data, speed_data, highspeed_data = \
                await self._device_session(ip, community, work)

            if not oper_data:
                logger.warning(f"无法获取端口数据: {ip}")
                return []

            # 只采集有 ifName 的物理端口 (过滤掉虚拟端口)
            physical_port_indices = set()
            for oid in name_data:
                suffix = _oid_suffix(oid, OID_IF_NAME)
                physical_port_indices.add(_safe_int(suffix))

            ports = []
            for oid, val in oper_data.items():
                suffix = _oid_suffix(oid, OID_IF_OPER_STATUS)
                port_index = _safe_int(suffix)
                if port_index == 0:
                    continue
                # 只采集物理端口
                if port_index not in physical_port_indices:
                    continue

                # 优先用 ifHighSpeed (Mbps), 回退到 ifSpeed (bps)
                hs_mbps = _safe_int(highspeed_data.get(f"{OID_IF_HIGH_SPEED}.{suffix}", 0))
                speed_bps = _safe_int(speed_data.get(f"{OID_IF_SPEED}.{suffix}", 0))
                if hs_mbps > 0:
                    speed_bps = hs_mbps * 1_000_000  # Mbps -> bps

                ports.append({
                    "port_index": port_index,
                    "port_name": _safe_str(name_data.get(f"{OID_IF_NAME}.{suffix}", f"Port {port_index}")),
                    "admin_status": _safe_int(admin_data.get(f"{OID_IF_ADMIN_STATUS}.{suffix}", 2)),
                    "oper_status": _safe_int(val, 2),
                    "speed_bps": speed_bps,
                })

            return ports

    async def collect_traffic_stats(self, ip: str, community: str) -> list[dict]:
        """采集端口流量统计"""
        async with self.device_semaphore:
            logger.debug(f"采集流量统计: {ip}")

            bases = [
                OID_IF_IN_OCTETS, OID_IF_OUT_OCTETS, OID_IF_IN_ERRORS, OID_IF_OUT_ERRORS,
                OID_IF_IN_DISCARDS, OID_IF_OUT_DISCARDS, OID_IF_IN_UCAST, OID_IF_OUT_UCAST,
            ]
            bulk_size = self.snmp_config.get("bulk_size", 50)

            async def work(client):
                return [await _walk_to_dict(client, b, bulk_size) for b in bases]

            in_oct, out_oct, in_err, out_err, in_disc, out_disc, in_ucast, out_ucast = \
                await self._device_session(ip, community, work)

            now = datetime.now()
            stats_list = []
            all_suffixes = set()
            for oid in in_oct:
                all_suffixes.add(_oid_suffix(oid, OID_IF_IN_OCTETS))
            for oid in out_oct:
                all_suffixes.add(_oid_suffix(oid, OID_IF_OUT_OCTETS))

            for suffix in all_suffixes:
                port_index = _safe_int(suffix)
                if port_index == 0:
                    continue

                cur_in = _safe_int(in_oct.get(f"{OID_IF_IN_OCTETS}.{suffix}", 0))
                cur_out = _safe_int(out_oct.get(f"{OID_IF_OUT_OCTETS}.{suffix}", 0))
                cur_in_err = _safe_int(in_err.get(f"{OID_IF_IN_ERRORS}.{suffix}", 0))
                cur_out_err = _safe_int(out_err.get(f"{OID_IF_OUT_ERRORS}.{suffix}", 0))
                cur_in_disc = _safe_int(in_disc.get(f"{OID_IF_IN_DISCARDS}.{suffix}", 0))
                cur_out_disc = _safe_int(out_disc.get(f"{OID_IF_OUT_DISCARDS}.{suffix}", 0))
                cur_in_ucast = _safe_int(in_ucast.get(f"{OID_IF_IN_UCAST}.{suffix}", 0))
                cur_out_ucast = _safe_int(out_ucast.get(f"{OID_IF_OUT_UCAST}.{suffix}", 0))

                # 计算速率
                last_key = f"{ip}:{port_index}"
                in_rate, out_rate, in_err_rate, out_err_rate = 0.0, 0.0, 0.0, 0.0

                if last_key in self._last_counters:
                    prev = self._last_counters[last_key]
                    dt = (now - prev["time"]).total_seconds()
                    if dt > 0:
                        in_rate = max(0, cur_in - prev["in_octets"]) * 8 / dt
                        out_rate = max(0, cur_out - prev["out_octets"]) * 8 / dt
                        total_in_pkts = cur_in_ucast - prev.get("in_ucast", 0)
                        total_out_pkts = cur_out_ucast - prev.get("out_ucast", 0)
                        in_err_delta = cur_in_err - prev.get("in_errors", 0)
                        out_err_delta = cur_out_err - prev.get("out_errors", 0)
                        in_err_rate = (in_err_delta / total_in_pkts * 100) if total_in_pkts > 0 else 0.0
                        out_err_rate = (out_err_delta / total_out_pkts * 100) if total_out_pkts > 0 else 0.0

                self._last_counters[last_key] = {
                    "time": now, "in_octets": cur_in, "out_octets": cur_out,
                    "in_errors": cur_in_err, "out_errors": cur_out_err,
                    "in_ucast": cur_in_ucast, "out_ucast": cur_out_ucast,
                }

                stats_list.append({
                    "port_index": port_index, "timestamp": now,
                    "in_octets": cur_in, "out_octets": cur_out,
                    "in_unicast_pkts": cur_in_ucast, "out_unicast_pkts": cur_out_ucast,
                    "in_errors": cur_in_err, "out_errors": cur_out_err,
                    "in_discards": cur_in_disc, "out_discards": cur_out_disc,
                    "in_rate_bps": round(in_rate, 2), "out_rate_bps": round(out_rate, 2),
                    "in_error_rate": round(abs(in_err_rate), 4),
                    "out_error_rate": round(abs(out_err_rate), 4),
                })

            return stats_list

    async def collect_hardware_health(self, ip: str, community: str) -> Optional[dict]:
        """采集硬件健康状态 (温度/电源/告警) - 按 sysObjectID 自动适配所有 MOXA 交换机系列

        两次有依赖关系的 GET 在单一会话(同一线程/循环/Client)内完成。
        """
        async with self.device_semaphore:
            logger.debug(f"采集硬件健康: {ip}")

            eds_oids = [
                OID_MOXA_EDS_TEMPERATURE, OID_MOXA_EDS_TEMP_ALARM,
                OID_MOXA_EDS_POWER1_STATUS, OID_MOXA_EDS_POWER2_STATUS,
                OID_MOXA_EDS_POWER1_VOLTAGE, OID_MOXA_EDS_POWER2_VOLTAGE,
                OID_MOXA_EDS_FAN_STATUS,
            ]

            async def work(client):
                # 用 sysObjectID 确定产品 MIB 分支 (各型号不同)
                oid_data = await _multiget(client, [OID_SYS_OBJECT_ID])
                sys_obj = oid_data.get(OID_SYS_OBJECT_ID)
                if not sys_obj:
                    # 取不到 sysObjectID (丢包/超时): 放弃本轮, 避免写入误导性的 0 值
                    return None
                base = _moxa_product_base(sys_obj)
                if base:
                    # MOXA 统一布局: 电源 <base>.1.10.1/2.0, 温度 <base>.1.11.1.6.0, 告警 <base>.1.11.2.N.0
                    alarm_oids = [f"{base}.1.11.2.{i}.0" for i in range(1, 8)]
                    oids = [base + REL_TEMP, base + REL_POWER1, base + REL_POWER2] + alarm_oids
                    return ("base", base, await _multiget(client, oids))
                if _is_moxa_nos(sys_obj):
                    # MOXA NOS 系列 (RKS-G4000 / EDS-4000/G4000): 温度 8691.602.1.3.2.3.0
                    return ("nos", "", await _multiget(client, [OID_MOXA_NOS_TEMP]))
                # 回退: 老式 EDS 系列私有 OID
                return ("eds", "", await _multiget(client, eds_oids))

            fetched = await self._device_session(ip, community, work)
            if not fetched:
                logger.debug(f"硬件采集: 无法获取 sysObjectID [{ip}], 跳过本轮")
                return None

            kind, base, data = fetched
            temp = 0.0
            p1 = p2 = 0
            temp_alarm = 0

            if kind == "base":
                if data:
                    temp = _safe_float(data.get(base + REL_TEMP, 0))
                    p1 = _safe_int(data.get(base + REL_POWER1, 0))
                    p2 = _safe_int(data.get(base + REL_POWER2, 0))
                    temp_alarm = 1 if any(
                        _safe_int(data.get(f"{base}.1.11.2.{i}.0", 0)) != 0 for i in range(1, 8)
                    ) else 0
            elif kind == "nos":
                if OID_MOXA_NOS_TEMP not in data:
                    logger.debug(f"硬件采集: NOS 温度取不到 [{ip}], 跳过本轮")
                    return None
                temp = _safe_float(data.get(OID_MOXA_NOS_TEMP, 0))
            elif not data:
                return None
            else:
                temp = _safe_float(data.get(OID_MOXA_EDS_TEMPERATURE, 0))
                temp_alarm = _safe_int(data.get(OID_MOXA_EDS_TEMP_ALARM, 0))
                p1 = _safe_int(data.get(OID_MOXA_EDS_POWER1_STATUS, 0))
                p2 = _safe_int(data.get(OID_MOXA_EDS_POWER2_STATUS, 0))

            # 综合状态判断
            overall = "normal"
            if p1 == 2 or p2 == 2:
                overall = "critical"
            elif temp_alarm == 1:
                overall = "warning"

            return {
                "timestamp": datetime.now(),
                "power1_status": p1, "power2_status": p2,
                "power1_voltage": 0.0,
                "power2_voltage": 0.0,
                "temperature": temp,
                "temperature_alarm": temp_alarm,
                "fan_status": 0,
                "overall_status": overall,
            }

    async def collect_system_info(self, ip: str, community: str) -> dict:
        """采集系统基本信息 (按 sysObjectID 自动适配所有 MOXA 交换机系列)

        基础信息 + 产品信息两次 GET 在单一会话内完成。
        """
        async with self.device_semaphore:
            base_oids = [
                OID_SYS_NAME, OID_SYS_DESCR, OID_SYS_UPTIME,
                OID_SYS_LOCATION, OID_SYS_OBJECT_ID,
            ]
            nos_oids = [OID_MOXA_NOS_MODEL, OID_MOXA_NOS_FIRMWARE, OID_MOXA_NOS_PORTS]

            async def work(client):
                data = await _multiget(client, base_oids)
                base = _moxa_product_base(data.get(OID_SYS_OBJECT_ID, ""))
                if base:
                    # MOXA 统一布局: 型号 <base>.1.2.0, 固件 <base>.1.4.0, 插槽 <base>.1.5.0
                    prod_oids = [base + REL_MODEL, base + REL_FIRMWARE, base + REL_SLOTS]
                    return data, base, "", await _multiget(client, prod_oids)
                if _is_moxa_nos(data.get(OID_SYS_OBJECT_ID, "")):
                    # MOXA NOS 系列: 型号/固件/端口数位于 8691.600.*/602.*
                    return data, "", "nos", await _multiget(client, nos_oids)
                return data, "", "", {}

            data, base, branch, pdata = await self._device_session(ip, community, work)

            model = ""
            firmware = ""
            slots = 0

            if base:
                model = _safe_str(pdata.get(base + REL_MODEL, ""))
                firmware = _safe_str(pdata.get(base + REL_FIRMWARE, ""))
                slots = _safe_int(pdata.get(base + REL_SLOTS, 0))
            elif branch == "nos":
                model = _safe_str(pdata.get(OID_MOXA_NOS_MODEL, ""))
                firmware = _safe_str(pdata.get(OID_MOXA_NOS_FIRMWARE, ""))
                slots = _safe_int(pdata.get(OID_MOXA_NOS_PORTS, 0))

            # 型号回退: 从 sysDescr 解析
            if not model:
                descr = _safe_str(data.get(OID_SYS_DESCR, ""))
                for part in descr.split():
                    if any(k in part.upper() for k in ["EDS", "PT-", "IKS-", "ICS-", "TN-"]):
                        model = part
                        break

            # 固件回退: sysDescr
            if not firmware:
                firmware = _safe_str(data.get(OID_SYS_DESCR, ""))
            # switch_devices.model / firmware_version 都是 VARCHAR(100);
            # sysDescr 常是带换行的设备 banner, 这里折叠空白并按列宽截断, 避免 MySQL 1406
            firmware = " ".join(firmware.split())[:100]

            return {
                "sys_name": _safe_str(data.get(OID_SYS_NAME, "")),
                "sys_descr": _safe_str(data.get(OID_SYS_DESCR, "")),
                "sys_location": _safe_str(data.get(OID_SYS_LOCATION, "")),
                "sys_object_id": _safe_str(data.get(OID_SYS_OBJECT_ID, "")),
                "sys_uptime": _safe_int(data.get(OID_SYS_UPTIME, 0)) // 100,
                "model": model[:100],
                "firmware": firmware,
                "slots": slots,
            }

    # ==================== 设备列表加载与分批调度 ====================

    async def _enabled_switches(self) -> list[dict]:
        """从数据库读取已启用的交换机, 转为采集所需的 dict 格式"""
        async with async_session() as session:
            result = await session.execute(
                select(SwitchDevice).where(SwitchDevice.enabled == True)
            )
            return [
                {
                    "id": sw.id,
                    "name": sw.name,
                    "ip": sw.ip,
                    "snmp": sw.snmp_config or {"version": "v2c", "community": "public"},
                    "ssh": sw.ssh_config or {},
                }
                for sw in result.scalars().all()
            ]

    async def _run_batches(self, handler, label: str, batch_size: Optional[int] = None) -> dict:
        """按批并行执行 handler(switch), 统计真实产出

        批次大小默认取配置 batch_size: 分批处理, 避免一次性并发过多请求.
        handler 返回采集结果标记 (collected/skipped/empty/failed): 只有 collected
        才代表有时序数据落库, 单靠"没抛异常"判断成功会掩盖"跑完但零写入".
        """
        switches = await self._enabled_switches()
        if not switches:
            logger.debug("数据库中没有已启用的交换机")
            return {"phase": label, "total": 0, "success": 0, "errors": 0,
                    "collected": 0, "skipped": 0, "empty": 0, "duration": 0.0}
        if batch_size is None:
            batch_size = self.snmp_config.get("batch_size", 16)
        total_errors = total_success = 0
        collected = skipped = empty = 0
        started = time.monotonic()
        logger.info(f"开始批量采集[{label}]: {len(switches)} 台 (每批 {batch_size} 台)")

        for i in range(0, len(switches), batch_size):
            batch = switches[i:i + batch_size]
            results = await asyncio.gather(
                *[handler(sw) for sw in batch], return_exceptions=True
            )
            errs = sum(1 for r in results if isinstance(r, Exception))
            for r in results:
                if r == "skipped":
                    skipped += 1
                elif r == "empty":
                    empty += 1
                elif r == "collected":
                    collected += 1
            total_errors += errs
            total_success += len(batch) - errs

            # 短让步, 让事件循环处理其他任务
            await asyncio.sleep(0.05)

        duration = round(time.monotonic() - started, 1)
        stats = {
            "phase": label,
            "total": len(switches),
            "success": total_success,
            "errors": total_errors,
            "collected": collected,
            "skipped": skipped,
            "empty": empty,
            "duration": duration,
        }
        # 如实汇报: 没抛异常不等于采到了数据, 零写入必须可见
        if collected == 0:
            logger.warning(
                f"批量采集[{label}]完成但无数据: {len(switches)} 台中 {collected} 台有样本, "
                f"{skipped} 台离线退避跳过, {empty} 台 SNMP 无响应, {total_errors} 台异常 "
                f"(耗时 {duration}s)"
            )
        elif total_errors or skipped or empty:
            logger.warning(
                f"批量采集[{label}]完成: {collected} 台有样本, {total_errors} 失败, "
                f"{skipped} 退避跳过, {empty} 无响应 (耗时 {duration}s)"
            )
        else:
            logger.info(f"批量采集[{label}]完成: 全部 {collected} 台有样本 (耗时 {duration}s)")

        return stats

    # ---- 三类采集任务, 供调度器按不同频率分别调用 ----

    async def poll_all_status(self, timeout: int = 120):
        """端口状态 + 系统信息"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_status, "状态"),
                    timeout=timeout
                )
                await self._after_cycle(stats)
            except asyncio.TimeoutError:
                logger.error(f"端口状态采集任务超时({timeout}s)，跳过本轮")

    async def poll_all_traffic(self, timeout: int = 180):
        """端口流量统计"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_traffic, "流量"),
                    timeout=timeout
                )
                await self._after_cycle(stats)
            except asyncio.TimeoutError:
                logger.error(f"流量采集任务超时({timeout}s)，跳过本轮")

    async def poll_all_hardware(self, timeout: int = 300):
        """硬件健康 温度/电源/告警"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_hardware, "硬件"),
                    timeout=timeout
                )
                await self._after_cycle(stats)
            except asyncio.TimeoutError:
                logger.error(f"硬件采集任务超时({timeout}s)，跳过本轮")

    async def _after_cycle(self, stats: dict):
        """一次采集周期收尾: 写心跳 (业务层据此判定采集层存活并驱动前端实时标签)"""
        detail = device_state.cycle_summary()
        # 把本轮真实产出附在心跳里, 让"作业成功但零写入"在系统健康页可直接看出
        detail.update({
            "collected": stats.get("collected", 0),
            "skipped": stats.get("skipped", 0),
            "empty": stats.get("empty", 0),
        })
        await write_heartbeat(
            phase=stats.get("phase", ""),
            total=stats.get("total", 0),
            success=stats.get("collected", 0),
            errors=stats.get("errors", 0),
            duration_seconds=stats.get("duration", 0.0),
            detail=detail,
        )

    async def poll_all_switches(self):
        """兼容入口: 依次执行全部三类采集"""
        await self.poll_all_status()
        await self.poll_all_traffic()
        await self.poll_all_hardware()

    # ==================== 单台设备采集 ====================

    def _probe_after(self) -> int:
        """离线退避窗口 (秒)"""
        return self.snmp_config.get("offline_backoff", {}).get("probe_after", 300)

    async def _probe_reachable(self, ip: str, community: str) -> bool:
        """轻量探活: 单个快速 GET (1s 超时不重试), 用于判定离线设备是否值得跑完整采集"""
        async def work(client):
            try:
                return await client.get(ObjectIdentifier(OID_SYS_DESCR))
            except Exception:
                return None

        fast_cfg = {**self._client_cfg(), "timeout": 1, "retries": 0}
        try:
            return await self._device_session(ip, community, work, fast_cfg) is not None
        except Exception:
            return False

    async def _poll_precheck(self, ip: str, community: str, force: bool = False) -> bool:
        """采集前置检查 (离线退避核心, 本机 CPU 优化关键路径)

        - 设备在线: 直接放行, 走完整采集
        - 设备离线且未超过 probe_after: 跳过 (True), 不再为其消耗线程/超时/重试
        - 设备离线且到达探活窗口: 发单个轻量 GET, 通则立即清除退避并放行完整采集,
          不通则顺延退避并跳过 (force=True 时跳过以上判定, 用于 Trap/手动定向刷新)
        """
        cfg = self.snmp_config.get("offline_backoff", {})
        if force or not cfg.get("enabled", True):
            return False
        probe_after = cfg.get("probe_after", 300)
        entry = device_state.switch_by_ip(ip)
        last_poll = entry.get("last_poll_time") if entry else None
        if not last_poll or entry.get("is_online", True):
            return False  # 从未采集过或在线: 本轮走完整采集
        try:
            last_ts = datetime.strptime(last_poll, "%Y-%m-%d %H:%M:%S").timestamp()
        except (ValueError, TypeError):
            return False
        # 退避期内 (离线且未到探活时刻): 跳过, 不消耗线程/超时/重试
        now_mono = time.monotonic()
        next_probe = self._next_probe_at.get(ip)
        if next_probe is not None and now_mono < next_probe:
            return True
        if next_probe is None:
            # 本轮完整采集刚判定过离线 (早于退避机制): 按上次采集时间补排探活窗口
            if time.time() - last_ts < probe_after:
                return True
        # 到达探活窗口: 快速试探 (加小抖动, 避免全部离线设备同时探测)
        await asyncio.sleep(random.uniform(0, 0.5))
        if await self._probe_reachable(ip, community):
            self._next_probe_at.pop(ip, None)
            logger.info(f"探活成功, 设备恢复在线, 本轮立即完整采集 [{ip}]")
            return False
        self._next_probe_at[ip] = now_mono + probe_after
        logger.debug(f"探活失败 [{ip}], 延长离线退避 {probe_after}s")
        return True

    async def poll_switch_status(self, switch_cfg: dict, force: bool = False):
        """采集端口状态 + 系统信息, 更新设备基础信息与端口最新状态

        返回 "skipped"/"empty"/"collected"/"failed", 供批量统计区分真实产出。
        """
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
        if await self._poll_precheck(ip, community, force=force):
            logger.debug(f"设备离线退避中, 跳过本轮采集 [{ip}]")
            return "skipped"
        try:
            ports, sys_info = await asyncio.gather(
                self.collect_port_status(ip, community),
                self.collect_system_info(ip, community),
            )
            now = datetime.now()
            # 在线判定: 能取到任一系统标识即视为在线 (SNMP 超时时 puresnmp 内部吞掉异常,
            # 若不显式判定, is_online 会永远为 True, 离线告警将失效)
            online = bool(
                sys_info.get("sys_name") or sys_info.get("sys_descr")
                or sys_info.get("sys_object_id")
            )
            # 维护离线退避时刻表: 在线则清除记录, 离线则预约 probe_after 秒后再探活
            if online:
                self._next_probe_at.pop(ip, None)
            else:
                self._next_probe_at.setdefault(ip, time.monotonic() + self._probe_after())
            async with self.db_lock:
                async with async_session() as session:
                    # 只写采集字段: 台账字段 (name/location/enabled/snmp_config/ssh_config)
                    # 归业务层维护, 避免两层双写相互覆盖
                    update_values = {
                        "is_online": online,
                        "last_poll_time": now,
                        "uptime_seconds": sys_info.get("sys_uptime", 0),
                    }
                    if online:
                        if ports:
                            update_values["total_ports"] = len(ports)
                        for k, v in (
                            ("model", sys_info.get("model")),
                            ("firmware_version", sys_info.get("firmware")),
                        ):
                            if v:
                                update_values[k] = v

                    await session.execute(
                        update(SwitchDevice).where(SwitchDevice.id == switch_id)
                        .values(**update_values)
                    )

                    # 端口状态"只保留最新": 先删该交换机旧记录, 再批量插入 (2 条语句替代 N 次 SELECT+UPDATE)
                    # 离线(ports 为空)时保留最后已知端口状态, 避免状态被清空
                    if ports:
                        await session.execute(
                            delete(PortStatus).where(PortStatus.switch_id == switch_id)
                        )
                        session.add_all([
                            PortStatus(switch_id=switch_id, **p) for p in ports
                        ])

                    await session.commit()
            # 更新进程内采集缓存 (供离线退避判定)
            fields = {
                "name": switch_cfg.get("name") or sys_info.get("sys_name") or ip,
                "ip": ip,
                "is_online": online,
                "last_poll_time": now.strftime("%Y-%m-%d %H:%M:%S"),
            }
            if ports:
                fields["total_ports"] = len(ports)
            device_state.update_switch(switch_id, **fields)
            logger.debug(f"采集完成[状态]: {ip} - {len(ports)} 物理端口")
            return "collected" if ports else "empty"
        except Exception as e:
            logger.error(f"采集异常[状态] [{ip}]: {e}", exc_info=True)
            self._next_probe_at.setdefault(ip, time.monotonic() + self._probe_after())
            await self._mark_offline(switch_id)
            return "failed"

    async def poll_switch_traffic(self, switch_cfg: dict):
        """采集端口流量统计 (时序, 批量插入)"""
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
        if await self._poll_precheck(ip, community):
            return "skipped"
        try:
            stats = await self.collect_traffic_stats(ip, community)
            if not stats:
                return "empty"
            async with self.db_lock:
                async with async_session() as session:
                    session.add_all([
                        PortTrafficStats(switch_id=switch_id, **s) for s in stats
                    ])
                    await session.commit()
            logger.debug(f"采集完成[流量]: {ip} - {len(stats)} 条")
            return "collected"
        except Exception as e:
            logger.error(f"采集异常[流量] [{ip}]: {e}", exc_info=True)
            return "failed"

    async def poll_switch_hardware(self, switch_cfg: dict):
        """采集硬件健康 (温度/电源/告警)"""
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
        if await self._poll_precheck(ip, community):
            return "skipped"
        try:
            hw = await self.collect_hardware_health(ip, community)
            if not hw:
                return "empty"
            async with self.db_lock:
                async with async_session() as session:
                    session.add(HardwareHealth(switch_id=switch_id, **hw))
                    # 同步回写设备表的 CPU/内存使用率 (告警规则 switch_cpu_high 直接读该列)
                    await session.execute(
                        update(SwitchDevice).where(SwitchDevice.id == switch_id)
                        .values(
                            cpu_usage=hw.get("cpu_usage", 0),
                            memory_usage=hw.get("memory_usage", 0),
                        )
                    )
                    await session.commit()
            logger.debug(f"采集完成[硬件]: {ip} - 温度 {hw.get('temperature')}°C")
            return "collected"
        except Exception as e:
            logger.error(f"采集异常[硬件] [{ip}]: {e}", exc_info=True)
            return "failed"

    async def _mark_offline(self, switch_id: str):
        now = datetime.now()
        async with self.db_lock:
            async with async_session() as session:
                await session.execute(
                    update(SwitchDevice).where(SwitchDevice.id == switch_id)
                    .values(is_online=False, last_poll_time=now)
                )
                await session.commit()
        device_state.update_switch(switch_id, is_online=False, last_poll_time=now.strftime("%Y-%m-%d %H:%M:%S"))
