"""
服务守护进程 (Watchdog / Supervisor) - 三层架构版
------------------------------------------------------------
同时守护两个进程, 任一异常退出后自动重启, 并对崩溃循环做退避:

    采集层  python <ops-collector>/run.py                     只采集 + 写库, 不监听端口
    业务层  java -jar <ops-server>/target/moxa-server.jar   接口 + 页面 + 定时任务

用法:
    python scripts/supervisor.py            # 前台运行 (Ctrl+C 停止两个子进程)
    pythonw scripts/supervisor.py           # 后台运行 (推荐配合 start_all.py)

特性:
    - 子进程异常退出自动拉起 (指数退避, 最长 60s), 稳定运行后重置退避
    - 两层共用同一份 MySQL 连接参数 (取自 config/collector.yaml, 已有环境变量优先)
    - 日志分文件: logs/collector.out.log / collector.err.log / server.out.log / server.err.log
    - 守护进程自身事件记录到 logs/supervisor.log
    - 心跳文件 logs/supervisor_heartbeat.json 记录两个子进程状态, 便于外部自监控
    - jar 尚未构建时只守护采集层, 并周期性探测, 一旦补上产物立即纳入守护
"""

import json
import logging
import os
import signal
import subprocess
import sys
import time
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MONITOR_DIR = os.path.dirname(SCRIPT_DIR)                     # <工作区>/ops-collector
WORKSPACE_DIR = os.path.dirname(MONITOR_DIR)                  # <工作区>
SERVER_DIR = os.path.join(WORKSPACE_DIR, "ops-server")       # 业务层工程

LOG_DIR = os.path.join(MONITOR_DIR, "logs")
SUPERVISOR_LOG = os.path.join(LOG_DIR, "supervisor.log")
HEARTBEAT_FILE = os.path.join(LOG_DIR, "supervisor_heartbeat.json")
SERVER_LOG_FILE = os.path.join(LOG_DIR, "server.log")         # Spring Boot 自身的滚动日志

RUN_SCRIPT = os.path.join(MONITOR_DIR, "run.py")
COLLECTOR_YAML = os.path.join(MONITOR_DIR, "config", "collector.yaml")
JAR_CANDIDATES = [os.environ.get("OPS_SERVER_JAR") or "",
                  os.path.join(SERVER_DIR, "target", "moxa-server.jar"),
                  os.path.join(SERVER_DIR, "target", "moxa-server-1.0.0.jar")]

BASE_DELAY = 5          # 首次重启等待秒数
MAX_DELAY = 60          # 最大退避秒数
STABLE_SECONDS = 300    # 子进程存活超过该时长视为稳定, 重置退避
STOP_GRACE = 15         # 优雅停止子进程的等待秒数
TICK_SECONDS = 2        # 守护轮询周期
REPROBE_SECONDS = 60    # 业务层产物缺失时重新探测的间隔秒数

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
    log.info("收到停止信号 (%s), 准备关闭…", signum)
    _shutdown["flag"] = True


def _mysql_env():
    """把 collector.yaml 的 mysql 段导出成 OPS_MYSQL_* 环境变量, 让两层连同一个实例.

    已存在的环境变量优先 (部署时可用 setx / 服务账户注入); 读取失败则整体留空,
    业务层退回 application.yml 中的默认连接参数.
    """
    env = dict(os.environ)
    try:
        import yaml
        with open(COLLECTOR_YAML, "r", encoding="utf-8") as f:
            mysql = (yaml.safe_load(f) or {}).get("mysql") or {}
        mapping = {
            "OPS_MYSQL_HOST": mysql.get("host"),
            "OPS_MYSQL_PORT": mysql.get("port"),
            "OPS_MYSQL_USER": mysql.get("user"),
            "OPS_MYSQL_PASSWORD": mysql.get("password"),
            "OPS_MYSQL_DB": mysql.get("database"),
        }
        for key, value in mapping.items():
            if value is not None and not env.get(key):
                env[key] = str(value)
    except Exception as exc:
        log.warning("读取采集层配置失败, 业务层将使用内置默认连接参数: %s", exc)
    return env


def _java_binary():
    """定位 java 可执行文件: OPS_JAVA > JAVA_HOME/bin/java.exe > PATH 上的 java

    最后一档不能直接返回裸 "java": 计划任务 / pythonw 后台拉起时子进程的 PATH 可能没有 java,
    CreateProcess 会报 [WinError 2] 系统找不到指定的文件, 业务层永远起不来, 所以用 shutil.which
    在启动时就解析成绝对路径, 找不到也宁可返回 None 让上层给出明确提示.
    """
    explicit = os.environ.get("OPS_JAVA")
    if explicit:
        return explicit
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "java.exe")
        if os.path.exists(candidate):
            return candidate
    import shutil
    return shutil.which("java")


