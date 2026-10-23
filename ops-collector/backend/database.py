"""数据库初始化模块 - SQLAlchemy 异步引擎和会话管理 (MySQL / aiomysql)

三层架构下建表与索引一律由 Spring Boot 的 Flyway 迁移脚本负责
(ops-server/src/main/resources/db/migration/V1__baseline.sql),
采集层只做连接与会话管理, 不再执行 create_all / PRAGMA / 索引补齐。
"""

from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase

from backend.config import config


# 异步引擎: MySQL 连接池 (pool_pre_ping 处理 wait_timeout 断连, pool_recycle 主动回收)
engine = create_async_engine(
    config.database_url,
    echo=config.database_echo,
    pool_size=config.db_pool_size,
    max_overflow=max(config.db_pool_size, 10),
    pool_recycle=config.db_pool_recycle,
    pool_pre_ping=True,
    pool_timeout=30,
    connect_args={
        "connect_timeout": 10,  # 建连超时 (秒)
    },
)

# 异步会话工厂
async_session = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


class Base(DeclarativeBase):
    """ORM 基类"""
    pass


async def check_connection() -> bool:
    """启动时校验 MySQL 可达且表已建好"""
    from sqlalchemy import text

    async with engine.connect() as conn:
        await conn.execute(text("SELECT 1"))
        return True


async def get_session() -> AsyncSession:
    """获取数据库会话 (依赖注入用)"""
    async with async_session() as session:
        yield session


async def close_db():
    """关闭数据库连接池"""
    await engine.dispose()
