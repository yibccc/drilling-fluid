package com.kira.server.controller.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.kira.server.service.ai.AgentCallbackService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 回调控制器
 * 接收 FastAPI Agent 的异步回调通知
 */
@Api(tags = "Agent回调接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/callback")
@RequiredArgsConstructor
public class AgentCallbackController {

    private final AgentCallbackService agentCallbackService;

    /**
     * 接收诊断结果回调
     *
     * @param payload 回调数据
     * @return 确认响应
     */
    @ApiOperation("诊断结果回调")
    @PostMapping("/diagnosis")
    public ResponseEntity<String> handleDiagnosisCallback(@RequestBody JsonNode payload) {
        log.info("收到诊断回调: {}", payload);

        try {
            agentCallbackService.handleDiagnosisCallback(payload);
            return ResponseEntity.ok("Callback received");
        } catch (Exception e) {
            log.error("处理诊断回调错误: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Callback processing failed");
        }
    }
}
