package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

class WebMvcAsyncConfigTest {

    @Test
    void shouldRegisterTaskExecutorForMvcAsyncStreaming() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.initialize();
        WebMvcAsyncConfig config = new WebMvcAsyncConfig(executor);
        AsyncSupportConfigurer configurer = new AsyncSupportConfigurer();

        config.configureAsyncSupport(configurer);

        AsyncTaskExecutor configuredExecutor =
                (AsyncTaskExecutor) ReflectionTestUtils.getField(configurer, "taskExecutor");
        assertThat(configuredExecutor).isSameAs(executor);
    }
}
