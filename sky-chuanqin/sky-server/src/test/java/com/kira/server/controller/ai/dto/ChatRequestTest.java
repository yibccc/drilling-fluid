package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ChatRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatRequestSerialization() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        request.setSessionId("session-123");
        request.setStream(true);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"message\":\"你好\"");
        assertThat(json).contains("\"session_id\":\"session-123\"");
        assertThat(json).contains("\"stream\":true");
    }

    @Test
    void testChatRequestDeserialization() throws Exception {
        String json = "{\"message\":\"test\",\"session_id\":\"abc\",\"stream\":false}";

        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        assertThat(request.getMessage()).isEqualTo("test");
        assertThat(request.getSessionId()).isEqualTo("abc");
        assertThat(request.getStream()).isFalse();
    }
}
