"""设备点检 API - 点检计划 / 任务 / 执行 / 履历"""

import logging
from datetime import datetime, date, time, timedelta

from fastapi import APIRouter, Query, HTTPException
from pydantic import BaseModel
from sqlalchemy import select, func, delete, and_

from apscheduler.triggers.cron import CronTrigger

from backend.database import async_session
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.inspection import (
    InspectionPlan, InspectionItem, InspectionTask, InspectionRecord,
)
from backend.inspection.service import (
    InspectionService, PRESET_TEMPLATES, plan_to_cron, TIMEZONE,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/inspection", tags=["设备点检"])

_service = InspectionService()


# ---------- 请求模型 ----------

class PlanItemModel(BaseModel):
    name: str
    check_type: str = "bool"
    standard: str = ""
    unit: str = ""
    required: bool = True
    sort_order: int = 0


class PlanModel(BaseModel):
    name: str
    description: str = ""
    device_type: str = "switch"
    target_scope: str = "all"
    target_ids: list[str] = []
    schedule_type: str = "daily"
    schedule_value: dict = {}
    cron_expr: str = ""
    assignee: str = ""
    grace_hours: int = 12
    enabled: bool = True
    items: list[PlanItemModel] = []


class RecordIn(BaseModel):
    item_name: str
    check_type: str = "bool"
    standard: str = ""
    value: str = ""
    result: str = "normal"
    remark: str = ""


class ExecuteRequest(BaseModel):
    operator: str = "admin"
    remark: str = ""
    records: list[RecordIn] = []


class SkipRequest(BaseModel):
    operator: str = "admin"
    reason: str = ""


class OperatorRequest(BaseModel):
    operator: str = "admin"


# ---------- 辅助 ----------

def _plan_summary(plan: InspectionPlan, item_count: int, next_run: str | None) -> dict:
    return {
        "id": plan.id,
        "name": plan.name,
        "description": plan.description,
        "device_type": plan.device_type,
        "target_scope": plan.target_scope,
        "target_ids": plan.target_ids or [],
        "schedule_type": plan.schedule_type,
        "schedule_value": plan.schedule_value or {},
        "cron_expr": plan.cron_expr,
        "assignee": plan.assignee,
        "grace_hours": plan.grace_hours,
        "enabled": plan.enabled,
        "item_count": item_count,
        "last_generated_at": str(plan.last_generated_at) if plan.last_generated_at else None,
        "next_run": next_run,
        "created_at": str(plan.created_at) if plan.created_at else None,
    }


def _next_run(plan: InspectionPlan) -> str | None:
    cron_expr = plan.cron_expr or plan_to_cron(plan.schedule_type, plan.schedule_value or {})
    if not cron_expr:
        return None
    try:
        trigger = CronTrigger.from_crontab(cron_expr, timezone=TIMEZONE)
        nxt = trigger.get_next_fire_time(None, datetime.now())
        return str(nxt) if nxt else None
    except Exception:
        return None


async def _load_items(session, plan_id: int) -> list[InspectionItem]:
    return (await session.execute(
        select(InspectionItem)
        .where(InspectionItem.plan_id == plan_id)
        .order_by(InspectionItem.sort_order, InspectionItem.id)
    )).scalars().all()


async def _replace_items(session, plan_id: int, items: list[PlanItemModel]):
    await session.execute(delete(InspectionItem).where(InspectionItem.plan_id == plan_id))
    for idx, it in enumerate(items):
        session.add(InspectionItem(
            plan_id=plan_id,
            name=it.name,
            check_type=it.check_type,
            standard=it.standard,
            unit=it.unit,
            required=it.required,
            sort_order=it.sort_order if it.sort_order else idx,
        ))


def _task_summary(t: InspectionTask) -> dict:
    return {
        "id": t.id,
        "plan_id": t.plan_id,
        "plan_name": t.plan_name,
        "device_id": t.device_id,
        "device_name": t.device_name,
        "device_type": t.device_type,
        "planned_time": str(t.planned_time) if t.planned_time else None,
        "due_time": str(t.due_time) if t.due_time else None,
        "status": t.status,
        "result": t.result,
        "abnormal_count": t.abnormal_count,
        "assignee": t.assignee,
        "operator": t.operator,
        "remark": t.remark,
        "started_at": str(t.started_at) if t.started_at else None,
        "finished_at": str(t.finished_at) if t.finished_at else None,
        "item_count": len(t.items or []),
    }


# ---------- 模板 / 设备选项 ----------

@router.get("/templates")
async def get_templates():
    """预置点检模板"""
    return {"templates": PRESET_TEMPLATES}


@router.get("/devices")
async def get_device_options(device_type: str = Query(None)):
    """目标设备选项 (供选择指定设备)"""
    async with async_session() as session:
        result = []
        if device_type in (None, "switch"):
            rows = (await session.execute(
                select(SwitchDevice).order_by(SwitchDevice.name)
            )).scalars().all()
            result += [
                {"id": r.id, "name": r.name, "ip": r.ip, "type": "switch", "online": r.is_online}
                for r in rows
            ]
        if device_type in (None, "server"):
            rows = (await session.execute(
                select(ServerDevice).order_by(ServerDevice.name)
            )).scalars().all()
            result += [
                {"id": r.id, "name": r.name, "ip": r.ip, "type": "server", "online": r.is_online}
                for r in rows
            ]
    return {"devices": result}


# ---------- 计划 CRUD ----------

@router.get("/plans")
async def list_plans(keyword: str = Query(""), enabled: str = Query(None)):
    async with async_session() as session:
        query = select(InspectionPlan)
        if keyword:
            query = query.where(InspectionPlan.name.ilike(f"%{keyword}%"))
        if enabled is not None and enabled != "":
            query = query.where(InspectionPlan.enabled == (enabled.lower() in ("1", "true")))
        query = query.order_by(InspectionPlan.id.desc())
        plans = (await session.execute(query)).scalars().all()

        items = []
        for p in plans:
            count = (await session.execute(
                select(func.count(InspectionItem.id)).where(InspectionItem.plan_id == p.id)
            )).scalar() or 0
            items.append(_plan_summary(p, count, _next_run(p)))
    return {"total": len(items), "items": items}


@router.get("/plans/{plan_id}")
async def get_plan(plan_id: int):
    async with async_session() as session:
        plan = (await session.execute(
            select(InspectionPlan).where(InspectionPlan.id == plan_id)
        )).scalar_one_or_none()
        if not plan:
            raise HTTPException(status_code=404, detail="计划不存在")
        items = await _load_items(session, plan_id)
        data = _plan_summary(plan, len(items), _next_run(plan))
        data["items"] = [
            {
                "id": it.id, "name": it.name, "check_type": it.check_type,
                "standard": it.standard, "unit": it.unit,
                "required": it.required, "sort_order": it.sort_order,
            }
            for it in items
        ]
        return data


@router.post("/plans")
async def create_plan(req: PlanModel):
    cron_expr = req.cron_expr if req.schedule_type == "cron" else plan_to_cron(
        req.schedule_type, req.schedule_value
    )
    if req.schedule_type == "cron" and not cron_expr:
        raise HTTPException(status_code=400, detail="请提供 cron 表达式")
    async with async_session() as session:
        plan = InspectionPlan(
            name=req.name,
            description=req.description,
            device_type=req.device_type,
            target_scope=req.target_scope,
            target_ids=req.target_ids,
            schedule_type=req.schedule_type,
            schedule_value=req.schedule_value,
            cron_expr=cron_expr,
            assignee=req.assignee,
            grace_hours=req.grace_hours,
            enabled=req.enabled,
            last_generated_at=datetime.now(),
        )
        session.add(plan)
        await session.flush()  # 获取 plan.id
        await _replace_items(session, plan.id, req.items)
        await session.commit()
        logger.info(f"点检计划已创建: {plan.name} (#{plan.id})")
        return {"status": "success", "id": plan.id}


@router.put("/plans/{plan_id}")
async def update_plan(plan_id: int, req: PlanModel):
    cron_expr = req.cron_expr if req.schedule_type == "cron" else plan_to_cron(
        req.schedule_type, req.schedule_value
    )
    async with async_session() as session:
        plan = (await session.execute(
            select(InspectionPlan).where(InspectionPlan.id == plan_id)
        )).scalar_one_or_none()
        if not plan:
            raise HTTPException(status_code=404, detail="计划不存在")
        plan.name = req.name
        plan.description = req.description
        plan.device_type = req.device_type
        plan.target_scope = req.target_scope
        plan.target_ids = req.target_ids
        plan.schedule_type = req.schedule_type
        plan.schedule_value = req.schedule_value
        plan.cron_expr = cron_expr
        plan.assignee = req.assignee
        plan.grace_hours = req.grace_hours
        plan.enabled = req.enabled
        await _replace_items(session, plan_id, req.items)
        await session.commit()
        return {"status": "success"}


@router.delete("/plans/{plan_id}")
async def delete_plan(plan_id: int):
    async with async_session() as session:
        await session.execute(delete(InspectionItem).where(InspectionItem.plan_id == plan_id))
        await session.execute(delete(InspectionPlan).where(InspectionPlan.id == plan_id))
        await session.commit()
    return {"status": "success"}


@router.post("/plans/{plan_id}/toggle")
async def toggle_plan(plan_id: int):
    async with async_session() as session:
        plan = (await session.execute(
            select(InspectionPlan).where(InspectionPlan.id == plan_id)
        )).scalar_one_or_none()
        if not plan:
            raise HTTPException(status_code=404, detail="计划不存在")
        plan.enabled = not plan.enabled
        await session.commit()
        return {"status": "success", "enabled": plan.enabled}


@router.post("/plans/{plan_id}/run-now")
async def run_plan_now(plan_id: int):
    """立即为计划的全部目标设备生成一条点检任务"""
    now = datetime.now()
    async with async_session() as session:
        plan = (await session.execute(
            select(InspectionPlan).where(InspectionPlan.id == plan_id)
        )).scalar_one_or_none()
        if not plan:
            raise HTTPException(status_code=404, detail="计划不存在")
        items = await _load_items(session, plan_id)
        snapshot = [
            {"name": it.name, "check_type": it.check_type, "standard": it.standard,
             "unit": it.unit, "required": it.required}
            for it in items
        ]
        targets = await _service.resolve_targets(session, plan)
        due = now + timedelta(hours=max(plan.grace_hours or 12, 1))
        created = 0
        for tgt in targets:
            session.add(InspectionTask(
                plan_id=plan.id, plan_name=plan.name,
                device_id=tgt["id"], device_name=tgt["name"], device_type=tgt["type"],
                planned_time=now, due_time=due, items=snapshot,
                status="pending", assignee=plan.assignee,
            ))
            created += 1
        await session.commit()
    return {"status": "success", "created": created}


# ---------- 任务 ----------

@router.get("/tasks")
async def list_tasks(
    status: str = Query(""),
    plan_id: int = Query(None),
    device_id: str = Query(""),
    device_type: str = Query(""),
    result: str = Query(""),
    date_from: str = Query(""),
    date_to: str = Query(""),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    async with async_session() as session:
        query = select(InspectionTask)
        if status and status != "all":
            query = query.where(InspectionTask.status == status)
        if plan_id:
            query = query.where(InspectionTask.plan_id == plan_id)
        if device_id:
            query = query.where(InspectionTask.device_id == device_id)
        if device_type:
            query = query.where(InspectionTask.device_type == device_type)
        if result:
            query = query.where(InspectionTask.result == result)
        if date_from:
            query = query.where(InspectionTask.planned_time >= datetime.fromisoformat(date_from))
        if date_to:
            query = query.where(InspectionTask.planned_time <= datetime.fromisoformat(date_to) + timedelta(days=1))

        total = (await session.execute(
            select(func.count()).select_from(query.subquery())
        )).scalar() or 0
        query = query.order_by(InspectionTask.planned_time.desc(), InspectionTask.id.desc())
        query = query.offset((page - 1) * page_size).limit(page_size)
        tasks = (await session.execute(query)).scalars().all()
    return {"total": total, "page": page, "page_size": page_size,
            "items": [_task_summary(t) for t in tasks]}


@router.get("/tasks/{task_id}")
async def get_task(task_id: int):
    async with async_session() as session:
        task = (await session.execute(
            select(InspectionTask).where(InspectionTask.id == task_id)
        )).scalar_one_or_none()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        data = _task_summary(task)
        data["items"] = task.items or []
        records = (await session.execute(
            select(InspectionRecord).where(InspectionRecord.task_id == task_id).order_by(InspectionRecord.id)
        )).scalars().all()
        data["records"] = [
            {"item_name": r.item_name, "check_type": r.check_type, "standard": r.standard,
             "value": r.value, "result": r.result, "remark": r.remark}
            for r in records
        ]
        return data


@router.post("/tasks/{task_id}/start")
async def start_task(task_id: int, req: OperatorRequest):
    async with async_session() as session:
        task = (await session.execute(
            select(InspectionTask).where(InspectionTask.id == task_id)
        )).scalar_one_or_none()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if task.status in ("pending", "overdue"):
            task.status = "in_progress"
            task.started_at = datetime.now()
            if req.operator:
                task.operator = req.operator
            await session.commit()
    return {"status": "success"}


@router.post("/tasks/{task_id}/execute")
async def execute_task(task_id: int, req: ExecuteRequest):
    """提交点检结果, 形成执行明细(履历)"""
    if not req.records:
        raise HTTPException(status_code=400, detail="请至少填写一个检查项结果")
    async with async_session() as session:
        task = (await session.execute(
            select(InspectionTask).where(InspectionTask.id == task_id)
        )).scalar_one_or_none()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if task.status == "completed":
            raise HTTPException(status_code=400, detail="任务已完成")

        abnormal = sum(1 for r in req.records if r.result == "abnormal")
        for r in req.records:
            session.add(InspectionRecord(
                task_id=task_id,
                item_name=r.item_name,
                check_type=r.check_type,
                standard=r.standard,
                value=r.value,
                result=r.result,
                remark=r.remark,
            ))
        task.status = "completed"
        task.result = "abnormal" if abnormal else "normal"
        task.abnormal_count = abnormal
        task.operator = req.operator
        task.remark = req.remark
        task.finished_at = datetime.now()
        if not task.started_at:
            task.started_at = task.finished_at
        await session.commit()
        logger.info(f"点检完成: 任务#{task_id} 结果={task.result} 异常{abnormal}项")
        return {"status": "success", "result": task.result, "abnormal_count": abnormal}


@router.post("/tasks/{task_id}/skip")
async def skip_task(task_id: int, req: SkipRequest):
    async with async_session() as session:
        task = (await session.execute(
            select(InspectionTask).where(InspectionTask.id == task_id)
        )).scalar_one_or_none()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        task.status = "skipped"
        task.operator = req.operator
        task.remark = req.reason
        task.finished_at = datetime.now()
        await session.commit()
    return {"status": "success"}


# ---------- 履历 / 统计 ----------

@router.get("/history")
async def get_history(
    device_id: str = Query(""),
    plan_id: int = Query(None),
    result: str = Query(""),
    date_from: str = Query(""),
    date_to: str = Query(""),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    """点检履历: 已执行(完成/跳过)的任务及明细"""
    async with async_session() as session:
        query = select(InspectionTask).where(
            InspectionTask.status.in_(["completed", "skipped"])
        )
        if device_id:
            query = query.where(InspectionTask.device_id == device_id)
        if plan_id:
            query = query.where(InspectionTask.plan_id == plan_id)
        if result:
            query = query.where(InspectionTask.result == result)
        if date_from:
            query = query.where(InspectionTask.finished_at >= datetime.fromisoformat(date_from))
        if date_to:
            query = query.where(InspectionTask.finished_at <= datetime.fromisoformat(date_to) + timedelta(days=1))

        total = (await session.execute(
            select(func.count()).select_from(query.subquery())
        )).scalar() or 0
        query = query.order_by(InspectionTask.finished_at.desc())
        query = query.offset((page - 1) * page_size).limit(page_size)
        tasks = (await session.execute(query)).scalars().all()

        items = []
        for t in tasks:
            recs = (await session.execute(
                select(InspectionRecord).where(InspectionRecord.task_id == t.id).order_by(InspectionRecord.id)
            )).scalars().all()
            summary = _task_summary(t)
            summary["records"] = [
                {"item_name": r.item_name, "check_type": r.check_type, "standard": r.standard,
                 "value": r.value, "result": r.result, "remark": r.remark}
                for r in recs
            ]
            items.append(summary)
    return {"total": total, "page": page, "page_size": page_size, "items": items}


@router.get("/stats")
async def get_stats():
    """点检概览统计"""
    now = datetime.now()
    today_start = datetime.combine(date.today(), time.min)

    async with async_session() as session:
        async def count_where(*conds):
            q = select(func.count(InspectionTask.id))
            for c in conds:
                q = q.where(c)
            return (await session.execute(q)).scalar() or 0

        plans_total = (await session.execute(select(func.count(InspectionPlan.id)))).scalar() or 0
        plans_enabled = (await session.execute(
            select(func.count(InspectionPlan.id)).where(InspectionPlan.enabled == True)  # noqa: E712
        )).scalar() or 0

        pending = await count_where(InspectionTask.status == "pending")
        in_progress = await count_where(InspectionTask.status == "in_progress")
        overdue = await count_where(InspectionTask.status == "overdue")
        completed = await count_where(InspectionTask.status == "completed")
        skipped = await count_where(InspectionTask.status == "skipped")
        completed_today = await count_where(
            InspectionTask.status == "completed",
            InspectionTask.finished_at >= today_start,
        )
        abnormal_today = await count_where(
            InspectionTask.status == "completed",
            InspectionTask.result == "abnormal",
            InspectionTask.finished_at >= today_start,
        )

    todo = pending + in_progress + overdue
    return {
        "plans_total": plans_total,
        "plans_enabled": plans_enabled,
        "tasks": {
            "pending": pending,
            "in_progress": in_progress,
            "overdue": overdue,
            "completed": completed,
            "skipped": skipped,
            "todo": todo,
        },
        "completed_today": completed_today,
        "abnormal_today": abnormal_today,
        "completion_rate": round(completed / (completed + overdue) * 100, 1) if (completed + overdue) > 0 else 100.0,
    }
