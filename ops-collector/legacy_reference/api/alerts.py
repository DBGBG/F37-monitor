"""告警相关 API 路由"""

from datetime import datetime, timedelta

from fastapi import APIRouter, Query
from pydantic import BaseModel
from sqlalchemy import select, func, and_
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import async_session
from backend.models.alert import AlertRecord
from backend.alerts.alert_engine import AlertEngine

router = APIRouter(prefix="/api/alerts", tags=["告警管理"])

alert_engine = AlertEngine()


class AcknowledgeRequest(BaseModel):
    alert_id: str
    acknowledged_by: str = "admin"


class ResolveRequest(BaseModel):
    alert_id: str
    resolved_by: str = "admin"


@router.get("/summary")
async def get_alert_summary():
    """获取告警总览统计"""
    async with async_session() as session:
        # 各状态计数
        active_result = await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "active")
        )
        active = active_result.scalar() or 0

        ack_result = await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "acknowledged")
        )
        acknowledged = ack_result.scalar() or 0

        resolved_result = await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "resolved")
        )
        resolved = resolved_result.scalar() or 0

        # 各级别计数
        critical_result = await session.execute(
            select(func.count(AlertRecord.id)).where(
                and_(AlertRecord.status == "active", AlertRecord.severity == "CRITICAL")
            )
        )
        critical = critical_result.scalar() or 0

        warning_result = await session.execute(
            select(func.count(AlertRecord.id)).where(
                and_(AlertRecord.status == "active", AlertRecord.severity == "WARNING")
            )
        )
        warning = warning_result.scalar() or 0

        # 最近 24 小时新增
        yesterday = datetime.now() - timedelta(hours=24)
        recent_result = await session.execute(
            select(func.count(AlertRecord.id)).where(
                AlertRecord.triggered_at >= yesterday
            )
        )
        recent_24h = recent_result.scalar() or 0

    return {
        "active": active,
        "acknowledged": acknowledged,
        "resolved": resolved,
        "total": active + acknowledged + resolved,
        "critical": critical,
        "warning": warning,
        "recent_24h": recent_24h,
    }


@router.get("/list")
async def get_alert_list(
    status: str = Query("active", description="状态: active/acknowledged/resolved/all"),
    severity: str = Query(None, description="级别: INFO/WARNING/CRITICAL"),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    """获取告警列表"""
    async with async_session() as session:
        query = select(AlertRecord)

        if status != "all":
            query = query.where(AlertRecord.status == status)
        if severity:
            query = query.where(AlertRecord.severity == severity)

        count_result = await session.execute(
            select(func.count()).select_from(query.subquery())
        )
        total = count_result.scalar() or 0

        query = query.order_by(AlertRecord.triggered_at.desc())
        query = query.offset((page - 1) * page_size).limit(page_size)
        result = await session.execute(query)
        alerts = result.scalars().all()

        return {
            "total": total,
            "page": page,
            "page_size": page_size,
            "items": [
                {
                    "alert_id": a.alert_id,
                    "device_id": a.device_id,
                    "device_name": a.device_name,
                    "device_type": a.device_type,
                    "rule_name": a.rule_name,
                    "severity": a.severity,
                    "title": a.title,
                    "description": a.description,
                    "metric_value": a.metric_value,
                    "threshold": a.threshold,
                    "status": a.status,
                    "acknowledged_by": a.acknowledged_by,
                    "notified": a.notified,
                    "triggered_at": str(a.triggered_at),
                    "resolved_at": str(a.resolved_at) if a.resolved_at else None,
                }
                for a in alerts
            ],
        }


@router.post("/acknowledge")
async def acknowledge_alert(req: AcknowledgeRequest):
    """确认告警"""
    await alert_engine.acknowledge_alert(req.alert_id, req.acknowledged_by)
    return {"status": "success"}


@router.post("/resolve")
async def resolve_alert(req: ResolveRequest):
    """标记告警为已恢复"""
    await alert_engine.resolve_alert(req.alert_id, req.resolved_by)
    return {"status": "success"}
