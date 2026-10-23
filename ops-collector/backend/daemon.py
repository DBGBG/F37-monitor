"""采集层守护进程入口 (原 FastAPI main.py 的 lifespan 部分, 已去掉全部 HTTP/前端相关逻辑)

生命周期:
  1. 连 MySQL 并校验连通性
  2. 用库中最新状态引导进程内缓存 (离线退避依赖)
  3. 设备发现 (扫描 discovery_groups) -> 新设备写入 switch_devices
  4. 服务器台账自检 (server_devices 由业务层/Flyway 种子维护)
  5. 启动 TaskScheduler (6 个采集任务 + 配置热加载 + 指令轮询)
  6. 启动 SNMP Trap 接收器
  7. 收到 SIGINT/SIGTERM 优雅停止

采集层不监听任何端口, 也不返回任何给前端的数据。
"""

import asyncio
import contextlib
import logging
import signal
import sys
from datetime import datetime

from sqlalchemy import select

from backend.config import config
from backend.collectors.state import bootstrap_state_from_db, load_enabled_servers
from backend.database import async_session, check_connection, close_db
from backend.models.device import ServerDevice, SwitchDevice

logger = logging.getLogger(__name__)

BANNER = "主动运维系统 - 采集层 (Collector Daemon)"


def fit_text(value: str, limit: int) -> str:
    """sysDescr 一类的文本往往是带换行的设备 banner, 长度远超列宽.

    先折叠空白再按目标列宽截断, 避免 MySQL 1406 Data too long 把整轮发现打断.
    """
    return " ".join((value or "").split())[:limit]


def setup_logging():
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


