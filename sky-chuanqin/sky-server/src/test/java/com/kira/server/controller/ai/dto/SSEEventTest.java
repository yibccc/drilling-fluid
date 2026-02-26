package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SSEEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatSSEEventStart() throws Exception {
        SSEEvent.ChatSSEEvent event = new SSEEvent.ChatSSEEvent();
        event.setType(SSEEventType.START);
        event.setSessionId("session-123");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"start\"");
        assertThat(json).contains("\"session_id\":\"session-123\"");
    }

    @Test
    void testChatSSEEventToken() throws Exception {
        SSEEvent.ChatSSEEvent event = new SSEEvent.ChatSSEEvent();
        event.setType(SSEEventType.TOKEN);
        event.setContent("你好");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"token\"");
        assertThat(json).contains("\"content\":\"你好\"");
    }

    @Test
    void testDiagnosisSSEEvent() throws Exception {
        SSEEvent.DiagnosisSSEEvent event = new SSEEvent.DiagnosisSSEEvent();
        event.setType(SSEEventType.DIAGNOSIS);
        event.setTaskId("task-001");
        event.setContent("分析完成");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"diagnosis\"");
        assertThat(json).contains("\"task_id\":\"task-001\"");
    }
}