def find_jar():
    for path in JAR_CANDIDATES:
        if path and os.path.exists(path):
            return path
    return None


def _collector_python():
    """采集层解释器: 用 python.exe 而不是当前进程的 pythonw.exe

    现场实测 (10.51.190.70 部署机): 同一个 dist-info 元数据文件,
      python.exe  读到 1181 字节 "[apscheduler.executors]..." 正常
      pythonw.exe 读到 5277 字节随机二进制, 按 UTF-8 解码直接抛
        UnicodeDecodeError: 'utf-8' codec can't decode byte 0xe7 in position 3
    apscheduler 在 import 时要调 importlib.metadata.entry_points() 扫全部已装包,
    于是 pythonw 启动的采集层会在延迟导入调度器时崩掉 (运行 46~227s 后 exit=1, 崩溃循环).
    这是解释器进程侧的读取异常, 与本项目代码无关, 因此这里显式避开 pythonw.exe.
    """
    exe = sys.executable or "python"
    if os.path.basename(exe).lower() != "pythonw.exe":
        return exe
    sibling = os.path.join(os.path.dirname(exe), "python.exe")
    return sibling if os.path.exists(sibling) else exe


class Service:
    """一个被守护的子进程: 启动命令 + 崩溃退避状态"""

    def __init__(self, key, label, argv, cwd, out, err, env, unavailable=None):
        self.key = key
        self.label = label
        self.argv = argv
        self.cwd = cwd
        self.out = out
        self.err = err
        self.env = env
        self.unavailable = unavailable
        self.child = None
        self.child_started = 0.0
        self.started_at = None
        self.starts = 0
        self.restarts = 0
        self.failures = 0
        self.not_before = 0.0
        self.exit_code = None

    # ---------- 生命周期 ----------

    def tick(self):
        """守护一轮: 进程不在就按退避节奏重启"""
        if self.unavailable:
            return
        child = self.child
        if child is not None:
            if child.poll() is None:
                return
            self._settle(child.poll())
            self.child = None
        if time.time() < self.not_before:
            return
        self._spawn()

    def _settle(self, exit_code):
        uptime = time.time() - self.child_started
        self.exit_code = exit_code
        self.restarts += 1
        self.child = None
        if _shutdown["flag"]:
            log.info("%s 已随守护进程停止 (exit=%s)", self.label, exit_code)
            return
        if uptime >= STABLE_SECONDS:
            self.failures = 1
            delay = BASE_DELAY
            log.warning("%s 退出 (exit=%s, 运行 %.0fs), %ss 后重启", self.label, exit_code, uptime, delay)
        else:
            self.failures += 1
            delay = min(BASE_DELAY * (2 ** (self.failures - 1)), MAX_DELAY)
            log.warning("%s 异常退出 (exit=%s, 仅运行 %.0fs), %ss 后拉起",
                        self.label, exit_code, uptime, delay)
        self.not_before = time.time() + delay

    def _popen_kwargs(self):
        """守护进程自己跑在 pythonw (无控制台) 下时, 子进程用 python.exe 会弹黑窗, 这里禁掉"""
        if os.name == "nt" and sys.executable and \
                os.path.basename(sys.executable).lower() == "pythonw.exe":
            return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)}
        return {}

    def _spawn(self):
        try:
            out = open(self.out, "ab", buffering=0)
            err = open(self.err, "ab", buffering=0)
        except Exception as exc:
            log.error("打开日志文件失败 (%s): %s", self.key, exc)
            self.not_before = time.time() + MAX_DELAY
            return
        argv = self.argv
        try:
            self.child = subprocess.Popen(
                argv, cwd=self.cwd, stdout=out, stderr=err,
                stdin=subprocess.DEVNULL, env=self.env,
                **self._popen_kwargs()
            )
        except Exception as exc:
            log.error("启动 %s 失败: %s", self.label, exc)
            self.failures += 1
            self.not_before = time.time() + min(BASE_DELAY * (2 ** self.failures), MAX_DELAY)
            out.close()
            err.close()
            return
        out.close()
        err.close()
        self.child_started = time.time()
        self.started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        self.starts += 1
        log.info("%s 已启动 (pid=%s, 第 %d 次拉起): %s",
                 self.label, self.child.pid, self.starts, " ".join(argv))

    def stop(self):
        child = self.child
        if child is None or child.poll() is not None:
            return
        log.info("正在停止 %s (pid=%s)…", self.label, child.pid)
        try:
            child.terminate()
            try:
                child.wait(timeout=STOP_GRACE)
            except subprocess.TimeoutExpired:
                log.warning("%s 未在 %ss 内退出, 强制结束", self.label, STOP_GRACE)
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(child.pid)], capture_output=True)
        except Exception as exc:
            log.warning("停止 %s 异常: %s", self.label, exc)

    def heartbeat(self):
        child = self.child
        return {
            "label": self.label,
            "available": not self.unavailable,
            "pid": child.pid if child else None,
            "running": bool(child and child.poll() is None),
            "started_at": self.started_at,
            "starts": self.starts,
            "restarts": self.restarts,
            "last_exit_code": self.exit_code,
            "log": self.out,
        }


