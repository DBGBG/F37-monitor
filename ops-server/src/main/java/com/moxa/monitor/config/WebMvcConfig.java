package com.moxa.monitor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 静态资源与 SPA 回退: 前端产物打包进 classpath:/static/,
 * 非 /api 路径且文件不存在时一律回 index.html, 由 vue-router 接管路由.
 *
 * 注册顺序必须早于 Spring Boot 默认的 "/**" 资源映射 (其顺序为 0),
 * 否则默认的只查文件的 handler 会先命中, 回退逻辑就不会执行.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 接口路径不做回退, 交给 DispatcherServlet 返回 404 信封
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("error")) {
                            return null;
                        }
                        return INDEX.exists() ? INDEX : null;
                    }
                });
    }

    /** SSE (SseEmitter) 不设超时, 由客户端断线重连 */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1L);
    }
}
