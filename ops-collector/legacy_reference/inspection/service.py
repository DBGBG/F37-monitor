"""
点检调度服务
- 将点检计划周期转换为 cron 表达式 (支持 每日/每周/每月 及自定义 cron)
- 按周期为每台目标设备生成点检任务 (幂等: 同一计划+设备+计划时间只生成一条)
- 标记逾期任务
"""

import logging
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from apscheduler.triggers.cron import CronTrigger
from sqlalchemy import select, update

from backend.database import async_session
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.inspection import (
    InspectionPlan, InspectionItem, InspectionTask,
)

logger = logging.getLogger(__name__)

TIMEZONE = "Asia/Shanghai"
_TZ = ZoneInfo(TIMEZONE)
_WEEKDAY_NAMES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

# 预置点检模板 (新建计划时可一键载入, 也可自定义增删)
PRESET_TEMPLATES = {
    "switch": {
        "name": "交换机常规点检",
        "items": [
            {"name": "电源指示灯", "check_type": "bool", "standard": "绿灯常亮", "required": True},
            {"name": "系统/告警指示灯", "check_type": "bool", "standard": "无红灯告警", "required": True},
            {"name": "风扇运转", "check_type": "bool", "standard": "运转正常无异响", "required": True},
            {"name": "设备温度", "check_type": "value", "standard": "< 60", "unit": "°C", "required": True},
            {"name": "端口指示灯", "check_type": "bool", "standard": "与业务一致", "required": True},
            {"name": "网线/光纤连接", "check_type": "bool", "standard": "连接牢固无松动", "required": True},
            {"name": "设备清洁除尘", "check_type": "bool", "standard": "无积尘", "required": False},
            {"name": "接地与安装固定", "check_type": "bool", "standard": "接地良好、固定牢靠", "required": False},
        ],
    },
    "server": {
        "name": "服务器常规点检",
        "items": [
            {"name": "电源/运行指示灯", "check_type": "bool", "standard": "正常", "required": True},
            {"name": "风扇/散热", "check_type": "bool", "standard": "运转正常", "required": True},
            {"name": "机房环境温度", "check_type": "value", "standard": "18~27", "unit": "°C", "required": True},
            {"name": "硬盘告警指示灯", "check_type": "bool", "standard": "无红灯", "required": True},
            {"name": "线缆连接", "check_type": "bool", "standard": "连接正常", "required": True},
            {"name": "设备清洁除尘", "check_type": "bool", "standard": "无积尘", "required": False},
        ],
    },
}


def plan_to_cron(schedule_type: str, schedule_value: dict, explicit: str = "") -> str:
    """把计划周期转换为 5 段 cron 表达式"""
    if schedule_type == "cron":
        return (explicit or schedule_value.get("expr", "")).strip()

    time_str = str(schedule_value.get("time", "08:00"))
    parts = time_str.split(":")
    hour = int(parts[0]) if parts and parts[0].isdigit() else 8
    minute = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0

    if schedule_type == "weekly":
        weekdays = schedule_value.get("weekdays") or [1]
        days = ",".join(
            _WEEKDAY_NAMES[(int(d) - 1) % 7] for d in weekdays
        ) or "mon"
        return f"{minute} {hour} * * {days}"

    if schedule_type == "monthly":
        days = schedule_value.get("days") or [1]
        days_str = ",".join(str(int(d)) for d in days) or "1"
        return f"{minute} {hour} {days_str} * *"

    # daily (默认)
    return f"{minute} {hour} * * *"


def iter_occurrences(cron_expr: str, start: datetime, end: datetime) -> list[datetime]:
    """返回 (start, end] 区间内该 cron 的所有触发时间 (本地朴素时间)"""
    if not cron_expr:
        return []
    try:
        trigger = CronTrigger.from_crontab(cron_expr, timezone=TIMEZONE)
    except Exception as e:
        logger.warning(f"无效的 cron 表达式 [{cron_expr}]: {e}")
        return []

    tz_start = start if start.tzinfo else start.replace(tzinfo=_TZ)
    tz_end = end if end.tzinfo else end.replace(tzinfo=_TZ)
    occurrences = []
    current = trigger.get_next_fire_time(None, tz_start)
    guard = 0
    while current is not None and current <= tz_end and guard < 2000:
        occurrences.append(current.astimezone(_TZ).replace(tzinfo=None))
        nxt = trigger.get_next_fire_time(current, current)
        if nxt is None or nxt <= current:
            break
        current = nxt
        guard += 1
    return occurrences


