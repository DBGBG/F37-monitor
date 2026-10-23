"""
实时状态与事件总线
------------------------------------------------------------
- LiveState: 内存中的设备/告警"最新态", 供 SSE 首屏快照与实时计算,
  避免仪表盘每次请求都扫库 (SQLite 本地读虽快, 但内存态用于秒级推送)。
- EventBus : 进程内发布/订阅 (每订阅者一个 asyncio 队列), 供 SSE 推送。
  单进程 uvicorn 下无需 Redis; 若未来多 worker 再替换实现。

发布方均在事件循环内 (采集任务 / 告警引擎), 故 put_nowait 线程安全。
"""

import asyncio
import logging
from datetime import datetime
from typing import Any, Optional

logger = logging.getLogger(__name__)


def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class EventBus:
    """进程内事件总线 (fan-out 到各 SSE 订阅者)"""

    def __init__(self):
        self._subs: set[asyncio.Queue] = set()

    def subscribe(self, maxsize: int = 200) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=maxsize)
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue):
        self._subs.discard(q)

    def publish(self, event_type: str, data: Any = None):
        if not self._subs:
            return
        event = {"type": event_type, "data": data, "ts": now_str()}
        for q in list(self._subs):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                # 订阅者消费过慢: 丢弃最旧一条再放入, 保证推送不被阻塞
                try:
                    q.get_nowait()
                    q.put_nowait(event)
                except Exception:
                    pass

    @property
    def subscriber_count(self) -> int:
        return len(self._subs)


class LiveState:
    """内存中的设备/告警最新状态"""

    def __init__(self):
        self.switches: dict[str, dict] = {}
        self.servers: dict[str, dict] = {}
        self.alerts: dict[str, int] = {"active": 0, "critical": 0}
        self.updated_at: Optional[str] = None
        self.last_cycle: Optional[dict] = None

    # ---- 更新 ----

    def update_switch(self, device_id: str, **fields):
        d = self.switches.setdefault(device_id, {"id": device_id, "type": "switch"})
        d.update(fields)
        self.updated_at = now_str()

    def update_server(self, device_id: str, **fields):
        d = self.servers.setdefault(device_id, {"id": device_id, "type": "server"})
        d.update(fields)
        self.updated_at = now_str()

    def set_alert_counts(self, active: int, critical: int):
        self.alerts = {"active": int(active), "critical": int(critical)}
        self.updated_at = now_str()

    def mark_cycle(self, phase: str, stats: dict):
        self.last_cycle = {"phase": phase, "at": now_str(), **stats}
        self.updated_at = now_str()

    # ---- 计算 ----

    @staticmethod
    def _counts(group: dict) -> tuple[int, int]:
        total = len(group)
        online = sum(1 for d in group.values() if d.get("is_online"))
        return total, online

    def overview(self) -> dict:
        sw_total, sw_online = self._counts(self.switches)
        srv_total, srv_online = self._counts(self.servers)
        total = sw_total + srv_total
        online = sw_online + srv_online
        return {
            "switches": {"total": sw_total, "online": sw_online, "offline": sw_total - sw_online},
            "servers": {"total": srv_total, "online": srv_online, "offline": srv_total - srv_online},
            "alerts": {
                "active": self.alerts.get("active", 0),
                "critical": self.alerts.get("critical", 0),
            },
            "total_devices": total,
            "online_devices": online,
            "online_rate": round(online / total * 100, 1) if total else 0,
            "updated_at": self.updated_at,
        }

    def snapshot(self) -> dict:
        return {
            "overview": self.overview(),
            "devices": list(self.switches.values()) + list(self.servers.values()),
            "last_cycle": self.last_cycle,
            "generated_at": now_str(),
        }

    def publish_overview(self):
        bus.publish("overview", self.overview())


bus = EventBus()
live = LiveState()
