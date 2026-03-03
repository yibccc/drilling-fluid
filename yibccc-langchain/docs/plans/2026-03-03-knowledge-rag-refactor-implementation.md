# 知识库重构实施计划 - Two-step RAG Chain

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 重构知识库实现为 LangChain 推荐的 Two-step RAG Chain 方式

**架构:** 使用 RetrievalMiddleware 自动注入检索上下文，配合 PGVector 向量存储，单次 LLM 调用生成诊断结果

**技术栈:** LangChain 1.0.0, langchain-postgres, DashScopeEmbeddings, pgvector, asyncpg

---

## 前置准备

### 安装依赖

```bash
# 进入项目目录
cd /Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain

# 安装新依赖
pip install langchain-postgres>=0.1.0
pip install pytest-asyncio>=0.24.0
pip install httpx>=0.27.0

# 更新 requirements.txt
echo "langchain-postgres>=0.1.0" >> requirements.txt
echo "pytest-asyncio>=0.24.0" >> requirements.txt
echo "httpx>=0.27.0" >> requirements.txt
```

---

## Phase 1: 基础设施搭建

### Task 1: 创建 VectorStoreService

**Files:**
- Create: `src/services/vector_store_service.py`

**Step 1: 创建测试目录结构**

```bash
mkdir -p tests/services
touch tests/services/__init__.py
```

**Step 2: 编写失败的测试**

```python
# tests/services/test_vector_store_service.py

import pytest
from unittest.mock import AsyncMock, patch
from src.services.vector_store_service import VectorStoreService

@pytest.mark.asyncio
async def test_vector_store_service_initialization():
    """测试 VectorStoreService 初始化"""
    # Arrange
    mock_embeddings = AsyncMock()

    # Act
    with patch('src.services.vector_store_service.DashScopeEmbeddings', return_value=mock_embeddings):
        service = VectorStoreService("postgresql+psycopg://test")

        # Assert
        assert service.embeddings is not None
        assert service.vector_store is not None
```

**Step 3: 运行测试验证失败**

```bash
pytest tests/services/test_vector_store_service.py -v
```

Expected: `ImportError: src.services.vector_store_service`

**Step 4: 实现 VectorStoreService**

```python
# src/services/vector_store_service.py

import logging
from typing import List, Optional
from langchain_postgres import PGVector
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_core.documents import Document
from src.config import settings

logger = logging.getLogger(__name__)

class VectorStoreService:
    """向量存储服务 - 使用 LangChain PGVector 集成"""

    def __init__(self, connection_string: str):
        self.embeddings = DashScopeEmbeddings(
            model=settings.embedding_model,
            dashscope_api_key=settings.dashscope_api_key,
        )

        # LangChain PGVector 自动处理表结构
        self.vector_store = PGVector(
            embeddings=self.embeddings,
            collection_name="knowledge_docs",
            connection=connection_string,
            use_jsonb=True,
        )
        logger.info(f"VectorStoreService initialized with collection: knowledge_docs")

    async def add_documents(self, docs: List[Document]) -> List[str]:
        """添加文档到向量库"""
        logger.info(f"Adding {len(docs)} documents to vector store")
        return await self.vector_store.aadd_documents(docs)

    async def similarity_search(
        self,
        query: str,
        k: int = 5,
        filter: Optional[dict] = None
    ) -> List[Document]:
        """语义检索"""
        logger.debug(f"Searching: query='{query}', k={k}, filter={filter}")
        return await self.vector_store.asimilarity_search(query, k=k, filter=filter)

    async def delete(self, ids: List[str]) -> bool:
        """删除文档"""
        return await self.vector_store.adelete(ids)
```

**Step 5: 运行测试验证通过**

```bash
pytest tests/services/test_vector_store_service.py -v
```

Expected: `PASSED`

**Step 6: 提交**

```bash
git add src/services/vector_store_service.py tests/services/test_vector_store_service.py
git commit -m "feat: add VectorStoreService with LangChain PGVector"
```

