package com.kira.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(WebSocketConfiguration.class);

    @Test
    void shouldNotRegisterServerEndpointExporterWhenWebSocketDisabled() {
        contextRunner
                .withPropertyValues("websocket.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ServerEndpointExporter.class));
    }

    @Test
    void shouldAllowContextStartupWithoutServerContainerWhenWebSocketEnabled() {
        contextRunner
                .withPropertyValues("websocket.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ServerEndpointExporter.class));
    }
}
