package com.moxa.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 自定义配置 (前缀 ops.*), 对应 application.yml.
 * 业务运行参数 (告警阈值/保留策略/通知开关等) 不放这里, 一律读 sys_config 表, 便于前端在线修改.
 */
@Data
@ConfigurationProperties(prefix = "ops")
public class AppProperties {

    private Security security = new Security();
    private Ssh ssh = new Ssh();
    private Alert alert = new Alert();
    private Cors cors = new Cors();

    @Data
    public static class Security {
        /** JWT 签名密钥, HS256 要求至少 32 字节 */
        private String jwtSecret = "5a3f0b7e91c2d84a6b5f7e9d3c1a8b6e4f2d0c9a8b7e6d5c4b3a291807f6e5d4";
        /** token 有效期(分钟) */
        private int jwtExpireMinutes = 720;
        private String issuer = "moxa-server";
    }

    @Data
    public static class Ssh {
        /** 单条命令等待回显的超时(秒) */
        private int timeoutSeconds = 30;
        private int connectTimeoutMs = 10000;
        /** 并发下发上限 (设备级锁之外的全局限流) */
        private int maxConcurrent = 5;
    }

    @Data
    public static class Alert {
        /** 采集心跳超过该秒数视为采集层离线 */
        private int collectorDeadSeconds = 180;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");
    }
}
