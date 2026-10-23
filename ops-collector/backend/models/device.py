"""设备表模型 - 交换机和服务器基础信息"""

from datetime import datetime
from sqlalchemy import String, Integer, Boolean, DateTime, Text, JSON
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class SwitchDevice(Base):
    """交换机设备表"""
    __tablename__ = "switch_devices"

    id: Mapped[str] = mapped_column(String(50), primary_key=True, comment="设备ID")
    name: Mapped[str] = mapped_column(String(200), comment="设备名称")
    ip: Mapped[str] = mapped_column(String(50), unique=True, comment="IP地址")
    model: Mapped[str] = mapped_column(String(100), default="", comment="设备型号")
    location: Mapped[str] = mapped_column(String(200), default="", comment="物理位置")
    firmware_version: Mapped[str] = mapped_column(String(100), default="", comment="固件版本")
    serial_number: Mapped[str] = mapped_column(String(100), default="", comment="序列号")
    total_ports: Mapped[int] = mapped_column(Integer, default=0, comment="端口总数")
    uptime_seconds: Mapped[int] = mapped_column(Integer, default=0, comment="运行时长(秒)")
    cpu_usage: Mapped[float] = mapped_column(default=0.0, comment="CPU使用率")
    memory_usage: Mapped[float] = mapped_column(default=0.0, comment="内存使用率")
    is_online: Mapped[bool] = mapped_column(Boolean, default=False, comment="是否在线")
    last_poll_time: Mapped[datetime | None] = mapped_column(DateTime, comment="最后采集时间")
    snmp_config: Mapped[dict] = mapped_column(JSON, default=dict, comment="SNMP配置")
    ssh_config: Mapped[dict] = mapped_column(JSON, default=dict, comment="SSH配置")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")


class ServerDevice(Base):
    """服务器设备表"""
    __tablename__ = "server_devices"

    id: Mapped[str] = mapped_column(String(50), primary_key=True, comment="服务器ID")
    name: Mapped[str] = mapped_column(String(200), comment="服务器名称")
    ip: Mapped[str] = mapped_column(String(50), unique=True, comment="IP地址")
    os_type: Mapped[str] = mapped_column(String(20), comment="操作系统类型: windows/linux")
    location: Mapped[str] = mapped_column(String(200), default="", comment="物理位置")
    hostname: Mapped[str] = mapped_column(String(200), default="", comment="主机名")
    os_version: Mapped[str] = mapped_column(String(200), default="", comment="系统版本")
    cpu_cores: Mapped[int] = mapped_column(Integer, default=0, comment="CPU核心数")
    total_memory_mb: Mapped[int] = mapped_column(Integer, default=0, comment="总内存(MB)")
    is_online: Mapped[bool] = mapped_column(Boolean, default=False, comment="是否在线")
    uptime_seconds: Mapped[int] = mapped_column(Integer, default=0, comment="运行时长(秒)")
    last_poll_time: Mapped[datetime | None] = mapped_column(DateTime, comment="最后采集时间")
    monitoring_config: Mapped[dict] = mapped_column(JSON, default=dict, comment="监控配置")
    processes_config: Mapped[list] = mapped_column(JSON, default=list, comment="进程监控配置")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, comment="是否启用")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, comment="创建时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")
