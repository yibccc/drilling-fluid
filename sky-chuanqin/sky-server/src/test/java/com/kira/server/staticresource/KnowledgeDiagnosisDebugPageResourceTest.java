package com.kira.server.staticresource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDiagnosisDebugPageResourceTest {

    @Test
    void shouldContainKnowledgeDiagnosisDebugPageWithKnowledgeAndDiagnosisEndpoints() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/knowledge-diagnosis-debug.html");

        assertThat(resource.exists()).isTrue();
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("/api/knowledge/upload");
        assertThat(content).contains("/api/knowledge/documents/");
        assertThat(content).contains("/api/knowledge/retrieval-test");
        assertThat(content).contains("/api/ai/diagnosis/analyze");
        assertThat(content).contains("alert_id: \"test-alert-ui-001\"");
        assertThat(content).contains("alert_threshold: {");
        assertThat(content).contains("samples: [");
        assertThat(content).contains("current_depth: 3200.0");
        assertThat(content).contains("validateDiagnosisPayload(payload)");
    }
}
