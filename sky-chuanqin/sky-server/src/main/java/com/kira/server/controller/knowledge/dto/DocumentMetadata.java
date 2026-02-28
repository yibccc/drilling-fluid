package com.kira.server.controller.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档元数据 DTO
 * 用于存储文档的文件信息和上传元数据
 *
 * @author Kira
 * @create 2026-02-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 内容类型（MIME Type）
     * 例如：application/pdf, application/msword
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 创建时间（上传时间）
     */
    private LocalDateTime createdAt;

    /**
     * 文档分类
     */
    private String category;

    /**
     * 文档子分类（可选）
     */
    private String subcategory;

    /**
     * 上传用户 ID（可选）
     */
    private String uploadedBy;

    /**
     * OSS 存储路径
     */
    private String ossPath;

    /**
     * 文件记录 ID
     */
    private Long fileRecordId;

    /**
     * 扩展元数据（用于存储额外的自定义字段）
     */
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();

    /**
     * 获取格式化的文件大小
     *
     * @return 格式化后的文件大小字符串，如 "1.5 MB"
     */
    public String getFormattedFileSize() {
        if (fileSize == null) {
            return "未知";
        }

        long bytes = fileSize;
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    /**
     * 获取文件扩展名
     *
     * @return 文件扩展名（不含点），如 "pdf", "docx"
     */
    public String getFileExtension() {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        int lastDotIndex = originalFilename.lastIndexOf(".");
        return originalFilename.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 添加扩展元数据
     *
     * @param key   键
     * @param value 值
     */
    public void addExtra(String key, Object value) {
        if (this.extra == null) {
            this.extra = new HashMap<>();
        }
        this.extra.put(key, value);
    }
}
