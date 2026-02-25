# 流式对话 API 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 构建 FastAPI 流式对话服务，通过 SSE 暴露 LLM 对话能力，支持工具调用和持久化会话

**架构:** FastAPI 路由层 → Chat Service（LangChain LLM + 工具编排）→ Redis 缓存 + PostgreSQL 归档

**技术栈:** FastAPI, LangChain, DeepSeek API, redis-py, asyncpg

---

## 前置准备

### 文档参考
- 设计文档: `docs/plans/2026-02-10-streaming-chat-api-design.md`
- 现有配置: `src/config.py`

---

## Task 1: 项目基础设施搭建

**目标:** 更新依赖、创建目录结构、配置管理

**Files:**
- Modify: `pyproject.toml`
- Create: `src/api/__init__.py`
- Create: `src/api/routes/__init__.py`
- Create: `src/services/__init__.py`
- Create: `src/repositories/__init__.py`
- Create: `src/models/__init__.py`
- Create: `tests/api/__init__.py`

---

### Step 1.1: 更新项目依赖

**文件:** `pyproject.toml`

**操作:** 在 `dependencies` 数组中添加新依赖

```toml
[project]
name = "yibccc-langchain"
version = "0.1.0"
description = "基于 LangChain 1.0.0 和 DeepSeek API 的个人博客 Agent 系统"
readme = "README.md"
requires-python = ">=3.10"
dependencies = [
    "langchain>=1.0.0",
    "langchain-openai>=0.1.0",
    "langgraph>=0.2.0",
    "python-dotenv>=1.0.0",
    "pydantic>=2.0.0",
    "pydantic-settings>=2.0.0",
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.30.0",
    "httpx>=0.27.0",
    "redis>=5.0.0",
    "asyncpg>=0.29.0",
]
```

**验证命令:**
```bash
uv sync
```

**预期输出:** 无错误，依赖安装成功

---

### Step 1.2: 创建目录结构

**操作:** 创建所有必要的 `__init__.py` 文件

```bash
mkdir -p src/api/routes
mkdir -p src/services
mkdir -p src/repositories
mkdir -p src/models
mkdir -p tests/api

touch src/api/__init__.py
touch src/api/routes/__init__.py
touch src/services/__init__.py
touch src/repositories/__init__.py
touch src/models/__init__.py
touch tests/api/__init__.py
```

**验证命令:**
```bash
find src -name "__init__.py" | sort
```

**预期输出:**
```
src/__init__.py
src/api/__init__.py
src/api/routes/__init__.py
src/models/__init__.py
src/repositories/__init__.py
src/services/__init__.py
```

---

### Step 1.3: 更新配置管理

**文件:** `src/config.py`

**操作:** 添加 Redis、PostgreSQL、API Key 配置

```python
"""
配置管理模块

加载和管理环境变量配置
"""

import os
from dotenv import load_dotenv
from pydantic import Field
from pydantic_settings import BaseSettings

# 加载环境变量
load_dotenv()


class Settings(BaseSettings):
    """应用配置"""

    # DeepSeek API 配置
    deepseek_api_key: str = Field(default="", alias="DEEPSEEK_API_KEY")
    deepseek_base_url: str = Field(
        default="https://api.deepseek.com/v1",
        alias="DEEPSEEK_BASE_URL"
    )
    deepseek_model: str = Field(default="deepseek-chat", alias="DEEPSEEK_MODEL")

    # Redis 配置
    redis_url: str = Field(default="redis://localhost:6379", alias="REDIS_URL")
    redis_db: int = Field(default=0, alias="REDIS_DB")
    redis_max_connections: int = Field(default=20, alias="REDIS_MAX_CONNECTIONS")

    # PostgreSQL 配置
    pg_dsn: str = Field(default="", alias="PG_DSN")
    pg_host: str = Field(default="localhost", alias="PG_HOST")
    pg_port: int = Field(default=5432, alias="PG_PORT")
    pg_database: str = Field(default="yibccc_chat", alias="PG_DATABASE")
    pg_user: str = Field(default="", alias="PG_USER")
    pg_password: str = Field(default="", alias="PG_PASSWORD")

    # API 认证配置
    api_keys: list[str] = Field(default_factory=list, alias="API_KEYS")

    # 应用配置
    app_name: str = "yibccc-langchain"
    app_version: str = "0.1.0"
    debug: bool = False

    class Config:
        env_file = ".env"
        case_sensitive = False

    def get_pg_dsn(self) -> str:
        """获取 PostgreSQL DSN"""
        if self.pg_dsn:
            return self.pg_dsn
        return f"postgresql://{self.pg_user}:{self.pg_password}@{self.pg_host}:{self.pg_port}/{self.pg_database}"

    def validate_api_key(self, api_key: str) -> bool:
        """验证 API Key"""
        return api_key in self.api_keys


# 全局配置实例
settings = Settings()


def get_llm_config() -> dict:
    """获取 LLM 配置字典"""
    return {
        "model": settings.deepseek_model,
        "api_key": settings.deepseek_api_key,
        "base_url": settings.deepseek_base_url,
    }
```

---

### Step 1.4: 更新环境变量示例

**文件:** `.env.example`

```env
# DeepSeek API 配置
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_MODEL=deepseek-chat

# Redis 配置
REDIS_URL=redis://localhost:6379
REDIS_DB=0
REDIS_MAX_CONNECTIONS=20

# PostgreSQL 配置
PG_HOST=localhost
PG_PORT=5432
PG_DATABASE=yibccc_chat
PG_USER=your_pg_user
PG_PASSWORD=your_pg_password

# API 认证配置（逗号分隔多个 Key）
API_KEYS=your_api_key_1,your_api_key_2

# 应用配置
DEBUG=False
```

---

### Step 1.5: 提交

```bash
git add pyproject.toml src/config.py .env.example src/api/ src/services/ src/repositories/ src/models/ tests/api/
git commit -m "feat: 添加项目基础设施 - 依赖更新和目录结构"
```

