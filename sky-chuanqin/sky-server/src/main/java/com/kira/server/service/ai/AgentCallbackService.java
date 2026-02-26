package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 回调服务
 * 接收 FastAPI Agent 的异步回调通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCallbackService {

    private final ObjectMapper objectMapper;

    /**
     * 接收 FastAPI 诊断结果回调
     *
     * @param payload 回调数据
     */
    public void handleDiagnosisCallback(JsonNode payload) {
        String taskId = payload.get("task_id").asText();
        String status = payload.get("status").asText();

        log.info("收到诊断回调: taskId={}, status={}", taskId, status);

        // TODO: 存储到数据库
        // saveDiagnosisResult(taskId, payload);

        // TODO: 通知前端（WebSocket）
        // notifyFrontend(taskId, status);
    }
}
