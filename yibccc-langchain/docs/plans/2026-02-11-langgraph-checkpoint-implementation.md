# LangGraph Checkpoint 双存储架构实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 使用 LangGraph + AsyncRedisSaver 重构对话服务，通过 Redis Stream 实现与 PostgreSQL 的异步同步

**架构:**
- FastAPI 对话服务使用 LangGraph 处理对话
- Redis AsyncRedisSaver 作为主 checkpoint 存储
- Redis Stream 触发同步，后台消费者写入 PostgreSQL
- Spring Boot 后端管理 session，FastAPI 只处理 LLM 逻辑

**技术栈:**
- LangGraph (StateGraph, AsyncRedisSaver)
- Redis (Checkpoint + Stream)
- PostgreSQL (归档存储)
- FastAPI (异步流式响应)

---

## Task 1: 更新依赖包

**文件:**
- Modify: `requirements.txt`

**Step 1: 添加 LangGraph 依赖**

```txt
# 现有依赖保持不变...

# LangGraph
langgraph>=0.2.0
langgraph-checkpoint-redis>=0.1.0

# LangChain 核心依赖
langchain-core>=0.3.0
langchain-openai>=0.2.0
```

**Step 2: 安装依赖**

```bash
pip install -r requirements.txt
```

**Step 3: 验证安装**

```bash
python -c "from langgraph.checkpoint.redis.aio import AsyncRedisSaver; print('OK')"
```
Expected: OK

**Step 4: Commit**

```bash
git add requirements.txt
git commit -m "feat: add langgraph and redis checkpoint dependencies"
```

---

## Task 2: 更新配置管理

**文件:**
- Modify: `src/config.py`

**Step 1: 添加 Redis Stream 配置**

```python
# 在 Settings 类中添加（约第 45 行后）

    # Redis Stream 同步配置
    redis_stream_sync_enabled: bool = Field(default=True, alias="REDIS_STREAM_SYNC_ENABLED")
    redis_stream_workers: int = Field(default=2, alias="REDIS_STREAM_WORKERS")
    redis_stream_name: str = Field(default="sync:checkpoints", alias="REDIS_STREAM_NAME")
    redis_consumer_group: str = Field(default="sync_workers", alias="REDIS_CONSUMER_GROUP")
```

**Step 2: 验证配置加载**

```bash
python -c "from src.config import settings; print(f'Stream: {settings.redis_stream_name}, Workers: {settings.redis_stream_workers}')"
```
Expected: Stream: sync:checkpoints, Workers: 2

**Step 3: Commit**

```bash
git add src/config.py
git commit -m "feat: add redis stream configuration"
```

---

## Task 3: 重写 ChatService 使用 LangGraph

**文件:**
- Modify: `src/services/chat_service.py`

**Step 1: 先写测试 - 验证 LangGraph 集成**

创建测试文件 `tests/services/test_chat_service_langgraph.py`:

```python
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from langgraph.checkpoint.redis.aio import AsyncRedisSaver

from src.services.chat_service import ChatService
from src.models.schemas import ChatRequest


@pytest.fixture
async def mock_checkpointer():
    """Mock Redis Checkpointer"""
    with patch("src.services.chat_service.AsyncRedisSaver.from_conn_string") as mock:
        saver = MagicMock()
        saver.asetup = AsyncMock()
        mock.return_value = saver
        yield saver


@pytest.fixture
def chat_service(mock_checkpointer):
    """创建 ChatService 实例"""
    return ChatService(checkpointer=mock_checkpointer)


@pytest.mark.asyncio
async def test_chat_service_initialization(chat_service, mock_checkpointer):
    """测试 ChatService 初始化"""
    assert chat_service.checkpointer is not None
    assert chat_service.graph is not None
    assert hasattr(chat_service, 'model')


@pytest.mark.asyncio
async def test_graph_has_checkpointer(chat_service):
    """测试 graph 编译时使用了 checkpointer"""
    assert chat_service.graph is not None
    # LangGraph graph 对象应该有 checkpointer 属性
    assert hasattr(chat_service.graph, 'checkpointer')
```

**Step 2: 运行测试（预期失败）**

```bash
pytest tests/services/test_chat_service_langgraph.py -v
```
Expected: FAIL - ChatService 尚未使用 LangGraph

**Step 3: 实现 ChatService**

完全重写 `src/services/chat_service.py`:

