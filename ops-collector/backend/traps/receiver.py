"""
SNMP Trap 接收器 (事件驱动)
------------------------------------------------------------
思路: 与其等下一轮轮询, 不如让设备"主动上报"。
- 监听 UDP (默认 162), 收到任意 Trap 即取其**源 IP**;
- 若该 IP 对应受管设备, 立即对该设备做一次定向采集并写心跳;
- 从而把"链路/电源变化"入库的时延从"采集周期"降到**亚秒级**。

三层架构下本模块不再做任何告警判定/事件推送: 告警评估与 SSE 推送均由 Spring Boot
业务层完成 (它每 30s 跑一次告警规约, 并在采集心跳变化时提前触发)。

说明: 无需解析 Trap 的具体 OID 即可从源地址定位设备 (源 IP 在 UDP 层可得),
      只依赖源 IP, 因此对 MOXA 各型号与其他品牌交换机同样适用。
      需要在受管设备上把 Trap 目的地址配置为本机 IP。
"""

import asyncio
import logging
import time

from backend.config import config
from backend.collectors.state import write_heartbeat

logger = logging.getLogger(__name__)


class _TrapProtocol(asyncio.DatagramProtocol):
    def __init__(self, receiver: "TrapReceiver"):
        self.receiver = receiver

    def datagram_received(self, data: bytes, addr):
        self.receiver.handle(data, addr)

    def error_received(self, exc):  # pragma: no cover
        logger.debug(f"Trap 接收错误: {exc}")


class TrapReceiver:
    """UDP SNMP Trap 接收器"""

    def __init__(self, scheduler=None):
        cfg = config.snmp_trap or {}
        self.enabled = cfg.get("enabled", False)
        self.port = int(cfg.get("port", 162))
        self.min_interval = float(cfg.get("min_interval", 5))  # 同一设备最小刷新间隔(秒), 防 trap 风暴
        self.scheduler = scheduler
        self._transport = None
        self._queue: asyncio.Queue | None = None
        self._task: asyncio.Task | None = None
        self._last_refresh: dict[str, float] = {}
        self._count = 0

    def start(self):
        if not self.enabled:
            logger.info("SNMP Trap 接收器未启用 (collector.yaml -> snmp_trap.enabled)")
            return
        loop = asyncio.get_running_loop()
        self._queue = asyncio.Queue(maxsize=2000)

        async def _bind():
            try:
                transport, _ = await loop.create_datagram_endpoint(
                    lambda: _TrapProtocol(self),
                    local_addr=("0.0.0.0", self.port),
                )
                self._transport = transport
                logger.info(f"SNMP Trap 接收器已启动: UDP 0.0.0.0:{self.port}")
            except OSError as e:
                logger.warning(f"SNMP Trap 端口 {self.port} 绑定失败 (可能被占用/权限): {e}")

        loop.create_task(_bind())
        self._task = loop.create_task(self._consume())

    def handle(self, data: bytes, addr):
        """收到一个 Trap: 记录源 IP 并排队定向刷新"""
        ip = addr[0]
        self._count += 1
        if self._queue is not None:
            try:
                self._queue.put_nowait(ip)
            except asyncio.QueueFull:
                logger.warning("Trap 队列已满, 丢弃")

    async def _consume(self):
        while True:
            ip = await self._queue.get()
            now = time.monotonic()
            if now - self._last_refresh.get(ip, 0) < self.min_interval:
                continue  # 抑制同设备短时间内的重复 trap
            self._last_refresh[ip] = now
            try:
                await self._refresh_device(ip)
            except Exception as e:
                logger.error(f"Trap 定向刷新异常 [{ip}]: {e}", exc_info=True)

    async def _refresh_device(self, ip: str):
        from sqlalchemy import select

        from backend.database import async_session
        from backend.models.device import SwitchDevice

        async with async_session() as session:
            sw = (await session.execute(
                select(SwitchDevice).where(SwitchDevice.ip == ip)
            )).scalar_one_or_none()
            if sw is None:
                return
            cfg = {
                "id": sw.id,
                "ip": sw.ip,
                "name": sw.name,
                "snmp": sw.snmp_config or {"version": "v2c", "community": "public"},
                "ssh": sw.ssh_config or {},
            }

        if self.scheduler is None:
            return
        logger.info(f"收到 Trap, 定向刷新: {cfg['name']}({ip})")
        started = time.monotonic()
        try:
            # force=True: Trap 代表设备侧刚发生变化, 跳过离线退避立即重采一次
            await self.scheduler.snmp_collector.poll_switch_status(cfg, force=True)
            ok, err = 1, 0
        except Exception as e:
            ok, err = 0, 1
            logger.error(f"Trap 定向重采失败 [{ip}]: {e}")
        await write_heartbeat(
            phase=f"trap:{ip}", total=1, success=ok, errors=err,
            duration_seconds=time.monotonic() - started,
            component="trap", detail={"device_id": cfg["id"], "source": ip},
        )

    def stop(self):
        if self._transport is not None:
            self._transport.close()
            self._transport = None
        if self._task is not None:
            self._task.cancel()
            self._task = None
        logger.info("SNMP Trap 接收器已停止")

    @property
    def received(self) -> int:
        return self._count
