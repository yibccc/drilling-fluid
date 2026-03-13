package com.kira.server.service.ai;

import com.kira.server.config.DiagnosisProperties;
import org.springframework.core.ParameterizedTypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式转发服务
 * 将 FastAPI 的 SSE 流直接透传给前端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SSEForwardService {

    private final WebClient agentWebClient;
    private final DiagnosisProperties diagnosisProperties;
    private final DiagnosisCacheService diagnosisCacheService;

    /**
     * SSE 流式转发
     * 将 FastAPI 的 SSE 流直接转发给前端
     *
     * @param uri     Agent 服务 URI (例如 "/api/v1/chat/stream")
     * @param request 请求体 (可以为 null，用于 GET 请求)
     * @param timeout 超时时间
     * @return SSE 事件流 (data: {json}\n\n 格式)
     */
    public Flux<String> forwardSSE(String uri, Object request, Duration timeout) {
        log.info("转发 SSE 请求到: {}", uri);

        WebClient.RequestBodySpec requestBodySpec = agentWebClient.post()
                .uri(uri)
                .header("X-Internal-Api-Key", diagnosisProperties.getInternalApiKey());

        if (request != null) {
            requestBodySpec.body(BodyInserters.fromValue(request));
        }

        return requestBodySpec
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(this::dataBufferToString)
                .timeout(timeout)
                .doOnNext(line -> log.debug("转发的 SSE 行: {}", line))
                .doOnError(error -> log.error("SSE 转发错误: {}", error.getMessage(), error))
                .onErrorResume(WebClientResponseException.class, e -> {
                    log.error("Agent 服务返回错误: status={}, body={}",
                            e.getRawStatusCode(), e.getResponseBodyAsString());
                    return Flux.just(buildUpstreamErrorEvent(e));
                })
                .onErrorResume(WebClientRequestException.class, e -> {
                    // Agent 服务不可用时返回友好错误
                    log.error("Agent 服务不可用: {}", e.getMessage());
                    return Flux.just(buildSimpleErrorEvent("Agent 服务暂时不可用，请稍后重试"));
                })
                .doOnComplete(() -> log.info("SSE 流完成，URI: {}", uri));
    }

    /**
     * GET 方式转发 JSON 响应
     *
     * @param uri Agent 服务 URI
     * @param timeout 超时时间
     * @return JSON 响应体
     */
    public Mono<Map<String, Object>> forwardJsonGet(String uri, Duration timeout) {
        log.info("转发 JSON GET 请求到: {}", uri);

        return agentWebClient.get()
                .uri(uri)
                .header("X-Internal-Api-Key", diagnosisProperties.getInternalApiKey())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(timeout)
                .doOnError(error -> log.error("JSON GET 转发错误: {}", error.getMessage(), error));
    }

    /**
     * SSE 转发并缓存结果
     *
     * @param uri     Agent 服务 URI
     * @param request 请求体
     * @param timeout 超时时间
     * @param alertId 用于缓存的 key
     * @return SSE 事件流
     */
    public Flux<String> forwardSSEWithCache(String uri, Object request,
                                            Duration timeout, String alertId) {
        // 1. 转发请求
        Flux<String> stream = forwardSSE(uri, request, timeout);

        // 2. 收集完整结果并缓存
        StringBuilder resultBuilder = new StringBuilder();

        return stream
                .doOnNext(resultBuilder::append)
                .doOnComplete(() -> {
                    // 缓存完整结果
                    diagnosisCacheService.saveDiagnosisResult(alertId, resultBuilder.toString());
                })
                .doOnError(e -> {
                    log.error("诊断失败，alertId={}, error={}", alertId, e.getMessage());
                });
    }

    /**
     * 回放缓存中的完整 SSE 事件，保持原始事件边界。
     *
     * @param cachedSse 缓存的完整 SSE 文本
     * @return 按完整事件切分后的 SSE 流
     */
    public Flux<String> replayCachedSSE(String cachedSse) {
        if (cachedSse == null || cachedSse.isBlank()) {
            return Flux.empty();
        }

        List<String> events = new ArrayList<>();
        int start = 0;
        int delimiterIndex;
        while ((delimiterIndex = cachedSse.indexOf("\n\n", start)) >= 0) {
            events.add(cachedSse.substring(start, delimiterIndex + 2));
            start = delimiterIndex + 2;
        }

        if (start < cachedSse.length()) {
            String tail = cachedSse.substring(start);
            events.add(tail.endsWith("\n\n") ? tail : tail + "\n\n");
        }

        return Flux.fromIterable(events);
    }

    /**
     * 将 DataBuffer 转换为 String
     */
    private String dataBufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String buildUpstreamErrorEvent(WebClientResponseException e) {
        String responseBody = e.getResponseBodyAsString();
        String content = responseBody == null || responseBody.isBlank()
                ? "上游诊断服务返回错误"
                : responseBody;
        return String.format(
                "data: {\"type\":\"error\",\"status\":%d,\"content\":\"%s\"}\n\n",
                e.getRawStatusCode(),
                escapeJson(content)
        );
    }

    private String buildSimpleErrorEvent(String content) {
        return String.format(
                "data: {\"type\":\"error\",\"content\":\"%s\"}\n\n",
                escapeJson(content)
        );
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
