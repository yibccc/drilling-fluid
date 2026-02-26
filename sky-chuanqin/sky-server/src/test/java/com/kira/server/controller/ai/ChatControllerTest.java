package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.ChatRequest;
import com.kira.server.service.ai.SSEForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
class ChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SSEForwardService sseForwardService;

    @Test
    void testChatStreamEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        webTestClient.post()
            .uri("/api/ai/chat/stream")
            .bodyValue(new ChatRequest())
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testChatStreamReturnsSSEContentType() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        webTestClient.post()
            .uri("/api/ai/chat/stream")
            .bodyValue(new ChatRequest())
            .exchange()
            .expectHeader().contentType("text/event-stream");
    }
}
