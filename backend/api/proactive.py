"""主动运维 API 路由 - 健康评分 / 异常中心 / 维护窗口 / 预防性建议"""

import logging
from datetime import datetime

from fastapi import APIRouter, Query, HTTPException
from pydantic import BaseModel
from sqlalchemy import select, func, desc, and_

from backend.database import async_session
from backend.models.proactive import (
    MetricBaseline, AnomalyEvent, DeviceHealth, MaintenanceWindow,
)
from backend.proactive.engine import ProactiveEngine
from backend.proactive.windows import MaintenanceService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/proactive", tags=["主动运维"])

_maintenance = MaintenanceService(ttl_seconds=5)
_recommend = ProactiveEngine.build_recommendations


# ---------- 总览 / 健康 ----------

@router.get("/overview")
async def get_overview():
    """主动运维总览: 健康分布 / 异常统计 / 最需关注设备 / 最近异常"""
    async with async_session() as session:
        # 健康等级分布
        grade_rows = (await session.execute(
            select(DeviceHealth.grade, func.count()).group_by(DeviceHealth.grade)
        )).all()
        grade_dist = {g: c for g, c in grade_rows}
        analyzed = sum(grade_dist.values())
        avg_score = (await session.execute(select(func.avg(DeviceHealth.score)))).scalar()

        # 未处理异常统计
        open_status = ["open", "acknowledged"]
        open_total = (await session.execute(
            select(func.count(AnomalyEvent.id)).where(AnomalyEvent.status.in_(open_status))
        )).scalar() or 0
        sev_rows = (await session.execute(
            select(AnomalyEvent.severity, func.count())
            .where(AnomalyEvent.status.in_(open_status))
            .group_by(AnomalyEvent.severity)
        )).all()
        sev_dist = {s: c for s, c in sev_rows}

        # 最需关注设备 (健康分最低)
        worst_rows = (await session.execute(
            select(DeviceHealth).order_by(DeviceHealth.score.asc()).limit(8)
        )).scalars().all()
        worst = [
            {
                "device_id": h.device_id, "device_name": h.device_name,
                "device_type": h.device_type, "score": h.score, "grade": h.grade,
                "summary": h.summary,
            }
            for h in worst_rows
        ]

        # 最近异常
        recent_rows = (await session.execute(
            select(AnomalyEvent).order_by(AnomalyEvent.last_seen_at.desc()).limit(10)
        )).scalars().all()
        recent = [_event_dict(e) for e in recent_rows]

    active_windows = await _maintenance.current_windows()

    return {
        "generated_at": str(datetime.now()),
        "health": {
            "analyzed": analyzed,
            "avg_score": round(float(avg_score), 1) if avg_score is not None else None,
            "distribution": {
                "优": grade_dist.get("优", 0), "良": grade_dist.get("良", 0),
                "中": grade_dist.get("中", 0), "差": grade_dist.get("差", 0),
            },
        },
        "anomalies": {"open": open_total, "by_severity": sev_dist},
        "maintenance": {"active_windows": len(active_windows), "windows": active_windows},
        "worst_devices": worst,
        "recent_anomalies": recent,
    }


def _event_dict(e: AnomalyEvent) -> dict:
    return {
        "id": e.id, "device_id": e.device_id, "device_name": e.device_name,
        "device_type": e.device_type, "metric": e.metric, "severity": e.severity,
        "title": e.title, "description": e.description, "value": e.value,
        "baseline": e.baseline, "deviation": e.deviation, "score": round(e.score, 2),
        "unit": e.unit, "status": e.status, "escalated": e.escalated,
        "occurred_at": str(e.occurred_at),
        "last_seen_at": str(e.last_seen_at),
        "resolved_at": str(e.resolved_at) if e.resolved_at else None,
        "acknowledged_by": e.acknowledged_by,
    }


