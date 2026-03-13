package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.DiagnosisCacheService;
import com.kira.server.service.ai.SSEForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiagnosisControllerTest {

    private static final String TEST_ALERT_ID = "TEST-ALERT-456";
    private static final String CACHED_RESULT = "data: {\"type\":\"diagnosis\",\"content\":\"测试结果\"}\\n\\n";

    @Test
    void testDiagnosisAnalyzeEndpointExists() {
        SSEForwardService sseForwardService = mock(SSEForwardService.class);
        DiagnosisCacheService cacheService = mock(DiagnosisCacheService.class);
        WebTestClient webTestClient = WebTestClient.bindToController(
                new DiagnosisController(sseForwardService, cacheService)
        ).build();

        when(sseForwardService.forwardSSEWithCache(any(), any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        DiagnosisRequest request = new DiagnosisRequest();
        request.setAlertId(TEST_ALERT_ID);
        request.setWellId("well-001");

        webTestClient.post()
            .uri("/api/ai/diagnosis/analyze")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testDiagnosisGetResultEndpointExists() {
        SSEForwardService sseForwardService = mock(SSEForwardService.class);
        DiagnosisCacheService cacheService = mock(DiagnosisCacheService.class);
        WebTestClient webTestClient = WebTestClient.bindToController(
                new DiagnosisController(sseForwardService, cacheService)
        ).build();

        when(sseForwardService.forwardJsonGet(eq("/api/v1/diagnosis/task-001"), any()))
            .thenReturn(Mono.just(java.util.Map.of(
                    "task", java.util.Map.of("task_id", "task-001"),
                    "result", java.util.Map.of("status", "SUCCESS")
            )));

        webTestClient.get()
            .uri("/api/ai/diagnosis/task-001")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.task.task_id").isEqualTo("task-001")
            .jsonPath("$.result.status").isEqualTo("SUCCESS");
    }

    @Test
    void testGetCachedDiagnosis() {
        SSEForwardService sseForwardService = mock(SSEForwardService.class);
        DiagnosisCacheService cacheService = mock(DiagnosisCacheService.class);
        WebTestClient webTestClient = WebTestClient.bindToController(
                new DiagnosisController(sseForwardService, cacheService)
        ).build();

        when(cacheService.getDiagnosisResult(TEST_ALERT_ID)).thenReturn(CACHED_RESULT);
        when(sseForwardService.replayCachedSSE(CACHED_RESULT)).thenReturn(Flux.just(CACHED_RESULT));

        webTestClient.get()
                .uri("/api/ai/diagnosis/stream?alertId=" + TEST_ALERT_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");

        verify(sseForwardService).replayCachedSSE(CACHED_RESULT);
    }

    @Test
    void testGetNonExistentDiagnosis() {
        SSEForwardService sseForwardService = mock(SSEForwardService.class);
        DiagnosisCacheService cacheService = mock(DiagnosisCacheService.class);
        WebTestClient webTestClient = WebTestClient.bindToController(
                new DiagnosisController(sseForwardService, cacheService)
        ).build();

        when(cacheService.getDiagnosisResult("NON-EXISTENT")).thenReturn(null);

        webTestClient.get()
                .uri("/api/ai/diagnosis/stream?alertId=NON-EXISTENT")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }
}
