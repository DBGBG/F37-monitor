"""配置加载模块 - 读取并管理 settings.yaml 和 devices.yaml"""

import os
import ipaddress
import yaml
from pathlib import Path
from typing import Any, Optional


# 项目根目录
BASE_DIR = Path(__file__).resolve().parent.parent
CONFIG_DIR = BASE_DIR / "config"
DATA_DIR = BASE_DIR / "data"

# 确保数据目录存在
DATA_DIR.mkdir(parents=True, exist_ok=True)


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


class Config:
    """全局配置管理器"""

    _instance: Optional["Config"] = None
    _settings: dict = {}
    _devices: dict = {}
    _expanded_switches: list = []

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self):
        """从 YAML 文件加载配置"""
        settings_path = CONFIG_DIR / "settings.yaml"
        devices_path = CONFIG_DIR / "devices.yaml"

        if settings_path.exists():
            with open(settings_path, "r", encoding="utf-8") as f:
                self._settings = yaml.safe_load(f) or {}

        if devices_path.exists():
            with open(devices_path, "r", encoding="utf-8") as f:
                self._devices = yaml.safe_load(f) or {}

        # 展开 switch_groups 中的 IP 范围
        self._expand_switch_groups()

    def _expand_switch_groups(self):
        """将 switch_groups 中的 IP 范围展开为独立交换机条目"""
        self._expanded_switches = []
        global_index = 0

        # 先处理 switch_groups (批量 IP 范围)
        for group in self._devices.get("switch_groups", []):
            ip_range = group.get("ip_range", "")
            if not ip_range:
                continue

            group_name = group.get("group_name", "未命名组")
            model = group.get("model", "")
            location_prefix = group.get("location_prefix", group_name)
            snmp_cfg = group.get("snmp", {"version": "v2c", "community": "public"})
            ssh_cfg = group.get("ssh", {})
            enabled = group.get("enabled", True)

            ips = expand_ip_range(ip_range)

            for ip in ips:
                global_index += 1
                switch = {
                    "id": f"sw-{global_index:04d}",
                    "name": f"{group_name}-{ip.split('.')[-1]}",
                    "ip": ip,
                    "model": model,
                    "location": f"{location_prefix}-{ip}",
                    "snmp": dict(snmp_cfg),
                    "ssh": dict(ssh_cfg),
                    "enabled": enabled,
                }
                self._expanded_switches.append(switch)

        # 再追加单独定义的 switches
        for sw in self._devices.get("switches", []):
            self._expanded_switches.append(sw)

    def reload(self):
        """重新加载配置"""
        self._load()

    # ---- 便捷属性 ----

    @property
    def system(self) -> dict:
        return self._settings.get("system", {})

    @property
    def database_url(self) -> str:
        # 始终使用 data 目录下的 SQLite 数据库
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        db_path = str(DATA_DIR / "moxa_monitor.db").replace("\\", "/")
        return f"sqlite+aiosqlite:///{db_path}"

    @property
    def snmp(self) -> dict:
        return self._settings.get("snmp", {})

    @property
    def snmp_v3(self) -> dict:
        return self._settings.get("snmp_v3", {})

    @property
    def snmp_trap(self) -> dict:
        return self._settings.get("snmp_trap", {})

    @property
    def ssh(self) -> dict:
        return self._settings.get("ssh", {})

    @property
    def server_monitor(self) -> dict:
        return self._settings.get("server_monitor", {})

    @property
    def process_monitor(self) -> dict:
        return self._settings.get("process_monitor", {})

    @property
    def alerts(self) -> dict:
        return self._settings.get("alerts", {})

    @property
    def inspection(self) -> dict:
        return self._settings.get("inspection", {})

    @property
    def proactive(self) -> dict:
        return self._settings.get("proactive", {})

    @property
    def notifications(self) -> dict:
        return self._settings.get("notifications", {})

    @property
    def retention(self) -> dict:
        return self._settings.get("retention", {})

    @property
    def switches(self) -> list:
        """获取单独定义的交换机 (不含 IP 范围展开，发现设备由 DeviceDiscovery 管理)"""
        return self._devices.get("switches", [])

    @property
    def servers(self) -> list:
        return self._devices.get("servers", [])

    @property
    def vlan_plan(self) -> list:
        return self._devices.get("vlan_plan", [])

    @property
    def switch_groups_raw(self) -> list:
        """获取原始 switch_groups 配置 (未展开)"""
        return self._devices.get("switch_groups", [])

    def get_enabled_switches(self) -> list:
        return [s for s in self.switches if s.get("enabled", True)]

    def get_enabled_servers(self) -> list:
        return [s for s in self.servers if s.get("enabled", True)]

    def get_switch_by_ip(self, ip: str) -> Optional[dict]:
        """根据 IP 查找交换机"""
        for sw in self.switches:
            if sw["ip"] == ip:
                return sw
        return None


# 全局配置实例
config = Config()
