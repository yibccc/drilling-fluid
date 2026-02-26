package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * SSE 事件 DTO
 * 包含对话和诊断两种类型的 SSE 事件
 */
@Data
public class SSEEvent {

    /**
     * 对话 SSE 事件
     */
    @Data
    public static class ChatSSEEvent {
        private SSEEventType type;
        @JsonProperty("session_id")
        private String sessionId;
        private String content;
        @JsonProperty("tool_data")
        private ToolData toolData;
        @JsonProperty("error_code")
        private String errorCode;
    }

    /**
     * 诊断 SSE 事件
     */
    @Data
    public static class DiagnosisSSEEvent {
        private SSEEventType type;
        @JsonProperty("task_id")
        private String taskId;
        @JsonProperty("well_id")
        private String wellId;
        private String content;
        private String step;
    }

    /**
     * 工具调用数据
     */
    @Data
    public static class ToolData {
        @JsonProperty("call_id")
        private String callId;
        private String name;
        private Map<String, Object> arguments;
        private String status; // calling, processing, result
        private String result;
        @JsonProperty("duration_ms")
        private Long durationMs;
    }
}