class InspectionService:
    """点检任务生成与状态维护"""

    async def resolve_targets(self, session, plan: InspectionPlan) -> list[dict]:
        """解析计划的目标设备 (返回 [{id,name,type}])"""
        model = SwitchDevice if plan.device_type == "switch" else ServerDevice
        query = select(model).where(model.enabled == True)  # noqa: E712
        if plan.target_scope == "devices" and plan.target_ids:
            query = query.where(model.id.in_(plan.target_ids))
        rows = (await session.execute(query)).scalars().all()
        return [
            {"id": r.id, "name": r.name, "type": plan.device_type}
            for r in rows
        ]

    async def generate_tasks(self, now: datetime | None = None) -> int:
        """为一轮周期生成点检任务, 返回新建任务数"""
        now = now or datetime.now()
        created = 0
        async with async_session() as session:
            plans = (await session.execute(
                select(InspectionPlan).where(InspectionPlan.enabled == True)  # noqa: E712
            )).scalars().all()

            for plan in plans:
                cron_expr = plan.cron_expr or plan_to_cron(
                    plan.schedule_type, plan.schedule_value or {}
                )
                # 从上次生成时间开始, 最多回看 24 小时, 避免重启后大量补单
                start = plan.last_generated_at or now
                if start < now - timedelta(hours=24):
                    start = now - timedelta(hours=24)

                occurrences = iter_occurrences(cron_expr, start, now)
                if not occurrences:
                    plan.last_generated_at = now
                    continue

                items_snapshot = [
                    {
                        "name": it.name,
                        "check_type": it.check_type,
                        "standard": it.standard,
                        "unit": it.unit,
                        "required": it.required,
                    }
                    for it in (await session.execute(
                        select(InspectionItem)
                        .where(InspectionItem.plan_id == plan.id)
                        .order_by(InspectionItem.sort_order, InspectionItem.id)
                    )).scalars().all()
                ]
                targets = await self.resolve_targets(session, plan)

                for occ in occurrences:
                    due = occ + timedelta(hours=max(plan.grace_hours or 12, 1))
                    for tgt in targets:
                        # 幂等: 同一 计划+设备+计划时间 已存在则跳过
                        exists = (await session.execute(
                            select(InspectionTask.id).where(
                                InspectionTask.plan_id == plan.id,
                                InspectionTask.device_id == tgt["id"],
                                InspectionTask.planned_time == occ,
                            )
                        )).scalar_one_or_none()
                        if exists:
                            continue
                        session.add(InspectionTask(
                            plan_id=plan.id,
                            plan_name=plan.name,
                            device_id=tgt["id"],
                            device_name=tgt["name"],
                            device_type=tgt["type"],
                            planned_time=occ,
                            due_time=due,
                            items=items_snapshot,
                            status="pending",
                            assignee=plan.assignee,
                        ))
                        created += 1

                plan.last_generated_at = now

            await session.commit()

        if created:
            logger.info(f"点检任务生成: 新增 {created} 条")
        return created

    async def mark_overdue(self, now: datetime | None = None) -> int:
        """将已过截止时间的未完成任务标记为逾期"""
        now = now or datetime.now()
        async with async_session() as session:
            result = await session.execute(
                update(InspectionTask)
                .where(
                    InspectionTask.status.in_(["pending", "in_progress"]),
                    InspectionTask.due_time < now,
                )
                .values(status="overdue")
            )
            await session.commit()
            count = result.rowcount or 0
        if count:
            logger.info(f"点检任务逾期标记: {count} 条")
        return count

    async def run(self):
        """调度器入口: 标记逾期 + 生成任务"""
        try:
            await self.mark_overdue()
            await self.generate_tasks()
        except Exception as e:
            logger.error(f"点检任务调度异常: {e}", exc_info=True)
