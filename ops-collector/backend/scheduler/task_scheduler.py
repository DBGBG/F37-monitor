"""任务调度器 - 采集层只保留 6 个采集类任务

周期与开关来自 MySQL 的 collector_jobs 表 (由 Spring Boot 业务层维护),
每 job_reload_seconds (默认 300s) 热加载一次; 告警检查/数据清理/点检生成/主动分析
四类业务任务已迁至业务层, 采集层不再注册。

同时轮询 collector_commands 表执行业务层下发的指令:
  rediscover     立即重新发现设备
  reload_config  重读 collector.yaml 并重建采集任务
  collect_now    立即定向采集一台设备 (payload.device_id)
"""

import asyncio
import logging
import time
from datetime import datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger
from sqlalchemy import select, update

from backend.config import config
from backend.collectors.process_monitor import ProcessMonitor
from backend.collectors.server_agent import ServerCollector
from backend.collectors.snmp_collector import SNMPCollector
from backend.collectors.state import prune_heartbeats, write_heartbeat
from backend.database import async_session
from backend.models.collector import CollectorCommand, CollectorJob

logger = logging.getLogger(__name__)

# job_id -> (任务名, 兜底周期秒, 兜底超时秒)
JOB_DEFS: dict[str, tuple[str, int, int]] = {
    "snmp_poll_status": ("SNMP 端口状态/系统信息采集", 120, 120),
    "snmp_poll_traffic": ("SNMP 端口流量采集", 300, 180),
    "snmp_poll_hardware": ("SNMP 硬件健康采集", 600, 300),
    "server_poll_all": ("服务器资源采集", 30, 120),
    "process_monitor_all": ("进程/服务监控", 60, 120),
    "device_rediscovery": ("设备重新发现", 1800, 600),
}