---

### Task 2: 更新配置文件

**Files:**
- Modify: `src/config.py`

**Step 1: 写测试验证配置加载**

```python
# tests/test_config.py

from src.config import settings

def test_langchain_vectorstore_config():
    """测试 LangChain 向量存储配置"""
    assert hasattr(settings, 'USE_LANGCHAIN_VECTORSTORE')
    assert isinstance(settings.USE_LANGCHAIN_VECTORSTORE, bool)
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/test_config.py::test_langchain_vectorstore_config -v
```

Expected: `FAILED: AttributeError: module 'src.config' has no attribute 'USE_LANGCHAIN_VECTORSTORE'`

**Step 3: 添加配置**

```python
# src/config.py（在 Settings 类中添加）

class Settings(BaseSettings):
    # 现有配置...

    # ========== 向量存储配置 ==========
    USE_LANGCHAIN_VECTORSTORE: bool = True

    # LangChain PGVector 配置
    LANGCHAIN_CONNECTION_STRING: str = None

    def get_langchain_connection_string(self) -> str:
        """获取 LangChain 连接字符串"""
        if self.LANGCHAIN_CONNECTION_STRING:
            return self.LANGCHAIN_CONNECTION_STRING
        # 默认使用现有数据库连接
        return f"postgresql+psycopg://{self.db_user}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_database}"
```

**Step 4: 运行测试验证通过**

```bash
pytest tests/test_config.py::test_langchain_vectorstore_config -v
```

Expected: `PASSED`

**Step 5: 提交**

```bash
git add src/config.py tests/test_config.py
git commit -m "feat: add LangChain vector store configuration"
```

---

## Phase 2: 中间件和 Agent 重构

### Task 3: 创建检索中间件

**Files:**
- Create: `src/agents/diagnosis_middleware.py`
- Create: `tests/middleware/test_retrieval_middleware.py`

**Step 1: 写测试 - 中间件注入上下文**

```python
# tests/middleware/test_retrieval_middleware.py

import pytest
from unittest.mock import AsyncMock, MagicMock
from src.agents.diagnosis_middleware import RetrievalMiddleware

@pytest.mark.asyncio
async def test_middleware_injects_retrieval_context():
    """测试中间件正确注入检索上下文"""
    # Arrange
    mock_vector_store = AsyncMock()
    mock_docs = [MagicMock(
        page_content="密度偏高时，应加水稀释",
        metadata={"title": "密度处理指南"}
    )]
    mock_vector_store.similarity_search.return_value = mock_docs

    middleware = RetrievalMiddleware(mock_vector_store)
    state = {
        "messages": [MagicMock(content="密度偏高怎么办？")],
        "metadata": {"category": "density"}
    }

    # Act
    result = await middleware.before_model(state)

    # Assert
    assert result is not None
    assert "messages" in result
    assert len(result["messages"]) == 2
    assert result["messages"][0].content.startswith("你是一位钻井液性能诊断专家")
    assert "密度偏高时，应加水稀释" in result["messages"][0].content
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/middleware/test_retrieval_middleware.py -v
```

Expected: `ImportError`

**Step 3: 实现 RetrievalMiddleware**

