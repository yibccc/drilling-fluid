package com.kira.server.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.knowledge.dto.DocumentContent;
import com.kira.server.controller.knowledge.dto.DocumentMetadata;
import com.kira.server.controller.knowledge.dto.FileRecordDTO;
import com.kira.server.enums.ImportStatus;
import com.kira.server.exception.DuplicateFileException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;

    private static final String STREAM_NAME = "stream:knowledge_import";
    private static final String STATUS_PREFIX = "knowledge:status:";
    private static final int MAX_TEXT_LENGTH = 10 * 1024 * 1024; // 10MB

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
     * @param file       上传的文件
     * @param category    文档分类
     * @param subcategory 文档子分类
     */
    @Async("taskExecutor")
    public void processFileAsync(String docId, MultipartFile file, String category, String subcategory) {
        try {
            String filename = file.getOriginalFilename();
            log.info("开始处理文件: docId={}, filename={}", docId, filename);

            // 1. 上传文件到 OSS（包含去重检查）
            FileRecordDTO fileRecord = fileStorageService.uploadAndCheckDuplicate(
                    file, category, subcategory
            );

            // 2. 更新状态：PARSING
            updateStatus(docId, ImportStatus.PARSING);

            // 3. 读取文件内容用于解析
            byte[] fileBytes = file.getBytes();
            DocumentContent content = parseContent(new ByteArrayInputStream(fileBytes), filename);
            log.info("Tika 解析完成: docId={}, contentLength={}", docId, content.getContentLength());

            // 4. 构建元数据
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .originalFilename(filename)
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .createdAt(LocalDateTime.now())
                    .category(category)
                    .subcategory(subcategory)
                    .ossPath(fileRecord.getOssPath())
                    .fileRecordId(fileRecord.getId())
                    .build();

            // 4. 发送 Redis Stream 消息
            sendImportMessage(docId, content, metadata);

            // 5. 更新状态：QUEUED
            updateStatus(docId, ImportStatus.QUEUED);

            log.info("文件处理完成，已入队: docId={}", docId);

        } catch (DuplicateFileException e) {
            log.warn("文件重复: docId={}, error={}", docId, e.getMessage());
            updateStatus(docId, ImportStatus.DUPLICATE);
        } catch (Exception e) {
            log.error("文件处理失败: docId={}, error={}", docId, e.getMessage(), e);
            updateStatus(docId, ImportStatus.FAILED);
        }
    }

    /**
     * 解析文档内容
     */
    private DocumentContent parseContent(InputStream inputStream, String filename)
            throws IOException, TikaException, org.xml.sax.SAXException {
        // 创建解析器
        AutoDetectParser parser = new AutoDetectParser();

        // 创建内容处理器（限制最大文本长度，防止 OOM）
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        // 创建元数据容器
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

        // 创建解析上下文
        ParseContext context = new ParseContext();

        // 关键配置1：将 Parser 注册到 Context
        context.set(Parser.class, parser);

        // 关键配置2：PDF 专用配置
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false); // 不提取内嵌图片
        context.set(PDFParserConfig.class, pdfConfig);

        // 解析文档
        parser.parse(inputStream, handler, metadata, context);

        // 提取内容
        String content = handler.toString();

        // 构建结果
        return DocumentContent.builder()
                .title(extractTitle(metadata, filename))
                .content(content)
                .author(metadata.get(TikaCoreProperties.CREATOR))
                .creationDate(parseDate(metadata.get(TikaCoreProperties.CREATED)))
                .modifiedDate(parseDate(metadata.get(TikaCoreProperties.MODIFIED)))
                .build();
    }

    /**
     * 提取文档标题
     */
    private String extractTitle(Metadata metadata, String filename) {
        String title = metadata.get(TikaCoreProperties.TITLE);
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        if (filename != null) {
            int lastDotIndex = filename.lastIndexOf('.');
            return lastDotIndex > 0 ? filename.substring(0, lastDotIndex) : filename;
        }
        return "Untitled";
    }

    /**
     * 解析日期字符串
     */
    private java.time.LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(
                    dateStr.replace("T", " ").replace("Z", "").substring(0, 19));
        } catch (Exception e) {
            return null;
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
    public void updateStatus(String docId, ImportStatus status) {
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
}
