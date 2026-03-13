package com.kira.server.staticresource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeImportTestPageResourceTest {

    @Test
    void shouldContainKnowledgeImportTestPageWithUploadAndStatusEndpoints() throws Exception {
        ClassPathResource resource = new ClassPathResource("static/knowledge-import-test.html");

        assertThat(resource.exists()).isTrue();
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("/api/knowledge/upload");
        assertThat(content).contains("/api/knowledge/documents/");
        assertThat(content).contains("/api/knowledge/retrieval-test");
    }
}
