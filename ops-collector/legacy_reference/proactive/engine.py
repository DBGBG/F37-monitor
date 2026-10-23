"""主动运维引擎

一次分析(analyze)完成:
1. 采集各设备的最新指标样本 (温度/CPU/内存/磁盘/端口误码增量)
2. 用 EWMA 动态基线判定是否偏离"正常" (而非固定阈值)
3. 维护异常事件生命周期 (开启 -> 持续合并 -> 自动恢复), 高危异常升级为告警
4. 计算并更新设备健康评分
5. 维护窗口内静默, 设备离线时不再产生子指标噪音
"""

import logging
from datetime import datetime, timedelta

from sqlalchemy import select, func, desc

from backend.config import config
from backend.database import async_session
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.hardware_health import HardwareHealth
from backend.models.server_metrics import ServerMetrics
from backend.models.port_status import PortTrafficStats
from backend.models.alert import AlertRecord
from backend.models.proactive import MetricBaseline, AnomalyEvent, DeviceHealth
from backend.proactive.baseline import EWMAState, ewma_update, evaluate
from backend.proactive.health import compute_health
from backend.alerts.alert_engine import AlertEngine

logger = logging.getLogger(__name__)

SEVERITY_RANK = {"INFO": 0, "WARNING": 1, "CRITICAL": 2}

_METRIC_META = {
    "temperature": ("设备温度", "°C"),
    "cpu": ("CPU 使用率", "%"),
    "memory": ("内存使用率", "%"),
    "disk": ("磁盘使用率", "%"),
    "port_error": ("端口误码增量", "个"),
}


