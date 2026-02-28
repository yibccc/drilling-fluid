-- ============================================
-- 知识库导入功能 - 数据库迁移脚本
-- ============================================
-- 项目: drilling-fluid
-- 版本: 1.0.0
-- 更新日期: 2026-02-26
-- 说明: 为 knowledge_documents 表添加 import_status 字段，支持文件导入状态跟踪
-- ============================================

-- 检查并添加 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- 修改 knowledge_documents 表 - 添加导入状态字段
-- ============================================

ALTER TABLE knowledge_documents
ADD COLUMN IF NOT EXISTS import_status VARCHAR(20) DEFAULT 'PENDING';

-- 添加导入状态约束
ALTER TABLE knowledge_documents
ADD CONSTRAINT chk_import_status
CHECK (import_status IN ('PENDING', 'PARSING', 'PARSED', 'QUEUED', 'CHUNKING', 'EMBEDDING', 'COMPLETED', 'FAILED', 'DUPLICATE'));

-- 添加 DUPLICATE 状态说明：文件重复（文件已存在，拒绝上传）

-- 添加导入状态索引
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_import_status
ON knowledge_documents(import_status);

-- ============================================
-- 知识库表结构说明
-- ============================================

-- knowledge_documents 表字段说明：
-- - id: UUID 主键
-- - doc_id: 文档唯一标识（如 DOC-1234567890-ABC12345）
-- - title: 文档标题
-- - category: 文档分类（如 pollution, equipment, materials）
-- - subcategory: 子分类（可选）
-- - content: 文档完整内容（纯文本）
-- - metadata: 元数据（JSONB）：original_filename, content_type, file_size, author, etc.
-- - chunk_count: 分块数量
-- - import_status: 导入状态
-- - created_at: 创建时间

-- knowledge_chunks 表字段说明：
-- - id: UUID 主键
-- - parent_doc_id: 父文档 doc_id
-- - chunk_index: 分块索引（从 0 开始）
-- - content: 分块内容
-- - embedding: 向量（1024维，使用 DashScope embedding 模型）
-- - created_at: 创建时间

-- ============================================
-- 导入状态说明
-- ============================================

-- PENDING: 待处理（文档已创建，等待处理）
-- PARSING: 解析中（Tika 正在解析文档）
-- PARSED: 解析完成（内容已提取，等待入队）
-- QUEUED: 已入队（Redis Stream 消息已发送）
-- CHUNKING: 分块中（Agent 正在进行文档分块）
-- EMBEDDING: 向量化中（正在生成向量）
-- COMPLETED: 完成（导入流程全部完成）
-- FAILED: 失败（导入过程中出错）
-- DUPLICATE: 文件重复（文件已存在，拒绝上传）

-- ============================================
-- 示例查询
-- ============================================

-- -- 查询所有待处理的文档
-- SELECT doc_id, title, import_status, created_at
-- FROM knowledge_documents
-- WHERE import_status IN ('PENDING', 'PARSING', 'QUEUED', 'CHUNKING', 'EMBEDDING')
-- ORDER BY created_at ASC;
--
-- -- 查询导入失败的文档
-- SELECT doc_id, title, import_status, metadata
-- FROM knowledge_documents
-- WHERE import_status = 'FAILED';
--
-- -- 查询某文档的所有分块
-- SELECT chunk_index, content, created_at
-- FROM knowledge_chunks
-- WHERE parent_doc_id = 'DOC-1234567890-ABC12345'
-- ORDER BY chunk_index;
--
-- -- 向量相似度搜索（使用 pgvector cosine 距离）
-- SELECT kd.doc_id, kd.title, kc.content,
--        kc.embedding <-> '[0.1, 0.2, ...]' as distance
-- FROM knowledge_chunks kc
-- JOIN knowledge_documents kd ON kc.parent_doc_id = kd.doc_id
-- WHERE kd.category = 'pollution'
-- ORDER BY kc.embedding <-> '[0.1, 0.2, ...]'
-- LIMIT 5;
