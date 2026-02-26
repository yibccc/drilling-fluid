package com.kira.server.controller.ai.dto;

/**
 * SSE 事件类型枚举
 */
public enum SSEEventType {
    // 通用事件
    START,
    END,
    ERROR,

    // 对话事件
    TOKEN,
    TOOL_CALL,
    TOOL_RESULT,

    // 诊断事件
    THINKING,
    TREND_ANALYSIS,
    RETRIEVAL,
    DIAGNOSIS,
    PRESCRIPTION,
    RESULT,
    DONE
}