class ProactiveEngine:
    """主动运维分析引擎"""

    def __init__(self):
        self.alert_engine = AlertEngine()
        self.maintenance = self.alert_engine.maintenance

    # ---------- 对外入口 ----------

    async def analyze(self, now: datetime | None = None) -> dict:
        """执行一轮主动分析"""
        cfg = config.proactive
        stats = {"devices": 0, "anomalies": 0, "resolved": 0, "escalated": 0}
        if not cfg.get("enabled", True):
            return {"skipped": "proactive disabled"}

        now = now or datetime.now()
        self._now = now
        self._alpha = float(cfg.get("ewma_alpha", 0.15))
        self._k = float(cfg.get("anomaly_k", 4.0))
        self._min_samples = int(cfg.get("min_samples", 20))
        self._metric_cfg = cfg.get("metrics", {}) or {}
        self._weights = cfg.get("health_weights", {}) or {}
        self._dedup = timedelta(minutes=float(cfg.get("dedup_minutes", 30)))
        self._outlier_damping = float(cfg.get("outlier_damping", 0.1))
        self._escalate_rank = SEVERITY_RANK.get(cfg.get("escalate_severity", "CRITICAL"), 99)

        try:
            await self.maintenance.refresh()
            async with async_session() as session:
                switches = (await session.execute(
                    select(SwitchDevice).where(SwitchDevice.enabled == True)  # noqa: E712
                )).scalars().all()
                servers = (await session.execute(
                    select(ServerDevice).where(ServerDevice.enabled == True)  # noqa: E712
                )).scalars().all()

                # 预加载: 基线 / 未关闭异常 / 活动告警 / 健康快照
                baselines = {
                    (b.device_id, b.metric): b
                    for b in (await session.execute(select(MetricBaseline))).scalars().all()
                }
                open_events = {
                    (e.device_id, e.metric): e
                    for e in (await session.execute(
                        select(AnomalyEvent).where(AnomalyEvent.status.in_(["open", "acknowledged"]))
                    )).scalars().all()
                }
                alerts_by_dev: dict = {}
                for did, sev, cnt in (await session.execute(
                    select(AlertRecord.device_id, AlertRecord.severity, func.count())
                    .where(AlertRecord.status == "active")
                    .group_by(AlertRecord.device_id, AlertRecord.severity)
                )).all():
                    alerts_by_dev.setdefault(did, {})[sev] = cnt
                health_map = {
                    h.device_id: h
                    for h in (await session.execute(select(DeviceHealth))).scalars().all()
                }

                # 最新样本来源
                hw_map = await self._latest_per(session, HardwareHealth, "switch_id")
                sm_map = await self._latest_per(session, ServerMetrics, "server_id")
                port_err = await self._port_error_deltas(session, now, cfg)

                for sw in switches:
                    samples = self._switch_samples(sw, hw_map.get(sw.id), port_err.get(sw.id))
                    await self._process_device(
                        session, sw.id, sw.name, "switch", sw.is_online, hw_map.get(sw.id),
                        samples, baselines, open_events, alerts_by_dev, health_map, stats,
                    )
                    stats["devices"] += 1

                for srv in servers:
                    samples = self._server_samples(sm_map.get(srv.id))
                    await self._process_device(
                        session, srv.id, srv.name, "server", srv.is_online, None,
                        samples, baselines, open_events, alerts_by_dev, health_map, stats,
                    )
                    stats["devices"] += 1

                await session.commit()

            if stats["anomalies"] or stats["resolved"] or stats["escalated"]:
                logger.info(
                    f"主动分析: 设备 {stats['devices']} 台, "
                    f"新增/持续异常 {stats['anomalies']}, 恢复 {stats['resolved']}, "
                    f"升级告警 {stats['escalated']}"
                )
            else:
                logger.debug(f"主动分析完成: {stats['devices']} 台, 无异常")
            return stats

        except Exception as e:
            logger.error(f"主动分析异常: {e}", exc_info=True)
            return {"error": str(e)}

    # ---------- 样本构造 ----------

    def _switch_samples(self, sw, hw, port_err) -> dict:
        """构造交换机指标样本: metric -> (value, sample_time, unit)"""
        samples = {}
        if hw is not None:
            temp = float(hw.temperature or 0)
            if temp > 0:
                samples["temperature"] = (temp, hw.timestamp, "°C")
        if sw.cpu_usage and sw.cpu_usage > 0:
            samples["cpu"] = (float(sw.cpu_usage), sw.last_poll_time, "%")
        if sw.memory_usage and sw.memory_usage > 0:
            samples["memory"] = (float(sw.memory_usage), sw.last_poll_time, "%")
        if port_err is not None:
            value, ts = port_err
            samples["port_error"] = (float(value), ts, "个")
        return samples

    def _server_samples(self, sm) -> dict:
        samples = {}
        if sm is None:
            return samples
        for metric, attr in (("cpu", "cpu_usage_percent"), ("memory", "memory_usage_percent"), ("disk", "disk_usage_percent")):
            val = getattr(sm, attr, None)
            if val is not None:
                samples[metric] = (float(val), sm.timestamp, "%")
        return samples

    # ---------- 单设备处理 ----------

    async def _process_device(
        self, session, device_id, device_name, device_type, is_online, hw,
        samples, baselines, open_events, alerts_by_dev, health_map, stats,
    ):
        device_anom: dict = {}

        if is_online:
            for metric, (value, sample_time, unit) in samples.items():
                mcfg = self._metric_cfg.get(metric)
                if not mcfg:
                    continue
                key = (device_id, metric)
                b = baselines.get(key)
                if b is None:
                    b = MetricBaseline(
                        device_id=device_id, device_type=device_type, metric=metric,
                        mean=value, variance=0.0, sample_count=0, last_value=value,
                    )
                    baselines[key] = b
                    session.add(b)

                # 去重: 同一采集样本不重复喂给基线 (采集频率可能高于分析频率)
                if b.sample_count > 0 and sample_time and b.updated_at and sample_time <= b.updated_at:
                    continue

                state = EWMAState(b.mean, b.variance, b.sample_count)
                hit, absdev, z = evaluate(mcfg, state, value, self._k, self._min_samples)

                # 基线自适应: 正常样本快速跟随(适应季节/负载漂移);
                # 异常样本几乎不更新(增量很小), 避免故障值把基线"带偏"从而导致持续故障停报
                adaptive_alpha = self._alpha if not hit else self._alpha * self._outlier_damping
                ns = ewma_update(state, value, adaptive_alpha)
                b.mean, b.variance, b.sample_count = ns.mean, ns.variance, ns.count
                b.last_value = value
                b.updated_at = self._now

                if hit:
                    critical = z >= self._k * 1.5 or absdev >= 2 * float(mcfg.get("min_deviation", 0) or 0)
                    device_anom[metric] = (
                        value, state.mean, absdev, z, unit,
                        "CRITICAL" if critical else "WARNING",
                    )

        # 异常事件生命周期
        for metric, (value, bmean, absdev, z, unit, severity) in device_anom.items():
            key = (device_id, metric)
            title, desc = self._describe(device_name, metric, value, bmean, severity, unit)
            suppressed, win_name = self.maintenance.is_suppressed(device_id, device_type, self._now)

            ev = open_events.get(key)
            if ev is None:
                ev = AnomalyEvent(
                    device_id=device_id, device_name=device_name, device_type=device_type,
                    metric=metric, severity=severity, title=title, description=desc,
                    value=value, baseline=bmean, deviation=absdev, score=z, unit=unit,
                    status="open", occurred_at=self._now, last_seen_at=self._now,
                )
                session.add(ev)
                open_events[key] = ev
                stats["anomalies"] += 1
            else:
                ev.value, ev.baseline, ev.deviation, ev.score = value, bmean, absdev, z
                ev.severity, ev.title, ev.description = severity, title, desc
                ev.last_seen_at = self._now

            # 高危异常升级为告警 (维护窗口内不打扰)
            if SEVERITY_RANK.get(severity, 0) >= self._escalate_rank and not suppressed:
                await self.alert_engine._create_alert(
                    session=session, device_id=device_id, device_name=device_name,
                    device_type=device_type, rule_name=f"anomaly_{metric}",
                    severity=severity, title=title, description=desc,
                    metric_value=f"{value:.1f}{unit}", threshold=f"基线 {bmean:.1f}{unit}",
                )
                ev.escalated = True
                stats["escalated"] += 1

        # 自动恢复: 本轮有样本且不再异常 -> 关闭事件
        present = set(samples.keys())
        for key, ev in list(open_events.items()):
            if key[0] != device_id:
                continue
            if key[1] in present and key[1] not in device_anom:
                ev.status = "resolved"
                ev.resolved_at = self._now
                del open_events[key]
                stats["resolved"] += 1
                await self.alert_engine._resolve_rule(session, device_id, f"anomaly_{key[1]}")

        # 健康评分
        open_count = sum(
            1 for k, e in open_events.items()
            if k[0] == device_id and e.status in ("open", "acknowledged")
        )
        score, grade, factors, summary = compute_health(
            device_type, is_online, hw, open_count, alerts_by_dev.get(device_id, {}), self._weights,
        )
        h = health_map.get(device_id)
        if h is None:
            h = DeviceHealth(device_id=device_id)
            session.add(h)
            health_map[device_id] = h
        h.device_name, h.device_type = device_name, device_type
        h.score, h.grade, h.factors, h.summary = score, grade, factors, summary
        h.updated_at = self._now

    def _describe(self, device_name, metric, value, baseline, severity, unit) -> tuple[str, str]:
        name, default_unit = _METRIC_META.get(metric, (metric, ""))
        unit = unit or default_unit
        arrow = "高于" if value >= baseline else "低于"
        title = f"[{device_name}] {name}异常: {value:.1f}{unit}"
        desc = (
            f"设备 {device_name} 的{name}为 {value:.1f}{unit}, "
            f"{arrow}基线 {baseline:.1f}{unit} (偏离 {abs(value - baseline):.1f}{unit})"
        )
        return title, desc

    # ---------- 数据查询辅助 ----------

    async def _latest_per(self, session, model, fk: str) -> dict:
        """每台设备取最新一条记录 (单次窗口函数查询, 避免 N+1)"""
        rn = func.row_number().over(
            partition_by=getattr(model, fk), order_by=desc(model.timestamp)
        ).label("rn")
        subq = select(model.id.label("id"), rn).subquery()
        rows = (await session.execute(
            select(model).join(subq, model.id == subq.c.id).where(subq.c.rn == 1)
        )).scalars().all()
        return {getattr(r, fk): r for r in rows}

    async def _port_error_deltas(self, session, now: datetime, cfg: dict) -> dict:
        """计算每台交换机 "本窗口内新增误码+丢弃" 总量。

        数据来源于累计计数器, 取每个端口最近两条记录求增量(处理计数器回绕)。
        返回: {switch_id: (新增总数, 最新样本时间)}
        """
        hours = float(cfg.get("port_error_window_hours", 2))
        since = now - timedelta(hours=hours)
        rn = func.row_number().over(
            partition_by=(PortTrafficStats.switch_id, PortTrafficStats.port_index),
            order_by=desc(PortTrafficStats.timestamp),
        ).label("rn")
        subq = select(
            PortTrafficStats.switch_id.label("sid"),
            PortTrafficStats.port_index.label("pi"),
            PortTrafficStats.in_errors, PortTrafficStats.out_errors,
            PortTrafficStats.in_discards, PortTrafficStats.out_discards,
            PortTrafficStats.timestamp.label("ts"),
            rn,
        ).where(PortTrafficStats.timestamp >= since).subquery()
        rows = (await session.execute(select(subq).where(subq.c.rn <= 2))).all()

        per_port: dict = {}
        for r in rows:
            per_port.setdefault((r.sid, r.pi), []).append(r)

        result: dict = {}
        for (sid, _pi), recs in per_port.items():
            recs.sort(key=lambda r: r.ts, reverse=True)
            cur = recs[0]
            latest_ts = cur.ts
            delta = 0
            if len(recs) >= 2:
                prev = recs[1]
                for a, b in (
                    (cur.in_errors, prev.in_errors), (cur.out_errors, prev.out_errors),
                    (cur.in_discards, prev.in_discards), (cur.out_discards, prev.out_discards),
                ):
                    delta += max(0, int(a or 0) - int(b or 0))
            entry = result.get(sid)
            if entry is None:
                result[sid] = [delta, latest_ts]
            else:
                entry[0] += delta
                if latest_ts > entry[1]:
                    entry[1] = latest_ts

        return {sid: (v[0], v[1]) for sid, v in result.items()}

    # ---------- 预防性维护建议 ----------

    @staticmethod
    def build_recommendations(anomalies: list) -> list[dict]:
        """依据未处理异常生成可执行的预防性维护建议"""
        advice = {
            "temperature": ("检查散热与环境", "确认机房/机柜通风、风扇运转, 清理积尘", "high"),
            "cpu": ("排查 CPU 高负载", "检查是否有广播风暴/环路或异常流量, 查看高负载进程", "high"),
            "memory": ("排查内存占用", "确认无内存泄漏, 评估是否需要重启或扩容", "medium"),
            "disk": ("清理磁盘空间", "清理日志/临时文件, 规划扩容, 避免写满导致服务中断", "high"),
            "port_error": ("检查物理链路", "端口误码持续增长, 检查网线/光模块/水晶头与接口接触", "high"),
        }
        items = []
        for a in anomalies:
            action, detail, priority = advice.get(
                a.metric, ("关注该指标", "持续观察该指标变化趋势", "low")
            )
            items.append({
                "device_id": a.device_id,
                "device_name": a.device_name,
                "device_type": a.device_type,
                "metric": a.metric,
                "severity": a.severity,
                "priority": priority,
                "action": action,
                "detail": detail,
                "value": a.value,
                "baseline": a.baseline,
                "unit": a.unit,
                "occurred_at": str(a.occurred_at),
            })
        order = {"high": 0, "medium": 1, "low": 2}
        items.sort(key=lambda x: (order.get(x["priority"], 3), SEVERITY_RANK.get(x["severity"], 0) * -1))
        return items