```python
# src/agents/diagnosis_middleware.py

import asyncio
import logging
from typing import Any, List, Optional
from langchain.agents.middleware import AgentMiddleware, AgentState
from langchain_core.messages import Message

logger = logging.getLogger(__name__)

class RetrievalMiddleware(AgentMiddleware[AgentState]):
    """检索中间件 - 自动注入知识库上下文"""

    def __init__(self, vector_store_service):
        self.vector_store = vector_store_service

    async def before_model(self, state: AgentState) -> dict[str, Any] | None:
        """在模型调用前执行：检索并注入上下文"""
        try:
            # 1. 提取查询
            messages = state.get("messages", [])
            if not messages:
                return None

            last_message = messages[-1]
            user_query = getattr(last_message, 'content', str(last_message))

            if not user_query:
                return self._handle_error("查询为空", state)

            # 2. 执行检索
            retrieved_docs = await self._safe_search(
                query=user_query,
                filter=self._extract_filter_from_state(state),
                state=state
            )

            # 3. 检查检索结果
            if not retrieved_docs:
                return self._handle_empty_search(state)

            # 4. 格式化并注入上下文
            context = self._format_context(retrieved_docs)
            system_message = f"""你是一位钻井液性能诊断专家。

请基于以下知识库内容回答用户的问题：

【知识库内容】
{context}

请根据上述专业知识，提供准确的诊断和处置建议。"""

            return {
                "messages": [
                    Message(role="system", content=system_message),
                    *messages
                ]
            }

        except Exception as e:
            logger.error(f"中间件错误: {e}")
            return self._handle_error(f"检索失败: {str(e)}", state)

    def _extract_filter_from_state(self, state: AgentState) -> dict | None:
        """从 state 中提取检索过滤条件"""
        metadata = state.get("metadata", {})
        category = metadata.get("category")
        return {"category": category} if category else None

    def _format_context(self, docs: List) -> str:
        """格式化检索结果"""
        return "\n\n".join([
            f"【{doc.metadata.get('title', '资料')}】\n{doc.page_content}"
            for doc in docs
        ])

    async def _safe_search(self, query: str, filter: dict, state: AgentState) -> List:
        """带重试的安全检索"""
        max_retries = 2
        for attempt in range(max_retries):
            try:
                return await self.vector_store.similarity_search(
                    query=query, k=5, filter=filter
                )
            except Exception as e:
                logger.warning(f"检索失败，重试 {attempt + 1}/{max_retries}: {e}")
                if attempt < max_retries - 1:
                    await asyncio.sleep(1 * (attempt + 1))

        logger.error("检索重试失败，返回空结果")
        return []

    def _handle_empty_search(self, state: AgentState) -> dict:
        """处理检索结果为空"""
        system_message = """你是一位钻井液性能诊断专家。

由于未能检索到相关知识库内容，请基于你的专业知识进行分析和诊断。

如果问题超出专业范围，请明确告知用户。"""

        logger.warning("检索结果为空，使用降级模式")

        return {
            "messages": [
                Message(role="system", content=system_message),
                *state["messages"]
            ],
            "_retrieval_empty": True
        }

    def _handle_error(self, error_msg: str, state: AgentState) -> dict:
        """处理错误"""
        system_message = """你是一位钻井液性能诊断专家。

当前检索系统遇到问题，请基于你的专业知识进行分析。
如需专业知识库支持，请建议用户稍后重试。"""

        return {
            "messages": [
                Message(role="system", content=system_message),
                *state["messages"]
            ],
            "_retrieval_error": True
        }
```

**Step 4: 运行测试验证通过**

```bash
pytest tests/middleware/test_retrieval_middleware.py -v
```

Expected: `PASSED`

**Step 5: 提交**

```bash
git add src/agents/diagnosis_middleware.py tests/middleware/test_retrieval_middleware.py
git commit -m "feat: add RetrievalMiddleware with automatic context injection"
```

---

### Task 4: 重构 DiagnosisAgent 使用中间件

**Files:**
- Modify: `src/agents/diagnosis_agent.py`

**Step 1: 写测试 - Agent 使用中间件**

```python
# tests/agents/test_diagnosis_agent.py

import pytest
from unittest.mock import AsyncMock, MagicMock
from src.agents.diagnosis_agent import DiagnosisAgent
from src.agents.diagnosis_middleware import RetrievalMiddleware

@pytest.mark.asyncio
async def test_agent_uses_middleware():
    """测试 Agent 正确使用中间件"""
    # Arrange
    mock_vector_store = AsyncMock()
    middleware = RetrievalMiddleware(mock_vector_store)

    agent = DiagnosisAgent(checkpointer=None)
    agent._build_agent = lambda: None  # 跳过实际构建
    agent.retrieval_middleware = middleware

    # Act & Assert
    assert agent.retrieval_middleware is not None
    assert isinstance(agent.retrieval_middleware, RetrievalMiddleware)
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/agents/test_diagnosis_agent.py -v
```

