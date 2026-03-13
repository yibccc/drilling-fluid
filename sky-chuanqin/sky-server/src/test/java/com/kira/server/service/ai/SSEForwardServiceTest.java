package com.kira.server.service.ai;

import com.kira.server.config.DiagnosisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SSEForwardServiceTest {

    @Test
    void testServiceBeanExists() {
        SSEForwardService sseForwardService = createService((request) -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body("data: {\"type\":\"start\"}\n\n")
                        .build()
        ));

        assertThat(sseForwardService).isNotNull();
    }

    @Test
    void testForwardSSEMethodExists() {
        SSEForwardService sseForwardService = createService((request) -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body("data: {\"type\":\"start\"}\n\n")
                        .build()
        ));

        Flux<String> result = sseForwardService.forwardSSE("/test", null, Duration.ofSeconds(5));
        assertThat(result).isNotNull();
    }

    @Test
    void testForwardSSEReturnsReadableErrorEventWhenUpstreamValidationFails() {
        String upstreamBody = "{\"detail\":[{\"loc\":[\"body\",\"samples\"],\"msg\":\"Field required\",\"type\":\"missing\"}]}";
        SSEForwardService sseForwardService = createService((request) -> Mono.just(
                ClientResponse.create(HttpStatus.UNPROCESSABLE_ENTITY)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(upstreamBody)
                        .build()
        ));

        Flux<String> result = sseForwardService.forwardSSE("/api/v1/diagnosis/analyze", null, Duration.ofSeconds(5));

        List<String> events = result.collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains("\"type\":\"error\"");
        assertThat(events.get(0)).contains("\"status\":422");
        assertThat(events.get(0)).contains("Field required");
    }

    @Test
    void testForwardJsonGetReturnsUpstreamJsonBody() {
        SSEForwardService sseForwardService = createService((request) -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"task\":{\"task_id\":\"TASK-001\"},\"result\":{\"status\":\"SUCCESS\"}}")
                        .build()
        ));

        Map<String, Object> response = sseForwardService.forwardJsonGet("/api/v1/diagnosis/TASK-001", Duration.ofSeconds(5))
                .block();

        assertThat(response).isNotNull();
        assertThat(response).containsKey("task");
        assertThat(response).containsKey("result");
    }

    @Test
    void testReplayCachedSSEPreservesWholeEvents() {
        SSEForwardService sseForwardService = createService((request) -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body("data: {\"type\":\"start\"}\n\n")
                        .build()
        ));

        List<String> replayed = sseForwardService.replayCachedSSE(
                "data: {\"type\":\"start\"}\n\n" +
                "data: {\"type\":\"result\"}\n\n"
        ).collectList().block();

        assertThat(replayed).containsExactly(
                "data: {\"type\":\"start\"}\n\n",
                "data: {\"type\":\"result\"}\n\n"
        );
    }

    private SSEForwardService createService(org.springframework.web.reactive.function.client.ExchangeFunction exchangeFunction) {
        WebClient agentWebClient = WebClient.builder()
                .baseUrl("http://test-agent:8000")
                .exchangeFunction(exchangeFunction)
                .build();

        DiagnosisProperties properties = new DiagnosisProperties();
        properties.setInternalApiKey("test-internal-key");

        return new SSEForwardService(agentWebClient, properties, mock(DiagnosisCacheService.class));
    }
}
