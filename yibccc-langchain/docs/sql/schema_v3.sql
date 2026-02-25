-- ============================================
-- 流式对话系统 - 数据库表结构 v3.0
-- ============================================
-- 项目: yibccc-langchain
-- 版本: 3.0.0
-- 更新日期: 2026-02-12
-- 说明: 基于 LangChain/LangGraph 官方消息结构设计
-- 存储策略: Redis Checkpoint (热) + PostgreSQL (归档)
-- ============================================

-- ============================================
-- 1. 会话表 (chat_sessions)
-- ============================================

CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    message_count INT DEFAULT 0,
    -- 会话标题（可选，用于会话列表展示）
    title VARCHAR(500),
    -- 会话状态：active/archived/deleted
    status VARCHAR(20) DEFAULT 'active'
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON chat_sessions(status);
CREATE INDEX IF NOT EXISTS idx_sessions_updated_at ON chat_sessions(updated_at DESC);

-- ============================================
-- 2. 消息表 (chat_messages) - 重新设计
-- ============================================
-- 符合 LangChain/LangGraph 官方消息结构：
-- - HumanMessage: message_type='human'
-- - AIMessage: message_type='ai' (可能包含 tool_calls)
-- - ToolMessage: message_type='tool' (通过 tool_call_id 关联)
-- - SystemMessage: message_type='system'

CREATE TABLE IF NOT EXISTS chat_messages (
    -- 主键
    id UUID PRIMARY KEY,

    -- 关联会话
    session_id UUID NOT NULL,

    -- ============================================
    -- 核心字段：消息类型（LangGraph 原生类型）
    -- ============================================
    -- 'human'   - 用户消息 (HumanMessage)
    -- 'ai'       - AI 消息 (AIMessage)
    -- 'system'   - 系统消息 (SystemMessage)
    -- 'tool'     - 工具结果消息 (ToolMessage)
    message_type VARCHAR(20) NOT NULL,

    -- 消息内容（所有消息类型都有 content）
    content TEXT,

    -- ============================================
    -- AIMessage 专用字段
    -- ============================================
    -- 存储 AIMessage 的 tool_calls 数组
    -- 格式: [{"id": "call_abc", "name": "get_weather", "args": {"loc": "SF"}}]
    tool_calls JSONB,

    -- AI 消息的额外参数（如 response_metadata, usage_metadata 等）
    additional_kwargs JSONB,

    -- ============================================
    -- ToolMessage 专用字段
    -- ============================================
    -- 关联到 AIMessage.tool_calls[i].id
    tool_call_id VARCHAR(255),

    -- 工具名称（方便查询，冗余但提升查询性能）
    tool_name VARCHAR(100),

    -- 工具调用状态：pending/success/failed
    tool_status VARCHAR(20),

    -- 工具执行错误信息（如果失败）
    tool_error TEXT,

    -- ============================================
    -- 通用字段
    -- ============================================
    -- 消息创建时间
    created_at TIMESTAMPTZ DEFAULT NOW(),

    -- LangGraph 消息的原始 ID（用于调试和追踪）
    langgraph_message_id VARCHAR(255)
);

