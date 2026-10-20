"""硬件健康状态模型 - 电源、温度、风扇等"""

from datetime import datetime
from sqlalchemy import String, Integer, Float, DateTime, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class HardwareHealth(Base):
    """交换机硬件健康状态表"""
    __tablename__ = "hardware_health"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    switch_id: Mapped[str] = mapped_column(String(50), ForeignKey("switch_devices.id"), index=True, comment="交换机ID")
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="采集时间")

    # 电源状态
    power1_status: Mapped[int] = mapped_column(Integer, default=0, comment="电源1状态: 0=未知, 1=正常, 2=故障")
    power2_status: Mapped[int] = mapped_column(Integer, default=0, comment="电源2状态: 0=未知, 1=正常, 2=故障")
    power1_voltage: Mapped[float] = mapped_column(Float, default=0.0, comment="电源1电压(V)")
    power2_voltage: Mapped[float] = mapped_column(Float, default=0.0, comment="电源2电压(V)")

    # 温度
    temperature: Mapped[float] = mapped_column(Float, default=0.0, comment="设备温度(°C)")
    temperature_alarm: Mapped[int] = mapped_column(Integer, default=0, comment="温度告警: 0=正常, 1=告警")

    # 风扇
    fan_status: Mapped[int] = mapped_column(Integer, default=0, comment="风扇状态: 0=未知, 1=正常, 2=故障")
    fan_speed: Mapped[int] = mapped_column(Integer, default=0, comment="风扇转速(RPM)")

    # 系统资源
    cpu_usage: Mapped[float] = mapped_column(Float, default=0.0, comment="CPU使用率(%)")
    memory_usage: Mapped[float] = mapped_column(Float, default=0.0, comment="内存使用率(%)")
    memory_total_kb: Mapped[int] = mapped_column(Integer, default=0, comment="总内存(KB)")
    memory_used_kb: Mapped[int] = mapped_column(Integer, default=0, comment="已用内存(KB)")

    # 综合健康状态
    overall_status: Mapped[str] = mapped_column(String(20), default="normal", comment="综合状态: normal/warning/critical")