---

## Task 2: 数据模型定义

**目标:** 定义所有 Pydantic 模型和异常类

**Files:**
- Create: `src/models/schemas.py`
- Create: `src/models/exceptions.py`
- Test: `tests/models/test_schemas.py`

---

### Step 2.1: 写模型测试

**文件:** `tests/models/test_schemas.py`

```python
"""测试 Pydantic 模型"""
import pytest
from datetime import datetime
from src.models.schemas import ToolCall, ChatMessage, ChatRequest, ChatResponse


def test_tool_call_creation():
    """测试工具调用模型创建"""
    tool = ToolCall(name="search", arguments={"query": "test"})
    assert tool.name == "search"
    assert tool.arguments == {"query": "test"}
    assert tool.result is None


def test_tool_call_with_result():
    """测试带结果的工具调用"""
    tool = ToolCall(name="search", arguments={"query": "test"}, result="found")
    assert tool.result == "found"


def test_chat_message_user():
    """测试用户消息"""
    msg = ChatMessage(
        role="user",
        content="你好",
        timestamp=datetime.now()
    )
    assert msg.role == "user"
    assert msg.content == "你好"
    assert msg.tool_calls == []


def test_chat_message_assistant_with_tools():
    """测试带工具调用的助手消息"""
    msg = ChatMessage(
        role="assistant",
        content="让我帮你查询",
        tool_calls=[ToolCall(name="search", arguments={"query": "test"})],
        timestamp=datetime.now()
    )
    assert len(msg.tool_calls) == 1
    assert msg.tool_calls[0].name == "search"


def test_chat_request():
    """测试对话请求"""
    req = ChatRequest(message="你好")
    assert req.message == "你好"
    assert req.session_id is None
    assert req.stream is True


def test_chat_request_with_session():
    """测试带会话 ID 的请求"""
    req = ChatRequest(
        session_id="uuid-xxx",
        message="你好",
        stream=False
    )
    assert req.session_id == "uuid-xxx"
    assert req.stream is False


def test_chat_response():
    """测试 SSE 响应模型"""
    resp = ChatResponse(type="start", session_id="uuid-xxx")
    assert resp.type == "start"
    assert resp.session_id == "uuid-xxx"


def test_chat_response_token():
    """测试 token 响应"""
    resp = ChatResponse(type="token", content="你好")
    assert resp.type == "token"
    assert resp.content == "你好"


def test_chat_response_error():
    """测试错误响应"""
    resp = ChatResponse(
        type="error",
        error_code="AUTH_FAILED",
        content="Invalid API Key"
    )
    assert resp.type == "error"
    assert resp.error_code == "AUTH_FAILED"
```

---

### Step 2.2: 运行测试验证失败

**命令:**
```bash
pytest tests/models/test_schemas.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.models.schemas`

---

### Step 2.3: 实现数据模型

**文件:** `src/models/schemas.py`

```python
"""
数据模型定义

Pydantic 模型用于请求/响应验证和序列化
"""

from pydantic import BaseModel, Field, field_validator
from typing import Literal, Optional
from datetime import datetime
from uuid import uuid4


class ToolCall(BaseModel):
    """工具调用信息"""
    name: str = Field(..., description="工具名称")
    arguments: dict = Field(default_factory=dict, description="工具参数")
    result: Optional[str] = Field(None, description="工具执行结果")


class ChatMessage(BaseModel):
    """对话消息（支持工具调用）"""
    role: Literal["user", "assistant", "system", "tool"] = Field(..., description="消息角色")
    content: Optional[str] = Field(None, description="消息内容")
    tool_calls: list[ToolCall] = Field(default_factory=list, description="工具调用列表")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="消息时间戳")

    @field_validator("content")
    @classmethod
    def validate_content(cls, v, info):
        """验证 content 和 tool_calls 不能同时为空"""
        if v is None and not info.data.get("tool_calls"):
            raise ValueError("content 或 tool_calls 至少需要一个")
        return v


class ChatRequest(BaseModel):
    """对话请求"""
    session_id: Optional[str] = Field(None, description="会话 ID，首次为空")
    message: str = Field(..., min_length=1, description="用户消息")
    stream: bool = Field(True, description="是否流式返回")


class ChatResponse(BaseModel):
    """SSE 事件响应"""
    type: Literal["start", "token", "tool_call", "tool_result", "end", "error"] = Field(
        ..., description="事件类型"
    )
    session_id: Optional[str] = Field(None, description="会话 ID")
    content: Optional[str] = Field(None, description="内容（token/error 消息）")
    tool_call: Optional[dict] = Field(None, description="工具调用信息")
    error_code: Optional[str] = Field(None, description="错误代码")

    def to_sse(self) -> str:
        """转换为 SSE 格式"""
        return f"data: {self.model_dump_json(exclude_none=True)}\n\n"

    @classmethod
    def start(cls, session_id: str) -> "ChatResponse":
        """创建开始事件"""
        return cls(type="start", session_id=session_id)

    @classmethod
    def token(cls, content: str) -> "ChatResponse":
        """创建 token 事件"""
        return cls(type="token", content=content)

    @classmethod
    def tool_call(cls, name: str, args: dict) -> "ChatResponse":
        """创建工具调用事件"""
        return cls(type="tool_call", tool_call={"name": name, "arguments": args})

    @classmethod
    def tool_result(cls, name: str, result: str) -> "ChatResponse":
        """创建工具结果事件"""
        return cls(type="tool_result", tool_call={"name": name, "result": result})

    @classmethod
    def end(cls, reason: str = "stop") -> "ChatResponse":
        """创建结束事件"""
        return cls(type="end", content=reason)

    @classmethod
    def error(cls, code: str, message: str) -> "ChatResponse":
        """创建错误事件"""
        return cls(type="error", error_code=code, content=message)
```

---

### Step 2.4: 运行测试验证通过

