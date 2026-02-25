-- docs/sql/diagnosis_schema.sql
-- 钻井液诊断系统相关表结构

-- pgvector 扩展（用于向量检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- 知识文档表（父文档）
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id VARCHAR(100) UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    category VARCHAR(50) NOT NULL,
    subcategory VARCHAR(100),
    content TEXT NOT NULL,
    metadata JSONB,
    chunk_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 子分块表（用于向量检索）
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_doc_id VARCHAR(100) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- HNSW 索引（需要 pgvector 扩展）
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- 诊断任务表
CREATE TABLE IF NOT EXISTS diagnosis_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) UNIQUE NOT NULL,
    well_id VARCHAR(50) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    alert_triggered_at TIMESTAMPTZ NOT NULL,
    alert_threshold JSONB,
    samples JSONB NOT NULL,
    context JSONB,
    callback_url TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 诊断结果表
CREATE TABLE IF NOT EXISTS diagnosis_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) UNIQUE NOT NULL,
    diagnosis JSONB NOT NULL,
    trend_analysis JSONB,
    measures JSONB,
    prescription JSONB,
    "references" JSONB,
    rag_metadata JSONB,
    llm_metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 分析过程事件表
CREATE TABLE IF NOT EXISTS diagnosis_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_data JSONB NOT NULL,
    sequence_num INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 处置反馈表
CREATE TABLE IF NOT EXISTS treatment_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) NOT NULL,
    well_id VARCHAR(50) NOT NULL,
    measures_taken JSONB,
    after_samples JSONB,
    effectiveness VARCHAR(20),
    feedback TEXT,
    rating INT,
    applied_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_diagnosis_tasks_well_id ON diagnosis_tasks(well_id);
CREATE INDEX IF NOT EXISTS idx_diagnosis_tasks_status ON diagnosis_tasks(status);
CREATE INDEX IF NOT EXISTS idx_diagnosis_events_task_id ON diagnosis_events(task_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_parent_doc_id ON knowledge_chunks(parent_doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_category ON knowledge_documents(category);
