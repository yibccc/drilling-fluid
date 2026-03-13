-- yibccc-langchain 统一数据库初始化脚本
-- 更新日期: 2026-03-13 19:44:25 +08:00
--
-- 说明：
-- 1. 本文件是 yibccc-langchain 唯一需要维护的 SQL 入口
-- 2. 覆盖聊天归档表、诊断业务表，以及 PGVector 扩展前置条件
-- 3. 知识库向量表由 LangChain PGVector 自动创建，不再单独维护 SQL

-- ============================================
-- PostgreSQL 扩展
-- ============================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- 聊天模块
-- 对应 src/repositories/pg_repo.py
-- ============================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    message_count INT DEFAULT 0,
    title VARCHAR(500),
    status VARCHAR(20) DEFAULT 'active'
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id
    ON chat_sessions(user_id);

CREATE INDEX IF NOT EXISTS idx_sessions_status
    ON chat_sessions(status);

CREATE INDEX IF NOT EXISTS idx_sessions_updated_at
    ON chat_sessions(updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls JSONB,
    additional_kwargs JSONB,
    tool_call_id VARCHAR(255),
    tool_name VARCHAR(100),
    tool_status VARCHAR(20),
    tool_error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    langgraph_message_id VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_messages_session_id
    ON chat_messages(session_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_messages_tool_call_id
    ON chat_messages(tool_call_id)
    WHERE tool_call_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_messages_type
    ON chat_messages(message_type);

CREATE INDEX IF NOT EXISTS idx_messages_created_at
    ON chat_messages(created_at DESC);

CREATE TABLE IF NOT EXISTS langchain_checkpoints (
    thread_id VARCHAR(255) PRIMARY KEY,
    checkpoint JSONB NOT NULL,
    metadata JSONB,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_checkpoints_updated_at
    ON langchain_checkpoints(updated_at DESC);

-- ============================================
-- 诊断模块
-- 对应 src/repositories/diagnosis_repo.py
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
    status VARCHAR(20) DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_tasks_well_id
    ON diagnosis_tasks(well_id);

CREATE INDEX IF NOT EXISTS idx_diagnosis_tasks_status
    ON diagnosis_tasks(status);

CREATE INDEX IF NOT EXISTS idx_diagnosis_tasks_created_at
    ON diagnosis_tasks(created_at DESC);

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

CREATE INDEX IF NOT EXISTS idx_diagnosis_results_created_at
    ON diagnosis_results(created_at DESC);

CREATE TABLE IF NOT EXISTS diagnosis_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_data JSONB NOT NULL,
    sequence_num INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_events_task_id
    ON diagnosis_events(task_id);

CREATE INDEX IF NOT EXISTS idx_diagnosis_events_task_seq
    ON diagnosis_events(task_id, sequence_num ASC);

-- ============================================
-- 知识库向量表说明
-- ============================================
-- VectorStoreService 会在首次写入时自动创建：
-- - langchain_pg_collection
-- - langchain_pg_embedding
--
-- 因此这里不再手工创建 knowledge_documents / knowledge_chunks。
