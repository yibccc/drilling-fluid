package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
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
 * AI 诊断分析控制器
 * 处理钻井液异常智能诊断分析请求
 */
@Api(tags = "AI诊断接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {

    private final SSEForwardService sseForwardService;

    /**
     * 诊断分析 - SSE 转发
     *
     * @param request 诊断请求
     * @return SSE 事件流
     */
    @ApiOperation("诊断分析")
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyze(@RequestBody DiagnosisRequest request) {
        log.info("诊断请求: wellId={}, alertType={}",
                request.getWellId(), request.getAlertType());

        return sseForwardService.forwardSSE(
                "/api/v1/diagnosis/analyze",
                request,
                Duration.ofMinutes(5)
        );
    }

    /**
     * 查询诊断结果
     *
     * @param taskId 任务ID
     * @return SSE 事件流
     */
    @ApiOperation("查询诊断结果")
    @GetMapping(value = "/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getResult(@PathVariable String taskId) {
        log.info("获取诊断结果: taskId={}", taskId);

        return sseForwardService.forwardSSE(
                "/api/v1/diagnosis/" + taskId,
                null,
                Duration.ofSeconds(30)
        );
    }
}
