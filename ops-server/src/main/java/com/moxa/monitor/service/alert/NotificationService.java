package com.moxa.monitor.service.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxa.monitor.common.DateTimeSerializers;
import com.moxa.monitor.domain.AlertRecord;
import com.moxa.monitor.service.system.SysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 告警通知服务: 迁移自 legacy alerts/notifiers.py.
 * 渠道配置一律来自 sys_config.notification_config (前端可在线改), 因此不复用 spring.mail.*,
 * 而是按配置临时构造 JavaMailSenderImpl; 钉钉走 HmacSHA256 加签的 Webhook.
 * 单渠道失败只记日志, 不影响另一渠道 —— 与迁移前 send_alert 的 try/except 语义一致.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Map<String, String> SEVERITY_TAGS = Map.of(
            "INFO", "[INFO]", "WARNING", "[WARNING]", "CRITICAL", "[CRITICAL]");

    private static final Map<String, String> SEVERITY_COLORS = Map.of(
            "INFO", "#1890ff", "WARNING", "#faad14", "CRITICAL", "#f5222d");

    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public NotificationService(SysConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 回填 alert_records.notify_channels, 与迁移前 get_active_channels 逐字一致 */
    public String activeChannels() {
        JsonNode root = configService.node(SysConfigService.NOTIFICATION_CONFIG);
        List<String> channels = new ArrayList<>();
        if (root.path("email").path("enabled").asBoolean(false)) {
            channels.add("email");
        }
        if (root.path("dingtalk").path("enabled").asBoolean(false)) {
            channels.add("dingtalk");
        }
        return String.join(",", channels);
    }

    /** 是否有任一渠道启用 (都没启用时引擎跳过通知扫描) */
    public boolean anyChannelEnabled() {
        return !activeChannels().isEmpty();
    }

    /** 发送到全部启用渠道, 返回是否至少发出一条 (迁移前 send_alert 的返回值语义) */
    public boolean sendAlert(AlertRecord alert) {
        JsonNode root = configService.node(SysConfigService.NOTIFICATION_CONFIG);
        boolean sent = false;

        JsonNode email = root.path("email");
        if (email.path("enabled").asBoolean(false)) {
            try {
                sendEmail(alert, email);
                sent = true;
            } catch (Exception ex) {
                log.error("邮件通知发送失败: {}", ex.getMessage());
            }
        }

        JsonNode dingtalk = root.path("dingtalk");
        if (dingtalk.path("enabled").asBoolean(false)) {
            try {
                sendDingtalk(alert, dingtalk);
                sent = true;
            } catch (Exception ex) {
                log.error("钉钉通知发送失败: {}", ex.getMessage());
            }
        }
        return sent;
    }

    // ==================== 邮件 ====================

    private void sendEmail(AlertRecord alert, JsonNode emailConfig) throws Exception {
        List<String> recipients = readStringList(emailConfig.path("recipients"));
        if (recipients.isEmpty()) {
            log.warn("邮件收件人列表为空");
            return;
        }

        String tag = SEVERITY_TAGS.getOrDefault(alert.getSeverity(), "[ALERT]");
        String subject = tag + " " + alert.getTitle();
        String body = buildEmailBody(alert);

        String host = emailConfig.path("smtp_host").asText("");
        int port = emailConfig.path("smtp_port").asInt(465);
        boolean useSsl = emailConfig.path("smtp_ssl").asBoolean(true);
        String username = emailConfig.path("username").asText("");
        String password = emailConfig.path("password").asText("");
        String sender = emailConfig.path("sender").asText("");

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);
        mailSender.setDefaultEncoding("UTF-8");
        Properties props = new Properties();
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (useSsl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        mailSender.setJavaMailProperties(props);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(sender.isEmpty() ? username : sender);
        helper.setTo(recipients.toArray(new String[0]));
        helper.setSubject(subject);
        helper.setText(body, false);
        mailSender.send(message);

        log.info("邮件通知已发送: {}", subject);
    }

    /** 正文模板与迁移前 notifiers.py 逐行一致 (仅抬头系统名按全项目命名口径改为"主动运维系统") */
    private String buildEmailBody(AlertRecord alert) {
        return "主动运维系统 - 告警通知\n\n"
                + "告警级别: " + alert.getSeverity() + "\n"
                + "告警标题: " + alert.getTitle() + "\n"
                + "设备名称: " + alert.getDeviceName() + "\n"
                + "设备类型: " + alert.getDeviceType() + "\n"
                + "告警描述: " + nullToEmpty(alert.getDescription()) + "\n"
                + "触发时间: " + DateTimeSerializers.fmt(alert.getTriggeredAt()) + "\n"
                + "指标值:   " + nullToEmpty(alert.getMetricValue()) + "\n"
                + "告警阈值: " + nullToEmpty(alert.getThreshold()) + "\n\n"
                + "请尽快处理。\n";
    }

    // ==================== 钉钉 ====================

    private void sendDingtalk(AlertRecord alert, JsonNode dingtalkConfig) throws Exception {
        String webhook = dingtalkConfig.path("webhook_url").asText("");
        String secret = dingtalkConfig.path("secret").asText("");
        if (webhook.isEmpty()) {
            log.warn("钉钉 Webhook URL 未配置");
            return;
        }
        if (!secret.isEmpty()) {
            long timestamp = System.currentTimeMillis();
            String sign = sign(timestamp, secret);
            webhook = webhook + "&timestamp=" + timestamp + "&sign=" + sign;
        }

        String color = SEVERITY_COLORS.getOrDefault(alert.getSeverity(), "#000");
        String markdown = "### " + alert.getSeverity() + " 告警通知\n\n"
                + "**告警标题**: " + alert.getTitle() + "\n\n"
                + "**设备名称**: " + alert.getDeviceName() + " (" + alert.getDeviceType() + ")\n\n"
                + "**告警级别**: <font color=\"" + color + "\">" + alert.getSeverity() + "</font>\n\n"
                + "**告警描述**: " + nullToEmpty(alert.getDescription()) + "\n\n"
                + "**触发时间**: " + DateTimeSerializers.fmt(alert.getTriggeredAt()) + "\n\n"
                + "**指标值**: " + nullToEmpty(alert.getMetricValue()) + "\n\n"
                + "---\n> 来自主动运维系统";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", Map.of("title", "[" + alert.getSeverity() + "] " + alert.getTitle(),
                "text", markdown));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhook))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode result = objectMapper.readTree(response.body());
        if (result.path("errcode").asInt(-1) != 0) {
            log.error("钉钉通知返回错误: {}", response.body());
        } else {
            log.info("钉钉通知已发送: {}", alert.getTitle());
        }
    }

    /** string_to_sign = "{timestamp}\n{secret}" -> HmacSHA256 -> Base64 -> percent-encode */
    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] code = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        // 与 Python urllib.parse.quote_plus(base64) 同义: + / = 均需转义, Base64 串中不含空格
        return URLEncoder.encode(Base64.getEncoder().encodeToString(code), StandardCharsets.UTF_8);
    }

    // ==================== 内部 ====================

    private static List<String> readStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> list.add(item.asText()));
        }
        return list;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
