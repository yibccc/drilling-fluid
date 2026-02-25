# 流式对话 API 设计文档

**日期**: 2026-02-10
**项目**: yibccc-langchain
**版本**: 0.1.0

## 1. 概述

本设计文档描述了基于 LangChain 和 DeepSeek API 的流式对话系统，通过 FastAPI 暴露 SSE 接口，供博客后端（Spring WebFlux）调用并流式返回给 Vue3 前端。

### 1.1 核心特性

- **流式响应**: SSE (Server-Sent Events) 格式实时推送
- **持久化会话**: Redis 缓存 + PostgreSQL 归档
- **工具调用**: 支持 LangChain Agent 工具执行
- **API Key 认证**: 简单安全的接口保护

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Vue3      │     │ Spring      │     │   FastAPI   │
│   前端      │◄────┤ WebFlux     │◄────┤   服务      │
│            │ SSE │  后端       │     │             │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                             │
                    ┌────────────────────────┼────────────────────────┐
                    │                        │                        │
                    ▼                        ▼                        ▼
             ┌──────────────┐        ┌──────────────┐        ┌──────────────┐
             │    Redis     │        │  PostgreSQL  │        │   DeepSeek   │
             │  - 对话上下文 │◄───────│  - 历史归档  │        │     LLM      │
             │  - RedisSearch│ 异步   │  - 长期存储  │        │              │
             │  - 实时读写   │ 同步   │  - 搜索索引  │        └──────────────┘
             └──────────────┘        └──────────────┘
```

### 2.2 数据流

1. **实时读写**: 对话上下文存储在 Redis 中，支持快速读写
2. **异步归档**: 后台任务定期将 Redis 数据同步到 PostgreSQL
3. **搜索支持**: Redis Search 提供历史对话的搜索能力

---

## 3. API 接口设计

### 3.1 REST 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/v1/chat/stream` | POST | SSE 流式对话 |
| `/api/v1/chat/history` | GET | 获取会话历史 |
| `/api/v1/chat/sessions/{session_id}` | DELETE | 删除会话 |
| `/health` | GET | 健康检查 |

### 3.2 流式对话接口

**请求** (`POST /api/v1/chat/stream`):

```json
{
  "session_id": "uuid-xxx",
  "message": "用户问题",
  "stream": true
}
```

**响应** (SSE):

```
data: {"type":"start","session_id":"uuid-xxx"}

data: {"type":"token","content":"你好"}

data: {"type":"token","content":"！"}

data: {"type":"tool_call","name":"search","args":{"query":"..."}}

data: {"type":"tool_result","name":"search","result":"..."}

data: {"type":"end","reason":"stop"}
```

### 3.3 事件类型

| 类型 | 描述 |
|------|------|
| `start` | 对话开始 |
| `token` | 文本片段 |
| `tool_call` | 工具调用 |
| `tool_result` | 工具执行结果 |
| `end` | 对话结束 |
| `error` | 错误信息 |

---

## 4. 数据模型

### 4.1 Pydantic 模型

```python
from pydantic import BaseModel, Field
from typing import Literal, Optional
from datetime import datetime

class ToolCall(BaseModel):
    """工具调用信息"""
    name: str
    arguments: dict
    result: Optional[str] = None

class ChatMessage(BaseModel):
    """对话消息（支持工具调用）"""
    role: Literal["user", "assistant", "system", "tool"]
    content: Optional[str] = None
    tool_calls: list[ToolCall] = []
    timestamp: datetime

class ChatRequest(BaseModel):
    """对话请求"""
    session_id: Optional[str] = None
    message: str
    stream: bool = True

class ChatResponse(BaseModel):
    """SSE 事件响应"""
    type: Literal["start", "token", "tool_call", "tool_result", "end", "error"]
    session_id: Optional[str] = None
    content: Optional[str] = None
    tool_call: Optional[dict] = None
    error_code: Optional[str] = None
```

### 4.2 Redis 数据结构

