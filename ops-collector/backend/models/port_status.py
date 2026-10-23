"""端口状态和流量统计模型"""

from datetime import datetime
from sqlalchemy import String, Integer, Float, DateTime, BigInteger, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class PortStatus(Base):
    """交换机端口状态表 (保存最新状态)"""
    __tablename__ = "port_status"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    switch_id: Mapped[str] = mapped_column(String(50), ForeignKey("switch_devices.id"), comment="交换机ID")
    port_index: Mapped[int] = mapped_column(Integer, comment="端口索引")
    port_name: Mapped[str] = mapped_column(String(100), default="", comment="端口名称")
    admin_status: Mapped[int] = mapped_column(Integer, default=1, comment="管理状态: 1=up, 2=down")
    oper_status: Mapped[int] = mapped_column(Integer, default=2, comment="运行状态: 1=up, 2=down")
    speed_bps: Mapped[int] = mapped_column(BigInteger, default=0, comment="端口速率(bps)")
    mac_address: Mapped[str] = mapped_column(String(50), default="", comment="MAC地址")
    vlan_id: Mapped[int] = mapped_column(Integer, default=1, comment="所属VLAN")
    port_mode: Mapped[str] = mapped_column(String(20), default="access", comment="端口模式: access/trunk/hybrid")
    last_change: Mapped[datetime | None] = mapped_column(DateTime, comment="状态最后变更时间")
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, onupdate=datetime.now, comment="更新时间")


class PortTrafficStats(Base):
    """端口流量统计表 (时序数据)"""
    __tablename__ = "port_traffic_stats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    switch_id: Mapped[str] = mapped_column(String(50), ForeignKey("switch_devices.id"), index=True, comment="交换机ID")
    port_index: Mapped[int] = mapped_column(Integer, comment="端口索引")
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="采集时间")

    # 流量计数器 (累计值)
    in_octets: Mapped[int] = mapped_column(BigInteger, default=0, comment="入方向字节数")
    out_octets: Mapped[int] = mapped_column(BigInteger, default=0, comment="出方向字节数")
    in_unicast_pkts: Mapped[int] = mapped_column(BigInteger, default=0, comment="入方向单播包数")
    out_unicast_pkts: Mapped[int] = mapped_column(BigInteger, default=0, comment="出方向单播包数")

    # 错误统计
    in_errors: Mapped[int] = mapped_column(BigInteger, default=0, comment="入方向错误包数")
    out_errors: Mapped[int] = mapped_column(BigInteger, default=0, comment="出方向错误包数")
    in_discards: Mapped[int] = mapped_column(BigInteger, default=0, comment="入方向丢包数")
    out_discards: Mapped[int] = mapped_column(BigInteger, default=0, comment="出方向丢包数")

    # 计算后的速率 (由采集器计算)
    in_rate_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="入方向速率(bps)")
    out_rate_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="出方向速率(bps)")
    in_error_rate: Mapped[float] = mapped_column(Float, default=0.0, comment="入方向错包率")
    out_error_rate: Mapped[float] = mapped_column(Float, default=0.0, comment="出方向错包率")
