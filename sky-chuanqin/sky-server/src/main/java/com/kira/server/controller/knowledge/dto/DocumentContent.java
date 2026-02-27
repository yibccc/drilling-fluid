package com.kira.server.controller.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档内容 DTO
 * 用于存储从文档中解析出的内容和元数据
 *
 * @author Kira
 * @create 2026-02-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档标题（从文档元数据中提取）
     */
    private String title;

    /**
     * 文档内容（纯文本）
     */
    private String content;

    /**
     * 文档作者（可选）
     */
    private String author;

    /**
     * 文档创建日期（可选）
     */
    private LocalDateTime creationDate;

    /**
     * 文档修改日期（可选）
     */
    private LocalDateTime modifiedDate;

    /**
     * 内容摘要（前 500 字符）
     */
    private String getSummary() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.length() > 500
            ? content.substring(0, 500) + "..."
            : content;
    }

    /**
     * 获取内容字符数
     */
    public int getContentLength() {
        return content != null ? content.length() : 0;
    }

    /**
     * 获取预估的分块数量（基于内容长度）
     * 假设每个分块约 1000 字符
     */
    public int getEstimatedChunkCount() {
        int length = getContentLength();
        return length > 0 ? (length + 999) / 1000 : 0;
    }
}