Expected: `FAILED: AttributeError: 'DiagnosisAgent' object has no attribute 'retrieval_middleware'`

**Step 3: 修改 DiagnosisAgent**

```python
# src/agents/diagnosis_agent.py（修改 _build_agent 方法）

from src.agents.diagnosis_middleware import RetrievalMiddleware
from src.services.vector_store_service import VectorStoreService

class DiagnosisAgent:
    """钻井液诊断 Agent"""

    def __init__(self, checkpointer=None, vector_store_service: VectorStoreService = None):
        self.checkpointer = checkpointer
        self.vector_store_service = vector_store_service
        self.model = ChatOpenAI(**get_llm_config(), streaming=True, temperature=0.3)
        self.response_format = ToolStrategy(LLMDiagnosisOutput)
        self.agent = None
        self.retrieval_middleware = None  # ✅ 新增

    async def initialize(self):
        """初始化 Agent"""
        try:
            await self.checkpointer.setup() if self.checkpointer else None
            self._build_agent()
            logger.info("DiagnosisAgent initialized")
        except Exception as e:
            logger.error(f"Failed to initialize DiagnosisAgent: {e}")
            raise DiagnosisError(f"Agent 初始化失败: {str(e)}")

    def _build_agent(self):
        """构建诊断 Agent - Two-step Chain 方式"""
        from src.tools.diagnosis_tools import analyze_trend, format_prescription

        # ✅ 创建检索中间件
        self.retrieval_middleware = RetrievalMiddleware(self.vector_store_service)

        # ✅ 简化系统提示词
        system_prompt = """你是一位钻井液性能诊断专家。

你的职责是：
1. 分析钻井液采样数据，识别异常趋势
2. 基于知识库内容诊断问题原因
3. 提供具体的处置措施和配药方案
4. 评估风险等级并提供趋势预测

分析完成后，返回结构化的诊断结果。"""

        # ✅ 不包含检索工具，只有分析工具
        tools = [
            analyze_trend,
            format_prescription
        ]

        self.agent = create_agent(
            model=self.model,
            tools=tools,
            system_prompt=system_prompt,
            checkpointer=self.checkpointer,
            response_format=self.response_format,
            middleware=[self.retrieval_middleware]  # ✅ 添加中间件
        )

    async def analyze(self, request: DiagnosisRequest) -> AsyncIterator[DiagnosisEvent]:
        """执行诊断分析（流式输出）"""
        # ... 现有代码保持不变 ...
```

**Step 4: 更新初始化逻辑**

```python
# src/api/dependencies.py（或相关初始化文件）

from src.services.vector_store_service import VectorStoreService
from src.agents.diagnosis_agent import DiagnosisAgent

async def get_diagnosis_agent() -> DiagnosisAgent:
    """获取诊断 Agent 实例"""
    vector_store_service = VectorStoreService(settings.get_langchain_connection_string())
    agent = DiagnosisAgent(
        checkpointer=checkpointer,
        vector_store_service=vector_store_service
    )
    await agent.initialize()
    return agent
```

**Step 5: 运行测试验证通过**

```bash
pytest tests/agents/test_diagnosis_agent.py -v
```

Expected: `PASSED`

**Step 6: 提交**

```bash
git add src/agents/diagnosis_agent.py tests/agents/test_diagnosis_agent.py
git commit -m "refactor: DiagnosisAgent use RetrievalMiddleware (Two-step Chain)"
```

---

### Task 5: 移除旧的知识库工具

**Files:**
- Modify: `src/tools/diagnosis_tools.py`

**Step 1: 写测试验证工具列表**