**命令:**
```bash
pytest tests/models/test_schemas.py -v
```

**预期输出:** PASSED (8 tests)

---

### Step 2.5: 提交

```bash
git add tests/models/test_schemas.py src/models/schemas.py
git commit -m "feat: 添加数据模型定义 - Pydantic schemas"
```

---

## Task 3: 异常类定义

**目标:** 定义应用异常层次结构

**Files:**
- Create: `src/models/exceptions.py`
- Test: `tests/models/test_exceptions.py`

---

### Step 3.1: 写异常测试

**文件:** `tests/models/test_exceptions.py`

```python
"""测试异常类"""
import pytest
from src.models.exceptions import (
    AppException,
    AuthenticationError,
    SessionNotFoundError,
    LLMError,
    ToolExecutionError,
)


def test_app_exception():
    """测试基础异常"""
    exc = AppException("基础错误")
    assert str(exc) == "基础错误"
    assert isinstance(exc, Exception)


def test_authentication_error():
    """测试认证错误"""
    exc = AuthenticationError("Invalid API Key")
    assert str(exc) == "Invalid API Key"
    assert isinstance(exc, AppException)


def test_session_not_found_error():
    """测试会话不存在错误"""
    exc = SessionNotFoundError("session_123")
    assert isinstance(exc, AppException)


def test_llm_error():
    """测试 LLM 错误"""
    exc = LLMError("DeepSeek API 超时")
    assert isinstance(exc, AppException)


def test_tool_execution_error():
    """测试工具执行错误"""
    exc = ToolExecutionError("工具调用失败")
    assert isinstance(exc, AppException)
```

---

### Step 3.2: 运行测试验证失败

**命令:**
```bash
pytest tests/models/test_exceptions.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.models.exceptions`

---

### Step 3.3: 实现异常类

**文件:** `src/models/exceptions.py`

```python
"""
应用异常定义

定义应用使用的异常层次结构
"""


class AppException(Exception):
    """应用基础异常"""

    def __init__(self, message: str, code: str = "APP_ERROR"):
        self.message = message
        self.code = code
        super().__init__(self.message)


class AuthenticationError(AppException):
    """API Key 认证失败"""

    def __init__(self, message: str = "Invalid API Key"):
        super().__init__(message, "AUTH_FAILED")


class SessionNotFoundError(AppException):
    """会话不存在"""

    def __init__(self, session_id: str):
        super().__init__(f"Session {session_id} not found", "SESSION_NOT_FOUND")


class LLMError(AppException):
    """LLM 调用失败"""

    def __init__(self, message: str):
        super().__init__(message, "LLM_ERROR")


class ToolExecutionError(AppException):
    """工具执行失败"""

    def __init__(self, tool_name: str, reason: str):
        super().__init__(f"Tool {tool_name} failed: {reason}", "TOOL_ERROR")
```

---

### Step 3.4: 运行测试验证通过

**命令:**
```bash
pytest tests/models/test_exceptions.py -v
```

**预期输出:** PASSED (5 tests)

---

### Step 3.5: 提交

```bash
git add tests/models/test_exceptions.py src/models/exceptions.py
git commit -m "feat: 添加异常类定义"
```

---

## Task 4: Redis 仓储层

**目标:** 实现 Redis 连接池和会话操作

**Files:**
- Create: `src/repositories/redis_repo.py`
- Test: `tests/repositories/test_redis_repo.py`

---

### Step 4.1: 写 Redis 仓储测试

**文件:** `tests/repositories/test_redis_repo.py`

```python
"""测试 Redis 仓储"""
import pytest
from datetime import datetime
from src.repositories.redis_repo import RedisRepository
from src.models.schemas import ChatMessage, ToolCall


@pytest.mark.asyncio
async def test_create_session(redis_repo: RedisRepository):
    """测试创建会话"""
    session_id = await redis_repo.create_session(user_id="user_123")
    assert session_id is not None
    assert len(session_id) == 36  # UUID format


@pytest.mark.asyncio
async def test_get_session_exists(redis_repo: RedisRepository):
    """测试获取存在的会话"""
    session_id = await redis_repo.create_session(user_id="user_123")
    session = await redis_repo.get_session(session_id)
    assert session is not None
    assert session["user_id"] == "user_123"


@pytest.mark.asyncio
async def test_get_session_not_exists(redis_repo: RedisRepository):
    """测试获取不存在的会话"""
    session = await redis_repo.get_session("non-existent-id")
    assert session is None


@pytest.mark.asyncio
async def test_add_message(redis_repo: RedisRepository):
    """测试添加消息"""
    session_id = await redis_repo.create_session(user_id="user_123")
    message = ChatMessage(
        role="user",
        content="测试消息",
        timestamp=datetime.now()
    )
    await redis_repo.add_message(session_id, message)

    messages = await redis_repo.get_messages(session_id)
    assert len(messages) == 1
    assert messages[0]["role"] == "user"
    assert messages[0]["content"] == "测试消息"


@pytest.mark.asyncio
async def test_get_messages_empty(redis_repo: RedisRepository):
    """测试获取空消息列表"""
    session_id = await redis_repo.create_session(user_id="user_123")
    messages = await redis_repo.get_messages(session_id)
    assert messages == []


@pytest.mark.asyncio
async def test_delete_session(redis_repo: RedisRepository):
    """测试删除会话"""
    session_id = await redis_repo.create_session(user_id="user_123")
    await redis_repo.delete_session(session_id)

    session = await redis_repo.get_session(session_id)
    assert session is None


@pytest.mark.asyncio
async def test_get_user_sessions(redis_repo: RedisRepository):
    """测试获取用户会话列表"""
    await redis_repo.create_session(user_id="user_123")
    await redis_repo.create_session(user_id="user_123")
    await redis_repo.create_session(user_id="user_456")

    sessions = await redis_repo.get_user_sessions("user_123")
    assert len(sessions) == 2
```

---

