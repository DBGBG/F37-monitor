package com.moxa.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 主动运维系统 - 业务层入口 (jar 产物名仍为 moxa-server.jar, 包名保持 com.moxa.monitor).
 *
 * 分层边界: 本进程只读数据库并对外提供 REST/SSE, 不做任何 SNMP/SSH 采集
 * (设备配置下发除外); 采集由 ops-collector 的 Python 守护进程写入 MySQL.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MoxaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoxaServerApplication.class, args);
    }
}