-- 索引设计
CREATE INDEX IF NOT EXISTS idx_messages_session_id ON chat_messages(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_messages_tool_call_id ON chat_messages(tool_call_id) WHERE tool_call_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_type ON chat_messages(message_type);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON chat_messages(created_at DESC);

-- ============================================
-- 3. LangGraph Checkpoint 备份表 (langchain_checkpoints)
-- ============================================

CREATE TABLE IF NOT EXISTS langchain_checkpoints (
    thread_id VARCHAR(255) PRIMARY KEY,
    checkpoint JSONB NOT NULL,
    metadata JSONB,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_checkpoints_updated_at ON langchain_checkpoints(updated_at DESC);

-- ============================================
-- 数据模型说明
-- ============================================

-- chat_messages 表设计说明：
--
-- 1. message_type 字段（LangGraph 原生类型）：
--    - 'human'  : HumanMessage，用户输入
--    - 'ai'     : AIMessage，AI 回复（可能包含 tool_calls）
--    - 'system'  : SystemMessage，系统提示
--    - 'tool'    : ToolMessage，工具执行结果
--
-- 2. AIMessage 结构（message_type='ai'）：
--    - content: 文本内容（可能为空）
--    - tool_calls: [{"id": "call_xxx", "name": "tool_name", "args": {...}}]
--    - additional_kwargs: response_metadata, usage_metadata 等
--
-- 3. ToolMessage 结构（message_type='tool'）：
--    - content: 工具返回结果
--    - tool_call_id: 关联到 AIMessage.tool_calls[x].id
--    - tool_name: 工具名称（冗余字段，方便查询）
--    - tool_status: pending/success/failed
--    - tool_error: 失败时的错误信息
--
-- 4. 消息顺序示例（工具调用场景）：
--    1. human: "今天几号？"
--    2. ai: tool_calls=[{"id": "call_1", "name": "get_time", "args": {}}]
--    3. tool: tool_call_id="call_1", content="2026-02-12"
--    4. ai: "今天是2026年2月12日。"
--
-- 5. 消息顺序示例（多工具调用场景）：
--    1. human: "北京现在几点？天气如何？"
--    2. ai: tool_calls=[
--          {"id": "call_1", "name": "get_time", "args": {"timezone": "Asia/Shanghai"}},
--          {"id": "call_2", "name": "get_weather", "args": {"location": "Beijing"}}
--        ]
--    3. tool: tool_call_id="call_1", content="14:30:00"
--    4. tool: tool_call_id="call_2", content="晴，15°C"
--    5. ai: "北京现在是14:30，天气晴朗，气温15°C。"

-- ============================================
-- 使用示例
-- ============================================

-- -- 查询会话的所有消息（按顺序）
-- SELECT id, message_type, content, tool_calls, tool_call_id, tool_name
-- FROM chat_messages
-- WHERE session_id = 'session-uuid'
-- ORDER BY created_at ASC;

-- -- 查询包含工具调用的消息
-- SELECT id, message_type, content, tool_calls
-- FROM chat_messages
-- WHERE session_id = 'session-uuid'
--   AND message_type = 'ai'
--   AND tool_calls IS NOT NULL
--   AND jsonb_array_length(tool_calls) > 0;

-- -- 查询工具执行结果
-- SELECT m.id, m.tool_name, m.content, m.tool_status
-- FROM chat_messages m
-- WHERE m.session_id = 'session-uuid'
--   AND m.message_type = 'tool'
-- ORDER BY m.created_at ASC;

-- -- 查询某个工具调用的完整流程（AI消息 → 工具结果 → 最终回复）
-- SELECT m.id, m.message_type, m.content, m.tool_calls, m.tool_call_id
-- FROM chat_messages m
-- WHERE m.session_id = 'session-uuid'
--   AND (
--     m.message_type IN ('ai', 'tool', 'human')
--     OR m.tool_call_id = 'specific-call-id'
--   )
-- ORDER BY m.created_at ASC;

-- -- 统计工具使用情况
-- SELECT
--     tool_name,
--     COUNT(*) as usage_count,
--     AVG(CASE WHEN tool_status = 'success' THEN 1 ELSE 0 END) as success_rate
-- FROM chat_messages
-- WHERE message_type = 'tool'
--   AND tool_name IS NOT NULL
-- GROUP BY tool_name
-- ORDER BY usage_count DESC;

-- ============================================
-- 测试数据
-- ============================================

-- 插入测试会话
INSERT INTO chat_sessions (id, user_id, created_at, updated_at, message_count)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000'::UUID,
    'test_user_001',
    '2026-02-11 06:02:04.114795+00'::TIMESTAMPTZ,
    '2026-02-12 02:49:53.295498+00'::TIMESTAMPTZ,
    14
)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 迁移说明（从 v2.0 升级）
-- ============================================

-- 从旧版 schema 迁移到新版：
-- 1. role 字段映射：
--    'user' → 'human'
--    'assistant' → 'ai'
--    'system' → 'system'
--    'tool' → 'tool'
--
-- 2. 旧版 tool_calls 列（从未正确填充）将被新的结构替代
--
-- 3. 迁移 SQL（可选，建议重建表）：
--    ALTER TABLE chat_messages RENAME TO chat_messages_v2;
--    -- 执行新 schema 创建
--    INSERT INTO chat_messages (id, session_id, message_type, content, created_at)
--    SELECT id, session_id,
--           CASE role
--             WHEN 'user' THEN 'human'
--             WHEN 'assistant' THEN 'ai'
--             ELSE role
--           END,
--           content, created_at
--    FROM chat_messages_v2;