### Step 4.2: 运行测试验证失败

**命令:**
```bash
pytest tests/repositories/test_redis_repo.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.repositories.redis_repo`

---

### Step 4.3: 实现 Redis 仓储

**文件:** `src/repositories/redis_repo.py`

```python
"""
Redis 仓储层

处理 Redis 连接池和会话操作
"""

import json
from uuid import uuid4
from datetime import datetime
from typing import Optional
import redis.asyncio as aioredis

from src.config import settings
from src.models.schemas import ChatMessage
from src.models.exceptions import AppException


class RedisRepository:
    """Redis 仓储类"""

    def __init__(self):
        self._pool: Optional[aioredis.ConnectionPool] = None
        self._redis: Optional[aioredis.Redis] = None

    async def connect(self):
        """建立 Redis 连接"""
        self._pool = aioredis.ConnectionPool.from_url(
            settings.redis_url,
            db=settings.redis_db,
            max_connections=settings.redis_max_connections,
            decode_responses=True
        )
        self._redis = aioredis.Redis(connection_pool=self._pool)

    async def disconnect(self):
        """关闭 Redis 连接"""
        if self._redis:
            await self._redis.close()
        if self._pool:
            await self._pool.disconnect()

    @property
    def client(self) -> aioredis.Redis:
        """获取 Redis 客户端"""
        if self._redis is None:
            raise AppException("Redis 未连接")
        return self._redis

    async def create_session(self, user_id: str) -> str:
        """创建新会话"""
        session_id = str(uuid4())
        now = datetime.utcnow().isoformat()

        session_key = f"session:{session_id}"
        await self.client.hset(session_key, mapping={
            "user_id": user_id,
            "created_at": now,
            "updated_at": now,
            "message_count": "0"
        })

        # 添加到用户会话索引
        await self.client.sadd(f"user:sessions:{user_id}", session_id)

        return session_id

    async def get_session(self, session_id: str) -> Optional[dict]:
        """获取会话信息"""
        session_key = f"session:{session_id}"
        data = await self.client.hgetall(session_key)
        if not data:
            return None
        return data

    async def update_session(self, session_id: str, **kwargs):
        """更新会话信息"""
        session_key = f"session:{session_id}"
        if kwargs:
            await self.client.hset(session_key, mapping=kwargs)
        await self.client.hset(session_key, "updated_at", datetime.utcnow().isoformat())

    async def add_message(self, session_id: str, message: ChatMessage):
        """添加消息到会话"""
        messages_key = f"messages:{session_id}"
        message_data = {
            "role": message.role,
            "content": message.content or "",
            "timestamp": message.timestamp.isoformat(),
        }
        if message.tool_calls:
            message_data["tool_calls"] = [
                {"name": tc.name, "arguments": tc.arguments, "result": tc.result}
                for tc in message.tool_calls
            ]

        await self.client.rpush(messages_key, json.dumps(message_data))

        # 更新消息计数
        await self.client.hincrby(f"session:{session_id}", "message_count", 1)

    async def get_messages(self, session_id: str, limit: int = 100) -> list:
        """获取会话消息"""
        messages_key = f"messages:{session_id}"
        messages = await self.client.lrange(messages_key, 0, limit - 1)
        return [json.loads(msg) for msg in messages]

    async def delete_session(self, session_id: str):
        """删除会话"""
        session = await self.get_session(session_id)
        if not session:
            return

        user_id = session["user_id"]

        # 删除会话和消息
        await self.client.delete(f"session:{session_id}")
        await self.client.delete(f"messages:{session_id}")

        # 从用户索引中移除
        await self.client.srem(f"user:sessions:{user_id}", session_id)

    async def get_user_sessions(self, user_id: str) -> list:
        """获取用户的所有会话"""
        return await self.client.smembers(f"user:sessions:{user_id}")


# 全局实例
redis_repo = RedisRepository()
```

---

### Step 4.4: 添加 pytest fixture

**文件:** `tests/conftest.py`

```python
"""pytest 配置和 fixtures"""
import pytest
import asyncio
from src.repositories.redis_repo import RedisRepository


@pytest.fixture(scope="session")
def event_loop():
    """创建事件循环"""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
async def redis_repo():
    """Redis 仓储 fixture"""
    from src.config import settings

    # 使用测试数据库
    original_db = settings.redis_db
    settings.redis_db = 15  # 使用测试 DB

    repo = RedisRepository()
    await repo.connect()

    yield repo

    # 清理
    await repo.client.flushdb()
    await repo.disconnect()

    settings.redis_db = original_db
```

---

### Step 4.5: 运行测试验证通过

**命令:**
```bash
pytest tests/repositories/test_redis_repo.py -v
```

**预期输出:** PASSED (8 tests)

---

### Step 4.6: 提交

```bash
git add tests/repositories/test_redis_repo.py tests/conftest.py src/repositories/redis_repo.py
git commit -m "feat: 实现 Redis 仓储层 - 会话管理"
```

---

## Task 5: PostgreSQL 仓储层

**目标:** 实现 PostgreSQL 连接和持久化操作

**Files:**
- Create: `src/repositories/pg_repo.py`
- Create: `src/repositories/migrations/001_init.sql`
- Test: `tests/repositories/test_pg_repo.py`

---

### Step 5.1: 创建数据库迁移脚本

**文件:** `src/repositories/migrations/001_init.sql`

```sql
-- 初始化数据库表

-- 会话表
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    message_count INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON chat_sessions(user_id);

-- 消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_session_id ON chat_messages(session_id);
```

---

### Step 5.2: 写 PostgreSQL 仓储测试

**文件:** `tests/repositories/test_pg_repo.py`

