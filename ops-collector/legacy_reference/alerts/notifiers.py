"""
通知推送模块
- 邮件通知 (SMTP)
- 钉钉机器人 Webhook
- 支持扩展其他渠道
"""

import hashlib
import hmac
import base64
import logging
import time
import urllib.parse
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
import smtplib
from typing import Optional

import httpx

from backend.config import config
from backend.models.alert import AlertRecord

logger = logging.getLogger(__name__)


class NotificationManager:
    """通知管理器"""

    def __init__(self):
        self.notify_config = config.notifications
        self.email_config = self.notify_config.get("email", {})
        self.dingtalk_config = self.notify_config.get("dingtalk", {})

    def get_active_channels(self) -> str:
        """获取已启用的通知渠道"""
        channels = []
        if self.email_config.get("enabled"):
            channels.append("email")
        if self.dingtalk_config.get("enabled"):
            channels.append("dingtalk")
        return ",".join(channels)

    async def send_alert(self, alert: AlertRecord) -> bool:
        """发送告警通知到所有已启用渠道"""
        sent = False

        if self.email_config.get("enabled"):
            try:
                await self._send_email(alert)
                sent = True
            except Exception as e:
                logger.error(f"邮件通知发送失败: {e}")

        if self.dingtalk_config.get("enabled"):
            try:
                await self._send_dingtalk(alert)
                sent = True
            except Exception as e:
                logger.error(f"钉钉通知发送失败: {e}")

        return sent

    async def _send_email(self, alert: AlertRecord):
        """发送邮件通知"""
        smtp_host = self.email_config["smtp_host"]
        smtp_port = self.email_config.get("smtp_port", 465)
        use_ssl = self.email_config.get("smtp_ssl", True)
        username = self.email_config.get("username", "")
        password = self.email_config.get("password", "")
        sender = self.email_config.get("sender", "")
        recipients = self.email_config.get("recipients", [])

        if not recipients:
            logger.warning("邮件收件人列表为空")
            return

        # 构建邮件内容
        severity_emoji = {
            "INFO": "[INFO]",
            "WARNING": "[WARNING]",
            "CRITICAL": "[CRITICAL]",
        }
        tag = severity_emoji.get(alert.severity, "[ALERT]")

        subject = f"{tag} {alert.title}"
        body = f"""
MOXA 主动运维监控系统 - 告警通知

告警级别: {alert.severity}
告警标题: {alert.title}
设备名称: {alert.device_name}
设备类型: {alert.device_type}
告警描述: {alert.description}
触发时间: {alert.triggered_at.strftime('%Y-%m-%d %H:%M:%S')}
指标值:   {alert.metric_value}
告警阈值: {alert.threshold}

请尽快处理。
"""

        msg = MIMEMultipart()
        msg["From"] = sender
        msg["To"] = ", ".join(recipients)
        msg["Subject"] = subject
        msg.attach(MIMEText(body, "plain", "utf-8"))

        # 发送邮件
        import asyncio
        loop = asyncio.get_event_loop()

        def _send():
            if use_ssl:
                server = smtplib.SMTP_SSL(smtp_host, smtp_port, timeout=10)
            else:
                server = smtplib.SMTP(smtp_host, smtp_port, timeout=10)
                server.starttls()

            if username:
                server.login(username, password)

            server.sendmail(sender, recipients, msg.as_string())
            server.quit()

        await loop.run_in_executor(None, _send)
        logger.info(f"邮件通知已发送: {subject}")

    async def _send_dingtalk(self, alert: AlertRecord):
        """发送钉钉机器人通知"""
        webhook_url = self.dingtalk_config.get("webhook_url", "")
        secret = self.dingtalk_config.get("secret", "")

        if not webhook_url:
            logger.warning("钉钉 Webhook URL 未配置")
            return

        # 加签
        if secret:
            timestamp = str(round(time.time() * 1000))
            string_to_sign = f"{timestamp}\n{secret}"
            hmac_code = hmac.new(
                secret.encode("utf-8"),
                string_to_sign.encode("utf-8"),
                digestmod=hashlib.sha256,
            ).digest()
            sign = urllib.parse.quote_plus(base64.b64encode(hmac_code))
            webhook_url = f"{webhook_url}&timestamp={timestamp}&sign={sign}"

        # 构建消息
        severity_colors = {
            "INFO": "#1890ff",
            "WARNING": "#faad14",
            "CRITICAL": "#f5222d",
        }

        markdown_text = f"""### {alert.severity} 告警通知

**告警标题**: {alert.title}

**设备名称**: {alert.device_name} ({alert.device_type})

**告警级别**: <font color="{severity_colors.get(alert.severity, '#000')}">{alert.severity}</font>

**告警描述**: {alert.description}

**触发时间**: {alert.triggered_at.strftime('%Y-%m-%d %H:%M:%S')}

**指标值**: {alert.metric_value}

---
> 来自 MOXA 主动运维监控系统"""

        payload = {
            "msgtype": "markdown",
            "markdown": {
                "title": f"[{alert.severity}] {alert.title}",
                "text": markdown_text,
            },
        }

        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(webhook_url, json=payload)
            resp.raise_for_status()
            result = resp.json()

        if result.get("errcode", -1) != 0:
            logger.error(f"钉钉通知返回错误: {result}")
        else:
            logger.info(f"钉钉通知已发送: {alert.title}")