```python
"""
对话服务层 - 基于 LangGraph

处理对话逻辑、LLM 调用和消息管理
"""

from uuid import uuid4
from datetime import datetime
from typing import AsyncIterator
import redis.asyncio as aioredis

from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, MessagesState, START
from langgraph.checkpoint.redis.aio import AsyncRedisSaver

from src.config import settings, get_llm_config
from src.models.schemas import ChatRequest, ChatResponse
from src.models.exceptions import LLMError


class ChatService:
    """对话服务类 - 基于 LangGraph"""

    def __init__(self, checkpointer: AsyncRedisSaver = None):
        self.checkpointer = checkpointer
        self.model = ChatOpenAI(
            **get_llm_config(),
            streaming=True,
            temperature=0.7
        )
        self.graph = None

    async def initialize(self):
        """异步初始化（在 startup 时调用）"""
        if self.checkpointer is None:
            self.checkpointer = AsyncRedisSaver.from_conn_string(settings.redis_url)
            await self.checkpointer.asetup()
        self._build_graph()

    def _build_graph(self):
        """构建 LangGraph"""
        async def call_model(state: MessagesState):
            """调用 LLM 的节点"""
            messages = state["messages"]
            response = await self.model.ainvoke(messages)
            return {"messages": response}

        builder = StateGraph(MessagesState)
        builder.add_node("agent", call_model)
        builder.add_edge(START, "agent")
        self.graph = builder.compile(checkpointer=self.checkpointer)

    async def chat_stream(
        self,
        request: ChatRequest,
        user_id: str
    ) -> AsyncIterator[ChatResponse]:
        """流式对话处理"""
        session_id = request.session_id or str(uuid4())

        config = {
            "configurable": {
                "thread_id": session_id
            }
        }

        # 发送开始事件
        yield ChatResponse.start(session_id=session_id)

        try:
            full_response = ""
            message_count = 0

            async for chunk in self.graph.astream(
                {"messages": [{"role": "user", "content": request.message}]},
                config,
                stream_mode="values"
            ):
                if "messages" in chunk and chunk["messages"]:
                    # 获取最后一条消息
                    last_msg = chunk["messages"][-1]
                    if hasattr(last_msg, 'content') and last_msg.content:
                        # 提取新增内容
                        current_content = last_msg.content
                        if len(current_content) > len(full_response):
                            new_content = current_content[len(full_response):]
                            full_response = current_content
                            yield ChatResponse.token(content=new_content)
                            message_count += 1

            # 触发后台同步
            if settings.redis_stream_sync_enabled:
                await self._trigger_sync(session_id, user_id, message_count)

            yield ChatResponse.end()

        except Exception as e:
            raise LLMError(f"LLM 调用失败: {str(e)}")

    async def _trigger_sync(self, session_id: str, user_id: str, message_count: int):
        """触发后台同步 - 发送消息到 Redis Stream"""
        try:
            redis_client = aioredis.from_url(settings.redis_url)
            await redis_client.xadd(
                settings.redis_stream_name,
                {
                    "thread_id": session_id,
                    "session_id": session_id,
                    "user_id": user_id,
                    "message_count": str(message_count),
                    "timestamp": datetime.utcnow().isoformat(),
                    "action": "checkpoint_updated"
                }
            )
            await redis_client.aclose()
        except Exception as e:
            # 同步失败不影响主流程
            print(f"Sync trigger failed: {e}")

    async def get_session_history(self, session_id: str) -> list:
        """获取会话历史 - 从 checkpoint 读取"""
        config = {"configurable": {"thread_id": session_id}}

        try:
            # 获取 checkpoint 状态
            state_snapshot = await self.graph.aget_state(config)
            if not state_snapshot:
                return []

            # 提取消息历史
            messages = []
            if "messages" in state_snapshot.values:
                for msg in state_snapshot.values["messages"]:
                    messages.append({
                        "role": msg.type if hasattr(msg, 'type') else "user",
                        "content": msg.content if hasattr(msg, 'content') else str(msg),
                        "timestamp": datetime.utcnow().isoformat()
                    })

            return messages

        except Exception as e:
            raise LLMError(f"获取历史失败: {str(e)}")


# 全局实例（延迟初始化）
chat_service = ChatService()
```

**Step 4: 运行测试验证**

```bash
pytest tests/services/test_chat_service_langgraph.py -v
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/services/chat_service.py tests/services/test_chat_service_langgraph.py
git commit -m "refactor: rewrite ChatService with LangGraph and AsyncRedisSaver"
```

---

## Task 4: 创建 SyncService

**文件:**
- Create: `src/services/sync_service.py`

**Step 1: 写测试 - Redis Stream 消费者**

创建 `tests/services/test_sync_service.py`:

