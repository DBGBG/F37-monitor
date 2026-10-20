"""
告警引擎
- 基于阈值的规则匹配
- 告警分级: INFO / WARNING / CRITICAL
- 告警去重与抑制
- 自动触发通知
"""

import asyncio
import hashlib
import logging
from datetime import datetime
from typing import Optional

from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import config
from backend.database import async_session
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.port_status import PortStatus
from backend.models.hardware_health import HardwareHealth
from backend.models.server_metrics import ServerMetrics, ProcessStatus
from backend.models.alert import AlertRecord
from backend.alerts.notifiers import NotificationManager
from backend.proactive.windows import MaintenanceService
from backend.realtime import live, bus

logger = logging.getLogger(__name__)


class AlertEngine:
    """告警引擎"""

    def __init__(self):
        self.alert_config = config.alerts
        self.rules = self.alert_config.get("rules", {})
        self.dedup_window = self.alert_config.get("dedup_window", 300)  # 秒
        self.notifier = NotificationManager()
        self.maintenance = MaintenanceService()
        # 端口链路 Down 事件检测: 记录上一轮 oper 状态, 只在 "up->down" 跳变时告警
        self._prev_oper: dict = {}
        self._alerted_down: dict = {}
        # 防重入: 定时检查与"采集后即时检查"可能并发, 串行化避免重复处理
        self._lock = asyncio.Lock()

    def _generate_alert_id(self, device_id: str, rule_name: str) -> str:
        """生成告警唯一 ID (基于设备+规则)"""
        raw = f"{device_id}:{rule_name}"
        return hashlib.md5(raw.encode()).hexdigest()[:16]

    async def _get_alert(self, session: AsyncSession, alert_id: str) -> Optional[AlertRecord]:
        result = await session.execute(
            select(AlertRecord).where(AlertRecord.alert_id == alert_id)
        )
        return result.scalar_one_or_none()

    async def _create_alert(
        self,
        session: AsyncSession,
        device_id: str,
        device_name: str,
        device_type: str,
        rule_name: str,
        severity: str,
        title: str,
        description: str,
        metric_value: str = "",
        threshold: str = "",
    ) -> Optional[AlertRecord]:
        """创建或更新告警记录 (同一设备+规则只保留一条记录, 避免 alert_id 唯一约束冲突)"""
        alert_id = self._generate_alert_id(device_id, rule_name)

        # 维护窗口内静默: 计划维护(断电/割接/升级)不产生打扰
        suppressed, win_name = self.maintenance.is_suppressed(device_id, device_type)
        if suppressed:
            logger.debug(f"维护窗口[{win_name}]内抑制告警: {title}")
            return None

        now = datetime.now()
        existing = await self._get_alert(session, alert_id)

        if existing is not None:
            # 已有同规则告警: 更新内容, 不新增记录
            if existing.status == "active":
                existing.severity = severity
                existing.title = title
                existing.description = description
                existing.metric_value = metric_value
                existing.threshold = threshold
                existing.updated_at = now
                return existing
            # 历史告警(已恢复/已确认)重新触发: 复用同一条记录并重置为 active
            existing.status = "active"
            existing.severity = severity
            existing.title = title
            existing.description = description
            existing.metric_value = metric_value
            existing.threshold = threshold
            existing.acknowledged_by = ""
            existing.resolved_at = None
            existing.resolved_by = ""
            existing.notified = False
            existing.notify_channels = ""
            existing.triggered_at = now
            existing.updated_at = now
            logger.warning(f"告警重新触发: [{severity}] {title}")
            bus.publish("alert", {
                "action": "triggered", "device_id": device_id,
                "device_name": device_name, "device_type": device_type,
                "rule": rule_name, "severity": severity, "title": title,
            })
            return existing

        alert = AlertRecord(
            alert_id=alert_id,
            device_id=device_id,
            device_name=device_name,
            device_type=device_type,
            rule_name=rule_name,
            severity=severity,
            title=title,
            description=description,
            metric_value=metric_value,
            threshold=threshold,
        )
        session.add(alert)
        logger.warning(f"告警触发: [{severity}] {title}")
        bus.publish("alert", {
            "action": "triggered", "device_id": device_id,
            "device_name": device_name, "device_type": device_type,
            "rule": rule_name, "severity": severity, "title": title,
        })
        return alert

    async def _resolve_rule(self, session: AsyncSession, device_id: str, rule_name: str):
        """条件已消失: 自动恢复该设备对应规则的活跃告警"""
        alert = await self._get_alert(session, self._generate_alert_id(device_id, rule_name))
        if alert is not None and alert.status in ("active", "acknowledged"):
            alert.status = "resolved"
            alert.resolved_at = datetime.now()
            alert.resolved_by = "system"
            logger.info(f"告警恢复: {device_id} - {rule_name}")
            bus.publish("alert", {
                "action": "resolved", "device_id": device_id,
                "rule": rule_name, "severity": alert.severity,
                "title": alert.title, "status": "resolved",
            })

    async def check_switch_alerts(self, session: AsyncSession):
        """检查交换机告警"""
        # 遍历所有已启用交换机 (含离线): 离线本身即为最高优先级事件
        result = await session.execute(
            select(SwitchDevice).where(SwitchDevice.enabled == True)  # noqa: E712
        )
        switches = result.scalars().all()

        for switch in switches:
            offline_rule = self.rules.get("switch_offline", {})

            # 0. 设备离线: 触发离线告警, 并吸收其端口级子告警(根因 > 症状)
            if not switch.is_online:
                if offline_rule:
                    await self._create_alert(
                        session=session,
                        device_id=switch.id,
                        device_name=switch.name,
                        device_type="switch",
                        rule_name="switch_offline",
                        severity=offline_rule.get("severity", "CRITICAL"),
                        title=f"[{switch.name}] 设备离线",
                        description=(
                            f"交换机 {switch.name}({switch.ip}) 无法通过 SNMP 采集, "
                            f"可能设备断电或网络中断, 请优先排查"
                        ),
                    )
                # 根因吸收: 离线期间不再报其端口 Down, 并清理跳变检测状态
                await self._resolve_rule(session, switch.id, "switch_port_down")
                self._prev_oper.pop(switch.id, None)
                self._alerted_down.pop(switch.id, None)
                continue

            if offline_rule:
                await self._resolve_rule(session, switch.id, "switch_offline")

            # 1. 检查端口 Down (仅对 "up->down" 链路跳变告警, 避免把未接线端口计入)
            port_down_rule = self.rules.get("switch_port_down", {})
            if port_down_rule:
                all_ports = (await session.execute(
                    select(PortStatus).where(PortStatus.switch_id == switch.id)
                )).scalars().all()
                prev = self._prev_oper.get(switch.id, {})
                alerted = self._alerted_down.setdefault(switch.id, set())
                for p in all_ports:
                    if p.admin_status == 1 and p.oper_status == 2:
                        if prev.get(p.port_index) == 1:  # 上一轮 Up, 本轮 Down => 链路中断
                            alerted.add(p.port_index)
                    else:
                        alerted.discard(p.port_index)  # 已恢复或管理关闭
                self._prev_oper[switch.id] = {p.port_index: p.oper_status for p in all_ports}

                if alerted:
                    port_names = [f"Port {i}" for i in sorted(alerted)]
                    await self._create_alert(
                        session=session,
                        device_id=switch.id,
                        device_name=switch.name,
                        device_type="switch",
                        rule_name="switch_port_down",
                        severity=port_down_rule.get("severity", "WARNING"),
                        title=f"[{switch.name}] 端口 Down: {', '.join(port_names)}",
                        description=f"交换机 {switch.name}({switch.ip}) 有 {len(alerted)} 个端口链路中断",
                        metric_value=str(len(alerted)),
                        threshold="0",
                    )
                else:
                    await self._resolve_rule(session, switch.id, "switch_port_down")

            # 2. 检查电源故障
            power_rule = self.rules.get("switch_power_failure", {})
            if power_rule:
                hw_result = await session.execute(
                    select(HardwareHealth)
                    .where(HardwareHealth.switch_id == switch.id)
                    .order_by(HardwareHealth.timestamp.desc())
                    .limit(1)
                )
                latest_hw = hw_result.scalar_one_or_none()
                if latest_hw:
                    if latest_hw.power1_status == 2 or latest_hw.power2_status == 2:
                        failed = []
                        if latest_hw.power1_status == 2:
                            failed.append("电源1")
                        if latest_hw.power2_status == 2:
                            failed.append("电源2")
                        await self._create_alert(
                            session=session,
                            device_id=switch.id,
                            device_name=switch.name,
                            device_type="switch",
                            rule_name="switch_power_failure",
                            severity=power_rule.get("severity", "CRITICAL"),
                            title=f"[{switch.name}] {', '.join(failed)} 故障",
                            description=f"交换机 {switch.name}({switch.ip}) {', '.join(failed)} 发生故障，请立即检查",
                        )
                    else:
                        await self._resolve_rule(session, switch.id, "switch_power_failure")

            # 3. 检查 CPU 过高
            cpu_rule = self.rules.get("switch_cpu_high", {})
            if cpu_rule:
                if switch.cpu_usage > cpu_rule.get("threshold", 90):
                    await self._create_alert(
                        session=session,
                        device_id=switch.id,
                        device_name=switch.name,
                        device_type="switch",
                        rule_name="switch_cpu_high",
                        severity=cpu_rule.get("severity", "WARNING"),
                        title=f"[{switch.name}] CPU 使用率过高: {switch.cpu_usage:.1f}%",
                        description=f"交换机 {switch.name} CPU 使用率 {switch.cpu_usage:.1f}%",
                        metric_value=f"{switch.cpu_usage:.1f}%",
                        threshold=f"{cpu_rule.get('threshold', 90)}%",
                    )
                else:
                    await self._resolve_rule(session, switch.id, "switch_cpu_high")

    async def check_server_alerts(self, session: AsyncSession):
        """检查服务器告警"""
        result = await session.execute(
            select(ServerDevice).where(ServerDevice.is_online == True)
        )
        servers = result.scalars().all()

        for server in servers:
            # 获取最新指标
            metrics_result = await session.execute(
                select(ServerMetrics)
                .where(ServerMetrics.server_id == server.id)
                .order_by(ServerMetrics.timestamp.desc())
                .limit(1)
            )
            metrics = metrics_result.scalar_one_or_none()

            if not metrics:
                continue

            # 1. CPU 告警
            cpu_rule = self.rules.get("server_cpu_high", {})
            if cpu_rule:
                if metrics.cpu_usage_percent > cpu_rule.get("threshold", 90):
                    await self._create_alert(
                        session=session,
                        device_id=server.id,
                        device_name=server.name,
                        device_type="server",
                        rule_name="server_cpu_high",
                        severity=cpu_rule.get("severity", "WARNING"),
                        title=f"[{server.name}] CPU 使用率过高: {metrics.cpu_usage_percent:.1f}%",
                        metric_value=f"{metrics.cpu_usage_percent:.1f}%",
                        threshold=f"{cpu_rule.get('threshold', 90)}%",
                    )
                else:
                    await self._resolve_rule(session, server.id, "server_cpu_high")

            # 2. 内存告警
            mem_rule = self.rules.get("server_memory_high", {})
            if mem_rule:
                if metrics.memory_usage_percent > mem_rule.get("threshold", 90):
                    await self._create_alert(
                        session=session,
                        device_id=server.id,
                        device_name=server.name,
                        device_type="server",
                        rule_name="server_memory_high",
                        severity=mem_rule.get("severity", "WARNING"),
                        title=f"[{server.name}] 内存使用率过高: {metrics.memory_usage_percent:.1f}%",
                        metric_value=f"{metrics.memory_usage_percent:.1f}%",
                        threshold=f"{mem_rule.get('threshold', 90)}%",
                    )
                else:
                    await self._resolve_rule(session, server.id, "server_memory_high")

            # 3. 磁盘告警
            disk_rule = self.rules.get("server_disk_high", {})
            if disk_rule:
                if metrics.disk_usage_percent > disk_rule.get("threshold", 90):
                    await self._create_alert(
                        session=session,
                        device_id=server.id,
                        device_name=server.name,
                        device_type="server",
                        rule_name="server_disk_high",
                        severity=disk_rule.get("severity", "WARNING"),
                        title=f"[{server.name}] 磁盘空间不足: {metrics.disk_usage_percent:.1f}%",
                        metric_value=f"{metrics.disk_usage_percent:.1f}%",
                        threshold=f"{disk_rule.get('threshold', 90)}%",
                    )
                else:
                    await self._resolve_rule(session, server.id, "server_disk_high")

            # 4. 关键进程 Down 告警
            proc_rule = self.rules.get("server_process_down", {})
            if proc_rule:
                proc_result = await session.execute(
                    select(ProcessStatus).where(
                        and_(
                            ProcessStatus.server_id == server.id,
                            ProcessStatus.is_critical == True,
                            ProcessStatus.is_running == False,
                        )
                    )
                )
                # 只取最新一轮的数据
                down_procs = proc_result.scalars().all()
                if down_procs:
                    proc_names = [p.process_name for p in down_procs]
                    await self._create_alert(
                        session=session,
                        device_id=server.id,
                        device_name=server.name,
                        device_type="server",
                        rule_name="server_process_down",
                        severity=proc_rule.get("severity", "CRITICAL"),
                        title=f"[{server.name}] 关键进程异常: {', '.join(proc_names)}",
                        description=f"服务器 {server.name} 关键进程 {', '.join(proc_names)} 未运行",
                    )
                else:
                    await self._resolve_rule(session, server.id, "server_process_down")

    async def check_all(self):
        """执行一轮完整的告警检查 (可被定时任务或"采集完成后"即时调用)"""
        if not self.alert_config.get("enabled", True):
            return
        # 防重入: 上一轮仍在执行则跳过, 避免定时检查与采集后即时检查重复处理
        if self._lock.locked():
            logger.debug("告警检查进行中, 跳过本轮")
            return

        async with self._lock:
            try:
                async with async_session() as session:
                    await self.maintenance.refresh(session)
                    await self.check_switch_alerts(session)
                    await self.check_server_alerts(session)
                    await session.commit()

                    # 发送通知
                    result = await session.execute(
                        select(AlertRecord).where(
                            and_(
                                AlertRecord.notified == False,  # noqa: E712
                                AlertRecord.status == "active",
                            )
                        )
                    )
                    pending_alerts = result.scalars().all()

                    for alert in pending_alerts:
                        sent = await self.notifier.send_alert(alert)
                        if sent:
                            alert.notified = True
                            alert.notify_channels = self.notifier.get_active_channels()

                    await session.commit()

                    # 更新内存实时态并推送最新告警统计 (SSE)
                    active = (await session.execute(
                        select(func.count(AlertRecord.id)).where(AlertRecord.status == "active")
                    )).scalar() or 0
                    critical = (await session.execute(
                        select(func.count(AlertRecord.id)).where(
                            and_(AlertRecord.status == "active", AlertRecord.severity == "CRITICAL")
                        )
                    )).scalar() or 0
                    live.set_alert_counts(active, critical)
                    live.publish_overview()
                    bus.publish("alerts", live.alerts)

            except Exception as e:
                logger.error(f"告警检查异常: {e}", exc_info=True)

    async def resolve_alert(self, alert_id: str, resolved_by: str = "system"):
        """标记告警为已恢复"""
        async with async_session() as session:
            result = await session.execute(
                select(AlertRecord).where(AlertRecord.alert_id == alert_id)
            )
            alert = result.scalar_one_or_none()
            if alert and alert.status == "active":
                alert.status = "resolved"
                alert.resolved_at = datetime.now()
                alert.resolved_by = resolved_by
                await session.commit()

    async def acknowledge_alert(self, alert_id: str, acknowledged_by: str):
        """确认告警"""
        async with async_session() as session:
            result = await session.execute(
                select(AlertRecord).where(AlertRecord.alert_id == alert_id)
            )
            alert = result.scalar_one_or_none()
            if alert and alert.status == "active":
                alert.status = "acknowledged"
                alert.acknowledged_by = acknowledged_by
                await session.commit()
