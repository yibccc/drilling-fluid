package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.DiagnosisCacheService;
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
    private final DiagnosisCacheService diagnosisCacheService;

    /**
     * 诊断分析 - SSE 转发并缓存
     *
     * @param request 诊断请求
     * @return SSE 事件流
     */
    @ApiOperation("诊断分析")
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyze(@RequestBody DiagnosisRequest request) {
        log.info("诊断请求: alertId={}, wellId={}, alertType={}",
                request.getAlertId(), request.getWellId(), request.getAlertType());

        return sseForwardService.forwardSSEWithCache(
                "/api/v1/diagnosis/analyze",
                request,
                Duration.ofMinutes(5),
                request.getAlertId()
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

    /**
     * 查询已有诊断结果（SSE 流式返回缓存的结果）
     * 前端收到 WebSocket 预警后调用此接口
     *
     * @param alertId 预警ID
     * @return SSE 事件流
     */
    @ApiOperation("查询缓存诊断结果")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getCachedDiagnosis(@RequestParam String alertId) {
        log.info("查询缓存诊断结果: alertId={}", alertId);

        // 从 Redis 获取诊断结果
        String cachedResult = diagnosisCacheService.getDiagnosisResult(alertId);

        if (cachedResult == null) {
            log.warn("诊断结果不存在或已过期: alertId={}", alertId);
            return Flux.just(
                    "data: {\"type\":\"error\",\"content\":\"诊断结果不存在或已过期\"}\n\n"
            );
        }

        log.info("返回缓存的诊断结果: alertId={}, length={}", alertId, cachedResult.length());
        // 将缓存的 SSE 内容重新作为流返回
        return Flux.fromArray(cachedResult.split("\n"));
    }
}