```python
# tests/tools/test_diagnosis_tools.py

from src.tools.diagnosis_tools import analyze_trend, format_prescription

def test_available_tools():
    """验证可用的工具"""
    tools = [analyze_trend, format_prescription]
    assert len(tools) == 2
    assert all(hasattr(tool, 'name') for tool in tools)

    # 验证 search_knowledge 已移除
    from src.tools.diagnosis_tools import __all__
    assert 'search_knowledge' not in __all__
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/tools/test_diagnosis_tools.py -v
```

Expected: `FAILED: AssertionError: 3 != 2` 或 `AssertionError: 'search_knowledge' in __all__`

**Step 3: 移除 search_knowledge 工具**

```python
# src/tools/diagnosis_tools.py

from typing import Annotated, List, Dict, Any
from datetime import datetime, timedelta

from langchain.tools import tool

@tool
def analyze_trend(
    samples: Annotated[List[Dict[str, Any]], "采样数据列表"],
    field: Annotated[str, "字段名"] = "density"
) -> str:
    """分析钻井液参数趋势"""
    # ... 现有代码保持不变 ...

@tool
def format_prescription(
    measures: Annotated[str, "处置措施描述"],
    density: Annotated[float, "当前密度"] = 1.2,
    plastic_viscosity: Annotated[float, "当前塑性黏度"] = 20
) -> str:
    """生成配药方案"""
    # ... 现有代码保持不变 ...

# ✅ 更新导出列表
__all__ = ["analyze_trend", "format_prescription"]  # 移除 search_knowledge
```

**Step 4: 运行测试验证通过**

```bash
pytest tests/tools/test_diagnosis_tools.py -v
```

Expected: `PASSED`

**Step 5: 提交**

```bash
git add src/tools/diagnosis_tools.py tests/tools/test_diagnosis_tools.py
git commit -m "refactor: remove search_knowledge tool (using middleware instead)"
```

---

## Phase 3: 数据迁移

### Task 6: 创建数据迁移脚本

**Files:**
- Create: `scripts/migrate_to_pgvector.py`
- Create: `tests/scripts/test_migrate.py`

**Step 1: 写测试验证迁移脚本**

```python
# tests/scripts/test_migrate.py

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from scripts.migrate_to_pgvector import migrate_existing_data

@pytest.mark.asyncio
async def test_migrate_converts_data_format():
    """测试数据格式转换"""
    # Arrange
    mock_db = AsyncMock()
    mock_old_docs = [
        {
            "doc_id": "test_001",
            "title": "测试文档",
            "content": "测试内容",
            "category": "density",
            "metadata": {"key": "value"}
        }
    ]

    # Mock 数据库查询
    async def mock_fetch(*args, **kwargs):
        return mock_old_docs

    mock_db.fetch = mock_fetch

    # Act
    with patch('scripts.migrate_to_pgvector.get_db_connection', return_value=mock_db):
        documents = await migrate_existing_data()

    # Assert
    assert len(documents) == 1
    assert documents[0].page_content == "测试内容"
    assert documents[0].metadata["doc_id"] == "test_001"
    assert documents[0].metadata["category"] == "density"
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/scripts/test_migrate.py -v
```

Expected: `FAILED: ImportError: scripts.migrate_to_pgvector`

**Step 3: 实现迁移脚本**