@router.get("/health")
async def list_health(
    device_type: str = Query(""),
    grade: str = Query(""),
    keyword: str = Query(""),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    """设备健康评分列表 (按分数升序, 最差的排最前)"""
    async with async_session() as session:
        query = select(DeviceHealth)
        if device_type:
            query = query.where(DeviceHealth.device_type == device_type)
        if grade:
            query = query.where(DeviceHealth.grade == grade)
        if keyword:
            query = query.where(
                DeviceHealth.device_name.ilike(f"%{keyword}%") |
                DeviceHealth.device_id.ilike(f"%{keyword}%")
            )
        total = (await session.execute(
            select(func.count()).select_from(query.subquery())
        )).scalar() or 0
        rows = (await session.execute(
            query.order_by(DeviceHealth.score.asc()).offset((page - 1) * page_size).limit(page_size)
        )).scalars().all()
        items = [
            {
                "device_id": h.device_id, "device_name": h.device_name,
                "device_type": h.device_type, "score": h.score, "grade": h.grade,
                "factors": h.factors or [], "summary": h.summary,
                "updated_at": str(h.updated_at) if h.updated_at else None,
            }
            for h in rows
        ]
    return {"total": total, "page": page, "page_size": page_size, "items": items}


@router.get("/health/{device_id}")
async def get_device_health_detail(device_id: str):
    """单设备健康详情: 评分 + 扣分因子 + 基线 + 未处理异常"""
    async with async_session() as session:
        h = (await session.execute(
            select(DeviceHealth).where(DeviceHealth.device_id == device_id)
        )).scalar_one_or_none()
        baselines = (await session.execute(
            select(MetricBaseline).where(MetricBaseline.device_id == device_id)
        )).scalars().all()
        anomalies = (await session.execute(
            select(AnomalyEvent)
            .where(and_(AnomalyEvent.device_id == device_id,
                        AnomalyEvent.status.in_(["open", "acknowledged"])))
            .order_by(AnomalyEvent.last_seen_at.desc())
        )).scalars().all()
    return {
        "device_id": device_id,
        "health": {
            "score": h.score, "grade": h.grade, "factors": h.factors or [],
            "summary": h.summary, "device_name": h.device_name, "device_type": h.device_type,
        } if h else None,
        "baselines": [
            {
                "metric": b.metric, "mean": round(b.mean, 3),
                "std": round((b.variance or 0) ** 0.5, 3),
                "last_value": round(b.last_value, 3), "sample_count": b.sample_count,
                "updated_at": str(b.updated_at) if b.updated_at else None,
            }
            for b in baselines
        ],
        "anomalies": [_event_dict(e) for e in anomalies],
    }


# ---------- 异常中心 ----------

@router.get("/anomalies")
async def list_anomalies(
    status: str = Query("open", description="open/acknowledged/resolved/all"),
    severity: str = Query(""),
    device_type: str = Query(""),
    device_id: str = Query(""),
    metric: str = Query(""),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    async with async_session() as session:
        query = select(AnomalyEvent)
        if status and status != "all":
            query = query.where(AnomalyEvent.status == status)
        if severity:
            query = query.where(AnomalyEvent.severity == severity)
        if device_type:
            query = query.where(AnomalyEvent.device_type == device_type)
        if device_id:
            query = query.where(AnomalyEvent.device_id == device_id)
        if metric:
            query = query.where(AnomalyEvent.metric == metric)
        total = (await session.execute(
            select(func.count()).select_from(query.subquery())
        )).scalar() or 0
        rows = (await session.execute(
            query.order_by(desc(AnomalyEvent.last_seen_at))
            .offset((page - 1) * page_size).limit(page_size)
        )).scalars().all()
        items = [_event_dict(e) for e in rows]
    return {"total": total, "page": page, "page_size": page_size, "items": items}


class ActorIn(BaseModel):
    by: str = "admin"


@router.post("/anomalies/{event_id}/acknowledge")
async def acknowledge_anomaly(event_id: int, req: ActorIn | None = None):
    req = req or ActorIn()
    async with async_session() as session:
        e = (await session.execute(
            select(AnomalyEvent).where(AnomalyEvent.id == event_id)
        )).scalar_one_or_none()
        if not e:
            raise HTTPException(status_code=404, detail="异常不存在")
        if e.status == "open":
            e.status = "acknowledged"
            e.acknowledged_by = req.by
            await session.commit()
    return {"status": "success"}


@router.post("/anomalies/{event_id}/resolve")
async def resolve_anomaly(event_id: int, req: ActorIn | None = None):
    req = req or ActorIn()
    async with async_session() as session:
        e = (await session.execute(
            select(AnomalyEvent).where(AnomalyEvent.id == event_id)
        )).scalar_one_or_none()
        if not e:
            raise HTTPException(status_code=404, detail="异常不存在")
        e.status = "resolved"
        e.resolved_at = datetime.now()
        e.acknowledged_by = e.acknowledged_by or req.by
        await session.commit()
    return {"status": "success"}


# ---------- 预防性维护建议 ----------

@router.get("/recommendations")
async def get_recommendations():
    """基于未处理异常生成可执行的预防性维护建议"""
    async with async_session() as session:
        rows = (await session.execute(
            select(AnomalyEvent)
            .where(AnomalyEvent.status.in_(["open", "acknowledged"]))
            .order_by(desc(AnomalyEvent.last_seen_at))
        )).scalars().all()
    return {"total": len(rows), "items": _recommend(rows)}


# ---------- 基线 ----------

@router.get("/baselines")
async def list_baselines(
    device_id: str = Query(""),
    metric: str = Query(""),
    page: int = Query(1, ge=1),
    page_size: int = Query(100, ge=1, le=500),
):
    async with async_session() as session:
        query = select(MetricBaseline)
        if device_id:
            query = query.where(MetricBaseline.device_id == device_id)
        if metric:
            query = query.where(MetricBaseline.metric == metric)
        total = (await session.execute(
            select(func.count()).select_from(query.subquery())
        )).scalar() or 0
        rows = (await session.execute(
            query.order_by(MetricBaseline.device_id, MetricBaseline.metric)
            .offset((page - 1) * page_size).limit(page_size)
        )).scalars().all()
        items = [
            {
                "device_id": b.device_id, "device_type": b.device_type, "metric": b.metric,
                "mean": round(b.mean, 3), "std": round((b.variance or 0) ** 0.5, 3),
                "last_value": round(b.last_value, 3), "sample_count": b.sample_count,
                "updated_at": str(b.updated_at) if b.updated_at else None,
            }
            for b in rows
        ]
    return {"total": total, "page": page, "page_size": page_size, "items": items}


@router.post("/analyze")
async def run_analyze_now():
    """立即执行一次主动分析"""
    stats = await ProactiveEngine().analyze()
    return {"status": "success", "stats": stats}


# ---------- 维护窗口 ----------

class WindowIn(BaseModel):
    name: str
    device_type: str = "all"
    device_ids: list[str] = []
    start_time: str
    end_time: str
    reason: str = ""
    enabled: bool = True
    created_by: str = "admin"


def _parse_dt(s: str) -> datetime:
    try:
        return datetime.fromisoformat(s.replace("Z", "").strip())
    except ValueError:
        raise HTTPException(status_code=400, detail=f"时间格式无效: {s}")


@router.get("/maintenance/windows")
async def list_windows():
    async with async_session() as session:
        rows = (await session.execute(
            select(MaintenanceWindow).order_by(desc(MaintenanceWindow.start_time))
        )).scalars().all()
    now = datetime.now()
    return {
        "items": [
            {
                "id": w.id, "name": w.name, "device_type": w.device_type,
                "device_ids": w.device_ids or [], "start_time": str(w.start_time),
                "end_time": str(w.end_time), "reason": w.reason, "enabled": w.enabled,
                "created_by": w.created_by, "created_at": str(w.created_at),
                "active": bool(w.enabled and w.start_time <= now <= w.end_time),
            }
            for w in rows
        ]
    }


@router.post("/maintenance/windows")
async def create_window(req: WindowIn):
    start = _parse_dt(req.start_time)
    end = _parse_dt(req.end_time)
    if end <= start:
        raise HTTPException(status_code=400, detail="结束时间必须晚于开始时间")
    async with async_session() as session:
        w = MaintenanceWindow(
            name=req.name, device_type=req.device_type, device_ids=req.device_ids,
            start_time=start, end_time=end, reason=req.reason,
            enabled=req.enabled, created_by=req.created_by,
        )
        session.add(w)
        await session.commit()
        await session.refresh(w)
        wid = w.id
    await _maintenance.refresh()
    return {"status": "success", "id": wid}


@router.put("/maintenance/windows/{window_id}")
async def update_window(window_id: int, req: WindowIn):
    start = _parse_dt(req.start_time)
    end = _parse_dt(req.end_time)
    if end <= start:
        raise HTTPException(status_code=400, detail="结束时间必须晚于开始时间")
    async with async_session() as session:
        w = (await session.execute(
            select(MaintenanceWindow).where(MaintenanceWindow.id == window_id)
        )).scalar_one_or_none()
        if not w:
            raise HTTPException(status_code=404, detail="窗口不存在")
        w.name, w.device_type, w.device_ids = req.name, req.device_type, req.device_ids
        w.start_time, w.end_time, w.reason = start, end, req.reason
        w.enabled = req.enabled
        await session.commit()
    await _maintenance.refresh()
    return {"status": "success"}


@router.post("/maintenance/windows/{window_id}/toggle")
async def toggle_window(window_id: int):
    async with async_session() as session:
        w = (await session.execute(
            select(MaintenanceWindow).where(MaintenanceWindow.id == window_id)
        )).scalar_one_or_none()
        if not w:
            raise HTTPException(status_code=404, detail="窗口不存在")
        w.enabled = not w.enabled
        await session.commit()
        enabled = w.enabled
    await _maintenance.refresh()
    return {"status": "success", "enabled": enabled}


@router.delete("/maintenance/windows/{window_id}")
async def delete_window(window_id: int):
    async with async_session() as session:
        w = (await session.execute(
            select(MaintenanceWindow).where(MaintenanceWindow.id == window_id)
        )).scalar_one_or_none()
        if not w:
            raise HTTPException(status_code=404, detail="窗口不存在")
        await session.delete(w)
        await session.commit()
    await _maintenance.refresh()
    return {"status": "success"}
