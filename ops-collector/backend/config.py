"""配置加载模块 - 采集层只读 config/collector.yaml

三层架构改造后, 采集层不再持有任何业务参数与设备清单:
  - 设备清单与扫描范围 -> MySQL 的 switch_devices / server_devices / discovery_groups 表
  - 采集周期与开关     -> MySQL 的 collector_jobs 表
  - 告警/基线/通知/保留策略/VLAN 规划 -> MySQL 的 sys_config 表 (由 Spring Boot 读写)
本模块只负责: MySQL 连接、SNMP/SNMP Trap 参数、服务器与进程采集参数、调度参数、日志级别。
连接参数支持环境变量覆盖: OPS_MYSQL_HOST / OPS_MYSQL_PORT / OPS_MYSQL_USER /
OPS_MYSQL_PASSWORD / OPS_MYSQL_DB
"""

import ipaddress
import os
from pathlib import Path
from typing import Any, Optional

import yaml

# 项目根目录
BASE_DIR = Path(__file__).resolve().parent.parent
CONFIG_DIR = BASE_DIR / "config"
COLLECTOR_YAML = CONFIG_DIR / "collector.yaml"

# 遗留配置文件 (devices.yaml / settings.yaml) 仅作历史对照, 采集层不再读取
LEGACY_SETTINGS = CONFIG_DIR / "settings.yaml"
LEGACY_DEVICES = CONFIG_DIR / "devices.yaml"


def expand_ip_range(range_str: str) -> list[str]:
    """
    展开 IP 范围字符串为 IP 列表。
    支持格式:
      - "10.51.200.1-10.51.200.200"  (起止 IP)
      - "10.51.200.1-200"            (简写，同网段)
      - "10.51.200.0/24"             (CIDR 格式)
    """
    range_str = range_str.strip()

    # CIDR 格式
    if "/" in range_str:
        network = ipaddress.ip_network(range_str, strict=False)
        return [str(ip) for ip in network.hosts()]

    # 起止 IP 格式
    if "-" in range_str:
        parts = range_str.split("-")
        start_str = parts[0].strip()
        end_str = parts[1].strip()

        start_ip = ipaddress.ip_address(start_str)

        # 简写格式: "10.51.200.1-200"
        if "." not in end_str:
            # end_str 只是最后一个字节
            prefix = start_str.rsplit(".", 1)[0]
            end_str = f"{prefix}.{end_str}"

        end_ip = ipaddress.ip_address(end_str)

        if int(start_ip) > int(end_ip):
            start_ip, end_ip = end_ip, start_ip

        ips = []
        current = int(start_ip)
        end = int(end_ip)
        while current <= end:
            ips.append(str(ipaddress.ip_address(current)))
            current += 1
        return ips

    # 单个 IP
    return [range_str]


_DEFAULTS: dict[str, Any] = {
    "mysql": {
        "host": "127.0.0.1", "port": 3306, "user": "ops",
        "password": "", "database": "monitor",
        "pool_size": 10, "pool_recycle": 1800, "echo": False,
    },
    "snmp": {
        "version": "v2c", "community": "public", "port": 161,
        "timeout": 2, "retries": 1, "bulk_size": 50,
        # 并发上限取现场实测值: 工业交换机 bulkwalk 慢, 32 路并发会集体超时
        # (实测 64 台并发只有 2 台取到端口, 8 台并发接近 100%), 周期需大于单轮耗时
        "max_concurrent": 8, "batch_size": 16,
        "discovery_interval_minutes": 30,
        "offline_backoff": {"enabled": True, "probe_after": 300},
        "poll_intervals": {"port_status": 600, "traffic_stats": 900, "hardware_health": 1800},
    },
    "snmp_v3": {},
    "snmp_trap": {"enabled": True, "port": 162, "min_interval": 5},
    "server_monitor": {
        "poll_interval": 30, "ssh_port": 22, "agent_port": 9100, "wmi_timeout": 10,
        "fail_backoff": {"threshold": 3, "cooldown": 300},
    },
    "process_monitor": {
        "poll_interval": 60, "fail_backoff": {"threshold": 3, "cooldown": 300},
        "default_processes": [],
    },
    "scheduler": {
        "job_reload_seconds": 300, "command_poll_seconds": 10,
        "heartbeat_retention_days": 7,
    },
    "log_level": "INFO",
}


class Config:
    """采集层配置管理器 (单例)"""

    _instance: Optional["Config"] = None
    _raw: dict = {}

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self):
        """从 collector.yaml 加载配置, 缺失字段用内置默认值补齐"""
        raw: dict[str, Any] = {}
        if COLLECTOR_YAML.exists():
            with open(COLLECTOR_YAML, "r", encoding="utf-8") as f:
                raw = yaml.safe_load(f) or {}
        merged: dict[str, Any] = {}
        for key, default in _DEFAULTS.items():
            value = raw.get(key, default)
            if isinstance(default, dict) and isinstance(value, dict):
                merged[key] = {**default, **value}
            else:
                merged[key] = value
        # 顶层未识别的键保留, 便于将来加参数
        for key, value in raw.items():
            merged.setdefault(key, value)
        self._raw = merged

    def reload(self):
        """重新加载配置 (收到 collector_commands 的 reload_config 指令时调用)"""
        self._load()

    # ---- MySQL ----

    @property
    def mysql(self) -> dict:
        cfg = dict(self._raw.get("mysql", {}))
        cfg["host"] = os.getenv("OPS_MYSQL_HOST", cfg.get("host", "127.0.0.1"))
        cfg["port"] = int(os.getenv("OPS_MYSQL_PORT", cfg.get("port", 3306)))
        cfg["user"] = os.getenv("OPS_MYSQL_USER", cfg.get("user", "ops"))
        cfg["password"] = os.getenv("OPS_MYSQL_PASSWORD", cfg.get("password", ""))
        cfg["database"] = os.getenv("OPS_MYSQL_DB", cfg.get("database", "monitor"))
        return cfg

    @property
    def database_url(self) -> str:
        """异步 MySQL 连接串 (aiomysql)"""
        c = self.mysql
        from urllib.parse import quote_plus
        return "mysql+aiomysql://{}:{}@{}:{}/{}?charset=utf8mb4".format(
            quote_plus(str(c["user"])), quote_plus(str(c["password"])),
            c["host"], c["port"], c["database"],
        )

    @property
    def database_echo(self) -> bool:
        return bool(self._raw.get("mysql", {}).get("echo", False))

    @property
    def db_pool_size(self) -> int:
        return int(self._raw.get("mysql", {}).get("pool_size", 10))

    @property
    def db_pool_recycle(self) -> int:
        return int(self._raw.get("mysql", {}).get("pool_recycle", 1800))

    # ---- 采集参数 ----

    @property
    def snmp(self) -> dict:
        return self._raw.get("snmp", {})

    @property
    def snmp_v3(self) -> dict:
        return self._raw.get("snmp_v3", {})

    @property
    def snmp_trap(self) -> dict:
        return self._raw.get("snmp_trap", {})

    @property
    def server_monitor(self) -> dict:
        return self._raw.get("server_monitor", {})

    @property
    def process_monitor(self) -> dict:
        return self._raw.get("process_monitor", {})

    @property
    def scheduler(self) -> dict:
        return self._raw.get("scheduler", {})

    @property
    def log_level(self) -> str:
        return str(self._raw.get("log_level", "INFO"))


# 全局配置实例
config = Config()
