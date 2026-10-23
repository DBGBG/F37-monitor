"""维护窗口 - 计划维护期间抑制异常与告警

主动运维的重要一环: 不是所有"异常"都需要打扰人。
- 计划维护窗口内的正常操作(断电/割接/升级)应静默, 避免告警风暴
- 设备整体离线时, 其端口/硬件子告警属于"症状", 应被根因告警吸收, 减少噪音
"""

import logging
from datetime import datetime

from sqlalchemy import select

from backend.database import async_session
from backend.models.proactive import MaintenanceWindow

logger = logging.getLogger(__name__)


def _match(window: MaintenanceWindow, device_id: str, device_type: str, now: datetime) -> bool:
    if not window.enabled:
        return False
    if window.start_time and now < window.start_time:
        return False
    if window.end_time and now > window.end_time:
        return False
    scope = window.device_type or "all"
    if scope not in ("all", device_type):
        return False
    ids = window.device_ids or []
    if ids and device_id not in ids:
        return False
    return True


class MaintenanceService:
    """维护窗口判定 (带短 TTL 内存缓存, 避免每次告警创建都查库)"""

    def __init__(self, ttl_seconds: int = 30):
        self._cache: list[MaintenanceWindow] = []
        self._cache_at: datetime | None = None
        self._ttl = ttl_seconds

    async def refresh(self, session=None) -> list[MaintenanceWindow]:
        """刷新活跃窗口缓存"""
        now = datetime.now()
        own = session is None
        if session is None:
            session = async_session()
            await session.__aenter__()
        try:
            rows = (await session.execute(
                select(MaintenanceWindow).where(
                    MaintenanceWindow.enabled == True,  # noqa: E712
                    MaintenanceWindow.start_time <= now,
                    MaintenanceWindow.end_time >= now,
                )
            )).scalars().all()
            self._cache = list(rows)
            self._cache_at = now
        finally:
            if own:
                await session.__aexit__(None, None, None)
        return self._cache

    async def ensure_fresh(self):
        now = datetime.now()
        if self._cache_at is None or (now - self._cache_at).total_seconds() > self._ttl:
            await self.refresh()

    def is_suppressed(self, device_id: str, device_type: str, now: datetime | None = None) -> tuple[bool, str]:
        """设备当前是否处于维护窗口内。返回 (是否抑制, 窗口名)"""
        now = now or datetime.now()
        for w in self._cache:
            if _match(w, device_id, device_type, now):
                return True, w.name
        return False, ""

    async def current_windows(self) -> list[dict]:
        """当前生效的窗口 (供 API/展示)"""
        await self.ensure_fresh()
        return [
            {
                "id": w.id, "name": w.name, "device_type": w.device_type,
                "device_ids": w.device_ids or [], "start_time": str(w.start_time),
                "end_time": str(w.end_time), "reason": w.reason,
            }
            for w in self._cache
        ]