```python
"""测试 PostgreSQL 仓储"""
import pytest
from datetime import datetime
from src.repositories.pg_repo import PostgreSQLRepository
from src.models.schemas import ChatMessage


@pytest.mark.asyncio
async def test_save_session(pg_repo: PostgreSQLRepository):
    """测试保存会话"""
    session_id = "test-session-1"
    await pg_repo.save_session(
        session_id=session_id,
        user_id="user_123",
        message_count=0
    )

    session = await pg_repo.get_session(session_id)
    assert session is not None
    assert session["user_id"] == "user_123"


@pytest.mark.asyncio
async def test_save_message(pg_repo: PostgreSQLRepository):
    """测试保存消息"""
    session_id = "test-session-2"
    await pg_repo.save_session(
        session_id=session_id,
        user_id="user_123",
        message_count=0
    )

    message = ChatMessage(
        role="user",
        content="测试消息",
        timestamp=datetime.now()
    )
    await pg_repo.save_message(session_id, message)

    messages = await pg_repo.get_messages(session_id)
    assert len(messages) == 1
    assert messages[0]["content"] == "测试消息"


@pytest.mark.asyncio
async def test_get_user_sessions(pg_repo: PostgreSQLRepository):
    """测试获取用户会话"""
    await pg_repo.save_session("session-1", "user_123", 0)
    await pg_repo.save_session("session-2", "user_123", 0)
    await pg_repo.save_session("session-3", "user_456", 0)

    sessions = await pg_repo.get_user_sessions("user_123")
    assert len(sessions) == 2
```

---

### Step 5.3: 运行测试验证失败

**命令:**
```bash
pytest tests/repositories/test_pg_repo.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.repositories.pg_repo`

---

### Step 5.4: 实现 PostgreSQL 仓储

**文件:** `src/repositories/pg_repo.py`

```python
"""
PostgreSQL 仓储层

处理 PostgreSQL 连接和持久化操作
"""

from uuid import uuid4
from datetime import datetime
from typing import Optional
import asyncpg

from src.config import settings
from src.models.schemas import ChatMessage
from src.models.exceptions import AppException


class PostgreSQLRepository:
    """PostgreSQL 仓储类"""

    def __init__(self):
        self._pool: Optional[asyncpg.Pool] = None

    async def connect(self):
        """建立数据库连接池"""
        self._pool = await asyncpg.create_pool(
            settings.get_pg_dsn(),
            min_size=5,
            max_size=20
        )

    async def disconnect(self):
        """关闭数据库连接"""
        if self._pool:
            await self._pool.close()

    @property
    def pool(self) -> asyncpg.Pool:
        """获取连接池"""
        if self._pool is None:
            raise AppException("PostgreSQL 未连接")
        return self._pool

    async def save_session(self, session_id: str, user_id: str, message_count: int = 0):
        """保存会话"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO chat_sessions (id, user_id, message_count)
                VALUES ($1, $2, $3)
                ON CONFLICT (id) DO UPDATE
                SET updated_at = NOW(), message_count = $3
                """,
                session_id, user_id, message_count
            )

    async def get_session(self, session_id: str) -> Optional[dict]:
        """获取会话"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM chat_sessions WHERE id = $1",
                session_id
            )
            return dict(row) if row else None

    async def save_message(self, session_id: str, message: ChatMessage):
        """保存消息"""
        message_id = str(uuid4())
        tool_calls_json = None
        if message.tool_calls:
            tool_calls_json = [
                {"name": tc.name, "arguments": tc.arguments, "result": tc.result}
                for tc in message.tool_calls
            ]

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """
                    INSERT INTO chat_messages (id, session_id, role, content, tool_calls)
                    VALUES ($1, $2, $3, $4, $5)
                    """,
                    message_id, session_id, message.role, message.content, tool_calls_json
                )
                # 更新会话消息计数
                await conn.execute(
                    """
                    UPDATE chat_sessions
                    SET message_count = message_count + 1, updated_at = NOW()
                    WHERE id = $1
                    """,
                    session_id
                )

    async def get_messages(self, session_id: str, limit: int = 100) -> list:
        """获取会话消息"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM chat_messages
                WHERE session_id = $1
                ORDER BY created_at ASC
                LIMIT $2
                """,
                session_id, limit
            )
            return [dict(row) for row in rows]

    async def get_user_sessions(self, user_id: str, limit: int = 50) -> list:
        """获取用户会话列表"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM chat_sessions
                WHERE user_id = $1
                ORDER BY updated_at DESC
                LIMIT $2
                """,
                user_id, limit
            )
            return [dict(row) for row in rows]


# 全局实例
pg_repo = PostgreSQLRepository()
```

---

### Step 5.5: 添加 PostgreSQL fixture

**文件:** `tests/conftest.py` (追加)

```python
@pytest.fixture
async def pg_repo():
    """PostgreSQL 仓储 fixture"""
    import os
    from src.config import settings

    # 使用测试数据库
    original_db = settings.pg_database
    test_db = f"{original_db}_test"

    # 连接到默认数据库创建测试数据库
    temp_dsn = settings.get_pg_dsn().replace(original_db, "postgres")
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.execute(f"CREATE DATABASE {test_db}")
    await conn.close()

    # 更新 DSN
    settings.pg_database = test_db

    repo = PostgreSQLRepository()
    await repo.connect()

    # 运行迁移
    migration_path = "src/repositories/migrations/001_init.sql"
    with open(migration_path) as f:
        migration_sql = f.read()
    async with repo.pool.acquire() as conn:
        await conn.execute(migration_sql)

    yield repo

    # 清理
    await repo.disconnect()
    settings.pg_database = original_db

    # 删除测试数据库
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.close()
```

---

### Step 5.6: 运行测试验证通过

**命令:**
```bash
pytest tests/repositories/test_pg_repo.py -v
```

**预期输出:** PASSED (3 tests)

---

### Step 5.7: 提交

```bash
git add tests/repositories/test_pg_repo.py tests/conftest.py src/repositories/pg_repo.py src/repositories/migrations/
git commit -m "feat: 实现 PostgreSQL 仓储层 - 数据持久化"
```

---

## Task 6: 对话服务层