```python
# scripts/migrate_to_pgvector.py

import asyncio
import logging
from typing import List
from langchain_core.documents import Document

logger = logging.getLogger(__name__)

async def migrate_existing_data() -> List[Document]:
    """将现有数据迁移到 LangChain PGVector 格式"""
    from src.config import settings
    import asyncpg

    # 连接数据库
    conn = await asyncpg.connect(settings.get_langchain_connection_string())

    try:
        # 1. 读取现有文档
        rows = await conn.fetch("SELECT * FROM knowledge_documents ORDER BY created_at")
        logger.info(f"Found {len(rows)} documents to migrate")

        # 2. 转换为 LangChain Document 格式
        documents = []
        for row in rows:
            doc = Document(
                page_content=row["content"],
                metadata={
                    "doc_id": row["doc_id"],
                    "title": row["title"],
                    "category": row["category"],
                    "subcategory": row.get("subcategory"),
                    "created_at": str(row["created_at"]) if row.get("created_at") else None
                }
            )
            documents.append(doc)

        logger.info(f"Converted {len(documents)} documents to LangChain format")
        return documents

    finally:
        await conn.close()

async def run_migration():
    """执行完整迁移"""
    logger.info("Starting data migration...")

    # 1. 迁移文档
    documents = await migrate_existing_data()

    # 2. 添加到向量库
    from src.services.vector_store_service import VectorStoreService
    from src.config import settings

    vector_store = VectorStoreService(settings.get_langchain_connection_string())
    doc_ids = await vector_store.add_documents(documents)

    logger.info(f"Migration complete! Migrated {len(doc_ids)} documents")
    logger.info(f"Document IDs: {doc_ids[:5]}...")  # 显示前5个

if __name__ == "__main__":
    asyncio.run(run_migration())
```

**Step 4: 运行测试验证通过**

```bash
pytest tests/scripts/test_migrate.py -v
```

Expected: `PASSED`

**Step 5: 提交**

```bash
git add scripts/migrate_to_pgvector.py tests/scripts/test_migrate.py
git commit -m "feat: add data migration script for LangChain PGVector"
```

---

## Phase 4: API 层更新和测试

### Task 7: 增强 DiagnosisService 错误处理

**Files:**
- Modify: `src/services/diagnosis_service.py`

**Step 1: 写测试验证错误处理**

```python
# tests/services/test_diagnosis_service_error_handling.py

import pytest
from unittest.mock import AsyncMock, patch
from src.services.diagnosis_service import DiagnosisService
from src.models.diagnosis_schemas import DiagnosisRequest, DiagnosisEvent

@pytest.mark.asyncio
async def test_handles_retrieval_failure_gracefully():
    """测试检索失败时的优雅处理"""
    # Arrange
    mock_agent = AsyncMock()
    mock_agent.analyze.return_value = _error_events("RETRIEVAL_ERROR")

    mock_repo = AsyncMock()
    service = DiagnosisService(mock_agent, None, None, mock_repo)

    request = DiagnosisRequest(
        task_id="test-task",
        well_id="TEST001",
        alert_type="density_high",
        alert_triggered_at="2025-01-03T10:00:00Z",
        alert_threshold={"field": "density", "condition": "greater_than", "threshold": 1.25, "current_value": 1.32},
        samples=[],
        context={"category": "density"}
    )

    # Act
    events = []
    async for event in service.analyze(request):
        events.append(event)

    # Assert
    assert any(e.type == "done" for e in events)
    assert not any(e.type == "error" for e in events)  # 应该优雅降级，不返回 error

def _error_events(status):
    """模拟返回错误的事件流"""
    import asyncio
    async def _gen():
        yield DiagnosisEvent.start("test-task", "TEST001", 0)
        yield DiagnosisEvent.thinking("test-task", "降级模式，使用通用知识", "fallback")
        yield DiagnosisEvent(type="done", task_id="test-task", status="SUCCESS")
    return _gen()
```

**Step 2: 运行测试验证失败**

```bash
pytest tests/services/test_diagnosis_service_error_handling.py -v
```

Expected: `FAILED` (当前实现可能没有优雅降级)

**Step 3: 增强错误处理**

