-- docs/sql/diagnosis_schema.sql
-- 钻井液诊断系统相关表结构
--
-- 架构说明：
-- - 知识库使用 LangChain PGVector 自动管理（langchain_pg_* 表）
-- - 不再需要手动创建 knowledge_documents 和 knowledge_chunks 表
-- - VectorStoreService 会在首次使用时自动创建必要的表和索引
--

-- ============================================
-- pgvector 扩展（必需）
-- ============================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- 注意：知识库表由 LangChain PGVector 自动管理
-- ============================================
--
-- LangChain PGVector 会自动创建以下表：
-- - langchain_pg_collection: 集合元数据
-- - langchain_pg_embedding: 文档和向量存储
--
-- 初始化方式（代码中自动执行）：
-- from langchain_postgres import PGVector
--
-- vector_store = PGVector(
--     embeddings=embeddings,
--     collection_name="knowledge_docs",
--     connection=connection_string,
--     use_jsonb=True,
-- )
--
-- 首次调用 add_documents() 时会自动创建表结构
--

-- ============================================
-- 诊断任务表
-- ============================================
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
