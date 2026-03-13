package com.kira.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket配置类，用于注册WebSocket的Bean
 */
@Configuration
public class WebSocketConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "websocket", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ServerEndpointExporter serverEndpointExporter() {
        return new SafeServerEndpointExporter();
    }

    /**
     * 测试环境或非标准 WebSocket 容器场景下，如果没有 ServerContainer，则跳过端点注册，避免上下文启动失败。
     */
    static class SafeServerEndpointExporter extends ServerEndpointExporter {

        @Override
        public void afterPropertiesSet() {
            if (getServerContainer() == null) {
                return;
            }
            super.afterPropertiesSet();
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (getServerContainer() == null) {
                return;
            }
            super.afterSingletonsInstantiated();
        }
    }

}