class TaskScheduler:
    """采集任务调度器"""

    # 设备发现退避上限: 网段持续无存活设备时, 扫描周期最长不超过 4 小时
    MAX_DISCOVERY_INTERVAL_MIN = 240

    def __init__(self):
        self.scheduler = AsyncIOScheduler(timezone="Asia/Shanghai")
        self.snmp_collector = SNMPCollector()
        self.server_collector = ServerCollector()
        self.process_monitor = ProcessMonitor()
        # 设备发现当前周期 (分钟), 无存活设备时自适应加倍
        self._discovery_interval_min = max(
            int(config.snmp.get("discovery_interval_minutes", 30)), 1
        ) * 60
        self._job_intervals: dict[str, int] = {}
        self._job_timeouts: dict[str, int] = {}

    # ---- 任务配置读取 ----

    async def load_jobs(self) -> dict[str, dict]:
        """从 collector_jobs 表读取周期与开关; 表为空时回落到 collector.yaml 的兜底值"""
        try:
            async with async_session() as session:
                rows = (await session.execute(select(CollectorJob))).scalars().all()
        except Exception as e:
            logger.warning("读取 collector_jobs 失败, 使用本地兜底周期: %s", e)
            rows = []

        result: dict[str, dict] = {}
        for job_id, (name, default_interval, default_timeout) in JOB_DEFS.items():
            result[job_id] = {
                "name": name,
                "interval": default_interval,
                "timeout": default_timeout,
                "enabled": True,
            }
        for row in rows:
            if row.job_id not in JOB_DEFS:
                logger.debug("忽略未知的采集任务配置: %s", row.job_id)
                continue
            result[row.job_id].update({
                "name": row.name or JOB_DEFS[row.job_id][0],
                "interval": max(int(row.interval_seconds or 60), 5),
                "timeout": max(int(row.timeout_seconds or 120), 10),
                "enabled": bool(row.enabled),
            })
        # 设备发现的兜底周期取 snmp.discovery_interval_minutes
        discovery_min = int(config.snmp.get("discovery_interval_minutes", 30))
        if "device_rediscovery" not in {r.job_id for r in rows}:
            result["device_rediscovery"]["interval"] = discovery_min * 60
        return result

    def _job_funcs(self) -> dict[str, callable]:
        return {
            "snmp_poll_status": self._wrap_status,
            "snmp_poll_traffic": self._wrap_traffic,
            "snmp_poll_hardware": self._wrap_hardware,
            "server_poll_all": self._wrap_server,
            "process_monitor_all": self._wrap_process,
            "device_rediscovery": self._rediscover_devices,
        }

    # 各采集入口的超时参数由 collector_jobs.timeout_seconds 传入
    async def _wrap_status(self):
        await self.snmp_collector.poll_all_status(timeout=self._job_timeouts.get("snmp_poll_status", 120))

    async def _wrap_traffic(self):
        await self.snmp_collector.poll_all_traffic(timeout=self._job_timeouts.get("snmp_poll_traffic", 180))

    async def _wrap_hardware(self):
        await self.snmp_collector.poll_all_hardware(timeout=self._job_timeouts.get("snmp_poll_hardware", 300))

    async def _wrap_server(self):
        await self.server_collector.poll_all_servers(timeout=self._job_timeouts.get("server_poll_all", 120))

    async def _wrap_process(self):
        await self.process_monitor.poll_all_processes(timeout=self._job_timeouts.get("process_monitor_all", 120))

    def _apply(self, jobs: dict):
        """按最新配置注册/调整任务 (周期变化则 reschedule, 关闭则 pause)"""
        funcs = self._job_funcs()
        summary = []
        for job_id, cfg in jobs.items():
            interval = int(cfg["interval"])
            if job_id == "device_rediscovery":
                interval = max(interval, self._discovery_interval_min)
                self._discovery_interval_min = interval
            timeout = int(cfg["timeout"])
            changed = self._job_intervals.get(job_id) != interval
            self._job_intervals[job_id] = interval
            self._job_timeouts[job_id] = timeout

            if job_id not in {j.id for j in self.scheduler.get_jobs()}:
                self.scheduler.add_job(
                    funcs[job_id],
                    trigger=IntervalTrigger(seconds=interval),
                    id=job_id,
                    name=cfg["name"],
                    max_instances=1,
                    coalesce=True,
                )
            else:
                if changed:
                    self.scheduler.reschedule_job(job_id, trigger=IntervalTrigger(seconds=interval))
                if cfg["enabled"]:
                    self.scheduler.resume_job(job_id)
                else:
                    self.scheduler.pause_job(job_id)
            summary.append(f"{job_id}={interval}s{'/off' if not cfg['enabled'] else ''}")
        logger.info("采集任务配置: " + ", ".join(summary))

    async def setup_jobs(self):
        """配置采集任务 + 心跳/配置热加载 + 指令轮询"""
        jobs = await self.load_jobs()
        self._apply(jobs)

        sched_cfg = config.scheduler
        # 周期热加载: 业务层改了 collector_jobs 后最多 5 分钟生效
        self.scheduler.add_job(
            self._reload_jobs,
            trigger=IntervalTrigger(seconds=int(sched_cfg.get("job_reload_seconds", 300))),
            id="job_config_reload",
            name="采集任务配置热加载",
            max_instances=1,
            coalesce=True,
        )
        # 指令轮询: 业务层通过 collector_commands 下发 rediscover/reload_config/collect_now
        self.scheduler.add_job(
            self._poll_commands,
            trigger=IntervalTrigger(seconds=int(sched_cfg.get("command_poll_seconds", 10))),
            id="command_poll",
            name="业务层指令轮询",
            max_instances=1,
            coalesce=True,
        )
        # 启动心跳: 让业务层立刻知道采集层已上线
        await write_heartbeat(
            phase="startup", total=len(jobs), success=len(jobs), errors=0,
            duration_seconds=0, detail={"pid": __import__("os").getpid()},
        )

    async def _reload_jobs(self):
        try:
            self._apply(await self.load_jobs())
        except Exception as e:
            logger.warning("采集任务配置热加载失败: %s", e)

    def start(self):
        """启动调度器"""
        self.scheduler.start()
        logger.info("任务调度器已启动 (采集层, 仅采集任务)")

    def stop(self):
        """停止调度器"""
        self.scheduler.shutdown(wait=False)
        # 释放 SNMP 采集线程池 (puresnmp 阻塞式 I/O 在独立线程中执行)
        try:
            self.snmp_collector.executor.shutdown(wait=False)
        except Exception:
            pass
        logger.info("任务调度器已停止")

    def get_jobs_info(self) -> list[dict]:
        """获取所有任务信息 (供 daemon 日志输出)"""
        jobs = []
        for job in self.scheduler.get_jobs():
            jobs.append({
                "id": job.id,
                "name": job.name,
                "next_run": str(job.next_run_time) if job.next_run_time else None,
                "trigger": str(job.trigger),
            })
        return jobs

    # ---- 设备发现 ----

    async def _rediscover_devices(self):
        """定期重新扫描网段, 发现新上线设备 (无存活设备时自适应延长周期)"""
        from backend.daemon import run_device_discovery

        started = time.monotonic()
        try:
            found = await run_device_discovery()
            self._adjust_discovery_interval(found)
            await write_heartbeat(
                phase="发现", total=found, success=found, errors=0,
                duration_seconds=time.monotonic() - started,
                detail={"interval_minutes": self._discovery_interval_min // 60},
            )
        except Exception as e:
            logger.warning(f"设备重新发现失败: {e}")
            await write_heartbeat(
                phase="发现", total=0, success=0, errors=1,
                duration_seconds=time.monotonic() - started, detail={"error": str(e)[:200]},
            )
        # 心跳表只保留最近若干天, 避免无限增长 (数据保留策略对业务表由 Java 负责)
        try:
            await prune_heartbeats(int(config.scheduler.get("heartbeat_retention_days", 7)))
        except Exception as e:
            logger.debug("心跳清理失败: %s", e)

    def _adjust_discovery_interval(self, found_count: int):
        """根据本轮发现结果动态调整设备扫描周期 (无存活则加倍, 有发现则恢复基准值)"""
        job = self.scheduler.get_job("device_rediscovery")
        if job is None:
            return
        base_min = int(config.snmp.get("discovery_interval_minutes", 30))
        current_min = max(self._discovery_interval_min // 60, 1)
        new_min = base_min if found_count > 0 else min(current_min * 2, self.MAX_DISCOVERY_INTERVAL_MIN)
        if new_min == current_min:
            return
        self._discovery_interval_min = new_min * 60
        self._job_intervals["device_rediscovery"] = new_min * 60
        self.scheduler.reschedule_job(
            "device_rediscovery", trigger=IntervalTrigger(minutes=new_min)
        )
        logger.info(f"设备发现周期已调整为 {new_min} 分钟 (本轮存活: {found_count})")

    # ---- 业务层指令 ----

    async def _poll_commands(self):
        """拉取并执行 collector_commands 中 pending 的指令"""
        try:
            async with async_session() as session:
                pending = (await session.execute(
                    select(CollectorCommand)
                    .where(CollectorCommand.status == "pending")
                    .order_by(CollectorCommand.id)
                    .limit(20)
                )).scalars().all()
                ids = [c.id for c in pending]
                if ids:
                    await session.execute(
                        update(CollectorCommand)
                        .where(CollectorCommand.id.in_(ids))
                        .values(status="running")
                    )
                    await session.commit()
                commands = [(c.id, c.cmd, dict(c.payload or {})) for c in pending]
        except Exception as e:
            logger.warning("读取采集指令失败: %s", e)
            return

        for cid, cmd, payload in commands:
            ok = True
            message = ""
            try:
                if cmd == "rediscover":
                    found = await self._rediscover_and_count()
                    message = f"发现 {found} 台设备"
                elif cmd == "reload_config":
                    config.reload()
                    self._apply(await self.load_jobs())
                    message = "配置已重载"
                elif cmd == "collect_now":
                    message = await self._collect_now(payload.get("device_id") or payload.get("ip", ""))
                else:
                    ok = False
                    message = f"未知指令: {cmd}"
            except Exception as e:
                ok = False
                message = str(e)[:400]
                logger.error("执行采集指令异常 [%s]: %s", cmd, e, exc_info=True)
            try:
                async with async_session() as session:
                    await session.execute(
                        update(CollectorCommand)
                        .where(CollectorCommand.id == cid)
                        .values(status="done" if ok else "failed",
                                result=message[:500], executed_at=datetime.now())
                    )
                    await session.commit()
            except Exception as e:
                logger.warning("回写采集指令状态失败: %s", e)

    async def _collect_now(self, device_ref: str) -> str:
        """立即定向采集一台交换机 (device_id 或 ip)"""
        from backend.models.device import SwitchDevice

        if not device_ref:
            return "缺少 device_id"
        async with async_session() as session:
            stmt = select(SwitchDevice).where(SwitchDevice.id == device_ref)
            sw = (await session.execute(stmt)).scalar_one_or_none()
            if sw is None:
                sw = (await session.execute(
                    select(SwitchDevice).where(SwitchDevice.ip == device_ref)
                )).scalar_one_or_none()
            if sw is None:
                return f"设备不存在: {device_ref}"
            cfg = {
                "id": sw.id, "name": sw.name, "ip": sw.ip,
                "snmp": sw.snmp_config or {"version": "v2c", "community": "public"},
                "ssh": sw.ssh_config or {},
            }
        await self.snmp_collector.poll_switch_status(cfg, force=True)
        await self.snmp_collector.poll_switch_traffic(cfg)
        await self.snmp_collector.poll_switch_hardware(cfg)
        return f"已采集 {cfg['name']}({cfg['ip']})"

    async def _rediscover_and_count(self) -> int:
        """供指令执行调用: 跑一轮设备发现并返回存活设备数"""
        from backend.daemon import run_device_discovery

        return await run_device_discovery()
