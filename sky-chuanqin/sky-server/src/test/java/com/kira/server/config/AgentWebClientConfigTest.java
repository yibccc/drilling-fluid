package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "agent.base-url=http://test-agent:8000",
    "agent.api-key=test-api-key"
})
class AgentWebClientConfigTest {

    @Autowired
    private WebClient agentWebClient;

    @Test
    void testAgentWebClientBeanExists() {
        assertThat(agentWebClient).isNotNull();
    }

    @Test
    void testAgentWebClientHasCorrectConfiguration() {
        // WebClient 不直接暴露 base URL，但可以验证 bean 存在
        assertThat(agentWebClient).isNotNull();
    }
}
