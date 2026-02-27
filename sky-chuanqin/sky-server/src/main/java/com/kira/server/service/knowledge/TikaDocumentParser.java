package com.kira.server.service.knowledge;

import com.kira.server.controller.knowledge.dto.DocumentContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * Tika 文档解析器
 * 使用 Apache Tika 解析多种文档格式（PDF、Word、Excel、PPT、TXT 等）
 *
 * @author Kira
 * @create 2026-02-26
 */
@Slf4j
@Component
public class TikaDocumentParser {

    private final Tika tika;
    private final Parser autoDetectParser;

    public TikaDocumentParser() {
        this.tika = new Tika();
        this.autoDetectParser = new AutoDetectParser();
    }

    /**
     * 解析文档并提取内容和元数据
     *
     * @param file 上传的文件
     * @return 文档内容对象
     * @throws Exception 解析失败时抛出异常
     */
    public DocumentContent parse(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            log.warn("空文件，返回空内容");
            return createEmptyContent(file != null ? file.getOriginalFilename() : "empty");
        }

        String filename = file.getOriginalFilename();
        log.info("开始解析文档: filename={}, size={}", filename, file.getSize());

        try (InputStream inputStream = file.getInputStream()) {
            // 创建元数据对象
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

            // 设置内容类型（如果检测不到）
            String contentType = file.getContentType();
            if (contentType != null) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }

            // 创建内容处理器（最大字符数 10MB）
            BodyContentHandler handler = new BodyContentHandler(10 * 1024 * 1024);

            // 创建解析上下文
            ParseContext context = new ParseContext();
            context.set(Parser.class, autoDetectParser);

            // 解析文档
            autoDetectParser.parse(inputStream, handler, metadata, context);

            // 提取内容
            String content = handler.toString();

            // 构建结果
            DocumentContent documentContent = DocumentContent.builder()
                    .title(extractTitle(metadata, filename))
                    .content(content)
                    .author(metadata.get(TikaCoreProperties.CREATOR))
                    .creationDate(parseDate(metadata.get(TikaCoreProperties.CREATED)))
                    .modifiedDate(parseDate(metadata.get(TikaCoreProperties.MODIFIED)))
                    .build();

            log.info("文档解析完成: filename={}, contentLength={}, author={}",
                    filename, documentContent.getContentLength(), documentContent.getAuthor());

            return documentContent;

        } catch (TikaException e) {
            log.error("Tika 解析失败: filename={}, error={}", filename, e.getMessage(), e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("文件读取失败: filename={}, error={}", filename, e.getMessage(), e);
            throw new RuntimeException("文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取文档标题
     *
     * @param metadata  Tika 元数据
     * @param filename  原始文件名
     * @return 文档标题
     */
    private String extractTitle(Metadata metadata, String filename) {
        // 尝试从元数据中获取标题
        String title = metadata.get(TikaCoreProperties.TITLE);

        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }

        // 如果没有标题，使用文件名（去掉扩展名）
        if (filename != null) {
            int lastDotIndex = filename.lastIndexOf('.');
            if (lastDotIndex > 0) {
                return filename.substring(0, lastDotIndex);
            }
            return filename;
        }

        return "Untitled";
    }

    /**
     * 解析日期字符串
     *
     * @param dateStr 日期字符串
     * @return 解析后的日期，解析失败返回 null
     */
    private java.time.LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        try {
            // Tika 返回的日期格式通常是 ISO-8601
            return java.time.LocalDateTime.parse(dateStr.replace("T", " ").replace("Z", "").substring(0, 19));
        } catch (Exception e) {
            log.debug("日期解析失败: dateStr={}", dateStr);
            return null;
        }
    }

    /**
     * 创建空内容对象
     *
     * @param filename 文件名
     * @return 空的文档内容
     */
    private DocumentContent createEmptyContent(String filename) {
        return DocumentContent.builder()
                .title(filename != null ? filename : "empty")
                .content("")
                .build();
    }

    /**
     * 检测文件类型
     *
     * @param file 上传的文件
     * @return MIME 类型
     */
    public String detectMimeType(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
            return tika.detect(inputStream, metadata);
        } catch (Exception e) {
            log.warn("MIME 类型检测失败: filename={}, error={}",
                    file.getOriginalFilename(), e.getMessage());
            return file.getContentType();
        }
    }

    /**
     * 使用简单方式解析文档（仅提取文本内容）
     *
     * @param file 上传的文件
     * @return 文本内容
     * @throws Exception 解析失败时抛出异常
     */
    public String parseToString(MultipartFile file) throws Exception {
        try (InputStream inputStream = file.getInputStream()) {
            String content = tika.parseToString(inputStream);
            log.info("文档解析完成: filename={}, contentLength={}",
                    file.getOriginalFilename(), content.length());
            return content;
        }
    }
}
