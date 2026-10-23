"""
数据生命周期管理
- 按配置的任务保留策略清理过期时序数据与历史告警, 防止 SQLite 无限增长
- 清理后执行 WAL checkpoint / vacuum, 回收磁盘空间
"""

import logging
from datetime import datetime, timedelta

from sqlalchemy import delete, select

from backend.config import config
from backend.database import async_session, engine
from backend.models.port_status import PortTrafficStats
from backend.models.hardware_health import HardwareHealth
from backend.models.server_metrics import ServerMetrics, ProcessStatus
from backend.models.alert import AlertRecord
from backend.models.config_audit import ConfigAuditLog
from backend.models.inspection import InspectionTask, InspectionRecord
from backend.models.proactive import AnomalyEvent

logger = logging.getLogger(__name__)

# 按 timestamp 字段清理的时序表
_TIME_SERIES = [
    (PortTrafficStats, "port_traffic_stats"),
    (ServerMetrics, "server_metrics"),
    (HardwareHealth, "hardware_health"),
    (ProcessStatus, "process_status"),
]


class DataMaintenance:
    """数据生命周期管理: 按保留策略清理过期数据"""

    async def cleanup_metrics(self, days: int) -> dict:
        """删除超过保留期的时序数据, 返回各表删除行数"""
        cutoff = datetime.now() - timedelta(days=days)
        deleted = {}
        async with async_session() as session:
            for model, name in _TIME_SERIES:
                result = await session.execute(
                    delete(model).where(model.timestamp < cutoff)
                )
                deleted[name] = result.rowcount or 0
            await session.commit()
        return deleted

    async def cleanup_alerts(self, days: int) -> int:
        """删除超过保留期且已恢复的告警记录 (活跃/已确认告警保留)"""
        cutoff = datetime.now() - timedelta(days=days)
        async with async_session() as session:
            result = await session.execute(
                delete(AlertRecord).where(
                    AlertRecord.status == "resolved",
                    AlertRecord.triggered_at < cutoff,
                )
            )
            await session.commit()
            return result.rowcount or 0

    async def cleanup_audit(self, days: int = 365) -> int:
        """删除超期的配置审计日志"""
        cutoff = datetime.now() - timedelta(days=days)
        async with async_session() as session:
            result = await session.execute(
                delete(ConfigAuditLog).where(ConfigAuditLog.executed_at < cutoff)
            )
            await session.commit()
            return result.rowcount or 0

    async def cleanup_inspection(self, days: int) -> int:
        """删除超期的点检履历 (仅已完成/已跳过的任务及其明细); days<=0 表示永久保留"""
        if days <= 0:
            return 0
        cutoff = datetime.now() - timedelta(days=days)
        async with async_session() as session:
            task_ids = (await session.execute(
                select(InspectionTask.id).where(
                    InspectionTask.status.in_(["completed", "skipped"]),
                    InspectionTask.finished_at < cutoff,
                )
            )).scalars().all()
            if not task_ids:
                return 0
            await session.execute(
                delete(InspectionRecord).where(InspectionRecord.task_id.in_(task_ids))
            )
            result = await session.execute(
                delete(InspectionTask).where(InspectionTask.id.in_(task_ids))
            )
            await session.commit()
            return result.rowcount or 0

    async def cleanup_anomalies(self, days: int) -> int:
        """删除超期且已恢复的主动异常事件 (未处理异常始终保留); days<=0 表示永久保留"""
        if days <= 0:
            return 0
        cutoff = datetime.now() - timedelta(days=days)
        async with async_session() as session:
            result = await session.execute(
                delete(AnomalyEvent).where(
                    AnomalyEvent.status == "resolved",
                    AnomalyEvent.last_seen_at < cutoff,
                )
            )
            await session.commit()
            return result.rowcount or 0

    async def _wal_checkpoint(self):
        """截断 WAL 文件, 释放磁盘空间"""
        try:
            async with engine.connect() as conn:
                await conn.exec_driver_sql("PRAGMA wal_checkpoint(TRUNCATE)")
        except Exception as e:
            logger.warning(f"WAL checkpoint 失败: {e}")

    async def _optimize(self):
        """更新统计信息, 帮助查询规划器"""
        try:
            async with engine.connect() as conn:
                await conn.exec_driver_sql("PRAGMA optimize")
        except Exception as e:
            logger.warning(f"PRAGMA optimize 失败: {e}")

    async def run_cleanup(
        self,
        metrics_days: int | None = None,
        alerts_days: int | None = None,
        checkpoint: bool = True,
    ) -> dict:
        """执行一轮完整清理 (默认使用 settings.yaml 的 retention 配置)"""
        retention = config.retention
        metrics_days = int(
            metrics_days if metrics_days is not None else retention.get("metrics_days", 30)
        )
        alerts_days = int(
            alerts_days if alerts_days is not None else retention.get("alerts_days", 90)
        )

        if checkpoint:
            await self._wal_checkpoint()

        deleted_metrics = await self.cleanup_metrics(metrics_days)
        deleted_alerts = await self.cleanup_alerts(alerts_days)
        audit_days = int(retention.get("audit_days", 365))
        deleted_audit = await self.cleanup_audit(audit_days)
        inspect = await self.cleanup_inspection(int(retention.get("inspection_days", 0)))
        anomalies_days = int(retention.get("anomalies_days", 180))
        deleted_anomalies = await self.cleanup_anomalies(anomalies_days)

        await self._optimize()
        if checkpoint:
            await self._wal_checkpoint()

        summary = {
            "metrics_days": metrics_days,
            "alerts_days": alerts_days,
            "audit_days": audit_days,
            "inspection_days": int(retention.get("inspection_days", 0)),
            "anomalies_days": anomalies_days,
            "deleted_metrics": deleted_metrics,
            "deleted_alerts": deleted_alerts,
            "deleted_audit": deleted_audit,
            "deleted_inspection": inspect,
            "deleted_anomalies": deleted_anomalies,
        }
        logger.info(f"数据清理完成: {summary}")
        return summary
