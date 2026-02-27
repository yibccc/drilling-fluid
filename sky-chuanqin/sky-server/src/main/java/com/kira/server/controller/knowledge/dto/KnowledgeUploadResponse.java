package com.kira.server.controller.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识库文件上传响应 DTO
 * 用于返回文件上传后的处理状态
 *
 * @author Kira
 * @create 2026-02-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一标识
     * 格式：DOC-{timestamp}-{random8}
     */
    @JsonProperty("doc_id")
    private String docId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 导入状态
     */
    @JsonProperty("import_status")
    private String status;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 文件大小（字节）
     */
    @JsonProperty("file_size")
    private Long fileSize;

    /**
     * 内容类型
     */
    @JsonProperty("content_type")
    private String contentType;

    /**
     * 预估的分块数量
     */
    @JsonProperty("estimated_chunks")
    private Integer estimatedChunks;

    /**
     * 当前分块数量（处理中更新）
     */
    @JsonProperty("current_chunks")
    private Integer currentChunks;

    /**
     * 错误信息（如果失败）
     */
    private String error;

    /**
     * 创建成功响应
     *
     * @param docId  文档 ID
     * @param title  文档标题
     * @param status 状态
     * @param message 消息
     * @return 响应对象
     */
    public static KnowledgeUploadResponse success(String docId, String title, String status, String message) {
        return KnowledgeUploadResponse.builder()
                .docId(docId)
                .title(title)
                .status(status)
                .message(message)
                .build();
    }

    /**
     * 创建失败响应
     *
     * @param title 文档标题
     * @param error 错误信息
     * @return 响应对象
     */
    public static KnowledgeUploadResponse failure(String title, String error) {
        return KnowledgeUploadResponse.builder()
                .title(title)
                .status("FAILED")
                .message("上传失败")
                .error(error)
                .build();
    }
}
