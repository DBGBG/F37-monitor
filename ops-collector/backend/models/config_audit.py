"""配置审计日志模型 - 记录所有自动化配置操作"""

from datetime import datetime
from sqlalchemy import String, Integer, DateTime, Text, JSON
from sqlalchemy.orm import Mapped, mapped_column

from backend.database import Base


class ConfigAuditLog(Base):
    """配置变更审计日志表"""
    __tablename__ = "config_audit_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    switch_id: Mapped[str] = mapped_column(String(50), index=True, comment="交换机ID")
    switch_name: Mapped[str] = mapped_column(String(200), default="", comment="交换机名称")
    switch_ip: Mapped[str] = mapped_column(String(50), default="", comment="交换机IP")

    # 操作信息
    operation_type: Mapped[str] = mapped_column(String(50), comment="操作类型: vlan/port/system")
    operation_desc: Mapped[str] = mapped_column(String(500), comment="操作描述")
    operator: Mapped[str] = mapped_column(String(100), default="system", comment="操作人")

    # 命令与结果
    commands_sent: Mapped[str] = mapped_column(Text, default="", comment="下发的命令")
    commands_output: Mapped[str] = mapped_column(Text, default="", comment="命令输出")
    parameters: Mapped[dict] = mapped_column(JSON, default=dict, comment="操作参数")

    # 结果
    status: Mapped[str] = mapped_column(String(20), comment="状态: success/failed/timeout")
    error_message: Mapped[str] = mapped_column(Text, default="", comment="错误信息")
    duration_ms: Mapped[int] = mapped_column(Integer, default=0, comment="执行耗时(ms)")

    # 时间
    executed_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True, comment="执行时间")