```
# 会话上下文 (Hash)
session:{session_id}
  ├── user_id: "user_123"
  ├── created_at: "2025-01-11T10:00:00Z"
  ├── updated_at: "2025-01-11T10:05:00Z"
  └── message_count: 10

# 对话消息 (List)
messages:{session_id}
  ├── {"role":"user","content":"你好","timestamp":"..."}
  ├── {"role":"assistant","content":"你好！","timestamp":"..."}
  └── ...

# 用户会话索引 (Set)
user:sessions:{user_id}
  └── ["session_1", "session_2", ...]
```

### 4.3 PostgreSQL 数据表

```sql
-- 会话表
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    message_count INT DEFAULT 0
);

CREATE INDEX idx_sessions_user_id ON chat_sessions(user_id);

-- 消息表
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_messages_session_id ON chat_messages(session_id);
```

---

## 5. 组件结构

### 5.1 目录结构

```
yibccc-langchain/
├── src/
│   ├── api/
│   │   ├── __init__.py
│   │   ├── main.py              # FastAPI 应用入口
│   │   ├── routes/
│   │   │   ├── __init__.py
│   │   │   └── chat.py          # 聊天路由
│   │   └── dependencies.py      # 依赖注入（API Key 验证）
│   ├── services/
│   │   ├── __init__.py
│   │   ├── chat_service.py      # 对话服务核心逻辑
│   │   └── sync_service.py      # Redis → PostgreSQL 异步同步
│   ├── repositories/
│   │   ├── __init__.py
│   │   ├── redis_repo.py        # Redis 操作封装
│   │   └── pg_repo.py           # PostgreSQL 操作封装
│   ├── models/
│   │   ├── __init__.py
│   │   └── schemas.py           # Pydantic 模型
│   ├── tools/                   # LangChain 工具
│   ├── agents/
│   │   └── __init__.py
│   └── config.py
├── tests/
│   └── api/
└── main.py
```

### 5.2 核心组件职责

| 组件 | 职责 |
|------|------|
| `routes/chat.py` | 处理 HTTP 请求，返回 SSE 流 |
| `services/chat_service.py` | LLM 调用、工具编排、消息管理 |
| `repositories/redis_repo.py` | Redis 会话读写、连接池管理 |
| `repositories/pg_repo.py` | PostgreSQL 持久化操作 |
| `services/sync_service.py` | 后台任务，定期同步 Redis → PG |

---

## 6. 工具调用流程

```
用户消息 → LLM 决策 → 工具调用 → 工具结果 → LLM 总结 → 流式输出
            (发送 SSE)       (发送 SSE)         (继续流式)
```

---

## 7. 错误处理

### 7.1 异常层次

```python
class AppException(Exception):
    """应用基础异常"""
    pass

class AuthenticationError(AppException):
    """API Key 认证失败"""
    pass

class SessionNotFoundError(AppException):
    """会话不存在"""
    pass

class LLMError(AppException):
    """LLM 调用失败"""
    pass

class ToolExecutionError(AppException):
    """工具执行失败"""
    pass
```

### 7.2 SSE 错误格式

```
data: {"type":"error","code":"AUTH_FAILED","message":"Invalid API Key"}
data: {"type":"error","code":"LLM_ERROR","message":"DeepSeek API 超时"}
```

---

## 8. 测试策略

| 测试类型 | 工具 | 覆盖内容 |
|----------|------|----------|
| 单元测试 | pytest | Service 层逻辑 |
| 集成测试 | pytest-asyncio | API 端点 |
| LLM Mock | pytest-mock | 模拟 DeepSeek 响应 |

---

## 9. 技术栈

- **FastAPI** >= 0.115.0 - Web 框架
- **LangChain** >= 1.0.0 - LLM 框架
- **redis-py** - Redis 异步客户端
- **asyncpg** - PostgreSQL 异步驱动
- **uvicorn** - ASGI 服务器

---

## 10. 依赖更新

需要在 `pyproject.toml` 中添加：

```toml
dependencies = [
    # 现有依赖...
    "redis>=5.0.0",
    "asyncpg>=0.29.0",
]
```
