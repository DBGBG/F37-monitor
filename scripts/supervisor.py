"""
服务守护进程 (Watchdog / Supervisor)
------------------------------------------------------------
保持监控系统常驻运行：run.py 异常退出后自动重启，并对崩溃循环做退避。

用法:
    python scripts/supervisor.py            # 前台运行（Ctrl+C 停止）
    pythonw scripts/supervisor.py           # 后台运行（推荐配合启动脚本）

特性:
    - 子进程异常退出自动拉起（指数退避，最长 60s）
    - 子进程稳定运行超过 STABLE_SECONDS 后重置退避
    - 标准输出/错误重定向到 logs/service.out.log / service.err.log
    - 守护进程自身事件记录到 logs/supervisor.log
    - 心跳文件 logs/supervisor_heartbeat.json 便于外部自监控
    - 收到停止信号时优雅关闭子进程
"""

import json
import logging
import os
import signal
import subprocess
import sys
import time
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(BASE_DIR, "logs")
SUPERVISOR_LOG = os.path.join(LOG_DIR, "supervisor.log")
SERVICE_OUT = os.path.join(LOG_DIR, "service.out.log")
SERVICE_ERR = os.path.join(LOG_DIR, "service.err.log")
HEARTBEAT_FILE = os.path.join(LOG_DIR, "supervisor_heartbeat.json")
RUN_SCRIPT = os.path.join(BASE_DIR, "run.py")

BASE_DELAY = 5          # 首次重启等待秒数
MAX_DELAY = 60          # 最大退避秒数
STABLE_SECONDS = 300    # 子进程存活超过该时长视为稳定，重置退避
STOP_GRACE = 15         # 优雅停止子进程的等待秒数

os.makedirs(LOG_DIR, exist_ok=True)

_handlers = [logging.FileHandler(SUPERVISOR_LOG, encoding="utf-8")]
if sys.stdout is not None:
    _handlers.append(logging.StreamHandler(sys.stdout))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=_handlers,
)
log = logging.getLogger("supervisor")

_shutdown = {"flag": False}


def _handle_stop(signum, frame):
    log.info("收到停止信号 (%s)，准备关闭…", signum)
    _shutdown["flag"] = True


def _write_heartbeat(state):
    try:
        tmp = HEARTBEAT_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False)
        os.replace(tmp, HEARTBEAT_FILE)
    except Exception as exc:  # pragma: no cover - 心跳失败不应影响主流程
        log.warning("写入心跳文件失败: %s", exc)


def _terminate_child(child):
    if child is None or child.poll() is not None:
        return
    log.info("正在停止子进程 (pid=%s)…", child.pid)
    try:
        child.terminate()
        try:
            child.wait(timeout=STOP_GRACE)
        except subprocess.TimeoutExpired:
            log.warning("子进程未在 %ss 内退出，强制结束", STOP_GRACE)
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(child.pid)],
                capture_output=True,
            )
    except Exception as exc:
        log.warning("停止子进程异常: %s", exc)


def run():
    for sig in ("SIGINT", "SIGTERM", "SIGBREAK"):
        if hasattr(signal, sig):
            try:
                signal.signal(getattr(signal, sig), _handle_stop)
            except (ValueError, OSError):
                pass

    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    restarts = 0
    consecutive_failures = 0
    log.info("=" * 60)
    log.info("服务守护进程启动 (pid=%s)", os.getpid())
    log.info("守护目标: %s", RUN_SCRIPT)
    log.info("=" * 60)

    child = None
    while not _shutdown["flag"]:
        out = open(SERVICE_OUT, "ab", buffering=0)
        err = open(SERVICE_ERR, "ab", buffering=0)
        child_start = time.time()
        child_start_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        log.info("启动服务进程: %s -u run.py", sys.executable)
        try:
            child = subprocess.Popen(
                [sys.executable, "-u", RUN_SCRIPT],
                cwd=BASE_DIR,
                stdout=out,
                stderr=err,
                stdin=subprocess.DEVNULL,
            )
        except Exception as exc:
            out.close()
            err.close()
            log.error("启动服务进程失败: %s", exc)
            child = None
            consecutive_failures += 1
            _sleep_backoff(consecutive_failures)
            continue

        _write_heartbeat({
            "supervisor_pid": os.getpid(),
            "child_pid": child.pid,
            "started_at": started_at,
            "child_started_at": child_start_str,
            "restarts": restarts,
            "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        })

        try:
            exit_code = child.wait()
        except KeyboardInterrupt:
            _handle_stop("KeyboardInterrupt", None)
            exit_code = None

        out.close()
        err.close()
        uptime = time.time() - child_start

        if _shutdown["flag"]:
            log.info("守护进程退出，子进程已停止")
            break

        restarts += 1
        if uptime >= STABLE_SECONDS:
            consecutive_failures = 1
            log.warning(
                "服务进程退出 (exit=%s, 运行 %.0fs)，第 %s 次重启，%ss 后拉起",
                exit_code, uptime, restarts, BASE_DELAY,
            )
            delay = BASE_DELAY
        else:
            consecutive_failures += 1
            delay = _sleep_backoff(consecutive_failures)
            log.warning(
                "服务进程异常退出 (exit=%s, 仅运行 %.0fs)，第 %s 次重启，%ss 后拉起",
                exit_code, uptime, restarts, delay,
            )
            continue

        _interruptible_sleep(delay)

    _terminate_child(child)
    _write_heartbeat({
        "supervisor_pid": os.getpid(),
        "child_pid": None,
        "started_at": started_at,
        "restarts": restarts,
        "state": "stopped",
        "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    })
    log.info("守护进程已停止")


def _sleep_backoff(failures):
    delay = min(BASE_DELAY * (2 ** (failures - 1)), MAX_DELAY)
    _interruptible_sleep(delay)
    return delay


def _interruptible_sleep(seconds):
    deadline = time.time() + seconds
    while not _shutdown["flag"] and time.time() < deadline:
        time.sleep(0.5)


if __name__ == "__main__":
    try:
        run()
    except Exception:
        log.exception("守护进程异常终止")
        sys.exit(1)
