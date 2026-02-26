package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.DiagnosisCacheService;
import com.kira.server.service.ai.SSEForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
class DiagnosisControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SSEForwardService sseForwardService;

    @Autowired
    private DiagnosisCacheService cacheService;

    private static final String TEST_ALERT_ID = "TEST-ALERT-456";
    private static final String CACHED_RESULT = "data: {\"type\":\"diagnosis\",\"content\":\"测试结果\"}\\n\\n";

    @BeforeEach
    void setup() {
        cacheService.saveDiagnosisResult(TEST_ALERT_ID, CACHED_RESULT);
    }

    @Test
    void testDiagnosisAnalyzeEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId("well-001");

        webTestClient.post()
            .uri("/api/ai/diagnosis/analyze")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testDiagnosisGetResultEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"result\"}\n\n"));

        webTestClient.get()
            .uri("/api/ai/diagnosis/task-001")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testGetCachedDiagnosis() {
        webTestClient.get()
                .uri("/api/ai/diagnosis/stream?alertId=" + TEST_ALERT_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }

    @Test
    void testGetNonExistentDiagnosis() {
        webTestClient.get()
                .uri("/api/ai/diagnosis/stream?alertId=NON-EXISTENT")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }
}
