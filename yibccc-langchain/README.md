# YIBCCC LangChain Agent

> 基于 LangChain 1.0.0 `create_agent` 的流式对话 API 服务

[![Python](https://img.shields.io/badge/Python-3.11+-blue.svg)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115+-green.svg)](https://fastapi.tiangolo.com/)
[![LangChain](https://img.shields.io/badge/LangChain-1.0.0+-red.svg)](https://github.com/langchain-ai/langchain)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 项目简介

本项目使用 LangChain 1.0.0 的 `create_agent()` 函数构建智能对话 Agent 服务，采用 **冷热分离架构**：
- **热数据 (Redis)**: 使用 LangGraph AsyncRedisSaver 实时存储对话状态
- **冷数据 (PostgreSQL)**: 通过 Redis Stream 异步同步持久化

### 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| Web 框架 | FastAPI | >= 0.115.0 |
| LLM 编排 | LangChain | >= 1.0.0 |
| Agent 编排 | LangGraph | >= 0.2.0 |
| Checkpoint 存储 | AsyncRedisSaver | >= 0.1.0 |
| 向量存储 | PostgreSQL + PGVector | >= 0.29.0 |
| 缓存/队列 | Redis | >= 5.0.0 |
| LLM 提供商 | 通义千问 API | OpenAI 兼容 |
| Embedding 提供商 | 通义千问 API | text-embedding-v3 |
| Python 版本 | | >= 3.11 |

### 架构特性

- **SSE 流式响应**: 支持服务端推送实时对话流
- **冷热分离**: Redis 热数据 + PostgreSQL 冷数据
- **异步解耦**: Redis Stream 实现异步数据同步，不阻塞对话流程
- **增量同步**: 只同步新增消息到 PostgreSQL，避免重复
- **容错机制**: Redis 不可用时降级到 MemorySaver
- **工具调用**: 支持动态工具注册和调用
- **知识库 RAG**: 基于 LangChain PGVector 的语义检索和诊断分析

## 快速开始

### 环境要求

- Python >= 3.11
- PostgreSQL >= 14
- Redis >= 6.0
- uv 包管理器（推荐）

### 安装依赖

```bash
# 使用 uv 安装依赖
uv pip install -e .

# 或使用 pip
pip install -e .
```

### 数据库初始化

```bash
# 创建数据库
createdb yibccc_agent

# 启用 pgvector 扩展
psql yibccc_agent -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 执行初始化脚本
psql yibccc_agent < docs/sql/schema.sql
```

**注意**: 知识库向量存储由 LangChain PGVector 自动管理，首次使用时会自动创建 `langchain_pg_*` 表。

### 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件：

```env
# DeepSeek API 配置
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_MODEL=deepseek-chat

# Redis 配置
REDIS_URL=redis://localhost:6379/0
REDIS_DB=0
REDIS_MAX_CONNECTIONS=50

# Redis Stream 同步配置
ENABLE_SYNC=true
SYNC_WORKERS=3
SYNC_STREAM_NAME=checkpoint_sync_stream
SYNC_CONSUMER_GROUP=checkpoint_consumers

# PostgreSQL 配置
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=yibccc_agent
POSTGRES_USER=postgres
POSTGRES_PASSWORD=password

# API 认证 (逗号分隔多个有效 Key)
API_KEYS=your_api_key_1,your_api_key_2
```

### 运行服务

```bash
# 启动 API 服务
python main.py

# 或使用 uvicorn 直接启动
uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000
```

服务将在 `http://localhost:8000` 启动。

### 运行同步服务（独立进程）

```bash
python -m src.services.sync_service
```

### 运行知识导入消费者（独立进程）

```bash
python -m src.services.knowledge_import_consumer
```

## 项目结构

```
yibccc-langchain/
├── src/
│   ├── api/                      # API 层
│   │   ├── main.py              # FastAPI 应用入口
│   │   ├── routes/
│   │   │   └── chat.py         # 对话路由 (SSE 流式端点)
│   │   └── dependencies.py      # 依赖注入 (API Key 认证)
│   ├── services/                 # 服务层
│   │   ├── chat_service.py      # 对话服务 (基于 create_agent)
│   │   └── sync_service.py      # 同步服务 (Redis Stream 消费者)
│   ├── repositories/            # 数据访问层
│   │   └── pg_repo.py          # PostgreSQL 仓储
│   ├── models/                 # 数据模型
│   │   ├── schemas.py          # Pydantic 请求/响应模型
│   │   └── exceptions.py       # 自定义异常
│   ├── tools/                  # 工具函数
│   │   ├── __init__.py
│   │   └── builtin.py          # 内置工具 (时间查询等)
│   ├── agents/                 # Agent 定义
│   │   ├── __init__.py
│   │   └── base.py            # 基础 LLM 创建
│   ├── prompts/                # 提示词模板
│   │   └── __init__.py
│   └── config.py              # 配置管理
├── tests/                      # 测试目录
│   ├── api/
│   ├── services/
│   ├── repositories/
│   ├── models/
│   └── integration/
├── docs/                       # 项目文档
│   ├── detailed-design/         # 详细设计文档
│   ├── plans/                  # 实施计划
│   ├── sql/                    # 数据库脚本
│   └── interview/              # 面试资料
├── sql/
│   └── schema.sql              # 数据库表结构
├── pyproject.toml              # 项目依赖和配置
├── .env.example               # 环境变量模板
└── main.py                    # 启动入口
```

## API 端点

### 健康检查

```http
GET /health
```

**响应**:
```json
{
  "status": "healthy",
  "redis": "connected",
  "postgres": "connected"
}
```

### 流式对话

```http
POST /api/v1/chat/stream
Content-Type: application/json
Authorization: Bearer your_api_key

{
  "session_id": "user-session-123",
  "message": "你好，请介绍一下你自己",
  "model": "deepseek-chat",
  "temperature": 0.7
}
```

**响应**: Server-Sent Events (SSE) 流

```
event: message
data: {"role": "assistant", "content": "你好！我是..."}

event: message
data: {"role": "assistant", "content": "基于 LangChain 构建..."}

event: done
data: {"session_id": "user-session-123", "message_id": "msg-456"}
```

## 开发指南

### 运行测试

```bash
# 安装测试依赖
uv pip install -e ".[dev]"

# 运行所有测试
pytest tests/

# 运行特定测试
pytest tests/services/test_chat_service.py

# 查看覆盖率
pytest tests/ --cov=src --cov-report=html
```

### 代码风格

项目使用 ruff 进行代码检查：

```bash
# 检查代码风格
ruff check src/

# 自动修复
ruff check --fix src/

# 格式化代码
ruff format src/
```

### 注册新工具

在 [src/tools/builtin.py](src/tools/builtin.py) 中添加工具函数：

```python
from langchain_core.tools import tool

@tool
def my_custom_tool(query: str) -> str:
    """工具描述，Agent 会根据此描述选择调用。"""
    # 工具实现
    return f"结果: {query}"
```

工具会自动注册到 Agent 中。

## 数据库架构

### 核心表

| 表名 | 描述 |
|------|------|
| `chat_sessions` | 会话元信息 (session_id, 创建时间等) |
| `chat_messages` | 消息历史 (角色、内容、时间戳) |
| `langchain_checkpoints` | LangGraph checkpoint 备份 |
| `langchain_pg_collection` | PGVector 集合元数据 (自动创建) |
| `langchain_pg_embedding` | PGVector 向量存储 (自动创建) |
| `knowledge_documents` | 知识库文档元数据 |
| `diagnosis_tasks` | 诊断任务 |
| `diagnosis_results` | 诊断结果 |

### 数据流

```
用户请求 → FastAPI → ChatService
                ↓
         LangGraph Agent (AsyncRedisSaver)
                ↓
         Redis (热数据，实时读写)
                ↓
         Redis Stream (异步触发)
                ↓
         SyncService (消费者)
                ↓
         PostgreSQL (冷数据，持久化)
```

## 文档

- [详细设计文档](docs/detailed-design/streaming-chat-api.md) - 架构、API、数据流
- [数据库设计](docs/detailed-design/database.md) - 表结构、索引、关系
- [面试资料](docs/interview/) - 冷热分离架构话术

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

---

**创建时间**: 2026-01-11
**维护者**: yibccc
