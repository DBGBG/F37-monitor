"""
MOXA 主动运维监控系统 - FastAPI 主入口
- 初始化数据库
- 注册 API 路由
- 启动定时调度器
- 挂载前端静态文件
"""

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from backend.config import config
from backend.database import init_db, close_db
from backend.scheduler.task_scheduler import TaskScheduler
from backend.api.switches import router as switches_router
from backend.api.servers import router as servers_router
from backend.api.automation import router as automation_router
from backend.api.alerts import router as alerts_router
from backend.api.system import router as system_router
from backend.api.inspection import router as inspection_router
from backend.api.proactive import router as proactive_router
from backend.api.stream import router as stream_router

# 日志配置
logging.basicConfig(
    level=getattr(logging, config.system.get("log_level", "INFO")),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)

# 全局调度器实例
scheduler: TaskScheduler = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    global scheduler

    logger.info("=" * 60)
    logger.info(f"  {config.system.get('name', 'MOXA 主动运维监控系统')}")
    logger.info("=" * 60)

    # 1. 初始化数据库
    logger.info("初始化数据库...")
    await init_db()

    # 2. 自动发现网络中的真实设备
    logger.info("开始网络设备发现...")
    await run_device_discovery()

    # 3. 同步配置中的静态设备 (服务器等)
    logger.info("同步静态配置...")
    await sync_devices_to_db()

    # 4. 用数据库引导实时内存态 (SSE 首屏快照)
    logger.info("初始化实时状态...")
    await bootstrap_realtime_state()

    # 5. 启动定时调度器
    logger.info("启动任务调度器...")
    scheduler = TaskScheduler()
    scheduler.start()

    # 6. 启动 SNMP Trap 接收器 (事件驱动, 秒级感知端口/电源变化)
    start_trap_receiver(scheduler)

    logger.info("系统启动完成!")
    yield

    # 关闭时清理
    logger.info("正在关闭系统...")
    if trap_receiver:
        trap_receiver.stop()
    if scheduler:
        scheduler.stop()
    await close_db()
    logger.info("系统已关闭")


async def run_device_discovery():
    """运行设备发现 - 扫描 IP 范围，将发现的真实设备入库 (返回本轮存活的 SNMP 设备数)"""
    from backend.collectors.device_discovery import DeviceDiscovery
    from backend.database import async_session
    from backend.models.device import SwitchDevice
    from sqlalchemy import select

    discovery = DeviceDiscovery()

    if not config.switch_groups_raw:
        logger.info("未配置 switch_groups，跳过设备发现")
        return 0

    # 执行扫描
    discovered = await discovery.discover_all()
    logger.info(f"设备发现结果: {len(discovered)} 台真实 SNMP 设备")

    if not discovered:
        logger.warning("未发现任何 SNMP 设备，请检查网络连通性和 SNMP 配置")
        return 0

    # 将发现的设备同步到数据库
    async with async_session() as session:
        for d in discovered:
            ip = d["ip"]

            # 用 IP 查找是否已存在
            result = await session.execute(
                select(SwitchDevice).where(SwitchDevice.ip == ip)
            )
            existing = result.scalar_one_or_none()

            # 从 sys_descr 中提取型号
            model = d.get("model", "")
            descr = d.get("sys_descr", "")
            if not model and descr:
                # "MOXA PT-7828" -> "PT-7828"
                parts = descr.split()
                for p in parts:
                    if any(k in p.upper() for k in ["EDS", "PT-", "ICS-", "TN-"]):
                        model = p
                        break

            device_id = f"sw-{ip.replace('.', '-')}"
            device_name = d.get("sys_name", "") or f"{d.get('group_name', '交换机')}-{ip.split('.')[-1]}"
            device_location = d.get("sys_location", "") or f"{d.get('location_prefix', '')}-{ip}"
            
            if existing:
                existing.is_online = True
                existing.last_poll_time = d.get("discovered_at")
                # 仅在字段缺失时补充, 避免覆盖 SNMP 采集到的真实型号/固件/运行时长
                if not existing.name:
                    existing.name = device_name
                if not existing.location:
                    existing.location = device_location
                if not existing.uptime_seconds:
                    existing.uptime_seconds = d.get("uptime", 0)
            else:
                session.add(SwitchDevice(
                    id=device_id,
                    name=device_name,
                    ip=ip,
                    model=model,
                    location=device_location,
                    firmware_version=descr,
                    is_online=True,
                    uptime_seconds=d.get("uptime", 0),
                    last_poll_time=d.get("discovered_at"),
                    snmp_config={"version": "v2c", "community": d.get("community", "public")},
                    ssh_config=d.get("ssh_config", {}),
                    enabled=True,
                ))

        await session.commit()
        logger.info(f"设备发现同步完成: {len(discovered)} 台设备已入库")
    return len(discovered)


