"""告警记录模型"""

from datetime import datetime
from sqlalchemy import String, Integer, DateTime, Text, Boolean
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class AlertRecord(Base):
    """告警记录表"""
    __tablename__ = "alert_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    alert_id: Mapped[str] = mapped_column(String(100), unique=True, comment="告警唯一ID")
    device_id: Mapped[str] = mapped_column(String(50), index=True, comment="设备ID")
    device_name: Mapped[str] = mapped_column(String(200), default="", comment="设备名称")
    device_type: Mapped[str] = mapped_column(String(20), comment="设备类型: switch/server")

    # 告警信息
    rule_name: Mapped[str] = mapped_column(String(100), comment="规则名称")
    severity: Mapped[str] = mapped_column(String(20), index=True, comment="严重级别: INFO/WARNING/CRITICAL")
    title: Mapped[str] = mapped_column(String(500), comment="告警标题")
    description: Mapped[str] = mapped_column(Text, default="", comment="告警描述")
    metric_value: Mapped[str] = mapped_column(String(100), default="", comment="触发指标值")
    threshold: Mapped[str] = mapped_column(String(100), default="", comment="告警阈值")

    # 状态管理
    status: Mapped[str] = mapped_column(String(20), default="active", index=True, comment="状态: active/acknowledged/resolved")
    acknowledged_by: Mapped[str] = mapped_column(String(100), default="", comment="确认人")
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime, comment="恢复时间")
    resolved_by: Mapped[str] = mapped_column(String(100), default="", comment="恢复操作人")

    # 通知状态
    notified: Mapped[bool] = mapped_column(Boolean, default=False, comment="是否已通知")
    notify_channels: Mapped[str] = mapped_column(String(200), default="", comment="通知渠道")

    # 时间
    triggered_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="触发时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")
