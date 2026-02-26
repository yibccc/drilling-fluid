package com.kira.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.ai.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Agent 服务集成测试
 *
 * 注意：这些测试需要 FastAPI Agent 服务正在运行
 * 如果服务未运行，测试将跳过或失败
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
class AgentIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatStreamIntegration() {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好，请介绍一下钻井液的基本性能");
        request.setStream(true);

        webTestClient.post()
                .uri("/api/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }

    @Test
    void testDiagnosisAnalyzeIntegration() {
        String requestJson = "{\n" +
            "    \"well_id\": \"well-001\",\n" +
            "    \"alert_type\": \"HIGH_DENSITY\",\n" +
            "    \"alert_triggered_at\": \"2026-02-26T10:00:00\",\n" +
            "    \"stream\": true\n" +
            "}";

        webTestClient.post()
                .uri("/api/ai/diagnosis/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }
}