**目标:** 实现核心对话逻辑、LLM 流式调用

**Files:**
- Create: `src/services/chat_service.py`
- Test: `tests/services/test_chat_service.py`

---

### Step 6.1: 写对话服务测试

**文件:** `tests/services/test_chat_service.py`

```python
"""测试对话服务"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from src.services.chat_service import ChatService
from src.models.schemas import ChatRequest, ChatMessage


@pytest.mark.asyncio
async def test_chat_stream_new_session(chat_service: ChatService):
    """测试流式对话 - 新会话"""
    request = ChatRequest(message="你好")

    events = []
    async for event in chat_service.chat_stream(request, user_id="user_123"):
        events.append(event)

    assert events[0].type == "start"
    assert events[0].session_id is not None
    assert any(e.type == "end" for e in events)


@pytest.mark.asyncio
async def test_chat_stream_existing_session(chat_service: ChatService):
    """测试流式对话 - 已有会话"""
    # 先创建会话
    from src.repositories.redis_repo import redis_repo
    session_id = await redis_repo.create_session(user_id="user_123")

    request = ChatRequest(session_id=session_id, message="你好")

    events = []
    async for event in chat_service.chat_stream(request, user_id="user_123"):
        events.append(event)

    assert events[0].type == "start"
    assert events[0].session_id == session_id


@pytest.mark.asyncio
async def test_get_session_history(chat_service: ChatService):
    """测试获取会话历史"""
    from src.repositories.redis_repo import redis_repo
    session_id = await redis_repo.create_session(user_id="user_123")

    history = await chat_service.get_session_history(session_id)
    assert isinstance(history, list)
```

---

### Step 6.2: 运行测试验证失败

**命令:**
```bash
pytest tests/services/test_chat_service.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.services.chat_service`

---

### Step 6.3: 实现对话服务

**文件:** `src/services/chat_service.py`

```python
"""
对话服务层

处理对话逻辑、LLM 调用和消息管理
"""

from typing import AsyncIterator
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage, SystemMessage

from src.config import settings, get_llm_config
from src.models.schemas import ChatRequest, ChatResponse, ChatMessage
from src.models.exceptions import SessionNotFoundError, LLMError
from src.repositories.redis_repo import redis_repo


class ChatService:
    """对话服务类"""

    def __init__(self):
        self.llm = ChatOpenAI(
            **get_llm_config(),
            streaming=True,
            temperature=0.7
        )

    async def chat_stream(
        self,
        request: ChatRequest,
        user_id: str
    ) -> AsyncIterator[ChatResponse]:
        """流式对话处理"""
        session_id = request.session_id

        # 获取或创建会话
        if session_id:
            session = await redis_repo.get_session(session_id)
            if not session:
                raise SessionNotFoundError(session_id)
        else:
            session_id = await redis_repo.create_session(user_id)

        # 保存用户消息
        user_message = ChatMessage(role="user", content=request.message)
        await redis_repo.add_message(session_id, user_message)

        # 获取历史消息
        history = await redis_repo.get_messages(session_id)
        lc_messages = self._convert_to_langchain_messages(history + [
            {"role": "user", "content": request.message, "timestamp": user_message.timestamp.isoformat()}
        ])

        # 发送开始事件
        yield ChatResponse.start(session_id=session_id)

        try:
            # 流式调用 LLM
            full_response = ""
            async for chunk in self.llm.astream(lc_messages):
                if hasattr(chunk, 'content') and chunk.content:
                    full_response += chunk.content
                    yield ChatResponse.token(content=chunk.content)

            # 保存助手回复
            assistant_message = ChatMessage(role="assistant", content=full_response)
            await redis_repo.add_message(session_id, assistant_message)

            # 更新会话
            await redis_repo.update_session(session_id)

            yield ChatResponse.end()

        except Exception as e:
            raise LLMError(f"LLM 调用失败: {str(e)}")

    async def get_session_history(self, session_id: str) -> list[ChatMessage]:
        """获取会话历史"""
        session = await redis_repo.get_session(session_id)
        if not session:
            raise SessionNotFoundError(session_id)

        messages_data = await redis_repo.get_messages(session_id)
        return [
            ChatMessage(
                role=msg["role"],
                content=msg.get("content"),
                timestamp=msg["timestamp"]
            )
            for msg in messages_data
        ]

    def _convert_to_langchain_messages(self, messages: list) -> list:
        """转换为 LangChain 消息格式"""
        lc_messages = []
        for msg in messages:
            role = msg["role"]
            content = msg.get("content", "")

            if role == "user":
                lc_messages.append(HumanMessage(content=content))
            elif role == "assistant":
                lc_messages.append(AIMessage(content=content))
            elif role == "system":
                lc_messages.append(SystemMessage(content=content))

        return lc_messages


# 全局实例
chat_service = ChatService()
```

---

### Step 6.4: 添加对话服务 fixture

**文件:** `tests/conftest.py` (追加)

```python
@pytest.fixture
async def chat_service(redis_repo):
    """对话服务 fixture"""
    from unittest.mock import AsyncMock, patch
    from src.services.chat_service import ChatService

    service = ChatService()

    # Mock LLM response
    async def mock_astream(messages):
        from langchain_core.messages import AIMessage
        yield AIMessage(content="你好！")

    service.llm.astream = mock_astream

    yield service
```

---

### Step 6.5: 运行测试验证通过

**命令:**
```bash
pytest tests/services/test_chat_service.py -v
```

**预期输出:** PASSED (3 tests)

---

### Step 6.6: 提交

```bash
git add tests/services/test_chat_service.py tests/conftest.py src/services/chat_service.py
git commit -m "feat: 实现对话服务层 - LLM 流式调用"
```

---

## Task 7: FastAPI 应用和路由

**目标:** 实现 FastAPI 应用和 SSE 路由

