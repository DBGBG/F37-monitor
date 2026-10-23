"""服务器指标和进程状态模型"""

from datetime import datetime
from sqlalchemy import String, Integer, Float, DateTime, BigInteger, ForeignKey, JSON
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class ServerMetrics(Base):
    """服务器资源指标表 (时序数据)"""
    __tablename__ = "server_metrics"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    server_id: Mapped[str] = mapped_column(String(50), ForeignKey("server_devices.id"), index=True, comment="服务器ID")
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="采集时间")

    # CPU
    cpu_usage_percent: Mapped[float] = mapped_column(Float, default=0.0, comment="CPU使用率(%)")
    cpu_per_core: Mapped[list | None] = mapped_column(JSON, comment="每核CPU使用率")
    cpu_freq_mhz: Mapped[float] = mapped_column(Float, default=0.0, comment="CPU频率(MHz)")

    # 内存
    memory_total_mb: Mapped[int] = mapped_column(BigInteger, default=0, comment="总内存(MB)")
    memory_used_mb: Mapped[int] = mapped_column(BigInteger, default=0, comment="已用内存(MB)")
    memory_usage_percent: Mapped[float] = mapped_column(Float, default=0.0, comment="内存使用率(%)")
    memory_available_mb: Mapped[int] = mapped_column(BigInteger, default=0, comment="可用内存(MB)")

    # 磁盘
    disk_total_gb: Mapped[float] = mapped_column(Float, default=0.0, comment="磁盘总空间(GB)")
    disk_used_gb: Mapped[float] = mapped_column(Float, default=0.0, comment="磁盘已用空间(GB)")
    disk_usage_percent: Mapped[float] = mapped_column(Float, default=0.0, comment="磁盘使用率(%)")
    disk_read_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="磁盘读速率(B/s)")
    disk_write_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="磁盘写速率(B/s)")

    # 网络
    net_bytes_sent: Mapped[int] = mapped_column(BigInteger, default=0, comment="网络发送字节数")
    net_bytes_recv: Mapped[int] = mapped_column(BigInteger, default=0, comment="网络接收字节数")
    net_send_rate_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="网络发送速率(bps)")
    net_recv_rate_bps: Mapped[float] = mapped_column(Float, default=0.0, comment="网络接收速率(bps)")

    # 系统
    uptime_seconds: Mapped[int] = mapped_column(BigInteger, default=0, comment="运行时长(秒)")
    load_avg: Mapped[list | None] = mapped_column(JSON, comment="负载平均值 [1m, 5m, 15m]")
    process_count: Mapped[int] = mapped_column(Integer, default=0, comment="进程总数")


class ProcessStatus(Base):
    """进程/服务状态表"""
    __tablename__ = "process_status"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    server_id: Mapped[str] = mapped_column(String(50), ForeignKey("server_devices.id"), index=True, comment="服务器ID")
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="采集时间")
    process_name: Mapped[str] = mapped_column(String(200), comment="进程名称")
    pid: Mapped[int] = mapped_column(Integer, default=0, comment="进程PID")
    is_running: Mapped[bool] = mapped_column(default=False, comment="是否运行中")
    cpu_percent: Mapped[float] = mapped_column(Float, default=0.0, comment="CPU占用(%)")
    memory_mb: Mapped[float] = mapped_column(Float, default=0.0, comment="内存占用(MB)")
    memory_percent: Mapped[float] = mapped_column(Float, default=0.0, comment="内存占比(%)")
    port: Mapped[int] = mapped_column(Integer, default=0, comment="监听端口")
    port_open: Mapped[bool] = mapped_column(default=False, comment="端口是否可达")
    is_critical: Mapped[bool] = mapped_column(default=False, comment="是否关键进程")
    status_detail: Mapped[str] = mapped_column(String(500), default="", comment="状态详情")
