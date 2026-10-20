"""数据库初始化模块 - SQLAlchemy 异步引擎和会话管理"""

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase

from backend.config import config


# 异步引擎 (SQLite WAL 模式支持并发读写)
engine = create_async_engine(
    config.database_url,
    echo=config._settings.get("database", {}).get("echo", False),
    connect_args={
        "timeout": 30,  # SQLite 锁等待超时 (秒)
    },
)


@event.listens_for(engine.sync_engine, "connect")
def _set_sqlite_pragma(dbapi_conn, conn_record):
    """每个新连接应用低延迟 PRAGMA (SQLite 的 PRAGMA 是"每连接"生效的)

    目标: 把本地读写延迟压到最低, 满足实时性要求。
    - synchronous=NORMAL: WAL 下安全且省去每次提交的 fsync (默认 FULL 会显著增加写延迟)
    - temp_store=MEMORY : 排序/临时表进内存
    - cache_size=-65536 : 64MB 页缓存 (负值单位为 KiB)
    - mmap_size         : 256MB 内存映射, 读接近纯内存速度
    """
    cursor = dbapi_conn.cursor()
    try:
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.execute("PRAGMA temp_store=MEMORY")
        cursor.execute("PRAGMA cache_size=-65536")
        cursor.execute("PRAGMA mmap_size=268435456")
    finally:
        cursor.close()

# 异步会话工厂
async_session = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


class Base(DeclarativeBase):
    """ORM 基类"""
    pass


# 幂等索引迁移: 对已存在的库补齐索引 (create_all 不会修改既有表)
_INDEX_MIGRATIONS = [
    # port_status 无任何索引, 而采集按 switch_id 删除/查询 -> 关键索引
    "CREATE INDEX IF NOT EXISTS ix_port_status_switch_id ON port_status (switch_id)",
    # 时序表复合索引: 支持 "某设备某端口/时间段" 的过滤 + 排序
    "CREATE INDEX IF NOT EXISTS ix_port_traffic_switch_port_ts ON port_traffic_stats (switch_id, port_index, timestamp)",
    "CREATE INDEX IF NOT EXISTS ix_hardware_health_switch_ts ON hardware_health (switch_id, timestamp)",
    "CREATE INDEX IF NOT EXISTS ix_server_metrics_server_ts ON server_metrics (server_id, timestamp)",
    "CREATE INDEX IF NOT EXISTS ix_process_status_server_ts ON process_status (server_id, timestamp)",
    # 告警按状态 + 触发时间过滤/排序
    "CREATE INDEX IF NOT EXISTS ix_alert_records_status_triggered ON alert_records (status, triggered_at)",
    # 点检任务: 按设备查履历 / 按状态查逾期
    "CREATE INDEX IF NOT EXISTS ix_inspection_tasks_device_planned ON inspection_tasks (device_id, planned_time)",
    "CREATE INDEX IF NOT EXISTS ix_inspection_tasks_status_due ON inspection_tasks (status, due_time)",
    # 主动运维异常: 按状态+发生时间 / 按设备+指标+状态
    "CREATE INDEX IF NOT EXISTS ix_anomaly_events_status_occurred ON anomaly_events (status, occurred_at)",
    "CREATE INDEX IF NOT EXISTS ix_anomaly_events_device_metric_status ON anomaly_events (device_id, metric, status)",
    # 维护窗口: 按启用状态+时间段快速判定当前是否处于窗口
    "CREATE INDEX IF NOT EXISTS ix_maintenance_windows_enabled_time ON maintenance_windows (enabled, start_time, end_time)",
    # 健康评分: 按类型+分数排序
    "CREATE INDEX IF NOT EXISTS ix_device_health_type_score ON device_health (device_type, score)",
]


async def init_db():
    """初始化数据库 - 创建所有表并启用 WAL 模式"""
    # 导入所有模型确保被注册
    from backend.models import device, port_status, hardware_health, server_metrics, alert, config_audit, inspection, proactive  # noqa: F401
    async with engine.begin() as conn:
        # 启用 WAL 模式: 允许并发读写，避免 database is locked
        await conn.exec_driver_sql("PRAGMA journal_mode=WAL")
        await conn.exec_driver_sql("PRAGMA busy_timeout=30000")  # 30秒锁等待
        await conn.run_sync(Base.metadata.create_all)
        # 补齐索引 (幂等, 对既有库同样生效)
        for stmt in _INDEX_MIGRATIONS:
            await conn.exec_driver_sql(stmt)


async def get_session() -> AsyncSession:
    """获取数据库会话 (依赖注入用)"""
    async with async_session() as session:
        yield session


async def close_db():
    """关闭数据库连接"""
    await engine.dispose()
