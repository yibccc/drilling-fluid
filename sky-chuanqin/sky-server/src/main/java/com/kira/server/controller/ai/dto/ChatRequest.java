package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * AI 对话请求 DTO
 * 用于转发到 FastAPI Agent 服务
 */
@Data
public class ChatRequest {

    /**
     * 用户消息内容
     */
    private String message;

    /**
     * 会话ID，用于保持上下文
     */
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 是否流式返回
     */
    private Boolean stream = true;
}
