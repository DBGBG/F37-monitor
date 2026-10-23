package com.moxa.monitor.service.automation;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.moxa.monitor.config.AppProperties;
import com.moxa.monitor.domain.SwitchDevice;
import com.moxa.monitor.repo.SwitchDeviceRepository;
import com.moxa.monitor.service.system.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH 命令下发器: 迁移自 legacy automation/ssh_executor.py (netmiko) , 改用 JSch 复刻同一套行为 ——
 * 设备级锁保证同一台串行, 先尝试 enable + 配置模式, 失败则回退直连逐条下发,
 * 每条命令等待回显 (超时取 sys_config.ssh_defaults.timeout, 默认 30 秒), 结束执行 save,
 * 无论成功失败都写 config_audit_logs.
 *
 * 凭据来源变更: 迁移前只认 devices.yaml 的 config.switches, 改造后统一读 switch_devices.ssh_config,
 * 因此自动发现的设备同样可以下发配置.
 *
 * 迁移前的内存任务队列 (submit_task / start_worker) 没有任何接口在用, 故不再移植.
 */
@Service
public class MoxaSshClient {

    private static final Logger log = LoggerFactory.getLogger(MoxaSshClient.class);

    /** 单条命令回显静默多久视为输出结束 (毫秒) */
    private static final long QUIET_MS = 800L;

    /** 结束保存配置的等待时间, 与迁移前 send_command("save", read_timeout=10) 对应 */
    private static final int SAVE_TIMEOUT_MS = 10_000;

    private final SwitchDeviceRepository switchRepository;
    private final SysConfigService configService;
    private final AuditLogService auditLogService;
    private final AppProperties properties;

    /** switch_id -> 设备锁, 与迁移前 _device_locks 等价 */
    private final Map<String, Lock> deviceLocks = new ConcurrentHashMap<>();

    /** 全局并发上限, 避免批量下发把设备 CLI 打爆 */
    private final Semaphore concurrencyLimit;

    public MoxaSshClient(SwitchDeviceRepository switchRepository,
                         SysConfigService configService,
                         AuditLogService auditLogService,
                         AppProperties properties) {
        this.switchRepository = switchRepository;
        this.configService = configService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.concurrencyLimit = new Semaphore(Math.max(properties.getSsh().getMaxConcurrent(), 1));
    }

    /** 下发目标: 一台交换机及其 SSH 凭据 */
    public record SwitchTarget(String id, String name, String ip, Map<String, Object> sshConfig) {
    }

    /** 按 ID 取下发目标, 设备不存在返回 null (调用方按迁移前语义回 "交换机不存在") */
    public SwitchTarget findTarget(String switchId) {
        if (switchId == null || switchId.isBlank()) {
            return null;
        }
        return switchRepository.findById(switchId)
                .map(device -> new SwitchTarget(device.getId(), device.getName(), device.getIp(),
                        device.getSshConfig()))
                .orElse(null);
    }

    /**
     * 在指定交换机上执行命令列表.
     * 返回结构与迁移前一致: {status, output, error, duration_ms, commands}.
     */
    public Map<String, Object> execute(SwitchTarget target, List<String> commands, String operationType,
                                       String operationDesc, String operator, Map<String, Object> parameters) {
        List<String> cmdList = commands == null ? List.of() : commands;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("output", "");
        result.put("error", "");
        result.put("duration_ms", 0);
        result.put("commands", cmdList);

        long startAt = System.currentTimeMillis();
        Lock lock = deviceLocks.computeIfAbsent(target.id(), key -> new ReentrantLock());
        lock.lock();
        try {
            String output = run(target, cmdList);
            result.put("output", output);
            result.put("status", "success");
        } catch (Exception ex) {
            result.put("status", "failed");
            result.put("error", String.valueOf(ex.getMessage()));
            log.error("SSH 执行失败 [{}]: {}", target.ip(), ex.getMessage());
        } finally {
            int durationMs = (int) (System.currentTimeMillis() - startAt);
            result.put("duration_ms", durationMs);
            auditLogService.record(target.id(), target.name(), target.ip(), operationType, operationDesc,
                    operator == null ? "system" : operator, cmdList,
                    String.valueOf(result.get("output")), parameters,
                    String.valueOf(result.get("status")), String.valueOf(result.get("error")), durationMs);
            lock.unlock();
        }
        return result;
    }

    // ==================== SSH 交互 ====================

    /** 先带 enable + 配置模式执行, 失败则回退为直接逐条下发 (部分 MOXA 型号不支持配置模式命令) */
    private String run(SwitchTarget target, List<String> commands) throws Exception {
        SshCredentials credential = credentials(target);
        int timeoutMs = commandTimeoutMs() * 1000;
        concurrencyLimit.acquire();
        try {
            try {
                return session(credential, commands, timeoutMs, true);
            } catch (Exception first) {
                log.warn("[{}] enable/配置模式下发失败, 回退直连逐条下发: {}", target.ip(), first.getMessage());
                try {
                    return session(credential, commands, timeoutMs, false);
                } catch (Exception second) {
                    throw new RuntimeException("SSH 连接失败: " + second.getMessage(), second);
                }
            }
        } finally {
            concurrencyLimit.release();
        }
    }

