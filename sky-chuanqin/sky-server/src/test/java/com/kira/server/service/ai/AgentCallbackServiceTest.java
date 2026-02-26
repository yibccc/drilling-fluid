package com.kira.server.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentCallbackServiceTest {

    @Autowired
    private AgentCallbackService agentCallbackService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testServiceBeanExists() {
        assertThat(agentCallbackService).isNotNull();
    }

    @Test
    void testHandleDiagnosisCallback() throws JsonProcessingException {
        String json = "{\"task_id\":\"task-001\",\"status\":\"completed\",\"result\":\"test result\"}";
        JsonNode payload = objectMapper.readTree(json);

        // 不应抛出异常
        agentCallbackService.handleDiagnosisCallback(payload);
    }
}
