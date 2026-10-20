"""服务器相关 API 路由"""

from datetime import datetime, timedelta

from fastapi import APIRouter, Query
from sqlalchemy import select, func, and_, desc
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import async_session
from backend.models.device import ServerDevice
from backend.models.server_metrics import ServerMetrics, ProcessStatus
from backend.config import config

router = APIRouter(prefix="/api/servers", tags=["服务器监控"])


@router.get("/summary")
async def get_server_summary():
    """获取服务器总览统计"""
    servers = config.get_enabled_servers()
    total = len(servers)

    async with async_session() as session:
        result = await session.execute(
            select(func.count(ServerDevice.id)).where(ServerDevice.is_online == True)
        )
        online = result.scalar() or 0

        # 统计异常进程数
        proc_result = await session.execute(
            select(func.count(ProcessStatus.id)).where(
                and_(
                    ProcessStatus.is_critical == True,
                    ProcessStatus.is_running == False,
                )
            )
        )
        abnormal_procs = proc_result.scalar() or 0

    return {
        "total": total,
        "online": online,
        "offline": total - online,
        "online_rate": round(online / total * 100, 1) if total > 0 else 0,
        "abnormal_processes": abnormal_procs,
    }


@router.get("/list")
async def get_server_list(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    keyword: str = Query(""),
):
    """获取服务器列表"""
    async with async_session() as session:
        query = select(ServerDevice)

        if keyword:
            query = query.where(
                ServerDevice.name.ilike(f"%{keyword}%") |
                ServerDevice.ip.ilike(f"%{keyword}%")
            )

        count_result = await session.execute(
            select(func.count()).select_from(query.subquery())
        )
        total = count_result.scalar() or 0

        query = query.order_by(ServerDevice.is_online.desc(), ServerDevice.id)
        query = query.offset((page - 1) * page_size).limit(page_size)
        result = await session.execute(query)
        servers = result.scalars().all()

        # 为每台服务器获取最新指标 (单次窗口函数查询, 避免 N+1)
        metrics_map = {}
        server_ids = [s.id for s in servers]
        if server_ids:
            row_num = func.row_number().over(
                partition_by=ServerMetrics.server_id,
                order_by=desc(ServerMetrics.timestamp),
            ).label("rn")
            subq = select(ServerMetrics, row_num).where(
                ServerMetrics.server_id.in_(server_ids)
            ).subquery()
            rows = (await session.execute(
                select(subq).where(subq.c.rn == 1)
            )).mappings().all()
            for row in rows:
                metrics_map[row["server_id"]] = row

        items = []
        for s in servers:
            latest = metrics_map.get(s.id)

            item = {
                "id": s.id,
                "name": s.name,
                "ip": s.ip,
                "os_type": s.os_type,
                "location": s.location,
                "hostname": s.hostname,
                "is_online": s.is_online,
                "uptime_seconds": s.uptime_seconds,
                "last_poll_time": str(s.last_poll_time) if s.last_poll_time else None,
                "metrics": None,
            }

            if latest:
                item["metrics"] = {
                    "cpu_usage": latest["cpu_usage_percent"],
                    "memory_usage": latest["memory_usage_percent"],
                    "memory_total_mb": latest["memory_total_mb"],
                    "disk_usage": latest["disk_usage_percent"],
                    "disk_total_gb": latest["disk_total_gb"],
                    "net_send_rate_bps": latest["net_send_rate_bps"],
                    "net_recv_rate_bps": latest["net_recv_rate_bps"],
                }

            items.append(item)

        return {"total": total, "page": page, "page_size": page_size, "items": items}


@router.get("/{server_id}/detail")
async def get_server_detail(server_id: str):
    """获取服务器详情"""
    async with async_session() as session:
        result = await session.execute(
            select(ServerDevice).where(ServerDevice.id == server_id)
        )
        server = result.scalar_one_or_none()
        if not server:
            return {"error": "服务器不存在"}

        return {
            "id": server.id,
            "name": server.name,
            "ip": server.ip,
            "os_type": server.os_type,
            "location": server.location,
            "hostname": server.hostname,
            "os_version": server.os_version,
            "cpu_cores": server.cpu_cores,
            "total_memory_mb": server.total_memory_mb,
            "is_online": server.is_online,
            "uptime_seconds": server.uptime_seconds,
            "last_poll_time": str(server.last_poll_time) if server.last_poll_time else None,
        }


@router.get("/{server_id}/metrics")
async def get_server_metrics(
    server_id: str,
    hours: int = Query(1, ge=1, le=24),
):
    """获取服务器指标趋势数据"""
    start_time = datetime.now() - timedelta(hours=hours)

    async with async_session() as session:
        result = await session.execute(
            select(ServerMetrics)
            .where(
                and_(
                    ServerMetrics.server_id == server_id,
                    ServerMetrics.timestamp >= start_time,
                )
            )
            .order_by(ServerMetrics.timestamp)
        )
        records = result.scalars().all()

        return {
            "server_id": server_id,
            "data": [
                {
                    "timestamp": str(r.timestamp),
                    "cpu_usage": r.cpu_usage_percent,
                    "memory_usage": r.memory_usage_percent,
                    "memory_used_mb": r.memory_used_mb,
                    "disk_usage": r.disk_usage_percent,
                    "disk_read_bps": r.disk_read_bps,
                    "disk_write_bps": r.disk_write_bps,
                    "net_send_rate": r.net_send_rate_bps,
                    "net_recv_rate": r.net_recv_rate_bps,
                }
                for r in records
            ],
        }


@router.get("/{server_id}/processes")
async def get_server_processes(server_id: str):
    """获取服务器进程/服务状态"""
    async with async_session() as session:
        # 只取最新一轮的进程状态 (同一台服务器一次采集共用同一 timestamp)
        latest_ts = (
            select(func.max(ProcessStatus.timestamp))
            .where(ProcessStatus.server_id == server_id)
            .scalar_subquery()
        )
        result = await session.execute(
            select(ProcessStatus).where(
                ProcessStatus.server_id == server_id,
                ProcessStatus.timestamp == latest_ts,
            )
        )
        all_procs = result.scalars().all()

        # 按进程名去重，保留最新记录
        seen = {}
        for p in all_procs:
            if p.process_name not in seen:
                seen[p.process_name] = p

        return {
            "server_id": server_id,
            "processes": [
                {
                    "process_name": p.process_name,
                    "pid": p.pid,
                    "is_running": p.is_running,
                    "cpu_percent": p.cpu_percent,
                    "memory_mb": round(p.memory_mb, 1),
                    "memory_percent": p.memory_percent,
                    "port": p.port,
                    "port_open": p.port_open,
                    "is_critical": p.is_critical,
                    "status_detail": p.status_detail,
                    "timestamp": str(p.timestamp),
                }
                for p in seen.values()
            ],
        }
