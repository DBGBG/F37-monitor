package com.moxa.monitor.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.moxa.monitor.common.DateTimeSerializers;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置.
 * 字段命名 (snake_case) 与时区由 application.yml 的 spring.jackson.* 统一控制,
 * 这里只挂时间序列化模块并放宽反序列化: 入参多传字段不报错, 与迁移前的 FastAPI 行为一致.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module moxaDateTimeModule() {
        return DateTimeSerializers.module();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer moxaJacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
