"""
VLAN 管理器 - 使用 Jinja2 模板生成 MOXA 交换机 VLAN 配置命令
- 支持 VLAN 创建/删除
- 支持端口 VLAN 分配
- 支持端口模式切换 (Access/Trunk/Hybrid)
"""

import logging
from pathlib import Path
from typing import Optional

from jinja2 import Environment, FileSystemLoader

from backend.config import config
from backend.automation.ssh_executor import SSHExecutor

logger = logging.getLogger(__name__)

# 模板目录
TEMPLATE_DIR = Path(__file__).parent / "config_templates"


class VLANManager:
    """VLAN 配置管理器"""

    def __init__(self, ssh_executor: SSHExecutor):
        self.ssh = ssh_executor
        self.jinja_env = Environment(
            loader=FileSystemLoader(str(TEMPLATE_DIR)),
            trim_blocks=True,
            lstrip_blocks=True,
        )

    def _render_commands(self, template_name: str, **kwargs) -> list[str]:
        """渲染配置模板为命令列表"""
        template = self.jinja_env.get_template(template_name)
        rendered = template.render(**kwargs)
        # 将渲染结果按行分割，过滤空行和注释
        commands = [
            line.strip()
            for line in rendered.split("\n")
            if line.strip() and not line.strip().startswith("#")
        ]
        return commands

    async def create_vlan(
        self,
        switch_cfg: dict,
        vlan_id: int,
        vlan_name: str,
        description: str = "",
        operator: str = "system",
    ) -> dict:
        """创建 VLAN"""
        commands = self._render_commands(
            "vlan_config.j2",
            action="create",
            vlan_id=vlan_id,
            vlan_name=vlan_name,
            description=description,
        )

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=commands,
            operation_type="vlan",
            operation_desc=f"创建 VLAN {vlan_id} ({vlan_name})",
            operator=operator,
            parameters={
                "vlan_id": vlan_id,
                "vlan_name": vlan_name,
                "description": description,
            },
        )

    async def delete_vlan(
        self,
        switch_cfg: dict,
        vlan_id: int,
        operator: str = "system",
    ) -> dict:
        """删除 VLAN"""
        commands = self._render_commands(
            "vlan_config.j2",
            action="delete",
            vlan_id=vlan_id,
        )

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=commands,
            operation_type="vlan",
            operation_desc=f"删除 VLAN {vlan_id}",
            operator=operator,
            parameters={"vlan_id": vlan_id},
        )

    async def assign_port_access(
        self,
        switch_cfg: dict,
        port_name: str,
        vlan_id: int,
        operator: str = "system",
    ) -> dict:
        """将端口设为 Access 模式并分配到指定 VLAN"""
        commands = self._render_commands(
            "port_config.j2",
            action="set_access",
            port_name=port_name,
            vlan_id=vlan_id,
        )

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=commands,
            operation_type="port",
            operation_desc=f"端口 {port_name} 设为 Access VLAN {vlan_id}",
            operator=operator,
            parameters={"port_name": port_name, "vlan_id": vlan_id, "mode": "access"},
        )

    async def set_port_trunk(
        self,
        switch_cfg: dict,
        port_name: str,
        allowed_vlans: list[int],
        native_vlan: int = 1,
        operator: str = "system",
    ) -> dict:
        """将端口设为 Trunk 模式"""
        commands = self._render_commands(
            "port_config.j2",
            action="set_trunk",
            port_name=port_name,
            allowed_vlans=allowed_vlans,
            native_vlan=native_vlan,
        )

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=commands,
            operation_type="port",
            operation_desc=f"端口 {port_name} 设为 Trunk (VLANs: {allowed_vlans})",
            operator=operator,
            parameters={
                "port_name": port_name,
                "mode": "trunk",
                "allowed_vlans": allowed_vlans,
                "native_vlan": native_vlan,
            },
        )

    async def enable_port(
        self,
        switch_cfg: dict,
        port_name: str,
        enabled: bool = True,
        operator: str = "system",
    ) -> dict:
        """启用/禁用端口"""
        commands = self._render_commands(
            "port_config.j2",
            action="enable" if enabled else "disable",
            port_name=port_name,
        )

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=commands,
            operation_type="port",
            operation_desc=f"{'启用' if enabled else '禁用'}端口 {port_name}",
            operator=operator,
            parameters={"port_name": port_name, "enabled": enabled},
        )

    async def batch_create_vlans(
        self,
        switch_cfg: dict,
        vlans: list[dict],
        operator: str = "system",
    ) -> dict:
        """
        批量创建多个 VLAN

        Args:
            vlans: [{"vlan_id": 10, "name": "Management", "description": "..."}]
        """
        all_commands = []
        for vlan in vlans:
            cmds = self._render_commands(
                "vlan_config.j2",
                action="create",
                vlan_id=vlan["vlan_id"],
                vlan_name=vlan.get("name", f"VLAN{vlan['vlan_id']}"),
                description=vlan.get("description", ""),
            )
            all_commands.extend(cmds)

        return await self.ssh.execute_commands(
            switch_cfg=switch_cfg,
            commands=all_commands,
            operation_type="vlan",
            operation_desc=f"批量创建 {len(vlans)} 个 VLAN",
            operator=operator,
            parameters={"vlans": vlans},
        )

    async def apply_vlan_plan(
        self,
        switch_cfg: dict,
        operator: str = "system",
    ) -> dict:
        """
        根据 devices.yaml 中的 VLAN 规划，在交换机上创建所有 VLAN
        """
        vlan_plan = config.vlan_plan
        if not vlan_plan:
            return {"status": "failed", "error": "未配置 VLAN 规划"}

        return await self.batch_create_vlans(switch_cfg, vlan_plan, operator)
