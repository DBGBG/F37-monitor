"""
SSH 执行器 - 通过 SSH 连接 MOXA 交换机执行配置命令
- 基于 netmiko 实现设备 CLI 交互
- 任务队列串行化，避免并发冲突
- 完整的审计日志记录
"""

import asyncio
import logging
import time
from datetime import datetime
from typing import Optional

from netmiko import ConnectHandler
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import config
from backend.database import async_session
from backend.models.config_audit import ConfigAuditLog

logger = logging.getLogger(__name__)


class SSHExecutor:
    """SSH 命令执行器"""

    def __init__(self):
        self.ssh_config = config.ssh
        # 每台设备的操作锁，确保串行执行
        self._device_locks: dict[str, asyncio.Lock] = {}
        # 任务队列
        self._task_queue: asyncio.Queue = asyncio.Queue(
            maxsize=self.ssh_config.get("task_queue_size", 100)
        )
        self._worker_task: Optional[asyncio.Task] = None

    def _get_device_lock(self, switch_id: str) -> asyncio.Lock:
        """获取设备级别的锁"""
        if switch_id not in self._device_locks:
            self._device_locks[switch_id] = asyncio.Lock()
        return self._device_locks[switch_id]

    async def execute_commands(
        self,
        switch_cfg: dict,
        commands: list[str],
        operation_type: str = "general",
        operation_desc: str = "",
        operator: str = "system",
        parameters: dict = None,
    ) -> dict:
        """
        在指定交换机上执行配置命令

        Args:
            switch_cfg: 交换机配置 (包含 ip, ssh 等信息)
            commands: 要执行的命令列表
            operation_type: 操作类型 (vlan/port/system)
            operation_desc: 操作描述
            operator: 操作人
            parameters: 操作参数

        Returns:
            dict: {status, output, error, duration_ms}
        """
        switch_id = switch_cfg["id"]
        ip = switch_cfg["ip"]
        ssh_cfg = switch_cfg.get("ssh", {})

        lock = self._get_device_lock(switch_id)
        start_time = time.time()

        async with lock:
            result = {
                "status": "success",
                "output": "",
                "error": "",
                "duration_ms": 0,
                "commands": commands,
            }

            try:
                # 构建 netmiko 连接参数
                device_params = {
                    "device_type": "linux",  # MOXA 使用类 Linux CLI
                    "host": ip,
                    "port": ssh_cfg.get("port", 22),
                    "username": ssh_cfg.get("username", "admin"),
                    "password": ssh_cfg.get("password", ""),
                    "timeout": self.ssh_config.get("timeout", 30),
                    "session_timeout": 60,
                    "auth_timeout": 30,
                }

                # 在线程池中执行 SSH 操作 (netmiko 不支持原生 async)
                loop = asyncio.get_event_loop()
                output = await loop.run_in_executor(
                    None,
                    self._execute_ssh_sync,
                    device_params,
                    commands,
                )

                result["output"] = output
                result["status"] = "success"

            except Exception as e:
                result["status"] = "failed"
                result["error"] = str(e)
                logger.error(f"SSH 执行失败 [{ip}]: {e}")

            finally:
                duration_ms = int((time.time() - start_time) * 1000)
                result["duration_ms"] = duration_ms

                # 记录审计日志
                await self._save_audit_log(
                    switch_cfg=switch_cfg,
                    operation_type=operation_type,
                    operation_desc=operation_desc,
                    operator=operator,
                    commands=commands,
                    output=result.get("output", ""),
                    parameters=parameters or {},
                    status=result["status"],
                    error=result.get("error", ""),
                    duration_ms=duration_ms,
                )

        return result

    def _execute_ssh_sync(self, device_params: dict, commands: list[str]) -> str:
        """同步执行 SSH 命令 (在线程池中调用)"""
        output = ""
        try:
            with ConnectHandler(**device_params) as conn:
                # 进入配置模式
                conn.enable()
                conn.config_mode()

                # 逐条执行命令
                for cmd in commands:
                    cmd_output = conn.send_command(cmd, read_timeout=30)
                    output += f">>> {cmd}\n{cmd_output}\n\n"

                # 退出配置模式并保存
                conn.exit_config_mode()
                save_output = conn.send_command("save", read_timeout=10)
                output += f">>> save\n{save_output}\n"

        except Exception as e:
            # 尝试直接执行命令 (某些 MOXA 型号不支持 enable/config_mode)
            try:
                with ConnectHandler(**device_params) as conn:
                    for cmd in commands:
                        cmd_output = conn.send_command(cmd, read_timeout=30)
                        output += f">>> {cmd}\n{cmd_output}\n\n"
                    # 保存配置
                    try:
                        save_output = conn.send_command("save", read_timeout=10)
                        output += f">>> save\n{save_output}\n"
                    except Exception:
                        pass
            except Exception as inner_e:
                raise RuntimeError(f"SSH 连接失败: {inner_e}") from inner_e

        return output

    async def _save_audit_log(
        self, switch_cfg, operation_type, operation_desc, operator,
        commands, output, parameters, status, error, duration_ms
    ):
        """保存配置变更审计日志"""
        try:
            async with async_session() as session:
                log = ConfigAuditLog(
                    switch_id=switch_cfg["id"],
                    switch_name=switch_cfg.get("name", ""),
                    switch_ip=switch_cfg["ip"],
                    operation_type=operation_type,
                    operation_desc=operation_desc,
                    operator=operator,
                    commands_sent="\n".join(commands),
                    commands_output=output,
                    parameters=parameters,
                    status=status,
                    error_message=error,
                    duration_ms=duration_ms,
                )
                session.add(log)
                await session.commit()
        except Exception as e:
            logger.error(f"保存审计日志失败: {e}")

    async def execute_on_multiple(
        self,
        switch_cfgs: list[dict],
        commands: list[str],
        **kwargs,
    ) -> list[dict]:
        """在多台交换机上批量执行命令"""
        tasks = [
            self.execute_commands(cfg, commands, **kwargs)
            for cfg in switch_cfgs
        ]
        return await asyncio.gather(*tasks, return_exceptions=True)

    async def submit_task(self, task_data: dict) -> bool:
        """提交配置任务到队列"""
        try:
            self._task_queue.put_nowait(task_data)
            return True
        except asyncio.QueueFull:
            logger.warning("配置任务队列已满")
            return False

    async def start_worker(self):
        """启动后台任务处理 Worker"""
        self._worker_task = asyncio.create_task(self._worker_loop())
        logger.info("SSH 任务 Worker 已启动")

    async def _worker_loop(self):
        """后台 Worker 循环处理队列中的任务"""
        while True:
            try:
                task_data = await self._task_queue.get()
                switch_cfg = task_data["switch_cfg"]
                commands = task_data["commands"]
                kwargs = {k: v for k, v in task_data.items()
                          if k not in ("switch_cfg", "commands")}
                await self.execute_commands(switch_cfg, commands, **kwargs)
                self._task_queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Worker 处理任务异常: {e}")

    async def stop_worker(self):
        """停止后台 Worker"""
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
            logger.info("SSH 任务 Worker 已停止")
