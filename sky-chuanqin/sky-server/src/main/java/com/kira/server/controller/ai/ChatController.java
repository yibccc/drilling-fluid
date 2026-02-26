package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.ChatRequest;
import com.kira.server.service.ai.SSEForwardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 对话控制器
 * 处理流式对话请求，SSE 转发到 FastAPI Agent 服务
 */
@Api(tags = "AI对话接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SSEForwardService sseForwardService;

    /**
     * 流式对话 - SSE 转发
     * Vue 前端通过 Fetch API + ReadableStream 接收
     *
     * @param request 对话请求
     * @return SSE 事件流
     */
    @ApiOperation("流式对话")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("对话流式请求: sessionId={}, message={}",
                request.getSessionId(), request.getMessage());

        // 转发到 FastAPI，返回 SSE 流
        return sseForwardService.forwardSSE(
                "/api/v1/chat/stream",
                request,
                Duration.ofMinutes(2)
        );
    }
}