    /** 建会话 -> exec 通道内起交互 shell -> 按需进入配置模式 -> 逐条下发 -> save */
    private String session(SshCredentials credential, List<String> commands, int timeoutMs, boolean enterConfig)
            throws Exception {
        JSch jsch = new JSch();
        Session ssh = jsch.getSession(credential.username(), credential.host(), credential.port());
        ssh.setPassword(credential.password());
        ssh.setConfig("StrictHostKeyChecking", "no");
        ssh.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey");
        // JSch 的 timeout 同时作用于 socket 读与会话 keepalive
        ssh.setTimeout(Math.max(timeoutMs, SAVE_TIMEOUT_MS));
        StringBuilder output = new StringBuilder();
        ChannelExec channel = null;
        try {
            ssh.connect(properties.getSsh().getConnectTimeoutMs());
            channel = (ChannelExec) ssh.openChannel("exec");
            channel.setCommand("sh");
            channel.setPty(true);
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            channel.connect(properties.getSsh().getConnectTimeoutMs());
            // 等登录横幅与首个提示符稳定下来
            drain(in, QUIET_MS, 2000L);

            if (enterConfig) {
                send(out, "enable");
                drain(in, QUIET_MS, timeoutMs);
                send(out, "configure terminal");
                drain(in, QUIET_MS, timeoutMs);
            }
            for (String command : commands) {
                send(out, command);
                String echo = drain(in, QUIET_MS, timeoutMs);
                output.append(">>> ").append(command).append('\n')
                        .append(stripEcho(echo, command)).append("\n\n");
            }
            if (enterConfig) {
                // 退出配置模式后统一保存
                send(out, "exit");
                drain(in, QUIET_MS, timeoutMs);
                send(out, "save");
                String saved = drain(in, QUIET_MS, SAVE_TIMEOUT_MS);
                output.append(">>> save\n").append(stripEcho(saved, "save")).append('\n');
            } else {
                try {
                    send(out, "save");
                    String saved = drain(in, QUIET_MS, SAVE_TIMEOUT_MS);
                    output.append(">>> save\n").append(stripEcho(saved, "save")).append('\n');
                } catch (Exception ignore) {
                    // 与迁移前一致: 回退路径下的 save 失败不影响命令结果
                }
            }
            return output.toString();
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (ssh.isConnected()) {
                ssh.disconnect();
            }
        }
    }

    /** 凭据: switch_devices.ssh_config 覆盖 sys_config.ssh_defaults, 再兜底 admin/22 */
    private SshCredentials credentials(SwitchTarget target) {
        Map<String, Object> cfg = target.sshConfig() == null ? Map.of() : target.sshConfig();
        int defaultPort = configService.integer(SysConfigService.SSH_DEFAULTS + ".port", 22);
        String defaultUser = configService.text(SysConfigService.SSH_DEFAULTS + ".username", "admin");
        return new SshCredentials(
            text(cfg, "host", target.ip()),
            text(cfg, "username", defaultUser),
            text(cfg, "password", ""),
            integer(cfg, "port", defaultPort));
    }

    private int commandTimeoutMs() {
        int timeout = configService.integer(SysConfigService.SSH_DEFAULTS + ".timeout",
                properties.getSsh().getTimeoutSeconds());
        return timeout <= 0 ? properties.getSsh().getTimeoutSeconds() : timeout;
    }

    // ==================== 读写小工具 ====================

    private static void send(OutputStream out, String command) throws Exception {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 读到"连续 QUIET_MS 没有新数据"为止, 最长不超过 maxWaitMs.
     * MOXA CLI 没有稳定的提示符正则, 用静默判定比匹配提示符更耐型号差异.
     */
    private static String drain(InputStream in, long quietMs, long maxWaitMs) throws Exception {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        long deadline = System.currentTimeMillis() + maxWaitMs;
        long lastReadAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(chunk, 0, Math.min(available, chunk.length));
                if (read < 0) {
                    break;
                }
                buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                lastReadAt = System.currentTimeMillis();
                continue;
            }
            if (buffer.length() > 0 && System.currentTimeMillis() - lastReadAt >= quietMs) {
                break;
            }
            Thread.sleep(50L);
        }
        return normalize(buffer.toString());
    }

    /** 去掉回显的命令行与前后空行, 让输出与迁移前 send_command 的返回值接近 */
    private static String stripEcho(String text, String command) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(command.trim())) {
                lines.remove(i);
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.replaceAll("\\r$", "")).append('\n');
        }
        return sb.toString().strip();
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String text(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private static int integer(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** 会话参数 */
    private record SshCredentials(String host, String username, String password, int port) {
    }
}
