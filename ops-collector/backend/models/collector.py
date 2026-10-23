"""跨层边界表模型 - 采集层与业务层通过数据库交换配置与指令

写入方约定 (详见 docs/ARCHITECTURE.md 表所有权矩阵):
  discovery_groups    业务层写 / 采集层读     扫描网段与凭据模板
  collector_jobs      业务层写 / 采集层读     采集周期与开关
  collector_heartbeats采集层写 / 业务层读     每轮采集心跳
  collector_commands  业务层写 / 采集层读     rediscover / reload_config / collect_now
"""

from datetime import datetime

from sqlalchemy import String, Integer, Boolean, DateTime, JSON, func
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class DiscoveryGroup(Base):
    """设备发现范围表"""
    __tablename__ = "discovery_groups"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    group_name: Mapped[str] = mapped_column(String(100), comment="分组名称")
    ip_range: Mapped[str] = mapped_column(String(100), comment="IP范围: a.b.c.1-200 / CIDR")
    model: Mapped[str] = mapped_column(String(100), default="", comment="设备型号")
    location_prefix: Mapped[str] = mapped_column(String(100), default="", comment="位置前缀")
    snmp_config: Mapped[dict | None] = mapped_column(JSON, comment="SNMP凭据模板")
    ssh_config: Mapped[dict | None] = mapped_column(JSON, comment="SSH凭据模板")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), comment="创建时间")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")


class CollectorJob(Base):
    """采集任务周期表"""
    __tablename__ = "collector_jobs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    job_id: Mapped[str] = mapped_column(String(50), unique=True, comment="任务ID")
    name: Mapped[str] = mapped_column(String(100), default="", comment="任务名称")
    interval_seconds: Mapped[int] = mapped_column(Integer, default=60, comment="采集周期(秒)")
    timeout_seconds: Mapped[int] = mapped_column(Integer, default=120, comment="单轮超时(秒)")
    max_instances: Mapped[int] = mapped_column(Integer, default=1, comment="最大实例数")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")


class CollectorHeartbeat(Base):
    """采集层心跳表"""
    __tablename__ = "collector_heartbeats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    component: Mapped[str] = mapped_column(String(30), default="collector", comment="组件: collector/trap")
    phase: Mapped[str] = mapped_column(String(30), default="", comment="阶段: 状态/流量/硬件/服务器/进程/发现")
    total: Mapped[int] = mapped_column(Integer, default=0, comment="本轮目标设备数")
    success: Mapped[int] = mapped_column(Integer, default=0, comment="成功数")
    errors: Mapped[int] = mapped_column(Integer, default=0, comment="失败数")
    duration_ms: Mapped[int] = mapped_column(Integer, default=0, comment="本轮耗时(ms)")
    last_beat_at: Mapped[datetime] = mapped_column(DateTime, comment="心跳时间")
    detail: Mapped[dict | None] = mapped_column(JSON, comment="附加统计")


class CollectorCommand(Base):
    """业务层下发给采集层的指令表"""
    __tablename__ = "collector_commands"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    cmd: Mapped[str] = mapped_column(String(50), comment="指令: rediscover/reload_config/collect_now")
    payload: Mapped[dict | None] = mapped_column(JSON, comment="指令参数")
    status: Mapped[str] = mapped_column(String(20), default="pending", comment="pending/running/done/failed")
    result: Mapped[str] = mapped_column(String(500), default="", comment="执行结果")
    created_by: Mapped[str] = mapped_column(String(100), default="system", comment="发起人")
    created_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), comment="创建时间")
    executed_at: Mapped[datetime | None] = mapped_column(DateTime, comment="执行时间")
