"""
任务调度器 - 基于 APScheduler 管理所有定时采集任务
- SNMP 交换机采集 (端口状态/流量/硬件健康)
- 服务器资源采集
- 进程/服务监控
- 告警检查
"""

import asyncio
import logging

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger
from apscheduler.triggers.cron import CronTrigger

from backend.config import config
from backend.collectors.snmp_collector import SNMPCollector
from backend.collectors.server_agent import ServerCollector
from backend.collectors.process_monitor import ProcessMonitor
from backend.alerts.alert_engine import AlertEngine
from backend.maintenance.cleanup import DataMaintenance
from backend.inspection.service import InspectionService
from backend.proactive.engine import ProactiveEngine

logger = logging.getLogger(__name__)


class TaskScheduler:
    """定时任务调度器"""

    def __init__(self):
        self.scheduler = AsyncIOScheduler(timezone="Asia/Shanghai")
        self.snmp_collector = SNMPCollector()
        self.server_collector = ServerCollector()
        self.process_monitor = ProcessMonitor()
        self.alert_engine = AlertEngine()
        self.maintenance = DataMaintenance()
        self.inspection_service = InspectionService()
        self.proactive_engine = ProactiveEngine()
        # 事件驱动: 端口状态采集完成后立即评估告警 (把告警时延从 30s 周期降到一轮采集时间)
        self.snmp_collector.on_cycle_complete = self.alert_engine.check_all

    def setup_jobs(self):
        """配置所有定时任务"""
        snmp_cfg = config.snmp
        intervals = snmp_cfg.get("poll_intervals", {})

        # 1. 交换机端口状态 + 系统信息采集 (高频: 近实时反映端口 up/down)
        port_interval = intervals.get("port_status", 60)
        self.scheduler.add_job(
            self.snmp_collector.poll_all_status,
            trigger=IntervalTrigger(seconds=port_interval),
            id="snmp_poll_status",
            name="SNMP 端口状态/系统信息采集",
            max_instances=1,
            coalesce=True,
        )

        # 1b. 端口流量统计采集 (中频: 流量趋势)
        traffic_interval = intervals.get("traffic_stats", 120)
        self.scheduler.add_job(
            self.snmp_collector.poll_all_traffic,
            trigger=IntervalTrigger(seconds=traffic_interval),
            id="snmp_poll_traffic",
            name="SNMP 端口流量采集",
            max_instances=1,
            coalesce=True,
        )

        # 1c. 硬件健康采集 (低频: 温度/电源变化缓慢)
        hardware_interval = intervals.get("hardware_health", 300)
        self.scheduler.add_job(
            self.snmp_collector.poll_all_hardware,
            trigger=IntervalTrigger(seconds=hardware_interval),
            id="snmp_poll_hardware",
            name="SNMP 硬件健康采集",
            max_instances=1,
            coalesce=True,
        )

        # 2. 服务器资源采集
        server_interval = config.server_monitor.get("poll_interval", 30)
        self.scheduler.add_job(
            self.server_collector.poll_all_servers,
            trigger=IntervalTrigger(seconds=server_interval),
            id="server_poll_all",
            name="服务器资源采集",
            max_instances=1,
            coalesce=True,
        )

        # 3. 进程/服务监控
        proc_interval = config.process_monitor.get("poll_interval", 60)
        self.scheduler.add_job(
            self.process_monitor.poll_all_processes,
            trigger=IntervalTrigger(seconds=proc_interval),
            id="process_monitor_all",
            name="进程/服务监控",
            max_instances=1,
            coalesce=True,
        )

        # 4. 告警检查
        alert_interval = config.alerts.get("check_interval", 30)
        self.scheduler.add_job(
            self.alert_engine.check_all,
            trigger=IntervalTrigger(seconds=alert_interval),
            id="alert_check",
            name="告警规则检查",
            max_instances=1,
            coalesce=True,
        )

        # 5. 定期设备发现 (每 5 分钟重新扫描，发现新上线的设备)
        self.scheduler.add_job(
            self._rediscover_devices,
            trigger=IntervalTrigger(minutes=5),
            id="device_rediscovery",
            name="设备重新发现",
            max_instances=1,
            coalesce=True,
        )

        # 6. 数据保留清理 (每天 03:30, 低峰期) - 防止时序数据无限增长
        self.scheduler.add_job(
            self.maintenance.run_cleanup,
            trigger=CronTrigger(hour=3, minute=30),
            id="data_cleanup",
            name="数据保留清理",
            max_instances=1,
            coalesce=True,
        )

        # 7. 点检任务生成 (按计划周期生成待执行任务 + 标记逾期)
        inspect_interval = config.inspection.get("task_generation_interval", 60)
        self.scheduler.add_job(
            self.inspection_service.run,
            trigger=IntervalTrigger(seconds=inspect_interval),
            id="inspection_generate",
            name="点检任务生成",
            max_instances=1,
            coalesce=True,
        )

        # 8. 主动运维分析 (动态基线 -> 异常检测 -> 健康评分)
        proactive_interval = config.proactive.get("analyze_interval", 300)
        if config.proactive.get("enabled", True):
            self.scheduler.add_job(
                self.proactive_engine.analyze,
                trigger=IntervalTrigger(seconds=proactive_interval),
                id="proactive_analyze",
                name="主动运维分析",
                max_instances=1,
                coalesce=True,
            )

        logger.info(
            f"定时任务已配置: "
            f"端口状态={port_interval}s, 流量={traffic_interval}s, 硬件={hardware_interval}s, "
            f"服务器={server_interval}s, 进程={proc_interval}s, 告警={alert_interval}s, "
            f"设备发现=5min, 数据清理=每日03:30, 点检生成={inspect_interval}s, "
            f"主动分析={proactive_interval}s"
        )

    def start(self):
        """启动调度器"""
        self.setup_jobs()
        self.scheduler.start()
        logger.info("任务调度器已启动")

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
        """获取所有任务信息"""
        jobs = []
        for job in self.scheduler.get_jobs():
            jobs.append({
                "id": job.id,
                "name": job.name,
                "next_run": str(job.next_run_time) if job.next_run_time else None,
                "trigger": str(job.trigger),
            })
        return jobs

    def pause_job(self, job_id: str):
        """暂停指定任务"""
        self.scheduler.pause_job(job_id)
        logger.info(f"任务已暂停: {job_id}")

    def resume_job(self, job_id: str):
        """恢复指定任务"""
        self.scheduler.resume_job(job_id)
        logger.info(f"任务已恢复: {job_id}")

    async def run_job_now(self, job_id: str):
        """立即执行一次指定任务"""
        job = self.scheduler.get_job(job_id)
        if job:
            await job.func()
            logger.info(f"任务已手动执行: {job_id}")
        else:
            raise ValueError(f"任务不存在: {job_id}")

    async def _rediscover_devices(self):
        """定期重新扫描 IP 范围，发现新上线的设备"""
        from backend.main import run_device_discovery
        try:
            await run_device_discovery()
        except Exception as e:
            logger.warning(f"设备重新发现失败: {e}")
