package com.kira.server.service.ai;

import com.kira.server.config.AgentWebClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "agent.base-url=http://test-agent:8000",
    "agent.api-key=test-key"
})
class SSEForwardServiceTest {

    @Autowired
    private SSEForwardService sseForwardService;

    @Test
    void testServiceBeanExists() {
        assertThat(sseForwardService).isNotNull();
    }

    @Test
    void testForwardSSEMethodExists() {
        // 验证方法存在且签名正确
        // 实际功能将在集成测试中验证
        Flux<String> result = sseForwardService.forwardSSE("/test", null, Duration.ofSeconds(5));
        assertThat(result).isNotNull();
    }
}