**Files:**
- Create: `src/api/main.py`
- Create: `src/api/routes/chat.py`
- Create: `src/api/dependencies.py`
- Test: `tests/api/test_chat_routes.py`

---

### Step 7.1: 写路由测试

**文件:** `tests/api/test_chat_routes.py`

```python
"""测试聊天路由"""
import pytest
from httpx import AsyncClient, ASGITransport
from src.api.main import app


@pytest.mark.asyncio
async def test_health_check():
    """测试健康检查"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_chat_stream_with_valid_api_key():
    """测试流式对话 - 有效 API Key"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"},
            headers={"X-API-Key": "test-key"}
        )
        assert response.status_code == 200
        assert "text/event-stream" in response.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_chat_stream_without_api_key():
    """测试流式对话 - 无 API Key"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"}
        )
        assert response.status_code == 401


@pytest.mark.asyncio
async def test_chat_stream_invalid_api_key():
    """测试流式对话 - 无效 API Key"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"},
            headers={"X-API-Key": "invalid-key"}
        )
        assert response.status_code == 401
```

---

### Step 7.2: 运行测试验证失败

**命令:**
```bash
pytest tests/api/test_chat_routes.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.api.main`

---

### Step 7.3: 实现依赖注入

**文件:** `src/api/dependencies.py`

```python
"""
API 依赖注入

处理 API Key 验证和请求上下文
"""

from fastapi import Header, HTTPException, status
from src.config import settings
from src.models.exceptions import AuthenticationError


async def verify_api_key(x_api_key: str = Header(..., alias="X-API-Key")) -> str:
    """验证 API Key"""
    if not settings.validate_api_key(x_api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API Key"
        )
    return x_api_key


async def get_user_id(x_api_key: str = Header(..., alias="X-API-Key")) -> str:
    """从 API Key 获取用户 ID（简化版，实际应从数据库或 JWT 解析）"""
    if not settings.validate_api_key(x_api_key):
        raise AuthenticationError()

    # 简化实现：使用 API Key 作为 user_id
    # 生产环境应使用用户系统
    return f"user:{x_api_key[:8]}"
```

---

### Step 7.4: 实现聊天路由

**文件:** `src/api/routes/chat.py`

```python
"""
聊天路由

处理流式对话 API 端点
"""

from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import StreamingResponse
from typing import AsyncIterator

from src.api.dependencies import get_user_id
from src.models.schemas import ChatRequest, ChatResponse
from src.services.chat_service import chat_service
from src.models.exceptions import AppException

router = APIRouter(prefix="/api/v1/chat", tags=["chat"])


async def sse_generator(events: AsyncIterator[ChatResponse]) -> AsyncIterator[str]:
    """SSE 事件生成器"""
    try:
        async for event in events:
            yield event.to_sse()
    except AppException as e:
        yield ChatResponse.error(e.code, e.message).to_sse()


@router.post("/stream")
async def chat_stream(
    request: ChatRequest,
    user_id: str = Depends(get_user_id)
):
    """流式对话端点"""
    return StreamingResponse(
        sse_generator(chat_service.chat_stream(request, user_id)),
        media_type="text/event-stream"
    )


@router.get("/history/{session_id}")
async def get_history(
    session_id: str,
    user_id: str = Depends(get_user_id)
):
    """获取会话历史"""
    history = await chat_service.get_session_history(session_id)
    return {"session_id": session_id, "messages": history}


@router.delete("/sessions/{session_id}")
async def delete_session(
    session_id: str,
    user_id: str = Depends(get_user_id)
):
    """删除会话"""
    from src.repositories.redis_repo import redis_repo
    session = await redis_repo.get_session(session_id)

    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    if session["user_id"] != user_id:
        raise HTTPException(status_code=403, detail="Forbidden")

    await redis_repo.delete_session(session_id)
    return {"message": "Session deleted"}
```

---

### Step 7.5: 实现 FastAPI 主应用

**文件:** `src/api/main.py`

```python
"""
FastAPI 应用入口

初始化应用和路由
"""

from contextlib import asynccontextmanager
from fastapi import FastAPI

from src.api.routes.chat import router as chat_router
from src.repositories.redis_repo import redis_repo
from src.repositories.pg_repo import pg_repo
from src.config import settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    # 启动
    await redis_repo.connect()
    await pg_repo.connect()
    yield
    # 关闭
    await redis_repo.disconnect()
    await pg_repo.disconnect()


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    lifespan=lifespan
)

app.include_router(chat_router)


@app.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "app": settings.app_name,
        "version": settings.app_version
    }
```

---

### Step 7.6: 添加测试环境配置

**文件:** `tests/conftest.py` (追加)

```python
@pytest.fixture(autouse=True)
async def setup_test_environment():
    """设置测试环境"""
    from src.config import settings
    from src.api.main import app

    # 设置测试 API Key
    settings.api_keys = ["test-key"]

    yield

    settings.api_keys = []
```

---

### Step 7.7: 运行测试验证通过

**命令:**
```bash
pytest tests/api/test_chat_routes.py -v
```

**预期输出:** PASSED (4 tests)

---

### Step 7.8: 提交

```bash
git add tests/api/test_chat_routes.py tests/conftest.py src/api/
git commit -m "feat: 实现 FastAPI 应用和聊天路由"
```

---

## Task 8: 异步同步服务

**目标:** 实现 Redis 到 PostgreSQL 的异步同步

**Files:**
- Create: `src/services/sync_service.py`
- Test: `tests/services/test_sync_service.py`

---

### Step 8.1: 写同步服务测试

**文件:** `tests/services/test_sync_service.py`

```python
"""测试同步服务"""
import pytest
from src.services.sync_service import SyncService


@pytest.mark.asyncio
async def test_sync_session_to_pg(sync_service: SyncService, redis_repo, pg_repo):
    """测试同步会话到 PostgreSQL"""
    from src.models.schemas import ChatMessage
    from datetime import datetime

    # 创建会话和消息
    session_id = await redis_repo.create_session(user_id="user_123")
    message = ChatMessage(role="user", content="测试消息", timestamp=datetime.now())
    await redis_repo.add_message(session_id, message)

    # 同步
    await sync_service.sync_session(session_id)

    # 验证
    pg_session = await pg_repo.get_session(session_id)
    assert pg_session is not None
    assert pg_session["user_id"] == "user_123"
```

