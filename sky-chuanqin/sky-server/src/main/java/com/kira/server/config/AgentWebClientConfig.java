package com.kira.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * FastAPI Agent 服务 WebClient 配置
 * 用于 SSE 流式转发
 */
@Configuration
public class AgentWebClientConfig {

    @Value("${agent.base-url}")
    private String agentBaseUrl;

    @Value("${agent.api-key}")
    private String apiKey;

    /**
     * 配置 Agent 服务 WebClient
     * - 增大内存缓冲区限制（10MB）用于 SSE 流
     * - 设置默认 API Key 认证头
     * - 设置默认 Content-Type
     */
    @Bean
    public WebClient agentWebClient() {
        // 增大内存缓冲区限制（10MB）用于 SSE 流式数据
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(agentBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-API-Key", apiKey)
                .exchangeStrategies(strategies)
                .build();
    }
}
