# models 包初始化
from backend.models.device import SwitchDevice, ServerDevice
from backend.models.port_status import PortStatus, PortTrafficStats
from backend.models.hardware_health import HardwareHealth
from backend.models.server_metrics import ServerMetrics, ProcessStatus
from backend.models.alert import AlertRecord
from backend.models.config_audit import ConfigAuditLog
from backend.models.inspection import (
    InspectionPlan, InspectionItem, InspectionTask, InspectionRecord,
)
from backend.models.proactive import (
    MetricBaseline, AnomalyEvent, DeviceHealth, MaintenanceWindow,
)
from backend.models.collector import (
    DiscoveryGroup, CollectorJob, CollectorHeartbeat, CollectorCommand,
)

__all__ = [
    "SwitchDevice", "ServerDevice",
    "PortStatus", "PortTrafficStats",
    "HardwareHealth",
    "ServerMetrics", "ProcessStatus",
    "AlertRecord",
    "ConfigAuditLog",
    "InspectionPlan", "InspectionItem", "InspectionTask", "InspectionRecord",
    "MetricBaseline", "AnomalyEvent", "DeviceHealth", "MaintenanceWindow",
    "DiscoveryGroup", "CollectorJob", "CollectorHeartbeat", "CollectorCommand",
]
