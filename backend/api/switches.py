"""交换机相关 API 路由"""

from datetime import datetime, timedelta
from typing import Optional

from fastapi import APIRouter, Query, Depends
from pydantic import BaseModel
from sqlalchemy import select, func, and_, desc
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_session, async_session
from backend.models.device import SwitchDevice
from backend.models.port_status import PortStatus, PortTrafficStats
from backend.models.hardware_health import HardwareHealth
from backend.config import config

router = APIRouter(prefix="/api/switches", tags=["交换机管理"])


# ---- 数据模型 ----

class SwitchSummary(BaseModel):
    total: int
    online: int
    offline: int
    online_rate: float


# ---- 接口 ----

@router.get("/summary")
async def get_switch_summary():
    """获取交换机总览统计"""
    async with async_session() as session:
        total = (await session.execute(
            select(func.count(SwitchDevice.id))
        )).scalar() or 0

        online = (await session.execute(
            select(func.count(SwitchDevice.id)).where(SwitchDevice.is_online == True)
        )).scalar() or 0

    offline = total - online
    return {
        "total": total,
        "online": online,
        "offline": offline,
        "online_rate": round(online / total * 100, 1) if total > 0 else 0,
    }


@router.get("/list")
async def get_switch_list(
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
    keyword: str = Query("", description="搜索关键词"),
    online_only: bool = Query(False, description="仅显示在线设备"),
):
    """获取交换机列表 (分页) - 包含最新温度数据"""
    async with async_session() as session:
        query = select(SwitchDevice)

        if keyword:
            query = query.where(
                SwitchDevice.name.ilike(f"%{keyword}%") |
                SwitchDevice.ip.ilike(f"%{keyword}%") |
                SwitchDevice.location.ilike(f"%{keyword}%")
            )
        if online_only:
            query = query.where(SwitchDevice.is_online == True)

        # 总数
        count_query = select(func.count()).select_from(query.subquery())
        total_result = await session.execute(count_query)
        total = total_result.scalar() or 0

        # 分页数据
        query = query.order_by(SwitchDevice.is_online.desc(), SwitchDevice.id)
        query = query.offset((page - 1) * page_size).limit(page_size)
        result = await session.execute(query)
        devices = result.scalars().all()

        # 获取每台交换机的最新温度 (单次窗口函数查询, 避免 N+1)
        device_ids = [d.id for d in devices]
        temp_map = {}
        if device_ids:
            row_num = func.row_number().over(
                partition_by=HardwareHealth.switch_id,
                order_by=desc(HardwareHealth.timestamp),
            ).label("rn")
            subq = select(
                HardwareHealth.switch_id.label("sid"),
                HardwareHealth.temperature.label("temp"),
                row_num,
            ).where(HardwareHealth.switch_id.in_(device_ids)).subquery()
            rows = await session.execute(
                select(subq.c.sid, subq.c.temp).where(subq.c.rn == 1)
            )
            for sid, temp in rows.all():
                if temp is not None:
                    temp_map[sid] = temp

        items = []
        for d in devices:
            items.append({
                "id": d.id,
                "name": d.name,
                "ip": d.ip,
                "model": d.model,
                "location": d.location,
                "is_online": d.is_online,
                "total_ports": d.total_ports,
                "uptime_seconds": d.uptime_seconds,
                "firmware_version": d.firmware_version,
                "temperature": temp_map.get(d.id),
                "last_poll_time": str(d.last_poll_time) if d.last_poll_time else None,
            })

        return {"total": total, "page": page, "page_size": page_size, "items": items}


@router.get("/{switch_id}/detail")
async def get_switch_detail(switch_id: str):
    """获取交换机详情"""
    async with async_session() as session:
        result = await session.execute(
            select(SwitchDevice).where(SwitchDevice.id == switch_id)
        )
        device = result.scalar_one_or_none()
        if not device:
            return {"error": "设备不存在"}

        return {
            "id": device.id,
            "name": device.name,
            "ip": device.ip,
            "model": device.model,
            "location": device.location,
            "firmware_version": device.firmware_version,
            "serial_number": device.serial_number,
            "is_online": device.is_online,
            "total_ports": device.total_ports,
            "uptime_seconds": device.uptime_seconds,
            "last_poll_time": str(device.last_poll_time) if device.last_poll_time else None,
        }


@router.get("/{switch_id}/ports")
async def get_switch_ports(switch_id: str):
    """获取交换机端口状态列表"""
    async with async_session() as session:
        result = await session.execute(
            select(PortStatus)
            .where(PortStatus.switch_id == switch_id)
            .order_by(PortStatus.port_index)
        )
        ports = result.scalars().all()

        return {
            "switch_id": switch_id,
            "ports": [
                {
                    "port_index": p.port_index,
                    "port_name": p.port_name,
                    "admin_status": p.admin_status,
                    "oper_status": p.oper_status,
                    "speed_bps": p.speed_bps,
                    "vlan_id": p.vlan_id,
                    "port_mode": p.port_mode,
                    "is_up": p.oper_status == 1,
                    "updated_at": str(p.updated_at) if p.updated_at else None,
                }
                for p in ports
            ],
        }


@router.get("/{switch_id}/traffic")
async def get_switch_traffic(
    switch_id: str,
    port_index: int = Query(..., description="端口索引"),
    hours: int = Query(1, ge=1, le=24, description="查询时间范围(小时)"),
):
    """获取端口流量趋势数据"""
    start_time = datetime.now() - timedelta(hours=hours)

    async with async_session() as session:
        result = await session.execute(
            select(PortTrafficStats)
            .where(
                and_(
                    PortTrafficStats.switch_id == switch_id,
                    PortTrafficStats.port_index == port_index,
                    PortTrafficStats.timestamp >= start_time,
                )
            )
            .order_by(PortTrafficStats.timestamp)
        )
        stats = result.scalars().all()

        return {
            "switch_id": switch_id,
            "port_index": port_index,
            "data": [
                {
                    "timestamp": str(s.timestamp),
                    "in_rate_bps": s.in_rate_bps,
                    "out_rate_bps": s.out_rate_bps,
                    "in_errors": s.in_errors,
                    "out_errors": s.out_errors,
                    "in_error_rate": s.in_error_rate,
                    "out_error_rate": s.out_error_rate,
                }
                for s in stats
            ],
        }


@router.get("/{switch_id}/hardware")
async def get_switch_hardware(switch_id: str, hours: int = Query(1, ge=1, le=24)):
    """获取硬件健康历史"""
    start_time = datetime.now() - timedelta(hours=hours)

    async with async_session() as session:
        result = await session.execute(
            select(HardwareHealth)
            .where(
                and_(
                    HardwareHealth.switch_id == switch_id,
                    HardwareHealth.timestamp >= start_time,
                )
            )
            .order_by(HardwareHealth.timestamp)
        )
        records = result.scalars().all()

        return {
            "switch_id": switch_id,
            "data": [
                {
                    "timestamp": str(r.timestamp),
                    "power1_status": r.power1_status,
                    "power2_status": r.power2_status,
                    "temperature": r.temperature,
                    "temperature_alarm": r.temperature_alarm,
                    "overall_status": r.overall_status,
                }
                for r in records
            ],
        }
