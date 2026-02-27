package com.kira.server.service.knowledge;

import com.kira.server.controller.knowledge.dto.DocumentContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tika 文档解析器测试
 */
@TestPropertySource(locations = "classpath:application-test.yml")
class TikaDocumentParserTest {

    private TikaDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new TikaDocumentParser();
    }

    @Test
    void testParsePlainText() throws Exception {
        // 准备测试文件
        MockMultipartFile file = new MockMultipartFile(
                "test.txt",
                "test.txt",
                "text/plain",
                "这是测试文档内容\n\n第二段内容".getBytes()
        );

        // 解析文档
        DocumentContent content = parser.parse(file);

        // 验证
        assertNotNull(content);
        // Tika 可能会从元数据中提取标题（去掉扩展名）
        assertTrue(content.getTitle().equals("test.txt") || content.getTitle().equals("test"));
        assertTrue(content.getContent().contains("测试文档内容"));
        assertTrue(content.getContent().contains("第二段内容"));
    }

    @Test
    void testParsePlainTextWithMetadata() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "document.txt",
                "document.txt",
                "text/plain",
                "文档内容".getBytes()
        );

        DocumentContent content = parser.parse(file);

        assertNotNull(content);
        assertNotNull(content.getTitle());
        assertNotNull(content.getContent());
        assertTrue(content.getContentLength() > 0);
    }

    @Test
    void testParseEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "empty.txt",
                "empty.txt",
                "text/plain",
                new byte[0]
        );

        DocumentContent content = parser.parse(file);

        assertNotNull(content);
        assertNotNull(content.getContent());
        assertEquals(0, content.getContentLength());
    }

    @Test
    void testParseLargeText() throws Exception {
        // 构建大文本（超过 100KB）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("这是第 ").append(i).append(" 行内容。\n");
        }

        MockMultipartFile file = new MockMultipartFile(
                "large.txt",
                "large.txt",
                "text/plain",
                sb.toString().getBytes()
        );

        DocumentContent content = parser.parse(file);

        assertNotNull(content);
        assertTrue(content.getContentLength() > 100000);
        assertTrue(content.getEstimatedChunkCount() > 100);
    }

    @Test
    void testParseWithUnsupportedType() {
        // 测试不支持的文件类型
        MockMultipartFile file = new MockMultipartFile(
                "test.xyz",
                "test.xyz",
                "application/xyz",
                "some content".getBytes()
        );

        // 应该仍然能解析（Tika 会尝试检测）
        assertDoesNotThrow(() -> parser.parse(file));
    }
}