async def run_device_discovery() -> int:
    """运行设备发现 - 扫描 discovery_groups 中的网段，将发现的真实设备入库

    只写采集/新增字段: 新设备首次入库时才带 name/location/snmp_config/ssh_config,
    已存在的设备不覆盖台账字段 (那些归业务层维护)。
    返回本轮存活的 SNMP 设备数。
    """
    from backend.collectors.device_discovery import DeviceDiscovery
    from backend.models.collector import DiscoveryGroup

    discovery = DeviceDiscovery()

    async with async_session() as session:
        group_count = len((await session.execute(
            select(DiscoveryGroup.id).where(DiscoveryGroup.enabled == True)
        )).all())
    if group_count == 0:
        logger.info("discovery_groups 中没有已启用的网段，跳过设备发现")
        return 0

    discovered = await discovery.discover_all()
    logger.info(f"设备发现结果: {len(discovered)} 台真实 SNMP 设备")

    if not discovered:
        logger.warning("未发现任何 SNMP 设备，请检查网络连通性和 SNMP 配置")
        return 0

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
                for p in descr.split():
                    if any(k in p.upper() for k in ["EDS", "PT-", "ICS-", "TN-"]):
                        model = p
                        break

            device_id = f"sw-{ip.replace('.', '-')}"
            device_name = fit_text(d.get("sys_name", "") or f"{d.get('group_name', '交换机')}-{ip.split('.')[-1]}", 200)
            device_location = fit_text(d.get("sys_location", "") or f"{d.get('location_prefix', '')}-{ip}", 200)
            device_model = fit_text(model, 100)
            device_firmware = fit_text(descr, 100)

            if existing:
                # 仅刷新采集字段; 台账字段为空时才补齐, 避免覆盖业务层设定值
                values = {"is_online": True, "last_poll_time": d.get("discovered_at") or datetime.now()}
                if not existing.name:
                    values["name"] = device_name
                if not existing.location:
                    values["location"] = device_location
                if not existing.firmware_version:
                    values["firmware_version"] = device_firmware
                if not existing.uptime_seconds:
                    values["uptime_seconds"] = d.get("uptime", 0)
                await session.execute(
                    SwitchDevice.__table__.update()
                    .where(SwitchDevice.id == existing.id).values(**values)
                )
            else:
                session.add(SwitchDevice(
                    id=device_id,
                    name=device_name,
                    ip=ip,
                    model=device_model,
                    location=device_location,
                    firmware_version=device_firmware,
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


async def sync_devices_to_db() -> dict:
    """设备台账自检 (服务器清单已由业务层写入 server_devices, 采集层不再从 yaml 同步)

    只做校验与告警: 缺少采集凭据/方式的服务器会被跳过并打印原因。
    """
    async with async_session() as session:
        sw_total = len((await session.execute(select(SwitchDevice.id))).all())
        srv_rows = (await session.execute(select(ServerDevice))).scalars().all()

    enabled_srv = [s for s in srv_rows if s.enabled]
    problems = []
    for s in enabled_srv:
        monitoring = s.monitoring_config or {}
        method = monitoring.get("method", "agent")
        if method == "ssh" and not (monitoring.get("ssh") or {}).get("username"):
            problems.append(f"{s.id}: method=ssh 但未配置 ssh.username")
        if method == "agent" and not s.ip:
            problems.append(f"{s.id}: 缺少 IP")

    logger.info(
        f"设备台账自检: 交换机 {sw_total} 台, 服务器 {len(enabled_srv)}/{len(srv_rows)} 台已启用"
    )
    for p in problems:
        logger.warning("服务器采集配置异常: %s", p)
    return {"switches": sw_total, "servers": len(enabled_srv), "problems": problems}


async def _stop(scheduler, trap_receiver):
    logger.info("正在停止采集层...")
    try:
        if trap_receiver:
            trap_receiver.stop()
    except Exception as e:
        logger.debug("停止 Trap 接收器异常: %s", e)
    try:
        if scheduler:
            scheduler.stop()
    except Exception as e:
        logger.debug("停止调度器异常: %s", e)
    try:
        await close_db()
    except Exception as e:
        logger.debug("关闭数据库连接池异常: %s", e)
    logger.info("采集层已停止")


async def main_async() -> int:
    setup_logging()
    print("=" * 60)
    print(f"  {BANNER}")
    c = config.mysql
    print(f"  MySQL    : {c['user']}@{c['host']}:{c['port']}/{c['database']}")
    print(f"  SNMP     : timeout={config.snmp.get('timeout')}s retries={config.snmp.get('retries')} "
          f"batch={config.snmp.get('batch_size')} 并发={config.snmp.get('max_concurrent')}")
    print(f"  Trap     : {'UDP 162' if config.snmp_trap.get('enabled') else '未启用'}")
    print(f"  HTTP 服务: 无 (采集层只写库, 前端接口由 Spring Boot 提供)")
    print("=" * 60)

    logger.info("连接 MySQL...")
    try:
        await check_connection()
    except Exception as e:
        logger.error("MySQL 连接失败: %s", e)
        logger.error("请确认已执行 sql/create_database.sql 且 Spring Boot 已完成 Flyway 建表")
        return 2

    logger.info("引导采集缓存...")
    await bootstrap_state_from_db()

    logger.info("开始网络设备发现...")
    try:
        await run_device_discovery()
    except Exception as e:
        logger.warning(f"设备发现失败 (采集仍将继续): {e}")

    await sync_devices_to_db()

    from backend.scheduler.task_scheduler import TaskScheduler

    scheduler = TaskScheduler()
    await scheduler.setup_jobs()
    scheduler.start()

    trap_receiver = None
    try:
        from backend.traps.receiver import TrapReceiver

        trap_receiver = TrapReceiver(scheduler)
        trap_receiver.start()
    except Exception as e:
        logger.warning(f"SNMP Trap 接收器启动失败 (不影响采集): {e}")

    logger.info("采集层启动完成: 定时任务 %d 个", len(scheduler.get_jobs_info()))

    # 等待停止信号
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()

    def _signal_handler():
        stop_event.set()

    for sig in (getattr(signal, "SIGINT", None), getattr(signal, "SIGTERM", None)):
        if sig is None:
            continue
        try:
            loop.add_signal_handler(sig, _signal_handler)
        except (NotImplementedError, RuntimeError):
            # Windows 不支持 add_signal_handler: 回退到 signal.signal + call_soon_threadsafe
            def _handler(signum, frame, _sig=sig):
                loop.call_soon_threadsafe(_signal_handler)

            with contextlib.suppress(ValueError, OSError):
                signal.signal(sig, _handler)
    try:
        await stop_event.wait()
    except KeyboardInterrupt:
        pass

    await _stop(scheduler, trap_receiver)
    return 0


def main() -> int:
    """采集层入口 (run.py 调用)"""
    try:
        return asyncio.run(main_async())
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
