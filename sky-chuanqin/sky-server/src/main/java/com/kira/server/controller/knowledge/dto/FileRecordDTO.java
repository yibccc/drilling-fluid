package com.kira.server.controller.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件记录 DTO
 *
 * @author Kira
 * @create 2026-02-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRecordDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件记录 ID
     */
    private Long id;

    /**
     * 文件 SHA256 哈希值
     */
    private String fileHash;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文档分类
     */
    private String category;

    /**
     * 文档子分类
     */
    private String subcategory;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * OSS 存储路径
     */
    private String ossPath;

    /**
     * Bucket 名称
     */
    private String bucketName;

    /**
     * 上传时间
     */
    private LocalDateTime uploadedAt;
}