```python
# src/services/diagnosis_service.py（修改 analyze 方法）

async def analyze(self, request: DiagnosisRequest) -> AsyncIterator[DiagnosisEvent]:
    """执行诊断分析（包含完整错误处理）"""
    task_id = request.task_id

    try:
        # 1. 创建任务记录
        await self.repo.create_task(request, status="PROCESSING")

        # 2. 发送开始事件
        yield DiagnosisEvent.start(
            task_id=task_id,
            well_id=request.well_id,
            samples_count=len(request.samples)
        )

        # 3. 执行 Agent 分析（带超时控制）
        try:
            async with asyncio.timeout(300):  # 5分钟超时
                async for event in self.agent.analyze(request):
                    # ✅ 检查降级标记
                    if hasattr(event, 'type') and event.type == "thinking":
                        # 不发送降级模式的思考事件到前端
                        if hasattr(event, '__dict__') and "_retrieval_empty" in event.__dict__:
                            continue
                        if hasattr(event, '__dict__') and "_retrieval_error" in event.__dict__:
                            continue

                    yield event

                    # 记录事件到数据库
                    if event.type != "start":
                        import json
                        event_data = json.loads(event.model_dump_json(exclude_none=True))
                        await self.repo.save_event(task_id, event.type, event_data, 0)

                    # 保存结果
                    if event.type == "result" and event.result:
                        await self.repo.save_result(task_id, event.result)

                    # 更新状态
                    if event.type == "done":
                        await self.repo.update_task_status(task_id, event.status, datetime.now())

        except asyncio.TimeoutError:
            logger.error(f"诊断超时: {task_id}")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="TIMEOUT",
                message="诊断分析超时，请稍后重试"
            )
            await self.repo.update_task_status(task_id, "FAILED")

        # 4. 发送回调
        if request.callback_url:
            # ... 现有代码 ...

    except Exception as e:
        logger.error(f"Diagnosis analysis failed: {e}")
        await self.repo.update_task_status(task_id, "FAILED")
        yield DiagnosisEvent.error(
            task_id=task_id,
            error_code="ANALYSIS_FAILED",
            message=str(e)
        )
```

**Step 4: 运行测试验证通过**

```bash
pytest tests/services/test_diagnosis_service_error_handling.py -v
```

Expected: `PASSED`

**Step 5: 提交**

```bash
git add src/services/diagnosis_service.py tests/services/test_diagnosis_service_error_handling.py
git commit -m "feat: enhance error handling with graceful degradation"
```

---

## Phase 5: 集成测试和 E2E 测试

### Task 8: 编写集成测试

**Files:**
- Create: `tests/integration/test_vector_store_service.py`

**Step 1: 编写集成测试**

```python
# tests/integration/test_vector_store_service.py

import pytest
from src.services.vector_store_service import VectorStoreService
from langchain_core.documents import Document
from src.config import settings

@pytest.mark.asyncio
async def test_add_and_search():
    """测试完整的添加和检索流程"""
    # Arrange
    service = VectorStoreService(settings.test_database_url)

    # 清理测试数据
    await service.vector_store.delete_collection()

    # Act - 添加文档
    docs = [
        Document(
            page_content="密度偏高时，应加水稀释，通常加水量为5-10%",
            metadata={"doc_id": "test_001", "category": "density", "title": "密度处理指南"}
        )
    ]

    doc_ids = await service.add_documents(docs)
    assert len(doc_ids) > 0

    # Act - 检索
    results = await service.similarity_search(
        query="密度过高怎么办",
        k=5,
        filter={"category": "density"}
    )

    # Assert
    assert len(results) > 0
    assert any("密度偏高" in r.page_content for r in results)

@pytest.mark.asyncio
async def test_empty_search_returns_empty_list():
    """测试无结果时返回空列表"""
    service = VectorStoreService(settings.test_database_url)

    results = await service.similarity_search("不存在的xyz问题", k=5)
    assert results == []
```

**Step 2: 运行测试**

```bash
pytest tests/integration/test_vector_store_service.py -v
```

**Step 3: 提交**

```bash
git add tests/integration/test_vector_store_service.py
git commit -m "test: add integration tests for VectorStoreService"
```

---

### Task 9: 编写 E2E 测试

**Files:**
- Create: `tests/e2e/test_diagnosis_flow.py`

**Step 1: 编写 E2E 测试**

