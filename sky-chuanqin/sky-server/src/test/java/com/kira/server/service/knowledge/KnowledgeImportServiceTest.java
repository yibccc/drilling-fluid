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
    private TikaDocumentParser parser;
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 创建 Mock 对象
        parser = mock(TikaDocumentParser.class);
        redisTemplate = mock(StringRedisTemplate.class);

        // 配置 ObjectMapper 支持 Java 8 日期类型
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 创建服务实例
        importService = new KnowledgeImportService(parser, redisTemplate, objectMapper);

        // 设置 Mock 行为
        when(redisTemplate.opsForValue()).thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
        when(redisTemplate.opsForStream()).thenReturn(mock(StreamOperations.class));
    }

    @Test
    void testProcessFileAsync() throws Exception {
        // 准备测试数据
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "测试文档内容".getBytes()
        );

        // Mock Tika 解析结果
        com.kira.server.controller.knowledge.dto.DocumentContent mockContent =
                com.kira.server.controller.knowledge.dto.DocumentContent.builder()
                        .title("test")
                        .content("测试文档内容")
                        .build();
        when(parser.parse(any())).thenReturn(mockContent);

        // 执行测试
        String docId = importService.processFileAsync(file, "test", null);

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
    void testProcessFileSendsRedisMessage() throws Exception {
        // 准备测试数据
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "文档内容".getBytes()
        );

        // Mock Tika 解析结果
        com.kira.server.controller.knowledge.dto.DocumentContent mockContent =
                com.kira.server.controller.knowledge.dto.DocumentContent.builder()
                        .title("Test Document")
                        .content("文档内容")
                        .build();
        when(parser.parse(any())).thenReturn(mockContent);

        // Mock StreamOperations
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);

        // 执行测试
        String docId = importService.processFileAsync(file, "category", "subcategory");

        // 验证 Redis Stream 消息被发送
        verify(streamOps).add(eq("stream:knowledge_import"), any());
    }

    @Test
    void testDocIdUniqueness() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "content".getBytes()
        );

        com.kira.server.controller.knowledge.dto.DocumentContent mockContent =
                com.kira.server.controller.knowledge.dto.DocumentContent.builder()
                        .title("test")
                        .content("content")
                        .build();
        when(parser.parse(any())).thenReturn(mockContent);

        String docId1 = importService.processFileAsync(file, "test", null);
        String docId2 = importService.processFileAsync(file, "test", null);

        assertNotEquals(docId1, docId2);
    }
}