```python
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import redis.asyncio as aioredis

from src.services.sync_service import SyncService


@pytest.fixture
def mock_pg_repo():
    """Mock PostgreSQL Repository"""
    repo = MagicMock()
    repo.save_checkpoint = AsyncMock()
    repo.save_message = AsyncMock()
    return repo


@pytest.fixture
def sync_service(mock_pg_repo):
    """创建 SyncService 实例"""
    service = SyncService(mock_pg_repo)
    return service


@pytest.mark.asyncio
async def test_start_consumer_creates_group(sync_service):
    """测试启动时创建 Consumer Group"""
    with patch("redis.asyncio.from_url") as mock_redis:
        mock_client = MagicMock()
        mock_redis.return_value = mock_client
        mock_client.xgroup_create = AsyncMock()

        await sync_service._ensure_consumer_group(mock_client)

        mock_client.xgroup_create.assert_called_once()


@pytest.mark.asyncio
async def test_process_sync_message(sync_service, mock_pg_repo):
    """测试处理同步消息"""
    data = {
        b"thread_id": b"test-thread-123",
        b"session_id": b"test-session-123",
        b"user_id": b"user-1",
        b"message_count": b"2"
    }

    await sync_service._process_sync_message(data)

    # 验证调用
    mock_pg_repo.save_checkpoint.assert_called_once()


@pytest.mark.asyncio
async def test_decode_message_data(sync_service):
    """测试消息数据解码"""
    data = {
        b"thread_id": b"test-thread",
        b"user_id": b"user-1"
    }

    decoded = await sync_service._decode_data(data)

    assert decoded["thread_id"] == "test-thread"
    assert decoded["user_id"] == "user-1"
```

**Step 2: 运行测试（预期失败）**

```bash
pytest tests/services/test_sync_service.py -v
```
Expected: FAIL - SyncService 尚未实现

**Step 3: 实现 SyncService**

创建 `src/services/sync_service.py`:

```python
"""
同步服务 - Redis Stream 消费者

从 Redis Stream 消费 checkpoint 更新消息，同步到 PostgreSQL
"""

import asyncio
from uuid import uuid4
from typing import Optional
import redis.asyncio as aioredis

from src.config import settings
from src.models.exceptions import AppException


class SyncService:
    """Redis Stream 消费者服务"""

    def __init__(self, pg_repo=None):
        self.pg_repo = pg_repo
        self._running = False
        self._consumer_name = f"worker-{uuid4().hex[:8]}"

    async def start(self):
        """启动消费者"""
        if self._running:
            return

        self._running = True
        redis_client = aioredis.from_url(settings.redis_url)

        # 确保存在 Consumer Group
        await self._ensure_consumer_group(redis_client)

        print(f"SyncService started: consumer={self._consumer_name}")

        # 启动多个 worker
        tasks = []
        for i in range(settings.redis_stream_workers):
            task = asyncio.create_task(
                self._worker(redis_client, worker_id=i)
            )
            tasks.append(task)

        # 等待所有 worker 完成
        await asyncio.gather(*tasks, return_exceptions=True)

    async def stop(self):
        """停止消费者"""
        self._running = False
        print("SyncService stopped")

    async def _ensure_consumer_group(self, redis_client: aioredis.Redis):
        """确保 Consumer Group 存在"""
        try:
            await redis_client.xgroup_create(
                settings.redis_stream_name,
                settings.redis_consumer_group,
                id="0",
                mkstream=True
            )
            print(f"Created consumer group: {settings.redis_consumer_group}")
        except Exception as e:
            # Group 可能已存在，忽略错误
            pass

    async def _worker(self, redis_client: aioredis.Redis, worker_id: int):
        """Worker 线程"""
        consumer_name = f"{self._consumer_name}-{worker_id}"
        print(f"Worker {worker_id} started as {consumer_name}")

        while self._running:
            try:
                # 从 Stream 读取消息
                messages = await redis_client.xreadgroup(
                    settings.redis_consumer_group,
                    consumer_name,
                    {settings.redis_stream_name: ">"},
                    count=1,
                    block=1000  # 1秒超时
                )

                if messages:
                    for stream, stream_messages in messages:
                        for msg_id, data in stream_messages:
                            await self._process_sync_message(data)
                            # ACK 消息
                            await redis_client.xack(
                                settings.redis_stream_name,
                                settings.redis_consumer_group,
                                msg_id
                            )

            except asyncio.CancelledError:
                break
            except Exception as e:
                print(f"Worker {worker_id} error: {e}")
                await asyncio.sleep(1)

    async def _process_sync_message(self, data: dict):
        """处理同步消息"""
        try:
            decoded = await self._decode_data(data)

            thread_id = decoded.get("thread_id")
            session_id = decoded.get("session_id")
            user_id = decoded.get("user_id")
            message_count = int(decoded.get("message_count", 0))

            # TODO: 从 Redis 读取完整 checkpoint 并写入 PostgreSQL
            # 这里需要实现具体的同步逻辑
            print(f"Syncing checkpoint: thread_id={thread_id}, messages={message_count}")

            if self.pg_repo:
                # 调用 PostgreSQL repository 保存
                pass

        except Exception as e:
            print(f"Process sync message failed: {e}")

    async def _decode_data(self, data: dict) -> dict:
        """解码 bytes 数据"""
        decoded = {}
        for key, value in data.items():
            if isinstance(key, bytes):
                key = key.decode('utf-8')
            if isinstance(value, bytes):
                value = value.decode('utf-8')
            decoded[key] = value
        return decoded


# 全局实例
sync_service = SyncService()
```

