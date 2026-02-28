package com.kira.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件存储记录表
 *
 * @author Kira
 * @create 2026-02-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_records")
public class FileRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
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
     * 文档子分类（可选）
     */
    private String subcategory;

    /**
     * 内容类型（MIME Type）
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * OSS 存储路径
     */
    private String ossPath;

    /**
     * OSS Bucket 名称
     */
    private String bucketName;

    /**
     * 上传时间
     */
    private LocalDateTime uploadedAt;
}