async def sync_devices_to_db():
    """同步静态配置中的设备 (服务器 + 手动定义的交换机)，不影响自动发现的设备"""
    from backend.database import async_session
    from backend.models.device import SwitchDevice, ServerDevice
    from sqlalchemy import select

    async with async_session() as session:
        # 只同步单独定义的交换机 (不触碰自动发现的设备)
        static_switch_ids = {sw["id"] for sw in config.switches}
        if config.switches:
            existing_result = await session.execute(
                select(SwitchDevice.id).where(SwitchDevice.id.in_(static_switch_ids))
            )
            existing_ids = {row[0] for row in existing_result.all()}

            new_switches = []
            for sw in config.switches:
                if sw["id"] in existing_ids:
                    await session.execute(
                        SwitchDevice.__table__.update()
                        .where(SwitchDevice.id == sw["id"])
                        .values(
                            name=sw.get("name", ""),
                            ip=sw["ip"],
                            model=sw.get("model", ""),
                            location=sw.get("location", ""),
                            snmp_config=sw.get("snmp", {}),
                            ssh_config=sw.get("ssh", {}),
                            enabled=sw.get("enabled", True),
                        )
                    )
                else:
                    new_switches.append(SwitchDevice(
                        id=sw["id"],
                        name=sw.get("name", ""),
                        ip=sw["ip"],
                        model=sw.get("model", ""),
                        location=sw.get("location", ""),
                        snmp_config=sw.get("snmp", {}),
                        ssh_config=sw.get("ssh", {}),
                        enabled=sw.get("enabled", True),
                    ))

            if new_switches:
                session.add_all(new_switches)
            logger.info(f"静态交换机同步: {len(config.switches)} 台 ({len(new_switches)} 新增)")
        else:
            logger.info("无静态定义的交换机, 跳过 (已发现设备保持不变)")

        # 同步服务器
        config_server_ids = {srv["id"] for srv in config.servers}
        existing_srv = await session.execute(select(ServerDevice.id))
        existing_srv_ids = {row[0] for row in existing_srv.all()}

        # 清理已移除的服务器
        from sqlalchemy import delete
        stale_srv = existing_srv_ids - config_server_ids
        if stale_srv:
            await session.execute(
                delete(ServerDevice).where(ServerDevice.id.in_(stale_srv))
            )
            logger.info(f"清理 {len(stale_srv)} 台已移除的服务器")

        new_servers = []
        for srv in config.servers:
            if srv["id"] in existing_srv_ids:
                await session.execute(
                    ServerDevice.__table__.update()
                    .where(ServerDevice.id == srv["id"])
                    .values(
                        name=srv.get("name", ""),
                        ip=srv["ip"],
                        os_type=srv.get("os_type", "linux"),
                        location=srv.get("location", ""),
                        monitoring_config=srv.get("monitoring", {}),
                        processes_config=srv.get("processes", []),
                        enabled=srv.get("enabled", True),
                    )
                )
            else:
                new_servers.append(ServerDevice(
                    id=srv["id"],
                    name=srv.get("name", ""),
                    ip=srv["ip"],
                    os_type=srv.get("os_type", "linux"),
                    location=srv.get("location", ""),
                    monitoring_config=srv.get("monitoring", {}),
                    processes_config=srv.get("processes", []),
                    enabled=srv.get("enabled", True),
                ))

        if new_servers:
            session.add_all(new_servers)

        await session.commit()
        logger.info(f"服务器同步: {len(config.servers)} 台 ({len(new_servers)} 新增)")


# 全局 SNMP Trap 接收器
trap_receiver = None


async def bootstrap_realtime_state():
    """用数据库最新状态初始化内存实时态 (SSE 首屏快照)"""
    from backend.database import async_session
    from backend.models.device import SwitchDevice, ServerDevice
    from backend.models.alert import AlertRecord
    from backend.realtime import live
    from sqlalchemy import select, func, and_

    async with async_session() as session:
        for sw in (await session.execute(select(SwitchDevice))).scalars().all():
            live.update_switch(
                sw.id,
                name=sw.name,
                ip=sw.ip,
                is_online=bool(sw.is_online),
                total_ports=sw.total_ports,
                last_poll_time=sw.last_poll_time.strftime("%Y-%m-%d %H:%M:%S") if sw.last_poll_time else None,
            )
        for srv in (await session.execute(select(ServerDevice))).scalars().all():
            live.update_server(srv.id, name=srv.name, ip=srv.ip, is_online=bool(srv.is_online))
        active = (await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "active")
        )).scalar() or 0
        critical = (await session.execute(
            select(func.count(AlertRecord.id)).where(
                and_(AlertRecord.status == "active", AlertRecord.severity == "CRITICAL")
            )
        )).scalar() or 0
        live.set_alert_counts(active, critical)

    logger.info(
        f"实时状态已初始化: {len(live.switches)} 交换机 / {len(live.servers)} 服务器, "
        f"活跃告警 {live.alerts.get('active', 0)}"
    )