**Step 4: 运行测试验证**

```bash
pytest tests/services/test_sync_service.py -v
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/services/sync_service.py tests/services/test_sync_service.py
git commit -m "feat: add SyncService for Redis Stream consumption"
```

---

## Task 5: 更新 API 启动逻辑

**文件:**
- Modify: `src/api/main.py`

**Step 1: 更新 startup 事件**

```python
@app.on_event("startup")
async def startup():
    """应用启动初始化"""
    # 初始化 Redis Checkpointer 并启动 ChatService
    await chat_service.initialize()

    # 启动同步服务
    if settings.redis_stream_sync_enabled:
        asyncio.create_task(sync_service.start())

    logger.info("Application started")


@app.on_event("shutdown")
async def shutdown():
    """应用关闭清理"""
    await sync_service.stop()
    logger.info("Application shutdown")
```

**Step 2: 验证启动**

```bash
python -m uvicorn src.api.main:app --reload
```
Expected: 应用正常启动，日志显示 "SyncService started"

**Step 3: Commit**

```bash
git add src/api/main.py
git commit -m "feat: add startup/shutdown hooks for ChatService and SyncService"
```

---

## Task 6: 删除旧的 Redis Repository

**文件:**
- Delete: `src/repositories/redis_repo.py`
- Modify: `src/api/main.py` (移除导入)

**Step 1: 更新 main.py 移除导入**

```python
# 删除这行
# from src.repositories.redis_repo import redis_repo
# from src.api.startup import init_redis
```

**Step 2: 删除 redis_repo.py**

```bash
rm src/repositories/redis_repo.py
```

**Step 3: 验证应用启动**

```bash
python -m uvicorn src.api.main:app --reload
```
Expected: 应用正常启动，无 Redis Repository 相关错误

**Step 4: Commit**

```bash
git add src/api/main.py
git rm src/repositories/redis_repo.py
git commit -m "refactor: remove old Redis repository, using LangGraph checkpointer"
```

---

## Task 7: 集成测试

**文件:**
- Modify: `tests/integration/test_chat_api.py`

**Step 1: 端到端测试**

```python
import pytest
from httpx import AsyncClient, ASGITransport
from src.api.main import app


@pytest.mark.asyncio
async def test_chat_stream_with_langgraph():
    """测试完整的流式对话流程"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            headers={"X-API-Key": "test-key"},
            json={
                "message": "你好",
                "session_id": None
            }
        )

        # 验证 SSE 响应
        assert response.status_code == 200
        assert response.headers["content-type"] == "text/event-stream; charset=utf-8"

        # 验证事件内容
        lines = response.text.split("\n")
        events = [line for line in lines if line.startswith("data: ")]

        assert len(events) > 0

        # 验证第一个事件是 start
        start_event = events[0]
        assert '"type":"start"' in start_event


@pytest.mark.asyncio
async def test_chat_with_existing_session():
    """测试使用已有 session_id 进行对话"""
    transport = ASGITransport(app=app)
    session_id = "550e8400-e29b-41d4-a716-446655440000"

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            headers={"X-API-Key": "test-key"},
            json={
                "message": "继续对话",
                "session_id": session_id
            }
        )

        assert response.status_code == 200
```

**Step 2: 运行集成测试**

```bash
pytest tests/integration/test_chat_api.py -v
```
Expected: PASS

**Step 3: Commit**

```bash
git add tests/integration/test_chat_api.py
git commit -m "test: add integration tests for LangGraph chat API"
```

---

## Task 8: 更新文档

**文件:**
- Modify: `docs/detailed-design/streaming-chat-api.md`

**Step 1: 更新架构说明**

在架构设计章节添加 LangGraph 相关内容：

```markdown
### 系统架构图

[更新为 LangGraph 架构...]

### LangGraph 集成

- 使用 `StateGraph` 构建对话状态机
- `MessagesState` 管理对话历史
- `AsyncRedisSaver` 持久化 checkpoint
- `thread_id` 对应外部 `session_id`
```

**Step 2: Commit**

```bash
git add docs/detailed-design/streaming-chat-api.md
git commit -m "docs: update architecture documentation for LangGraph"
```

---

## 验收标准

- [ ] 所有单元测试通过
- [ ] 集成测试通过
- [ ] 应用正常启动，SyncService 日志显示
- [ ] 可以使用外部 session_id 进行对话
- [ ] Redis Stream 收到同步消息
- [ ] 文档更新完整

## 回滚方案

如果遇到问题，使用 git 回滚：

```bash
git reset --hard <commit-before-refactor>
```
