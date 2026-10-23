package com.moxa.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器.
 * 定时任务 (@Scheduled: 告警引擎/主动分析/点检生成/快照轮询/保留清理) 由 Boot 自动配置的
 * ThreadPoolTaskScheduler 承载, 线程数见 application.yml 的 spring.task.scheduling.pool.size.
 * 这里只额外提供业务异步线程池, 避免 SSH 下发与邮件/钉钉通知阻塞接口请求线程.
 */
@EnableAsync
@Configuration
public class AsyncSchedulerConfig {

    /** 配置下发: 单台设备一次下发可能十几秒, 独立小池 + 有界队列, 满了直接拒绝并回 400xx */
    @Bean("sshExecutor")
    public Executor sshExecutor(AppProperties props) {
        int max = Math.max(1, props.getSsh().getMaxConcurrent());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(max);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ops-ssh-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 通知发送 (SMTP / 钉钉): 失败不影响告警落库, 全部异步 */
    @Bean("notifyExecutor")
    public Executor notifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ops-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 采集心跳变化后的即时告警检查: 单线程 + 队列长度 1, 多余的触发直接丢弃
     * (AlertEngineService 内部本来就有 tryLock 防重入, 排队没有意义, 更不能阻塞 2 秒一次的快照线程).
     */
    @Bean("alertCheckExecutor")
    public Executor alertCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("ops-alert-check-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
