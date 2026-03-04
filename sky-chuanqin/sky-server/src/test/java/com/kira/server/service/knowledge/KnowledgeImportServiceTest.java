package com.kira.server.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 知识库导入服务单元测试
 * 使用 Mock 避免依赖完整的 Spring 上下文
 */
class KnowledgeImportServiceTest {

    private KnowledgeImportService importService;
    private FileStorageService fileStorageService;
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 创建 Mock 对象
        fileStorageService = mock(FileStorageService.class);
        redisTemplate = mock(StringRedisTemplate.class);

        // 配置 ObjectMapper 支持 Java 8 日期类型
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 创建服务实例（使用 @RequiredArgsConstructor）
        importService = new KnowledgeImportService(redisTemplate, objectMapper, fileStorageService, null);

        // 设置 Mock 行为
        when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        when(redisTemplate.opsForStream()).thenReturn(mock(StreamOperations.class));

        // Mock FileStorageService 返回值
        com.kira.server.controller.knowledge.dto.FileRecordDTO mockFileRecord =
                com.kira.server.controller.knowledge.dto.FileRecordDTO.builder()
                        .id(1L)
                        .ossPath("knowledge/test/abc123/test.pdf")
                        .build();
        try {
            when(fileStorageService.uploadAndCheckDuplicate(any(), any(), any()))
                    .thenReturn(mockFileRecord);
        } catch (Exception e) {
            // ignore
        }
    }

    @Test
    void testGenerateDocId() {
        // 执行测试
        String docId = importService.generateDocId();

        // 验证
        assertNotNull(docId);
        assertTrue(docId.startsWith("DOC-"));
    }

    @Test
    void testGetDocumentStatus() {
        String testDocId = "DOC-TEST-12345";

        // Mock Redis 返回值
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("QUEUED");

        // 执行测试
        Map<String, Object> status = importService.getDocumentStatus(testDocId);

        // 验证
        assertNotNull(status);
        assertEquals(testDocId, status.get("docId"));
        assertEquals("QUEUED", status.get("importStatus"));
    }

    @Test
    void testGetDocumentStatusUnknown() {
        String testDocId = "DOC-UNKNOWN";

        // Mock Redis 返回 null
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        // 执行测试
        Map<String, Object> status = importService.getDocumentStatus(testDocId);

        // 验证
        assertNotNull(status);
        assertEquals("UNKNOWN", status.get("importStatus"));
    }

    @Test
    void testDocIdUniqueness() {
        String docId1 = importService.generateDocId();
        String docId2 = importService.generateDocId();

        assertNotEquals(docId1, docId2);
    }
}
