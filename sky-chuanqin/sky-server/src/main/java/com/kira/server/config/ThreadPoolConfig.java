package com.kira.server.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置类
 * 用于配置应用程序中使用的线程池，特别是用于处理钻井数据的并发任务
 *
 * @author Kira
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "thread-pool")
@Data
public class ThreadPoolConfig {

    /**
     * 核心线程数
     * 默认为0，表示使用CPU核心数
     */
    private int corePoolSize = 0;

    /**
     * 最大线程数
     * 默认为0，表示使用CPU核心数的2倍
     */
    private int maxPoolSize = 0;

    /**
     * 队列容量
     */
    private int queueCapacity = 100;

    /**
     * 线程空闲时间（秒）
     */
    private int keepAliveSeconds = 60;

    /**
     * 线程名称前缀
     */
    private String threadNamePrefix = "drilling-data-";

    /**
     * 是否等待任务完成后再关闭
     */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /**
     * 等待终止的秒数
     */
    private int awaitTerminationSeconds = 60;

    /**
     * 创建用于处理钻井数据的线程池
     *
     * @return 配置好的线程池执行器
     */
    @Bean(name = "drillingDataExecutor")
    public Executor drillingDataExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 获取CPU核心数
        int processors = Runtime.getRuntime().availableProcessors();

        // 如果配置为0，则使用默认值
        int actualCorePoolSize = corePoolSize > 0 ? corePoolSize : processors;
        int actualMaxPoolSize = maxPoolSize > 0 ? maxPoolSize : processors * 2;

        executor.setCorePoolSize(actualCorePoolSize);
        executor.setMaxPoolSize(actualMaxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(waitForTasksToCompleteOnShutdown);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);

        // 拒绝策略：由调用线程处理该任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 初始化线程池
        executor.initialize();

        log.info("钻井数据线程池初始化完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                actualCorePoolSize, actualMaxPoolSize, queueCapacity);

        return executor;
    }

    /**
     * 创建用于异步任务的线程池（如操作日志记录）
     * 使用较小的线程池，避免占用过多资源
     *
     * @return 配置好的线程池执行器
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 异步任务线程池使用较小的配置
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 拒绝策略：由调用线程处理该任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 初始化线程池
        executor.initialize();

        log.info("异步任务线程池初始化完成 - 核心线程数: 2, 最大线程数: 5, 队列容量: 200");

        return executor;
    }
}

