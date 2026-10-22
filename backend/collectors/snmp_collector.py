"""
SNMP 交换机状态采集器
- 使用 puresnmp 异步接口批量采集 MOXA 交换机状态
- 支持端口状态、流量统计、硬件健康（温度/告警）
- 通过 asyncio.Semaphore 控制并发数，避免网络拥塞
- 自动适配 PT 系列 / EDS 系列 MOXA 设备
"""

import asyncio
import logging
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
from backend.realtime import live, bus

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


def _run_in_new_loop(coro_factory):
    """在工作线程中新建事件循环执行协程工厂。

    puresnmp 的底层 UDP 收发会阻塞当前事件循环, 因此把网络调用放到独立工作
    线程自己的事件循环中执行, 主事件循环(API/调度)保持响应。
    """
    return asyncio.run(coro_factory())


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
        # 采集周期结束后的即时回调 (用于"数据到达即评估告警", 由调度器注入)
        self.on_cycle_complete = None

    def _community(self, switch_cfg: dict) -> str:
        return switch_cfg.get("snmp", {}).get(
            "community", self.snmp_config.get("community", "public")
        )

    def _timeout(self) -> int:
        return self.snmp_config.get("timeout", 5)

    def _client(self, ip: str, community: str) -> Client:
        """创建并配置 puresnmp Client (应用超时/重试, 避免丢包时长时间重传)"""
        client = Client(ip, V2C(community), port=self.snmp_config.get("port", 161))
        client.configure(
            timeout=self._timeout(),
            retries=self.snmp_config.get("retries", 2),
        )
        return client

    async def _offload(self, coro_factory):
        """在工作线程中运行 SNMP 协程 (该线程有独立事件循环)"""
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self.executor, _run_in_new_loop, coro_factory)

    async def _snmp_get(self, ip: str, community: str, oids: list[str]) -> dict:
        """SNMP GET - 单次 PDU 批量获取多个 OID (multiget; 失败时回退逐条 GET)"""
        if not oids:
            return {}

        async def job():
            client = self._client(ip, community)
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

        return await self._offload(job)

    async def _snmp_walk(self, ip: str, community: str, base_oid: str) -> dict:
        """SNMP WALK - bulkwalk; bulk_size 越大每个 PDU 返回行越多, 往返次数越少"""
        bulk_size = self.snmp_config.get("bulk_size", 50)

        async def job():
            result = {}
            client = self._client(ip, community)
            try:
                async for binding in client.bulkwalk(
                    [ObjectIdentifier(base_oid)], bulk_size=bulk_size
                ):
                    oid_str = str(binding.oid)
                    if not oid_str.startswith(base_oid):
                        break
                    result[oid_str] = binding.value
            except Exception as e:
                logger.debug(f"SNMP WALK 结束 [{ip}] OID={base_oid}: {e}")
            return result

        return await self._offload(job)

    async def collect_port_status(self, ip: str, community: str) -> list[dict]:
        """采集交换机端口状态 (只采集物理端口，通过 ifName 过滤)"""
        async with self.device_semaphore:
            logger.debug(f"采集端口状态: {ip}")

            # 并行采集: 端口运行状态 + 端口名称 + 管理状态 + 速率
            oper_data, name_data, admin_data, speed_data, highspeed_data = await asyncio.gather(
                self._snmp_walk(ip, community, OID_IF_OPER_STATUS),
                self._snmp_walk(ip, community, OID_IF_NAME),
                self._snmp_walk(ip, community, OID_IF_ADMIN_STATUS),
                self._snmp_walk(ip, community, OID_IF_SPEED),
                self._snmp_walk(ip, community, OID_IF_HIGH_SPEED),
            )

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

            in_oct, out_oct, in_err, out_err, in_disc, out_disc, in_ucast, out_ucast = await asyncio.gather(
                self._snmp_walk(ip, community, OID_IF_IN_OCTETS),
                self._snmp_walk(ip, community, OID_IF_OUT_OCTETS),
                self._snmp_walk(ip, community, OID_IF_IN_ERRORS),
                self._snmp_walk(ip, community, OID_IF_OUT_ERRORS),
                self._snmp_walk(ip, community, OID_IF_IN_DISCARDS),
                self._snmp_walk(ip, community, OID_IF_OUT_DISCARDS),
                self._snmp_walk(ip, community, OID_IF_IN_UCAST),
                self._snmp_walk(ip, community, OID_IF_OUT_UCAST),
            )

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
        """采集硬件健康状态 (温度/电源/告警) - 按 sysObjectID 自动适配所有 MOXA 交换机系列"""
        async with self.device_semaphore:
            logger.debug(f"采集硬件健康: {ip}")

            # 用 sysObjectID 确定产品 MIB 分支 (各型号不同)
            oid_data = await self._snmp_get(ip, community, [OID_SYS_OBJECT_ID])
            sys_obj = oid_data.get(OID_SYS_OBJECT_ID)
            if not sys_obj:
                # 取不到 sysObjectID (丢包/超时): 放弃本轮, 避免写入误导性的 0 值
                logger.debug(f"硬件采集: 无法获取 sysObjectID [{ip}], 跳过本轮")
                return None
            base = _moxa_product_base(sys_obj)
            is_nos = _is_moxa_nos(sys_obj)

            temp = 0.0
            p1 = p2 = 0
            temp_alarm = 0
            got = False

            if base:
                # MOXA 交换机统一布局: 电源 <base>.1.10.1/2.0, 温度 <base>.1.11.1.6.0, 告警 <base>.1.11.2.N.0
                temp_oid = base + REL_TEMP
                p1_oid = base + REL_POWER1
                p2_oid = base + REL_POWER2
                alarm_oids = [f"{base}.1.11.2.{i}.0" for i in range(1, 8)]
                data = await self._snmp_get(
                    ip, community, [temp_oid, p1_oid, p2_oid] + alarm_oids
                )
                if data:
                    temp = _safe_float(data.get(temp_oid, 0))
                    p1 = _safe_int(data.get(p1_oid, 0))
                    p2 = _safe_int(data.get(p2_oid, 0))
                    temp_alarm = 1 if any(
                        _safe_int(data.get(o, 0)) != 0 for o in alarm_oids
                    ) else 0
                    got = True

            elif is_nos:
                # MOXA NOS 系列 (RKS-G4000 / EDS-4000/G4000): 温度位于 8691.602.1.3.2.3.0
                ndata = await self._snmp_get(ip, community, [OID_MOXA_NOS_TEMP])
                if OID_MOXA_NOS_TEMP not in ndata:
                    logger.debug(f"硬件采集: NOS 温度取不到 [{ip}], 跳过本轮")
                    return None
                temp = _safe_float(ndata.get(OID_MOXA_NOS_TEMP, 0))
                got = True

            if not got and not base and not is_nos:
                # 回退: 老式 EDS 系列私有 OID
                eds_oids = [
                    OID_MOXA_EDS_TEMPERATURE, OID_MOXA_EDS_TEMP_ALARM,
                    OID_MOXA_EDS_POWER1_STATUS, OID_MOXA_EDS_POWER2_STATUS,
                    OID_MOXA_EDS_POWER1_VOLTAGE, OID_MOXA_EDS_POWER2_VOLTAGE,
                    OID_MOXA_EDS_FAN_STATUS,
                ]
                data = await self._snmp_get(ip, community, eds_oids)
                if not data:
                    return None
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
        """采集系统基本信息 (按 sysObjectID 自动适配所有 MOXA 交换机系列)"""
        async with self.device_semaphore:
            # 标准系统信息 + sysObjectID
            base_oids = [
                OID_SYS_NAME, OID_SYS_DESCR, OID_SYS_UPTIME,
                OID_SYS_LOCATION, OID_SYS_OBJECT_ID,
            ]
            data = await self._snmp_get(ip, community, base_oids)

            base = _moxa_product_base(data.get(OID_SYS_OBJECT_ID, ""))

            model = ""
            firmware = ""
            slots = 0

            if base:
                # MOXA 交换机统一布局: 型号 <base>.1.2.0, 固件 <base>.1.4.0, 插槽 <base>.1.5.0
                prod_oids = [
                    base + REL_MODEL, base + REL_FIRMWARE, base + REL_SLOTS,
                ]
                pdata = await self._snmp_get(ip, community, prod_oids)
                model = _safe_str(pdata.get(base + REL_MODEL, ""))
                firmware = _safe_str(pdata.get(base + REL_FIRMWARE, ""))
                slots = _safe_int(pdata.get(base + REL_SLOTS, 0))
            elif _is_moxa_nos(data.get(OID_SYS_OBJECT_ID, "")):
                # MOXA NOS 系列: 型号 8691.600.2.2.4.0, 固件 8691.602.1.1.2.2.1.0,
                # 序列号 8691.600.2.2.3.0, 端口数 8691.600.2.2.1.0
                ndata = await self._snmp_get(
                    ip, community,
                    [OID_MOXA_NOS_MODEL, OID_MOXA_NOS_FIRMWARE, OID_MOXA_NOS_PORTS],
                )
                model = _safe_str(ndata.get(OID_MOXA_NOS_MODEL, ""))
                firmware = _safe_str(ndata.get(OID_MOXA_NOS_FIRMWARE, ""))
                slots = _safe_int(ndata.get(OID_MOXA_NOS_PORTS, 0))

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

            return {
                "sys_name": _safe_str(data.get(OID_SYS_NAME, "")),
                "sys_descr": _safe_str(data.get(OID_SYS_DESCR, "")),
                "sys_location": _safe_str(data.get(OID_SYS_LOCATION, "")),
                "sys_object_id": _safe_str(data.get(OID_SYS_OBJECT_ID, "")),
                "sys_uptime": _safe_int(data.get(OID_SYS_UPTIME, 0)) // 100,
                "model": model,
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
        """按批并行执行 handler(switch), 统计成功/失败

        批次大小默认取配置 batch_size: 分批处理, 避免一次性并发过多请求.
        采集完成后批量推送设备更新事件, 减少 SSE 消息数量.
        """
        switches = await self._enabled_switches()
        if not switches:
            logger.debug("数据库中没有已启用的交换机")
            return {"phase": label, "total": 0, "success": 0, "errors": 0, "duration": 0.0}
        if batch_size is None:
            batch_size = self.snmp_config.get("batch_size", 64)
        total_errors = total_success = 0
        started = time.monotonic()
        logger.info(f"开始批量采集[{label}]: {len(switches)} 台 (每批 {batch_size} 台)")
        
        # 收集所有更新的设备ID, 用于批量推送
        updated_device_ids = []
        
        for i in range(0, len(switches), batch_size):
            batch = switches[i:i + batch_size]
            results = await asyncio.gather(
                *[handler(sw) for sw in batch], return_exceptions=True
            )
            errs = sum(1 for r in results if isinstance(r, Exception))
            total_errors += errs
            total_success += len(batch) - errs
            
            # 收集成功更新的设备ID
            for sw, result in zip(batch, results):
                if not isinstance(result, Exception):
                    updated_device_ids.append(sw["id"])
            
            # 短让步, 让事件循环处理 API 请求及其他任务
            await asyncio.sleep(0.05)
        
        duration = round(time.monotonic() - started, 1)
        stats = {
            "phase": label,
            "total": len(switches),
            "success": total_success,
            "errors": total_errors,
            "duration": duration,
        }
        if total_errors:
            logger.warning(
                f"批量采集[{label}]完成: {total_success} 成功, {total_errors} 失败 (耗时 {duration}s)"
            )
        else:
            logger.info(f"批量采集[{label}]完成: 全部 {total_success} 台成功 (耗时 {duration}s)")
        
        # 批量推送设备更新事件 (减少 SSE 消息数量)
        if updated_device_ids and label == "状态":  # 只在状态采集后推送
            devices_data = [live.switches[did] for did in updated_device_ids if did in live.switches]
            if devices_data:
                bus.publish("devices_batch", devices_data)
        
        return stats

    # ---- 三类采集任务, 供调度器按不同频率分别调用 ----
    
    async def poll_all_status(self):
        """端口状态 + 系统信息 (实时性关键: 完成后立即评估告警)"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_status, "状态"),
                    timeout=120  # 最多120秒
                )
                await self._after_cycle(stats, alert=True)
            except asyncio.TimeoutError:
                logger.error("端口状态采集任务超时(120s)，跳过本轮")
    
    async def poll_all_traffic(self):
        """端口流量统计"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_traffic, "流量"),
                    timeout=180  # 最多180秒
                )
                await self._after_cycle(stats)
            except asyncio.TimeoutError:
                logger.error("流量采集任务超时(180s)，跳过本轮")
    
    async def poll_all_hardware(self):
        """硬件健康 温度/电源/告警"""
        async with self.phase_lock:
            try:
                stats = await asyncio.wait_for(
                    self._run_batches(self.poll_switch_hardware, "硬件"),
                    timeout=300  # 最多300秒
                )
                await self._after_cycle(stats)
            except asyncio.TimeoutError:
                logger.error("硬件采集任务超时(300s)，跳过本轮")

    async def _after_cycle(self, stats: dict, alert: bool = False):
        """一次采集周期收尾: 更新内存态 -> 推送 -> (可选)立即评估告警"""
        live.mark_cycle(stats.get("phase", ""), stats)
        live.publish_overview()
        bus.publish("cycle", live.last_cycle)
        if alert and self.on_cycle_complete is not None:
            try:
                await self.on_cycle_complete()
            except Exception as e:
                logger.error(f"采集后即时告警检查异常: {e}", exc_info=True)

    async def poll_all_switches(self):
        """兼容入口: 依次执行全部三类采集"""
        await self.poll_all_status()
        await self.poll_all_traffic()
        await self.poll_all_hardware()

    # ==================== 单台设备采集 ====================

    async def poll_switch_status(self, switch_cfg: dict):
        """采集端口状态 + 系统信息, 更新设备基础信息与端口最新状态"""
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
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
            async with self.db_lock:
                async with async_session() as session:
                    update_values = {
                        "is_online": online,
                        "last_poll_time": now,
                        "uptime_seconds": sys_info.get("sys_uptime", 0),
                    }
                    if online:
                        if ports:
                            update_values["total_ports"] = len(ports)
                        for k, v in (
                            ("name", sys_info.get("sys_name")),
                            ("location", sys_info.get("sys_location")),
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
            # 更新内存实时态 (供 SSE 推送 / 秒级仪表盘)
            fields = {
                "name": switch_cfg.get("name") or sys_info.get("sys_name") or ip,
                "ip": ip,
                "is_online": online,
                "last_poll_time": now.strftime("%Y-%m-%d %H:%M:%S"),
            }
            if ports:
                fields["total_ports"] = len(ports)
            live.update_switch(switch_id, **fields)
            # 不再单独推送设备事件, 改为在 _run_batches 中批量推送
            # bus.publish("device", live.switches[switch_id])
            logger.debug(f"采集完成[状态]: {ip} - {len(ports)} 物理端口")
        except Exception as e:
            logger.error(f"采集异常[状态] [{ip}]: {e}", exc_info=True)
            await self._mark_offline(switch_id)

    async def poll_switch_traffic(self, switch_cfg: dict):
        """采集端口流量统计 (时序, 批量插入)"""
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
        try:
            stats = await self.collect_traffic_stats(ip, community)
            if not stats:
                return
            async with self.db_lock:
                async with async_session() as session:
                    session.add_all([
                        PortTrafficStats(switch_id=switch_id, **s) for s in stats
                    ])
                    await session.commit()
            logger.debug(f"采集完成[流量]: {ip} - {len(stats)} 条")
        except Exception as e:
            logger.error(f"采集异常[流量] [{ip}]: {e}", exc_info=True)

    async def poll_switch_hardware(self, switch_cfg: dict):
        """采集硬件健康 (温度/电源/告警)"""
        ip, switch_id = switch_cfg["ip"], switch_cfg["id"]
        community = self._community(switch_cfg)
        try:
            hw = await self.collect_hardware_health(ip, community)
            if not hw:
                return
            async with self.db_lock:
                async with async_session() as session:
                    session.add(HardwareHealth(switch_id=switch_id, **hw))
                    await session.commit()
            logger.debug(f"采集完成[硬件]: {ip} - 温度 {hw.get('temperature')}°C")
        except Exception as e:
            logger.error(f"采集异常[硬件] [{ip}]: {e}", exc_info=True)

    async def _mark_offline(self, switch_id: str):
        now = datetime.now()
        async with self.db_lock:
            async with async_session() as session:
                await session.execute(
                    update(SwitchDevice).where(SwitchDevice.id == switch_id)
                    .values(is_online=False, last_poll_time=now)
                )
                await session.commit()
        live.update_switch(switch_id, is_online=False, last_poll_time=now.strftime("%Y-%m-%d %H:%M:%S"))
        # 不再单独推送设备事件, 改为在 _run_batches 中批量推送
        # bus.publish("device", live.switches[switch_id])
