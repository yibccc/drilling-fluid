package com.kira.server.controller.knowledge;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * KnowledgeController 轻量测试
 */
class KnowledgeControllerIntegrationTest {

    @Test
    void testSyncUploadEndpointMethodNotPresent() {
        boolean hasSyncUploadMethod = Arrays.stream(KnowledgeController.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("uploadFileSync"::equals);

        assertFalse(hasSyncUploadMethod, "KnowledgeController 不应再暴露同步上传入口");
    }
}
