"""
实时推送 API (Server-Sent Events)
------------------------------------------------------------
GET /api/stream  ->  持续推送:
    snapshot : 连接建立时的首屏全量快照
    overview : 设备/告警统计变化 (仪表盘卡片)
    device   : 单台设备状态更新 (在线态/端口数)
    alert    : 告警触发/恢复事件
    cycle    : 一次采集周期完成 (用于界面"数据已刷新"提示)
每 15s 发送一次 SSE 注释心跳, 保持连接存活。
"""

import asyncio
import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from backend.realtime import bus, live, now_str

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api", tags=["realtime"])

HEARTBEAT_SECONDS = 15


def _sse(event_type: str, data) -> str:
    payload = json.dumps(data, ensure_ascii=False, default=str)
    return f"event: {event_type}\ndata: {payload}\n\n"


@router.get("/stream")
async def stream(request: Request):
    """SSE 实时事件流"""

    async def event_generator():
        queue = bus.subscribe()
        try:
            yield _sse("snapshot", live.snapshot())
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
                    continue
                yield _sse(event["type"], event["data"])
        except asyncio.CancelledError:
            raise
        finally:
            bus.unsubscribe(queue)

    headers = {
        "Cache-Control": "no-cache",
        "Connection": "keep-alive",
        "X-Accel-Buffering": "no",
    }
    return StreamingResponse(
        event_generator(), media_type="text/event-stream", headers=headers
    )


@router.get("/realtime/status")
async def realtime_status():
    """实时子系统状态 (自监控)"""
    return {
        "subscribers": bus.subscriber_count,
        "switches_tracked": len(live.switches),
        "servers_tracked": len(live.servers),
        "alerts": live.alerts,
        "last_cycle": live.last_cycle,
        "updated_at": live.updated_at,
        "server_time": now_str(),
    }
