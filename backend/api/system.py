"""系统级 API 路由 - 健康自监控与数据维护"""

import logging
from pathlib import Path

from fastapi import APIRouter
from pydantic import BaseModel
from sqlalchemy import select, func

from backend.config import config, DATA_DIR
from backend.database import async_session
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.port_status import PortStatus, PortTrafficStats
from backend.models.hardware_health import HardwareHealth
from backend.models.server_metrics import ServerMetrics, ProcessStatus
from backend.models.alert import AlertRecord
from backend.maintenance.cleanup import DataMaintenance

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/system", tags=["系统自监控"])

_maintenance = DataMaintenance()


def _file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


async def _count(session, model) -> int:
    return (await session.execute(select(func.count(model.id)))).scalar() or 0


@router.get("/health")
async def get_health():
    """系统健康自监控: 数据库规模 / 各类数据新鲜度 / 设备在线率"""
    db_path = DATA_DIR / "moxa_monitor.db"
    db_size = _file_size(db_path)
    wal_size = _file_size(Path(str(db_path) + "-wal"))

    async with async_session() as session:
        sw_total = await _count(session, SwitchDevice)
        sw_online = (await session.execute(
            select(func.count(SwitchDevice.id)).where(SwitchDevice.is_online == True)
        )).scalar() or 0
        srv_total = await _count(session, ServerDevice)
        srv_online = (await session.execute(
            select(func.count(ServerDevice.id)).where(ServerDevice.is_online == True)
        )).scalar() or 0
        active_alerts = (await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "active")
        )).scalar() or 0

        table_rows = {
            "port_status": await _count(session, PortStatus),
            "port_traffic_stats": await _count(session, PortTrafficStats),
            "hardware_health": await _count(session, HardwareHealth),
            "server_metrics": await _count(session, ServerMetrics),
            "process_status": await _count(session, ProcessStatus),
            "alert_records": await _count(session, AlertRecord),
        }

        last_poll_sw = (await session.execute(
            select(func.max(SwitchDevice.last_poll_time))
        )).scalar()
        last_poll_srv = (await session.execute(
            select(func.max(ServerDevice.last_poll_time))
        )).scalar()
        last_traffic = (await session.execute(
            select(func.max(PortTrafficStats.timestamp))
        )).scalar()
        last_hw = (await session.execute(
            select(func.max(HardwareHealth.timestamp))
        )).scalar()
        last_metrics = (await session.execute(
            select(func.max(ServerMetrics.timestamp))
        )).scalar()

    return {
        "status": "ok",
        "database": {
            "size_bytes": db_size,
            "wal_size_bytes": wal_size,
            "total_size_mb": round((db_size + wal_size) / (1024 * 1024), 2),
        },
        "devices": {
            "switches": {"total": sw_total, "online": sw_online},
            "servers": {"total": srv_total, "online": srv_online},
        },
        "alerts": {"active": active_alerts},
        "table_rows": table_rows,
        "freshness": {
            "switch_last_poll": str(last_poll_sw) if last_poll_sw else None,
            "server_last_poll": str(last_poll_srv) if last_poll_srv else None,
            "traffic_last": str(last_traffic) if last_traffic else None,
            "hardware_last": str(last_hw) if last_hw else None,
            "server_metrics_last": str(last_metrics) if last_metrics else None,
        },
    }


class CleanupRequest(BaseModel):
    metrics_days: int | None = None
    alerts_days: int | None = None
    checkpoint: bool = True


@router.post("/maintenance/cleanup")
async def run_cleanup(req: CleanupRequest | None = None):
    """手动触发数据清理 (默认按 settings.yaml 的 retention 配置)"""
    req = req or CleanupRequest()
    summary = await _maintenance.run_cleanup(
        metrics_days=req.metrics_days,
        alerts_days=req.alerts_days,
        checkpoint=req.checkpoint,
    )
    return {"status": "success", "summary": summary}


@router.get("/maintenance/stats")
async def maintenance_stats():
    """各时序表最早/最新时间与行数, 用于评估保留策略"""
    async with async_session() as session:
        async def span(model):
            rows = await _count(session, model)
            oldest = (await session.execute(select(func.min(model.timestamp)))).scalar()
            newest = (await session.execute(select(func.max(model.timestamp)))).scalar()
            return {
                "rows": rows,
                "oldest": str(oldest) if oldest else None,
                "newest": str(newest) if newest else None,
            }

        return {
            "port_traffic_stats": await span(PortTrafficStats),
            "hardware_health": await span(HardwareHealth),
            "server_metrics": await span(ServerMetrics),
            "process_status": await span(ProcessStatus),
            "retention": config.retention,
        }
