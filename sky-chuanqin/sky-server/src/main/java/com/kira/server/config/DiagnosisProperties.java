package com.kira.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 诊断配置属性
 */
@Data
@ConfigurationProperties(prefix = "diagnosis")
public class DiagnosisProperties {

    /**
     * 预警信息 TTL（分钟）
     */
    private int alertCacheTtl = 15;

    /**
     * 诊断结果 TTL（分钟）
     */
    private int resultCacheTtl = 15;

    /**
     * 诊断超时时间（分钟）
     */
    private int timeoutMinutes = 5;

    /**
     * 是否启用 AI 诊断
     */
    private boolean enabled = true;

    /**
     * 内部 API Key（用于 SpringBoot 调用 FastAPI 鉴权）
     */
    private String internalApiKey;
}
