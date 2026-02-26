package com.kira.server.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SSE 流式转发服务
 * 将 FastAPI 的 SSE 流直接透传给前端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SSEForwardService {

    private final WebClient agentWebClient;

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

        WebClient.RequestHeadersSpec<?> requestSpec = agentWebClient.post()
                .uri(uri);

        if (request != null) {
            requestSpec.bodyValue(request);
        }

        return requestSpec
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(this::dataBufferToString)
                .timeout(timeout)
                .doOnNext(line -> log.debug("转发的 SSE 行: {}", line))
                .doOnError(error -> log.error("SSE 转发错误: {}", error.getMessage(), error))
                .onErrorResume(WebClientRequestException.class, e -> {
                    // Agent 服务不可用时返回友好错误
                    log.error("Agent 服务不可用: {}", e.getMessage());
                    String errorEvent = String.format(
                            "data: {\"type\":\"error\",\"content\":\"Agent 服务暂时不可用，请稍后重试\"}\n\n"
                    );
                    return Flux.just(errorEvent);
                })
                .doOnComplete(() -> log.info("SSE 流完成，URI: {}", uri));
    }

    /**
     * 将 DataBuffer 转换为 String
     */
    private String dataBufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
