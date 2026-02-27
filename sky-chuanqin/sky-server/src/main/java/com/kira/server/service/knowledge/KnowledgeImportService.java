package com.kira.server.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.knowledge.dto.DocumentContent;
import com.kira.server.controller.knowledge.dto.DocumentMetadata;
import com.kira.server.enums.ImportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库导入服务
 * 处理文件上传、解析、入队等操作
 *
 * @author Kira
 * @create 2026-02-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeImportService {

    private final TikaDocumentParser tikaParser;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STREAM_NAME = "stream:knowledge_import";
    private static final String STATUS_PREFIX = "knowledge:status:";

    /**
     * 同步生成文档 ID（立即返回）
     *
     * @return 文档 ID
     */
    public String generateDocId() {
        return "DOC-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 异步处理文件
     *
     * @param docId      文档 ID（预先生成）
     * @param fileBytes   文件字节数组
     * @param filename   文件名
     * @param contentType 内容类型
     * @param fileSize   文件大小
     * @param category    文档分类
     * @param subcategory 文档子分类
     */
    @Async("taskExecutor")
    public void processFileAsync(String docId, byte[] fileBytes, String filename,
                                  String contentType, long fileSize, String category, String subcategory) {
        try {
            log.info("开始处理文件: docId={}, filename={}", docId, filename);

            // 1. 更新状态：PARSING
            updateStatus(docId, ImportStatus.PARSING);

            // 2. 将字节数组转换为 MockMultipartFile 供 Tika 使用
            org.springframework.mock.web.MockMultipartFile mockFile =
                    new org.springframework.mock.web.MockMultipartFile(
                            "file",
                            filename,
                            contentType,
                            fileBytes
                    );

            // 3. Tika 解析
            DocumentContent content = tikaParser.parse(mockFile);
            log.info("Tika 解析完成: docId={}, contentLength={}", docId, content.getContentLength());

            // 4. 构建元数据
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .originalFilename(filename)
                    .contentType(contentType)
                    .fileSize(fileSize)
                    .createdAt(LocalDateTime.now())
                    .category(category)
                    .subcategory(subcategory)
                    .build();

            // 5. 发送 Redis Stream 消息
            sendImportMessage(docId, content, metadata);

            // 6. 更新状态：QUEUED
            updateStatus(docId, ImportStatus.QUEUED);

            log.info("文件处理完成，已入队: docId={}", docId);

        } catch (Exception e) {
            log.error("文件处理失败: docId={}, error={}", docId, e.getMessage(), e);
            updateStatus(docId, ImportStatus.FAILED);
        }
    }

    /**
     * 兼容旧接口：处理 MultipartFile（转换为字节数组后异步处理）
     */
    public String processFileAsync(MultipartFile file, String category, String subcategory) {
        String docId = generateDocId();

        try {
            // 立即更新状态
            updateStatus(docId, ImportStatus.PARSING);

            // 读取文件内容到字节数组
            byte[] fileBytes = file.getBytes();

            // 异步处理
            processFileAsync(docId, fileBytes, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), category, subcategory);

            return docId;

        } catch (Exception e) {
            log.error("启动异步处理失败: docId={}, error={}", docId, e.getMessage(), e);
            updateStatus(docId, ImportStatus.FAILED);
            return docId;
        }
    }

    /**
     * 获取文档状态
     *
     * @param docId 文档 ID
     * @return 文档状态信息
     */
    public Map<String, Object> getDocumentStatus(String docId) {
        String statusKey = STATUS_PREFIX + docId;
        String status = redisTemplate.opsForValue().get(statusKey);

        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("importStatus", status != null ? status : "UNKNOWN");
        return result;
    }

    /**
     * 更新导入状态
     *
     * @param docId  文档 ID
     * @param status 导入状态
     */
    private void updateStatus(String docId, ImportStatus status) {
        String statusKey = STATUS_PREFIX + docId;
        redisTemplate.opsForValue().set(statusKey, status.name());
        log.debug("更新文档状态: docId={}, status={}", docId, status);
    }

    /**
     * 发送导入消息到 Redis Stream
     *
     * @param docId    文档 ID
     * @param content  文档内容
     * @param metadata 文档元数据
     */
    private void sendImportMessage(String docId, DocumentContent content, DocumentMetadata metadata) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("doc_id", docId);
            message.put("title", content.getTitle() != null ? content.getTitle() : "Untitled");
            message.put("content", content.getContent());
            message.put("category", metadata.getCategory() != null ? metadata.getCategory() : "default");
            message.put("subcategory", metadata.getSubcategory() != null ? metadata.getSubcategory() : "");
            message.put("original_filename", metadata.getOriginalFilename());
            message.put("content_type", metadata.getContentType());
            message.put("file_size", String.valueOf(metadata.getFileSize()));
            message.put("timestamp", String.valueOf(System.currentTimeMillis()));

            // 序列化元数据为 JSON
            String metadataJson = objectMapper.writeValueAsString(metadata);
            message.put("metadata", metadataJson);

            redisTemplate.opsForStream().add(STREAM_NAME, message);
            log.info("已发送知识库导入消息: docId={}", docId);
        } catch (Exception e) {
            log.error("发送 Redis 消息失败: docId={}", docId, e);
            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文档 ID
     *
     * @return 文档 ID
     */
    private String generateDocId() {
        return "DOC-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