def start_trap_receiver(scheduler):
    """启动 SNMP Trap 接收器 (事件驱动: 收到 trap 即定向刷新该设备)"""
    global trap_receiver
    try:
        from backend.traps.receiver import TrapReceiver
        trap_receiver = TrapReceiver(scheduler)
        trap_receiver.start()
    except Exception as e:
        logger.warning(f"SNMP Trap 接收器启动失败 (不影响主服务): {e}")


# 创建 FastAPI 应用
app = FastAPI(
    title="MOXA 主动运维监控系统",
    description="面向 MOXA 工业交换机和服务器的主动运维与监控平台",
    version="1.0.0",
    lifespan=lifespan,
)

# 注册 API 路由
app.include_router(switches_router)
app.include_router(servers_router)
app.include_router(automation_router)
app.include_router(alerts_router)
app.include_router(system_router)
app.include_router(inspection_router)
app.include_router(proactive_router)
app.include_router(stream_router)


# ---- 系统级接口 ----

@app.get("/api/system/status")
async def get_system_status():
    """获取系统运行状态"""
    jobs_info = scheduler.get_jobs_info() if scheduler else []
    return {
        "system_name": config.system.get("name", "MOXA Monitor"),
        "status": "running",
        "scheduler_jobs": jobs_info,
    }


@app.post("/api/system/sync-devices")
async def sync_devices():
    """手动触发设备配置同步"""
    config.reload()
    await sync_devices_to_db()
    return {"status": "success", "message": "设备配置已重新加载"}


@app.post("/api/system/rediscover")
async def rediscover_devices():
    """手动触发设备重新发现"""
    await run_device_discovery()
    # 手动触发视为用户明确意图: 把调度器的自适应周期重置回基准值
    if scheduler:
        scheduler._adjust_discovery_interval(1)
    return {"status": "success", "message": "设备发现完成"}


@app.get("/api/dashboard/overview")
async def get_dashboard_overview():
    """仪表盘总览数据"""
    from backend.database import async_session
    from backend.models.device import SwitchDevice, ServerDevice
    from backend.models.alert import AlertRecord
    from sqlalchemy import select, func, and_

    async with async_session() as session:
        # 从数据库统计实际设备数 (不依赖配置文件)
        sw_total = (await session.execute(
            select(func.count(SwitchDevice.id))
        )).scalar() or 0

        srv_total = (await session.execute(
            select(func.count(ServerDevice.id))
        )).scalar() or 0

        sw_online = (await session.execute(
            select(func.count(SwitchDevice.id)).where(SwitchDevice.is_online == True)
        )).scalar() or 0

        srv_online = (await session.execute(
            select(func.count(ServerDevice.id)).where(ServerDevice.is_online == True)
        )).scalar() or 0

        active_alerts = (await session.execute(
            select(func.count(AlertRecord.id)).where(AlertRecord.status == "active")
        )).scalar() or 0

        critical_alerts = (await session.execute(
            select(func.count(AlertRecord.id)).where(
                and_(AlertRecord.status == "active", AlertRecord.severity == "CRITICAL")
            )
        )).scalar() or 0

    return {
        "switches": {"total": sw_total, "online": sw_online, "offline": sw_total - sw_online},
        "servers": {"total": srv_total, "online": srv_online, "offline": srv_total - srv_online},
        "alerts": {"active": active_alerts, "critical": critical_alerts},
        "total_devices": sw_total + srv_total,
        "online_devices": sw_online + srv_online,
        "online_rate": round(
            (sw_online + srv_online) / (sw_total + srv_total) * 100, 1
        ) if (sw_total + srv_total) > 0 else 0,
    }


# ---- 前端静态文件 ----

FRONTEND_DIR = Path(__file__).parent.parent / "frontend"

# 挂载静态资源
app.mount("/css", StaticFiles(directory=str(FRONTEND_DIR / "css")), name="css")
app.mount("/js", StaticFiles(directory=str(FRONTEND_DIR / "js")), name="js")

# 前端入口
@app.get("/")
async def serve_frontend():
    return FileResponse(str(FRONTEND_DIR / "index.html"))
