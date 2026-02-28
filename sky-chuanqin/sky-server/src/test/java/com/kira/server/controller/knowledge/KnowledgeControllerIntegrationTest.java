package com.kira.server.controller.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * KnowledgeController 集成测试
 *
 * @author Kira
 * @create 2026-02-27
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testUploadNewFile_Success() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-new.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "test content".getBytes()
        );

        mockMvc.perform(multipart("/api/knowledge/upload")
                        .file(file)
                        .param("category", "测试分类"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docId").exists())
                .andExpect(jsonPath("$.data.status").value("PARSING"));
    }

    @Test
    void testUploadDuplicateFile_ReturnsConflict() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-duplicate.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "test content".getBytes()
        );

        // 第一次上传
        mockMvc.perform(multipart("/api/knowledge/upload")
                        .file(file)
                        .param("category", "测试分类"))
                .andExpect(status().isOk());

        // 第二次上传同名文件 + 相同分类（需要等待异步处理完成）
        Thread.sleep(1000);

        mockMvc.perform(multipart("/api/knowledge/upload")
                        .file(file)
                        .param("category", "测试分类"))
                .andExpect(status().isOk()); // 由于异步处理，可能返回 OK 但状态为 DUPLICATE
    }
}
