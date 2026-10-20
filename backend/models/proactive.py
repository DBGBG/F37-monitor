"""主动运维模型 - 动态基线 / 异常事件 / 健康评分 / 维护窗口

设计说明:
- MetricBaseline: 每台设备每个指标的 EWMA 动态基线 (在线增量维护, 无需全量历史)
- AnomalyEvent:   偏离基线判定出的异常事件 (支持 开启/确认/恢复 状态流转)
- DeviceHealth:   设备健康度评分快照 (每次分析后更新, 用于快速排序展示)
- MaintenanceWindow: 计划维护窗口, 窗口内抑制主动异常与告警
"""

from datetime import datetime
from sqlalchemy import (
    String, Integer, Float, Boolean, DateTime, Text, JSON,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class MetricBaseline(Base):
    """指标动态基线 (EWMA 均值/方差, 随采集增量更新)"""
    __tablename__ = "metric_baselines"
    __table_args__ = (
        UniqueConstraint("device_id", "metric", name="uq_baseline_device_metric"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(50), index=True, comment="设备ID")
    device_type: Mapped[str] = mapped_column(String(20), default="switch", comment="设备类型")
    metric: Mapped[str] = mapped_column(String(30), index=True, comment="指标名")
    mean: Mapped[float] = mapped_column(Float, default=0.0, comment="EWMA 均值(基线)")
    variance: Mapped[float] = mapped_column(Float, default=0.0, comment="EWMA 方差")
    sample_count: Mapped[int] = mapped_column(Integer, default=0, comment="累计样本数")
    last_value: Mapped[float] = mapped_column(Float, default=0.0, comment="最近一次取值")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间"
    )


class AnomalyEvent(Base):
    """主动运维异常事件"""
    __tablename__ = "anomaly_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(50), index=True, comment="设备ID")
    device_name: Mapped[str] = mapped_column(String(200), default="", comment="设备名称")
    device_type: Mapped[str] = mapped_column(String(20), default="switch", comment="设备类型")
    metric: Mapped[str] = mapped_column(String(30), index=True, comment="异常指标")
    severity: Mapped[str] = mapped_column(String(20), default="WARNING", index=True, comment="级别: WARNING/CRITICAL")
    title: Mapped[str] = mapped_column(String(300), default="", comment="标题")
    description: Mapped[str] = mapped_column(Text, default="", comment="描述")
    value: Mapped[float] = mapped_column(Float, default=0.0, comment="异常值")
    baseline: Mapped[float] = mapped_column(Float, default=0.0, comment="基线值")
    deviation: Mapped[float] = mapped_column(Float, default=0.0, comment="绝对偏离")
    score: Mapped[float] = mapped_column(Float, default=0.0, comment="z 分数")
    unit: Mapped[str] = mapped_column(String(20), default="", comment="单位")
    status: Mapped[str] = mapped_column(String(20), default="open", index=True, comment="open/acknowledged/resolved")
    occurred_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="首次发生时间")
    last_seen_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="最近一次出现时间")
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime, comment="恢复时间")
    acknowledged_by: Mapped[str] = mapped_column(String(100), default="", comment="确认人")
    escalated: Mapped[bool] = mapped_column(Boolean, default=False, comment="是否已升级为告警")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")


class DeviceHealth(Base):
    """设备健康度评分 (每次分析后覆盖更新)"""
    __tablename__ = "device_health"

    device_id: Mapped[str] = mapped_column(String(50), primary_key=True, comment="设备ID")
    device_name: Mapped[str] = mapped_column(String(200), default="", comment="设备名称")
    device_type: Mapped[str] = mapped_column(String(20), default="switch", index=True, comment="设备类型")
    score: Mapped[int] = mapped_column(Integer, default=100, comment="健康分 0-100")
    grade: Mapped[str] = mapped_column(String(10), default="优", comment="等级: 优/良/中/差")
    factors: Mapped[list] = mapped_column(JSON, default=list, comment="扣分因子明细")
    summary: Mapped[str] = mapped_column(String(300), default="", comment="健康摘要")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间"
    )


class MaintenanceWindow(Base):
    """计划维护窗口 (窗口内抑制主动异常与告警)"""
    __tablename__ = "maintenance_windows"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(200), comment="窗口名称")
    device_type: Mapped[str] = mapped_column(String(20), default="all", comment="作用范围: all/switch/server")
    device_ids: Mapped[list] = mapped_column(JSON, default=list, comment="指定设备ID列表(空=全部)")
    start_time: Mapped[datetime] = mapped_column(DateTime, comment="开始时间")
    end_time: Mapped[datetime] = mapped_column(DateTime, comment="结束时间")
    reason: Mapped[str] = mapped_column(Text, default="", comment="维护原因")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    created_by: Mapped[str] = mapped_column(String(100), default="admin", comment="创建人")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")
