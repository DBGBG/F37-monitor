"""设备点检模块模型 - 点检计划 / 计划项 / 点检任务 / 执行明细(履历)"""

from datetime import datetime
from sqlalchemy import (
    String, Integer, Boolean, DateTime, JSON, ForeignKey, UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class InspectionPlan(Base):
    """点检计划 - 定义对哪些设备、按什么周期、检查哪些项目"""
    __tablename__ = "inspection_plans"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(200), comment="计划名称")
    description: Mapped[str] = mapped_column(String(500), default="", comment="说明")

    # 目标设备
    device_type: Mapped[str] = mapped_column(String(20), default="switch", comment="设备类型: switch/server")
    target_scope: Mapped[str] = mapped_column(String(20), default="all", comment="范围: all=全部, devices=指定设备")
    target_ids: Mapped[list] = mapped_column(JSON, default=list, comment="指定设备ID列表")

    # 周期
    schedule_type: Mapped[str] = mapped_column(String(20), default="daily", comment="周期类型: daily/weekly/monthly/cron")
    schedule_value: Mapped[dict] = mapped_column(JSON, default=dict, comment="周期参数(时刻/星期/日期)")
    cron_expr: Mapped[str] = mapped_column(String(100), default="", comment="cron 表达式(由周期转换或显式指定)")

    assignee: Mapped[str] = mapped_column(String(100), default="", comment="默认执行人")
    grace_hours: Mapped[int] = mapped_column(Integer, default=12, comment="完成时限(小时)")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    last_generated_at: Mapped[datetime | None] = mapped_column(DateTime, comment="上次生成任务时间")

    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")


class InspectionItem(Base):
    """点检计划项 - 计划的检查项模板"""
    __tablename__ = "inspection_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    plan_id: Mapped[int] = mapped_column(Integer, ForeignKey("inspection_plans.id"), index=True, comment="所属计划")
    name: Mapped[str] = mapped_column(String(200), comment="检查项名称")
    check_type: Mapped[str] = mapped_column(String(20), default="bool", comment="类型: bool=正常/异常, value=数值, text=文本")
    standard: Mapped[str] = mapped_column(String(200), default="", comment="标准/参考值")
    unit: Mapped[str] = mapped_column(String(20), default="", comment="单位")
    required: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否必填")
    sort_order: Mapped[int] = mapped_column(Integer, default=0, comment="排序")


class InspectionTask(Base):
    """点检任务实例 - 按计划为每台设备生成的待执行任务"""
    __tablename__ = "inspection_tasks"
    __table_args__ = (
        UniqueConstraint("plan_id", "device_id", "planned_time", name="uq_inspection_task"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    plan_id: Mapped[int] = mapped_column(Integer, ForeignKey("inspection_plans.id"), index=True, comment="计划ID")
    plan_name: Mapped[str] = mapped_column(String(200), default="", comment="计划名称(冗余)")

    device_id: Mapped[str] = mapped_column(String(50), index=True, comment="设备ID")
    device_name: Mapped[str] = mapped_column(String(200), default="", comment="设备名称")
    device_type: Mapped[str] = mapped_column(String(20), default="switch", comment="设备类型")

    planned_time: Mapped[datetime] = mapped_column(DateTime, index=True, comment="计划执行时间")
    due_time: Mapped[datetime] = mapped_column(DateTime, comment="完成截止时间")
    items: Mapped[list] = mapped_column(JSON, default=list, comment="点检项快照")

    status: Mapped[str] = mapped_column(String(20), default="pending", index=True, comment="状态: pending/in_progress/completed/skipped/overdue")
    result: Mapped[str] = mapped_column(String(20), default="", comment="结果: normal/abnormal")
    abnormal_count: Mapped[int] = mapped_column(Integer, default=0, comment="异常项数")
    assignee: Mapped[str] = mapped_column(String(100), default="", comment="执行人")
    operator: Mapped[str] = mapped_column(String(100), default="", comment="实际执行人")
    remark: Mapped[str] = mapped_column(String(500), default="", comment="备注")

    started_at: Mapped[datetime | None] = mapped_column(DateTime, comment="开始时间")
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, comment="完成时间")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")


class InspectionRecord(Base):
    """点检执行明细 - 每条检查项的实际结果 (构成点检履历)"""
    __tablename__ = "inspection_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    task_id: Mapped[int] = mapped_column(Integer, ForeignKey("inspection_tasks.id"), index=True, comment="任务ID")
    item_name: Mapped[str] = mapped_column(String(200), comment="检查项名称")
    check_type: Mapped[str] = mapped_column(String(20), default="bool", comment="类型")
    standard: Mapped[str] = mapped_column(String(200), default="", comment="标准/参考值")
    value: Mapped[str] = mapped_column(String(500), default="", comment="实测值/描述")
    result: Mapped[str] = mapped_column(String(20), default="normal", comment="结果: normal/abnormal/na")
    remark: Mapped[str] = mapped_column(String(500), default="", comment="备注")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="记录时间")
