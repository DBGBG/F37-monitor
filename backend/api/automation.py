"""自动化配置相关 API 路由"""

from typing import Optional
from datetime import datetime

from fastapi import APIRouter, Query
from pydantic import BaseModel
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import async_session
from backend.models.config_audit import ConfigAuditLog
from backend.automation.ssh_executor import SSHExecutor
from backend.automation.vlan_manager import VLANManager
from backend.config import config

router = APIRouter(prefix="/api/automation", tags=["自动化配置"])

# 全局实例
ssh_executor = SSHExecutor()
vlan_manager = VLANManager(ssh_executor)


# ---- 请求模型 ----

class VLANCreateRequest(BaseModel):
    switch_id: str
    vlan_id: int
    vlan_name: str
    description: str = ""
    operator: str = "admin"


class VLANDeleteRequest(BaseModel):
    switch_id: str
    vlan_id: int
    operator: str = "admin"


class PortAccessRequest(BaseModel):
    switch_id: str
    port_name: str
    vlan_id: int
    operator: str = "admin"


class PortTrunkRequest(BaseModel):
    switch_id: str
    port_name: str
    allowed_vlans: list[int]
    native_vlan: int = 1
    operator: str = "admin"


class PortToggleRequest(BaseModel):
    switch_id: str
    port_name: str
    enabled: bool
    operator: str = "admin"


class BatchVLANRequest(BaseModel):
    switch_id: str
    operator: str = "admin"
    # 如果为 True，使用 devices.yaml 中的 VLAN 规划
    use_vlan_plan: bool = True
    # 或者手动指定
    vlans: Optional[list[dict]] = None


class RawCommandRequest(BaseModel):
    switch_id: str
    commands: list[str]
    operator: str = "admin"


# ---- 辅助函数 ----

def _find_switch(switch_id: str) -> Optional[dict]:
    """根据 ID 查找交换机配置"""
    for sw in config.switches:
        if sw["id"] == switch_id:
            return sw
    return None


# ---- 接口 ----

@router.post("/vlan/create")
async def create_vlan(req: VLANCreateRequest):
    """创建 VLAN"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await vlan_manager.create_vlan(
        sw, req.vlan_id, req.vlan_name, req.description, req.operator
    )
    return result


@router.post("/vlan/delete")
async def delete_vlan(req: VLANDeleteRequest):
    """删除 VLAN"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await vlan_manager.delete_vlan(sw, req.vlan_id, req.operator)
    return result


@router.post("/port/access")
async def set_port_access(req: PortAccessRequest):
    """设置端口为 Access 模式"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await vlan_manager.assign_port_access(
        sw, req.port_name, req.vlan_id, req.operator
    )
    return result


@router.post("/port/trunk")
async def set_port_trunk(req: PortTrunkRequest):
    """设置端口为 Trunk 模式"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await vlan_manager.set_port_trunk(
        sw, req.port_name, req.allowed_vlans, req.native_vlan, req.operator
    )
    return result


@router.post("/port/toggle")
async def toggle_port(req: PortToggleRequest):
    """启用/禁用端口"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await vlan_manager.enable_port(
        sw, req.port_name, req.enabled, req.operator
    )
    return result


@router.post("/vlan/batch")
async def batch_create_vlans(req: BatchVLANRequest):
    """批量创建 VLAN"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    if req.use_vlan_plan:
        result = await vlan_manager.apply_vlan_plan(sw, req.operator)
    else:
        result = await vlan_manager.batch_create_vlans(
            sw, req.vlans or [], req.operator
        )
    return result


@router.post("/raw-command")
async def execute_raw_command(req: RawCommandRequest):
    """执行原始 CLI 命令 (高级功能)"""
    sw = _find_switch(req.switch_id)
    if not sw:
        return {"status": "error", "message": "交换机不存在"}

    result = await ssh_executor.execute_commands(
        switch_cfg=sw,
        commands=req.commands,
        operation_type="raw",
        operation_desc="手动执行原始命令",
        operator=req.operator,
    )
    return result


@router.get("/audit-logs")
async def get_audit_logs(
    switch_id: str = Query(None),
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    """获取配置审计日志"""
    async with async_session() as session:
        query = select(ConfigAuditLog)

        if switch_id:
            query = query.where(ConfigAuditLog.switch_id == switch_id)

        count_result = await session.execute(
            select(func.count()).select_from(query.subquery())
        )
        total = count_result.scalar() or 0

        query = query.order_by(ConfigAuditLog.executed_at.desc())
        query = query.offset((page - 1) * page_size).limit(page_size)
        result = await session.execute(query)
        logs = result.scalars().all()

        return {
            "total": total,
            "items": [
                {
                    "id": log.id,
                    "switch_id": log.switch_id,
                    "switch_name": log.switch_name,
                    "switch_ip": log.switch_ip,
                    "operation_type": log.operation_type,
                    "operation_desc": log.operation_desc,
                    "operator": log.operator,
                    "status": log.status,
                    "duration_ms": log.duration_ms,
                    "error_message": log.error_message,
                    "executed_at": str(log.executed_at),
                }
                for log in logs
            ],
        }


@router.get("/vlan-plan")
async def get_vlan_plan():
    """获取当前 VLAN 规划"""
    return {"vlan_plan": config.vlan_plan}