---

### Step 8.2: 运行测试验证失败

**命令:**
```bash
pytest tests/services/test_sync_service.py -v
```

**预期输出:** FAILED - `ModuleNotFoundError: src.services.sync_service`

---

### Step 8.3: 实现同步服务

**文件:** `src/services/sync_service.py`

```python
"""
同步服务层

处理 Redis 到 PostgreSQL 的异步同步
"""

import asyncio
from typing import Optional

from src.repositories.redis_repo import redis_repo
from src.repositories.pg_repo import pg_repo
from src.models.schemas import ChatMessage


class SyncService:
    """同步服务类"""

    async def sync_session(self, session_id: str):
        """同步单个会话到 PostgreSQL"""
        # 获取 Redis 会话
        session = await redis_repo.get_session(session_id)
        if not session:
            return

        # 保存会话
        await pg_repo.save_session(
            session_id=session_id,
            user_id=session["user_id"],
            message_count=int(session.get("message_count", 0))
        )

        # 获取并保存消息
        messages_data = await redis_repo.get_messages(session_id)
        for msg_data in messages_data:
            message = ChatMessage(
                role=msg_data["role"],
                content=msg_data.get("content"),
                timestamp=msg_data["timestamp"]
            )
            await pg_repo.save_message(session_id, message)

    async def sync_user_sessions(self, user_id: str):
        """同步用户所有会话"""
        session_ids = await redis_repo.get_user_sessions(user_id)
        for session_id in session_ids:
            await self.sync_session(session_id)

    async def start_background_sync(self, interval: int = 300):
        """启动后台同步任务"""
        while True:
            try:
                # 获取所有需要同步的会话
                # 简化实现：同步最近更新的会话
                await asyncio.sleep(interval)
            except asyncio.CancelledError:
                break


# 全局实例
sync_service = SyncService()
```

---

### Step 8.4: 添加同步服务 fixture

**文件:** `tests/conftest.py` (追加)

```python
@pytest.fixture
async def sync_service(redis_repo, pg_repo):
    """同步服务 fixture"""
    from src.services.sync_service import SyncService
    return SyncService()
```

---

### Step 8.5: 运行测试验证通过

**命令:**
```bash
pytest tests/services/test_sync_service.py -v
```

**预期输出:** PASSED (1 test)

---

### Step 8.6: 提交

```bash
git add tests/services/test_sync_service.py tests/conftest.py src/services/sync_service.py
git commit -m "feat: 实现异步同步服务 - Redis 到 PostgreSQL"
```

---

## Task 9: 集成测试

**目标:** 端到端测试流式对话

**Files:**
- Create: `tests/integration/test_e2e_chat.py`

---

### Step 9.1: 写端到端测试

**文件:** `tests/integration/test_e2e_chat.py`

```python
"""端到端集成测试"""
import pytest
from httpx import AsyncClient, ASGITransport
from src.api.main import app


@pytest.mark.asyncio
async def test_full_conversation_flow():
    """测试完整对话流程"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. 开始对话
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好，我是小明"},
            headers={"X-API-Key": "test-key"}
        )

        assert response.status_code == 200
        assert "text/event-stream" in response.headers.get("content-type", "")

        # 2. 解析 SSE 事件
        events = []
        async for line in response.aiter_lines():
            if line.startswith("data: "):
                import json
                data = json.loads(line[6:])
                events.append(data)
                if data.get("type") == "end":
                    break

        # 3. 验证事件序列
        assert events[0]["type"] == "start"
        assert events[0]["session_id"] is not None

        session_id = events[0]["session_id"]

        # 4. 获取历史
        history_response = await client.get(
            f"/api/v1/chat/history/{session_id}",
            headers={"X-API-Key": "test-key"}
        )
        assert history_response.status_code == 200
        history = history_response.json()
        assert len(history["messages"]) >= 2  # user + assistant
```

---

### Step 9.2: 运行测试验证通过

**命令:**
```bash
pytest tests/integration/test_e2e_chat.py -v
```

**预期输出:** PASSED (1 test)

---

### Step 9.3: 提交

```bash
git add tests/integration/test_e2e_chat.py
git commit -m "test: 添加端到端集成测试"
```

---

## Task 10: 更新入口文件

**目标:** 更新 main.py 启动脚本

**Files:**
- Modify: `main.py`

---

### Step 10.1: 更新 main.py

**文件:** `main.py`

```python
"""
YIBCCC LangChain Agent 主入口

启动 FastAPI 服务器
"""

import uvicorn
from src.api.main import app
from src.config import settings


def main():
    """启动服务器"""
    uvicorn.run(
        "src.api.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.debug,
        log_level="info"
    )


if __name__ == "__main__":
    main()
```

---

### Step 10.2: 提交

```bash
git add main.py
git commit -m "feat: 更新启动脚本"
```

---

## 完成检查清单

- [ ] 所有测试通过: `pytest -v`
- [ ] 代码格式检查: `ruff check src/`
- [ ] 类型检查: `ruff check --select I src/`
- [ ] 手动测试 SSE 流式输出
- [ ] 验证 Redis 数据存储
- [ ] 验证 PostgreSQL 持久化

---

## 后续优化建议

1. **工具调用支持**: 在 ChatService 中添加 LangChain Agent 和工具调用
2. **限流和配额**: 添加基于 API Key 的请求限流
3. **监控**: 添加 Prometheus 指标和日志
4. **WebSocket 考虑**: 评估是否需要 WebSocket 作为 SSE 的替代
5. **批量同步**: 优化同步服务，支持批量操作减少数据库往返
