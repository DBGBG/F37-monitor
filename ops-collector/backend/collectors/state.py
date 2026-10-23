"""采集层进程内状态缓存 + 心跳写入

三层架构改造后采集层不再持有任何对外接口, 内存态只服务于两个内部目的:
  1) 离线退避判定 (snmp_collector 需要知道设备上次采集时间与在线状态, 避免每轮都读库)
  2) 服务器可达性判定 (process_monitor 据此把"采集通道不可达"与"进程未运行"区分开)
对前端的实时展示改由 Spring Boot 的 SnapshotPoller 读库 + SSE 推送, 因此这里不再有任何
事件总线 / SSE 相关代码。

心跳写库 (collector_heartbeats) 是业务层判定"采集层是否活着"的唯一依据。
"""

import logging
from datetime import datetime, timedelta
from typing import Any, Optional

from sqlalchemy import delete, select

from backend.database import async_session
from backend.models.collector import CollectorHeartbeat

logger = logging.getLogger(__name__)


class DeviceStateCache:
    """设备最新采集状态的进程内缓存 (仅采集层内部使用)"""

    def __init__(self):
        # device_id -> {name, ip, is_online, last_poll_time, total_ports}
        self.switches: dict[str, dict] = {}
        self.servers: dict[str, dict] = {}

    def update_switch(self, device_id: str, **fields: Any):
        entry = self.switches.setdefault(device_id, {"id": device_id})
        entry.update(fields)

    def update_server(self, device_id: str, **fields: Any):
        entry = self.servers.setdefault(device_id, {"id": device_id})
        entry.update(fields)

    def switch_by_ip(self, ip: str) -> Optional[dict]:
        entry = self.switches.get(f"sw-{ip.replace('.', '-')}")
        if entry:
            return entry
        return next((d for d in self.switches.values() if d.get("ip") == ip), None)

    def server_online(self, device_id: str) -> bool:
        return bool(self.servers.get(device_id, {}).get("is_online", True))

    def cycle_summary(self) -> dict:
        return {
            "switches": len(self.switches),
            "switches_online": sum(1 for d in self.switches.values() if d.get("is_online")),
            "servers": len(self.servers),
            "servers_online": sum(1 for d in self.servers.values() if d.get("is_online")),
        }


device_state = DeviceStateCache()


async def bootstrap_state_from_db() -> dict:
    """启动时用库中最新状态填充缓存 (避免首轮把全部设备当成"从未采集")"""
    from backend.models.device import ServerDevice, SwitchDevice

    async with async_session() as session:
        for sw in (await session.execute(select(SwitchDevice))).scalars().all():
            device_state.update_switch(
                sw.id,
                name=sw.name,
                ip=sw.ip,
                is_online=bool(sw.is_online),
                total_ports=sw.total_ports,
                last_poll_time=sw.last_poll_time.strftime("%Y-%m-%d %H:%M:%S") if sw.last_poll_time else None,
            )
        for srv in (await session.execute(select(ServerDevice))).scalars().all():
            device_state.update_server(
                srv.id,
                name=srv.name,
                ip=srv.ip,
                is_online=bool(srv.is_online),
                last_poll_time=srv.last_poll_time.strftime("%Y-%m-%d %H:%M:%S") if srv.last_poll_time else None,
            )
    summary = device_state.cycle_summary()
    logger.info(
        "采集缓存已从数据库引导: %(switches)s 台交换机(在线 %(switches_online)s), "
        "%(servers)s 台服务器(在线 %(servers_online)s)" % summary
    )
    return summary


async def load_enabled_servers() -> list[dict]:
    """从 server_devices 表读取已启用服务器, 构造采集所需的配置字典

    三层架构下服务器台账由业务层维护 (Flyway 种子 / Java 增删改), 采集层不再读 devices.yaml。
    返回结构与旧版 yaml 条目保持一致: id/name/ip/os_type/monitoring{method,ssh,agent_port}/processes[]
    """
    from backend.models.device import ServerDevice

    async with async_session() as session:
        rows = (await session.execute(
            select(ServerDevice).where(ServerDevice.enabled == True).order_by(ServerDevice.id)
        )).scalars().all()

    servers = []
    for s in rows:
        monitoring = s.monitoring_config or {}
        if not isinstance(monitoring, dict):
            monitoring = {}
        processes = s.processes_config if isinstance(s.processes_config, list) else []
        servers.append({
            "id": s.id,
            "name": s.name,
            "ip": s.ip,
            "os_type": s.os_type or "linux",
            "location": s.location or "",
            "monitoring": monitoring,
            "processes": processes,
        })
    return servers


async def write_heartbeat(
    phase: str,
    total: int,
    success: int,
    errors: int,
    duration_seconds: float,
    component: str = "collector",
    detail: Optional[dict] = None,
):
    """写一条采集心跳, 供业务层判断采集层存活与本轮结果"""
    try:
        async with async_session() as session:
            session.add(CollectorHeartbeat(
                component=component,
                phase=phase,
                total=int(total or 0),
                success=int(success or 0),
                errors=int(errors or 0),
                duration_ms=int(float(duration_seconds or 0) * 1000),
                last_beat_at=datetime.now(),
                detail=detail or {},
            ))
            await session.commit()
    except Exception as e:
        # 心跳写库失败不能影响采集本身
        logger.warning("写采集心跳失败 [%s]: %s", phase, e)


async def prune_heartbeats(retention_days: int = 7) -> int:
    """清理过期心跳, 返回删除行数"""
    cutoff = datetime.now() - timedelta(days=max(int(retention_days or 7), 1))
    async with async_session() as session:
        result = await session.execute(
            delete(CollectorHeartbeat).where(CollectorHeartbeat.last_beat_at < cutoff)
        )
        await session.commit()
    deleted = result.rowcount or 0
    if deleted:
        logger.info("清理过期采集心跳 %d 条 (保留 %d 天)", deleted, retention_days)
    return deleted
