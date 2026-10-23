"""SNMP 并发吞吐实测工具 — 用于给 collector.yaml 的 max_concurrent / batch_size 定值.

为什么需要它: 工业交换机的 bulkwalk 极慢, 并发拉高后不是"变快"而是"集体超时"。
现场实测 (107 台, timeout=2s/retries=1): 64 路并发只有 2 台取到端口数据,
12 路约 30% 成功, 6~8 路接近 100%。设备数量或网段变化后, 用本工具复测再调参。

用法 (需采集层与 MySQL 可用):
    python tools\\snmp_concurrency_probe.py --levels 6,12,32 --limit 64
    # 连接参数默认取现场库, 也可覆盖: --host/--port/--user/--password/--db
结论口径: 取"有端口数据设备数 ≈ 探测台数"的最大并发值。
"""
from __future__ import annotations

import argparse
import asyncio
import concurrent.futures
import sys
import time
from pathlib import Path

# 复用采集层的生产代码路径 (每设备一个 Client + 单事件循环, 设备侧串行)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ops-collector"))

from puresnmp import Client, V2C  # noqa: E402

from backend.collectors.snmp_collector import (  # noqa: E402
    OID_IF_OPER_STATUS,
    _walk_to_dict,
)


def online_ips(args: argparse.Namespace) -> list[str]:
    import pymysql

    conn = pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, database=args.db, autocommit=True)
    cur = conn.cursor()
    cur.execute("SELECT ip FROM switch_devices WHERE is_online=1 ORDER BY id LIMIT %s", (args.limit,))
    ips = [r[0] for r in cur.fetchall()]
    conn.close()
    return ips


def probe_one(ip: str, community: str, port: int, timeout: int, bulk_size: int) -> int:
    """walk 一次 ifOperStatus, 返回端口条数 (异常由调用方统计)"""

    async def main() -> int:
        client = Client(ip, V2C(community), port=port)
        client.configure(timeout=timeout, retries=1)
        return len(await _walk_to_dict(client, OID_IF_OPER_STATUS, bulk_size))

    return asyncio.run(main())


def run_level(ips: list[str], conc: int, args: argparse.Namespace) -> None:
    got = ports = 0
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=conc) as pool:
        futures = [pool.submit(probe_one, ip, args.community, args.snmp_port, args.timeout, args.bulk_size)
                   for ip in ips]
        for fut in concurrent.futures.as_completed(futures):
            try:
                n = fut.result()
            except Exception:  # noqa: BLE001
                n = -1
            if n >= 0:
                got += 1
                ports += n
    cost = time.time() - started
    print(f"并发 {conc:>3} | 探测 {len(ips)} 台 | 有端口数据 {got}/{len(ips)} | "
          f"端口合计 {ports} | 耗时 {cost:.1f}s | 单台均摊 {cost / max(len(ips), 1):.2f}s")


def main() -> int:
    ap = argparse.ArgumentParser(description="按并发档位实测 SNMP bulkwalk 成功率")
    ap.add_argument("--levels", default="6,12,32", help="逗号分隔的并发档位")
    ap.add_argument("--limit", type=int, default=64, help="最多探测多少台在线交换机")
    ap.add_argument("--community", default="public")
    ap.add_argument("--snmp-port", type=int, default=161)
    ap.add_argument("--timeout", type=int, default=2, help="单次 SNMP 超时秒数, 与 collector.yaml 一致")
    ap.add_argument("--bulk-size", type=int, default=50)
    ap.add_argument("--host", default="10.51.190.70")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="test")
    ap.add_argument("--password", default="1234")
    ap.add_argument("--db", default="monitor")
    args = ap.parse_args()

    ips = online_ips(args)
    if not ips:
        print("没有在线交换机可探测 (检查 switch_devices.is_online)")
        return 1
    print(f"探测样本 {len(ips)} 台, timeout={args.timeout}s, community={args.community}")
    for level in [int(x) for x in args.levels.split(",") if x.strip()]:
        run_level(ips, level, args)
    print("\n把\"有端口数据 ≈ 探测台数\"的最大并发写进 ops-collector/config/collector.yaml 的 snmp.max_concurrent,")
    print("并保证 collector_jobs 的周期大于 (台数/并发 × 单台均摊耗时), 否则作业会被 APScheduler 合并。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