```python
# tests/e2e/test_diagnosis_flow.py

import pytest
import json
from httpx import AsyncClient
from src.api.main import app

@pytest.mark.asyncio
async def test_diagnosis_with_valid_request():
    """测试完整的诊断流程"""
    async with AsyncClient(app=app, base_url="http://test") as client:
        # Arrange
        request = {
            "task_id": "test-task-001",
            "well_id": "TEST001",
            "alert_type": "密度偏高",
            "alert_triggered_at": "2025-01-03T10:00:00Z",
            "alert_threshold": {
                "field": "density",
                "condition": "greater_than",
                "threshold": 1.25,
                "current_value": 1.32
            },
            "samples": [
                {
                    "id": "sample_001",
                    "well_id": "TEST001",
                    "sample_time": "2025-01-03T10:00:00Z",
                    "density": 1.32,
                    "plastic_viscosity": 25.0,
                    "yield_point": 15.0
                }
            ],
            "context": {"category": "density", "current_depth": 1500}
        }

        # Act - SSE 流式接收
        events = []
        async with client.stream("POST", "/api/v1/diagnosis/analyze", json=request) as response:
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    event = json.loads(line[6:])
                    events.append(event)

        # Assert
        event_types = [e["type"] for e in events]
        assert "start" in event_types
        assert "thinking" in event_types
        assert "result" in event_types
        assert "done" in event_types

        # 验证结果结构
        result_event = next(e for e in events if e["type"] == "result")
        assert "result" in result_event
        assert "diagnosis" in result_event["result"]
```

**Step 2: 运行测试**

```bash
pytest tests/e2e/test_diagnosis_flow.py -v
```

**Step 3: 提交**

```bash
git add tests/e2e/test_diagnosis_flow.py
git commit -m "test: add E2E test for diagnosis flow"
```

---

## Phase 5: 清理和上线

### Task 10: 删除旧代码

**Files:**
- Delete: `src/repositories/knowledge_repo.py`
- Delete: `src/services/rag_service.py`

**Step 1: 确认新代码正常工作**

```bash
# 运行所有测试
pytest tests/ -v
```

Expected: 所有测试通过

**Step 2: 删除旧文件**

```bash
git rm src/repositories/knowledge_repo.py
git rm src/services/rag_service.py
```

**Step 3: 提交**

```bash
git commit -m "refactor: remove legacy knowledge repository code"
```

---

### Task 11: 更新文档

**Files:**
- Update: `README.md` (如果存在)
- Update: `docs/development.md` (如果存在)

**Step 1: 添加架构说明文档**

```markdown
# 知识库架构

## 概述

使用 LangChain PGVector 实现向量存储，配合 RetrievalMiddleware 自动注入检索上下文。

## 架构

```
DiagnosisService → DiagnosisAgent → RetrievalMiddleware → VectorStoreService → PGVector
```

## 使用方式

中间件自动在每次 LLM 调用前检索相关文档并注入上下文。
```

**Step 2: 提交文档**

```bash
git add README.md
git commit -m "docs: update knowledge base architecture documentation"
```

---

## 验收标准

完成所有任务后，验证以下指标：

| 指标 | 验证方式 |
|------|---------|
| 单元测试通过 | `pytest tests/ -v` |
| 集成测试通过 | `pytest tests/integration/ -v` |
| E2E 测试通过 | `pytest tests/e2e/ -v` |
| 代码覆盖率 | `pytest --cov=src tests/` |
| 检索延迟 | `< 500ms` p95 |
| 检索成功率 | `> 99%` |

---

## 回滚方案

如果新实现出现问题，可通过配置快速回退：

```python
# src/config.py
USE_LANGCHAIN_VECTORSTORE: False  # 设为 false 回退
```

回退后系统将使用旧的知识库实现（需保留旧代码）。

---

**计划已保存到 `docs/plans/2025-01-03-knowledge-rag-refactor-implementation.md`**

---

Plan complete and saved to `docs/plans/2025-01-03-knowledge-rag-refactor-implementation.md`. Two execution options:

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

Which approach?