def build_services():
    """受监管的服务清单; jar 缺失时业务层标记 unavailable, 采集层照常运行"""
    env = _mysql_env()
    services = [Service(
        key="collector", label="采集层 (Python)",
        # -X utf8: 采集层日志里有中文设备名与告警文案, 不加时 stdout 按系统 ANSI 代码页(cp936)编码,
        # 重定向到 collector.*.log 后与手工前台运行的 UTF-8 日志混在同一个文件里无法阅读
        argv=[_collector_python(), "-X", "utf8", "-u", RUN_SCRIPT], cwd=MONITOR_DIR,
        out=os.path.join(LOG_DIR, "collector.out.log"),
        err=os.path.join(LOG_DIR, "collector.err.log"), env=env,
    )]

    jar = find_jar()
    java = _java_binary()
    if jar and java:
        services.append(Service(
            key="server", label="业务层 (Spring Boot)",
            argv=[java, "-jar", jar, "--logging.file.name=" + SERVER_LOG_FILE],
            cwd=SERVER_DIR,
            out=os.path.join(LOG_DIR, "server.out.log"),
            err=os.path.join(LOG_DIR, "server.err.log"), env=env,
        ))
    else:
        services.append(Service(
            key="server", label="业务层 (Spring Boot)", argv=[], cwd=SERVER_DIR,
            out=os.path.join(LOG_DIR, "server.out.log"),
            err=os.path.join(LOG_DIR, "server.err.log"), env=env,
            unavailable=("未找到 ops-server/target/moxa-server.jar, 请先执行 "
                         "mvn -f ops-server/pom.xml clean package" if not jar else
                         "PATH / JAVA_HOME / OPS_JAVA 里都没找到 java.exe, 请用 "
                         "start_all.py --java=<java.exe> 或设置 JAVA_HOME 后重启"),
        ))
    return services


def _write_heartbeat(payload):
    try:
        tmp = HEARTBEAT_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        os.replace(tmp, HEARTBEAT_FILE)
    except Exception as exc:  # pragma: no cover - 心跳失败不应影响主流程
        log.warning("写入心跳文件失败: %s", exc)


def _dump_heartbeat(services, started_at, stopped=False):
    _write_heartbeat({
        "supervisor_pid": os.getpid(),
        "state": "stopped" if stopped else "running",
        "started_at": started_at,
        "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "services": {svc.key: svc.heartbeat() for svc in services},
    })


def _interruptible_sleep(seconds):
    deadline = time.time() + seconds
    while not _shutdown["flag"] and time.time() < deadline:
        time.sleep(0.5)


def run():
    for sig in ("SIGINT", "SIGTERM", "SIGBREAK"):
        if hasattr(signal, sig):
            try:
                signal.signal(getattr(signal, sig), _handle_stop)
            except (ValueError, OSError):
                pass

    services = build_services()
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    log.info("=" * 60)
    log.info("服务守护进程启动 (pid=%s), 受监管进程 %d 个", os.getpid(), len(services))
    for svc in services:
        if svc.unavailable:
            log.warning("%s: %s", svc.label, svc.unavailable)
        else:
            log.info("%s: %s", svc.label, " ".join(svc.argv))
    log.info("日志目录: %s", LOG_DIR)
    log.info("=" * 60)

    next_probe = 0.0
    while not _shutdown["flag"]:
        for svc in services:
            # 产物可能刚被别的窗口构建出来, 每隔 REPROBE_SECONDS 重新探测一次
            if svc.unavailable and time.time() >= next_probe:
                jar = find_jar()
                java = _java_binary()
                if jar and java:
                    svc.argv = [java, "-jar", jar, "--logging.file.name=" + SERVER_LOG_FILE]
                    svc.env = _mysql_env()
                    svc.unavailable = None
                    log.info("业务层产物已就绪, 纳入守护: %s", " ".join(svc.argv))
            svc.tick()
        next_probe = time.time() + REPROBE_SECONDS if any(s.unavailable for s in services) else 0.0
        _dump_heartbeat(services, started_at)
        _interruptible_sleep(TICK_SECONDS)

    for svc in services:
        svc.stop()
    _dump_heartbeat(services, started_at, stopped=True)
    log.info("守护进程已停止")


if __name__ == "__main__":
    try:
        run()
    except Exception:
        log.exception("守护进程异常终止")
        sys.exit(1)
