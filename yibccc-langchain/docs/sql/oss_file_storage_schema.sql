-- 阿里云 OSS 文件存储数据库迁移脚本（MySQL 版本）
-- 创建日期: 2026-02-27
-- 作者: Kira
-- 数据库: MySQL

-- 1. 创建文件存储记录表
CREATE TABLE IF NOT EXISTS file_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL COMMENT '文件 SHA256 哈希值',
    original_filename VARCHAR(500) NOT NULL COMMENT '原始文件名',
    category VARCHAR(50) NOT NULL COMMENT '文档分类',
    subcategory VARCHAR(100) COMMENT '文档子分类（可选）',
    content_type VARCHAR(100) COMMENT '内容类型（MIME Type）',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    oss_path VARCHAR(500) NOT NULL COMMENT 'OSS 存储路径',
    bucket_name VARCHAR(100) NOT NULL COMMENT 'OSS Bucket 名称',
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    UNIQUE KEY uq_filename_category (original_filename, category),
    INDEX idx_file_hash (file_hash),
    INDEX idx_file_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件存储记录表';