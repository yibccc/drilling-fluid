# 钻井液性能实时检测与自动化分析系统 - 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 在现有 FastAPI + LangChain Agent 服务基础上，为钻井液性能实时检测与自动化分析系统添加 AI 功能，包括预警诊断分析、RAG 专家知识库、SSE 流式响应和结构化输出。

**架构:** 采用分层架构（API 层 → 服务层 → 仓储层 → 模型层），复用现有的 LangChain create_agent 模式、AsyncRedisSaver checkpoint 机制和 SSE 流式响应框架。新增诊断 Agent、RAG 服务（pgvector + HNSW）和回调服务。

**技术栈:** FastAPI 0.115+, LangChain 1.0.0, LangGraph 0.2.0, PostgreSQL + pgvector, Redis, AsyncRedisSaver, httpx

---

## 前置条件

在开始实施前，确保已完成以下环境准备：

1. PostgreSQL 已安装 pgvector 扩展
2. Redis 服务正常运行
3. DeepSeek API Key 已配置
4. 数据库已创建并执行基础 schema.sql

---

## Phase 1: 基础设施（数据库与配置）

### Task 1.1: 创建诊断相关数据库表

**Files:**
- Create: `sql/diagnosis_schema.sql`

**Step 1: 创建 SQL 文件**

```bash
# 创建目录
mkdir -p sql

# 创建文件
touch sql/diagnosis_schema.sql
```

**Step 2: 编写诊断相关表结构**

```sql
-- sql/diagnosis_schema.sql
-- 钻井液诊断系统相关表结构

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
    references JSONB,
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
```

**Step 3: 验证 SQL 语法**

```bash
# 如果有 psql 客户端，可以验证语法
psql -h localhost -U root -d yibccc_chat -f sql/diagnosis_schema.sql --dry-run
```

**Step 4: 提交**

```bash
git add sql/diagnosis_schema.sql
git commit -m "feat: add diagnosis system database schema"
```

---

### Task 1.2: 更新项目依赖

**Files:**
- Modify: `pyproject.toml`

**Step 1: 读取现有依赖**

```bash
cat pyproject.toml
```

**Step 2: 添加新依赖**

在 `dependencies` 数组中添加：

```toml
[project]
# ... 现有配置 ...
dependencies = [
    "langchain>=1.0.0",
    "langchain-openai>=0.1.0",
    "langgraph>=0.2.0",
    "langgraph-checkpoint-redis>=0.1.0",
    "python-dotenv>=1.0.0",
    "pydantic>=2.0.0",
    "pydantic-settings>=2.0.0",
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.30.0",
    "httpx>=0.27.0",
    "redis>=5.0.0",
    "asyncpg>=0.29.0",
    # 新增诊断系统依赖
    "pgvector>=0.2.0",
    "langchain-community>=0.2.0",
]
```

**Step 3: 安装依赖**

```bash
uv sync
```

**Step 4: 验证安装**

```bash
uv run python -c "import pgvector; print('pgvector installed')"
```

**Step 5: 提交**

```bash
git add pyproject.toml
git commit -m "feat: add pgvector and langchain-community dependencies"
```

---

### Task 1.3: 扩展配置管理

**Files:**
- Modify: `src/config.py`

**Step 1: 添加诊断相关配置**

在 `Settings` 类中添加新字段：

```python
# src/config.py - 在 Settings 类中添加

# 诊断系统配置 (通义千问 DashScope)
embedding_model: str = Field(default="text-embedding-v3", alias="EMBEDDING_MODEL")
dashscope_api_key: str = Field(default="", alias="DASHSCOPE_API_KEY")

# SpringBoot 回调配置
springboot_callback_timeout: int = Field(default=30, alias="SPRINGBOOT_CALLBACK_TIMEOUT")
springboot_callback_retry_max: int = Field(default=3, alias="SPRINGBOOT_CALLBACK_RETRY_MAX")
```

**Step 2: 添加 embedding 配置方法**

```python
# src/config.py - 添加新方法

def get_embedding_config(self) -> dict:
    """获取 Embedding 模型配置 (通义千问 DashScope)"""
    return {
        "model": self.embedding_model,
        "api_key": self.dashscope_api_key,
    }
```

**Step 3: 更新 .env.example**

```bash
# .env.example - 添加新配置
DASHSCOPE_API_KEY=your_dashscope_api_key_here
EMBEDDING_MODEL=text-embedding-v3
SPRINGBOOT_CALLBACK_TIMEOUT=30
SPRINGBOOT_CALLBACK_RETRY_MAX=3
```

**Step 4: 运行测试验证配置加载**

```bash
# 创建临时测试文件
echo "from src.config import settings; print(f'Embedding: {settings.embedding_model}')" > /tmp/test_config.py
uv run python /tmp/test_config.py
rm /tmp/test_config.py
```

**Step 5: 提交**

```bash
git add src/config.py .env.example
git commit -m "feat: add diagnosis system configuration"
```

---

## Phase 2: 数据模型层

### Task 2.1: 创建诊断专用数据模型

**Files:**
- Create: `src/models/diagnosis_schemas.py`

**Step 1: 创建模型文件**

```bash
touch src/models/diagnosis_schemas.py
```

**Step 2: 编写基础数据模型**

```python
# src/models/diagnosis_schemas.py
"""
钻井液诊断系统数据模型

定义诊断相关的请求/响应模型
"""

from pydantic import BaseModel, Field, field_validator
from typing import Literal, Optional, List, Dict, Any
from datetime import datetime
from uuid import uuid4


# ========== 基础模型 ==========

class AlertThreshold(BaseModel):
    """预警阈值配置"""
    field: str = Field(..., description="阈值字段名")
    condition: Literal["greater_than", "less_than", "equal", "between"] = Field(
        ..., description="阈值条件"
    )
    threshold: float = Field(..., description="阈值")
    current_value: float = Field(..., description="当前值")


class DrillingFluidSample(BaseModel):
    """钻井液采样数据"""
    id: str
    well_id: str
    sample_time: datetime
    formation: str
    outlet_temp: float
    density: float
    gel_10s: float
    gel_10m: float
    rpm_3: float
    rpm_6: float
    rpm_100: float
    rpm_200: float
    rpm_300: float
    rpm_600: float
    plastic_viscosity: float
    yield_point: float
    flow_behavior_index: float
    consistency_coefficient: float
    apparent_viscosity: float
    yield_plastic_ratio: float


class DiagnosisContext(BaseModel):
    """诊断上下文信息"""
    current_depth: Optional[float] = None
    formation_type: Optional[str] = None
    drilling_phase: Optional[str] = None
    recent_operations: Optional[List[str]] = None


# ========== 请求模型 ==========

class DiagnosisRequest(BaseModel):
    """诊断分析请求"""
    task_id: str = Field(default_factory=lambda: f"TASK-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:6].upper()}")
    well_id: str = Field(..., description="井号")
    alert_type: str = Field(..., description="预警类型")
    alert_triggered_at: datetime = Field(..., description="预警触发时间")
    alert_threshold: AlertThreshold = Field(..., description="阈值配置")
    samples: List[DrillingFluidSample] = Field(..., min_length=1, max_length=20, description="采样数据")
    context: Optional[DiagnosisContext] = Field(default_factory=DiagnosisContext, description="上下文信息")
    callback_url: Optional[str] = Field(None, description="回调URL")
    stream: bool = Field(True, description="是否流式返回")

    @field_validator("samples")
    @classmethod
    def validate_samples(cls, v):
        """验证采样数据"""
        if not v:
            raise ValueError("至少需要一条采样数据")
        # 按时间排序（最新的在前）
        return sorted(v, key=lambda x: x.sample_time, reverse=True)


# ========== 响应模型 ==========

class TrendAnalysis(BaseModel):
    """趋势分析结果"""
    field: str
    trend: Literal["rising", "falling", "stable", "fluctuating"]
    from_value: float
    to_value: float
    rate: float
    duration: str
    acceleration: Optional[Literal["increasing", "decreasing", "constant"]] = None


class DiagnosisConclusion(BaseModel):
    """诊断结论"""
    summary: str
    cause: str
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    trend_outlook: Optional[str] = None


class TreatmentMeasure(BaseModel):
    """处置措施"""
    step: int
    action: str
    duration: Optional[str] = None
    amount: Optional[str] = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    notes: Optional[str] = None


class Prescription(BaseModel):
    """配药方案"""
    dilution_water: Optional[str] = None
    viscosity_reducer: Optional[str] = None
    mixing_time: Optional[str] = None
    other_agents: Optional[Dict[str, str]] = None


class DiagnosisResult(BaseModel):
    """完整诊断结果"""
    diagnosis: DiagnosisConclusion
    trend_analysis: Optional[List[TrendAnalysis]] = None
    measures: List[TreatmentMeasure]
    prescription: Prescription
    references: Optional[List[str]] = None


# ========== SSE 事件模型 ==========

class DiagnosisEventType(BaseModel):
    """诊断事件类型"""
    pass


class DiagnosisEvent(BaseModel):
    """诊断 SSE 事件"""
    type: Literal[
        "start", "thinking", "trend_analysis", "retrieval",
        "diagnosis", "prescription", "result", "done", "error"
    ] = Field(..., description="事件类型")

    # 通用字段
    task_id: Optional[str] = None
    well_id: Optional[str] = None

    # 特定事件字段
    content: Optional[str] = None
    step: Optional[str] = None

    # trend_analysis 事件字段
    field: Optional[str] = None
    analysis: Optional[Dict[str, Any]] = None

    # retrieval 事件字段
    docs_found: Optional[int] = None
    sources: Optional[List[str]] = None

    # diagnosis 事件字段
    summary: Optional[str] = None
    cause: Optional[str] = None
    risk_level: Optional[str] = None
    trend_outlook: Optional[str] = None

    # prescription 事件字段
    action: Optional[str] = None
    prescription: Optional[Dict[str, Any]] = None

    # result 事件字段
    result: Optional[DiagnosisResult] = None

    # done 事件字段
    completed_at: Optional[datetime] = None
    status: Optional[str] = None
    tokens_used: Optional[int] = None

    # error 事件字段
    error_code: Optional[str] = None

    def to_sse(self) -> str:
        """转换为 SSE 格式"""
        return f"data: {self.model_dump_json(exclude_none=True)}\n\n"

    @classmethod
    def start(cls, task_id: str, well_id: str, samples_count: int) -> "DiagnosisEvent":
        """创建开始事件"""
        return cls(
            type="start",
            task_id=task_id,
            well_id=well_id,
            content=f"开始分析井号 {well_id} 的 {samples_count} 条采样数据"
        )

    @classmethod
    def thinking(cls, task_id: str, content: str, step: str) -> "DiagnosisEvent":
        """创建思考事件"""
        return cls(type="thinking", task_id=task_id, content=content, step=step)

    @classmethod
    def error(cls, task_id: str, error_code: str, message: str) -> "DiagnosisEvent":
        """创建错误事件"""
        return cls(type="error", task_id=task_id, error_code=error_code, content=message)


# ========== 回调模型 ==========

class CallbackRequest(BaseModel):
    """回调请求（Agent 发送给 SpringBoot）"""
    task_id: str
    well_id: str
    status: Literal["SUCCESS", "FAILED", "PARTIAL"]
    completed_at: datetime
    result: Optional[DiagnosisResult] = None
    error: Optional[str] = None


# ========== 知识库模型 ==========

class KnowledgeDocumentCreate(BaseModel):
    """创建知识文档请求"""
    doc_id: str
    title: str
    category: str
    subcategory: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None


class KnowledgeDocumentResponse(BaseModel):
    """知识文档响应"""
    id: str
    doc_id: str
    title: str
    category: str
    subcategory: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None
    chunk_count: int
    created_at: datetime


class KnowledgeSearchRequest(BaseModel):
    """知识检索请求"""
    query: str
    category: Optional[str] = None
    top_k: int = Field(default=5, ge=1, le=20)
```

**Step 3: 验证模型导入**

```bash
uv run python -c "from src.models.diagnosis_schemas import DiagnosisRequest; print('Models imported successfully')"
```

**Step 4: 提交**

```bash
git add src/models/diagnosis_schemas.py
git commit -m "feat: add diagnosis system data models"
```

---

### Task 2.2: 扩展异常定义

**Files:**
- Modify: `src/models/exceptions.py`

**Step 1: 添加诊断系统异常**

在文件末尾添加：

```python
# src/models/exceptions.py - 添加以下异常类

class DiagnosisError(AppException):
    """诊断分析失败"""

    def __init__(self, message: str):
        super().__init__(message, "DIAGNOSIS_ERROR")


class RAGError(AppException):
    """RAG 检索失败"""

    def __init__(self, message: str):
        super().__init__(message, "RAG_ERROR")


class CallbackError(AppException):
    """回调失败"""

    def __init__(self, message: str):
        super().__init__(message, "CALLBACK_ERROR")


class KnowledgeBaseError(AppException):
    """知识库操作失败"""

    def __init__(self, message: str):
        super().__init__(message, "KNOWLEDGE_BASE_ERROR")
```

**Step 2: 验证导入**

```bash
uv run python -c "from src.models.exceptions import DiagnosisError, RAGError; print('Exceptions imported')"
```

**Step 3: 提交**

```bash
git add src/models/exceptions.py
git commit -m "feat: add diagnosis system exceptions"
```

---

## Phase 3: 数据访问层

### Task 3.1: 创建诊断仓储

**Files:**
- Create: `src/repositories/diagnosis_repo.py`

**Step 1: 创建仓储文件**

```bash
touch src/repositories/diagnosis_repo.py
```

**Step 2: 编写诊断仓储类**

```python
# src/repositories/diagnosis_repo.py
"""
诊断系统数据仓储

处理诊断任务、结果、事件的数据库操作
"""

import json
from uuid import UUID, uuid4
from datetime import datetime
from typing import Optional, List, Dict, Any

import asyncpg

from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisResult,
    DiagnosisEvent,
)
from src.models.exceptions import AppException


class DiagnosisRepository:
    """诊断数据仓储类"""

    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    # ========== 诊断任务操作 ==========

    async def create_task(
        self,
        request: DiagnosisRequest,
        status: str = "PENDING"
    ) -> str:
        """创建诊断任务"""
        task_id = request.task_id
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_tasks
                (task_id, well_id, alert_type, alert_triggered_at,
                 alert_threshold, samples, context, callback_url, status, started_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                """,
                task_id,
                request.well_id,
                request.alert_type,
                request.alert_triggered_at,
                json.dumps(request.alert_threshold.model_dump()),
                json.dumps([s.model_dump() for s in request.samples]),
                json.dumps(request.context.model_dump() if request.context else {}),
                request.callback_url,
                status,
                datetime.now()
            )
        return task_id

    async def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取诊断任务"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM diagnosis_tasks WHERE task_id = $1",
                task_id
            )
            return dict(row) if row else None

    async def update_task_status(
        self,
        task_id: str,
        status: str,
        completed_at: Optional[datetime] = None
    ) -> bool:
        """更新任务状态"""
        async with self.pool.acquire() as conn:
            result = await conn.execute(
                """
                UPDATE diagnosis_tasks
                SET status = $1, completed_at = $2
                WHERE task_id = $3
                """,
                status,
                completed_at or datetime.now(),
                task_id
            )
            return result == "UPDATE 1"

    async def get_latest_task_by_well(self, well_id: str) -> Optional[Dict[str, Any]]:
        """获取井号最新的诊断任务"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM diagnosis_tasks
                WHERE well_id = $1
                ORDER BY created_at DESC
                LIMIT 1
                """,
                well_id
            )
            return dict(row) if row else None

    # ========== 诊断结果操作 ==========

    async def save_result(
        self,
        task_id: str,
        result: DiagnosisResult,
        rag_metadata: Optional[Dict[str, Any]] = None,
        llm_metadata: Optional[Dict[str, Any]] = None
    ) -> bool:
        """保存诊断结果"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_results
                (task_id, diagnosis, trend_analysis, measures, prescription,
                 references, rag_metadata, llm_metadata)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (task_id) DO UPDATE
                SET diagnosis = $2, trend_analysis = $3, measures = $4,
                    prescription = $5, references = $6,
                    rag_metadata = $7, llm_metadata = $8
                """,
                task_id,
                json.dumps(result.diagnosis.model_dump()),
                json.dumps([t.model_dump() for t in result.trend_analysis]) if result.trend_analysis else None,
                json.dumps([m.model_dump() for m in result.measures]),
                json.dumps(result.prescription.model_dump()),
                json.dumps(result.references) if result.references else None,
                json.dumps(rag_metadata or {}),
                json.dumps(llm_metadata or {})
            )
        return True

    async def get_result(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取诊断结果"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM diagnosis_results WHERE task_id = $1",
                task_id
            )
            return dict(row) if row else None

    # ========== 事件记录操作 ==========

    async def save_event(
        self,
        task_id: str,
        event_type: str,
        event_data: Dict[str, Any],
        sequence_num: int
    ) -> bool:
        """保存诊断事件"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_events
                (task_id, event_type, event_data, sequence_num)
                VALUES ($1, $2, $3, $4)
                """,
                task_id,
                event_type,
                json.dumps(event_data),
                sequence_num
            )
        return True

    async def get_events(
        self,
        task_id: str,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """获取诊断事件列表"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM diagnosis_events
                WHERE task_id = $1
                ORDER BY sequence_num ASC
                LIMIT $2
                """,
                task_id,
                limit
            )
            return [dict(row) for row in rows]
```

**Step 3: 提交**

```bash
git add src/repositories/diagnosis_repo.py
git commit -m "feat: add diagnosis repository"
```

---

### Task 3.2: 创建知识库仓储

**Files:**
- Create: `src/repositories/knowledge_repo.py`

**Step 1: 创建仓储文件**

```bash
touch src/repositories/knowledge_repo.py
```

**Step 2: 编写知识库仓储类**

```python
# src/repositories/knowledge_repo.py
"""
知识库数据仓储

处理知识文档和向量分块的数据库操作
"""

import json
from uuid import UUID
from typing import Optional, List, Dict, Any

import asyncpg

from src.models.diagnosis_schemas import KnowledgeDocumentCreate
from src.models.exceptions import KnowledgeBaseError


class KnowledgeRepository:
    """知识库仓储类"""

    def __init__(self, pool: asyncpg.Pool, embedding_client=None):
        self.pool = pool
        self.embedding_client = embedding_client

    # ========== 知识文档操作 ==========

    async def create_document(self, doc: KnowledgeDocumentCreate) -> str:
        """创建知识文档（不含分块）"""
        async with self.pool.acquire() as conn:
            try:
                await conn.execute(
                    """
                    INSERT INTO knowledge_documents
                    (doc_id, title, category, subcategory, content, metadata)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    """,
                    doc.doc_id,
                    doc.title,
                    doc.category,
                    doc.subcategory,
                    doc.content,
                    json.dumps(doc.metadata or {})
                )
                return doc.doc_id
            except asyncpg.UniqueViolationError:
                raise KnowledgeBaseError(f"文档 {doc.doc_id} 已存在")

    async def get_document(self, doc_id: str) -> Optional[Dict[str, Any]]:
        """获取知识文档"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )
            return dict(row) if row else None

    async def delete_document(self, doc_id: str) -> bool:
        """删除知识文档及其分块"""
        async with self.pool.acquire() as conn:
            # 先删除分块
            await conn.execute(
                "DELETE FROM knowledge_chunks WHERE parent_doc_id = $1",
                doc_id
            )
            # 再删除文档
            result = await conn.execute(
                "DELETE FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )
            return result == "DELETE 1"

    async def list_documents(
        self,
        category: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """列出知识文档"""
        async with self.pool.acquire() as conn:
            if category:
                rows = await conn.fetch(
                    """
                    SELECT * FROM knowledge_documents
                    WHERE category = $1
                    ORDER BY created_at DESC
                    LIMIT $2
                    """,
                    category,
                    limit
                )
            else:
                rows = await conn.fetch(
                    """
                    SELECT * FROM knowledge_documents
                    ORDER BY created_at DESC
                    LIMIT $1
                    """,
                    limit
                )
            return [dict(row) for row in rows]

    # ========== 向量分块操作 ==========

    async def create_chunks(
        self,
        doc_id: str,
        chunks: List[Dict[str, Any]]
    ) -> int:
        """创建文档分块（含向量）"""
        if not self.embedding_client:
            raise KnowledgeBaseError("Embedding 客户端未配置")

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                # 删除旧分块
                await conn.execute(
                    "DELETE FROM knowledge_chunks WHERE parent_doc_id = $1",
                    doc_id
                )

                # 插入新分块
                for idx, chunk in enumerate(chunks):
                    content = chunk["content"]
                    # 生成 embedding
                    embedding = await self._embed_text(content)

                    await conn.execute(
                        """
                        INSERT INTO knowledge_chunks
                        (parent_doc_id, chunk_index, content, embedding)
                        VALUES ($1, $2, $3, $4)
                        """,
                        doc_id,
                        idx,
                        content,
                        str(embedding.tolist())  # 转换为字符串存储
                    )

                # 更新文档的分块计数
                await conn.execute(
                    "UPDATE knowledge_documents SET chunk_count = $1 WHERE doc_id = $2",
                    len(chunks),
                    doc_id
                )

        return len(chunks)

    async def vector_search(
        self,
        query: str,
        top_k: int = 5,
        category: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """向量搜索"""
        if not self.embedding_client:
            raise KnowledgeBaseError("Embedding 客户端未配置")

        # 生成查询向量
        query_embedding = await self._embed_text(query)

        async with self.pool.acquire() as conn:
            if category:
                # 使用子查询先筛选分类
                rows = await conn.fetch(
                    """
                    SELECT DISTINCT ON (kd.doc_id)
                        kd.doc_id, kd.title, kd.category, kd.content,
                        kc.embedding <-> $1 as distance
                    FROM knowledge_documents kd
                    JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                    WHERE kd.category = $2
                    ORDER BY kd.doc_id, distance
                    LIMIT $3
                    """,
                    str(query_embedding.tolist()),
                    category,
                    top_k
                )
            else:
                rows = await conn.fetch(
                    """
                    SELECT DISTINCT ON (kd.doc_id)
                        kd.doc_id, kd.title, kd.category, kd.content,
                        MIN(kc.embedding <-> $1) as distance
                    FROM knowledge_documents kd
                    JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                    GROUP BY kd.doc_id, kd.title, kd.category, kd.content
                    ORDER BY distance
                    LIMIT $2
                    """,
                    str(query_embedding.tolist()),
                    top_k
                )

            return [dict(row) for row in rows]

    async def _embed_text(self, text: str) -> List[float]:
        """生成文本 embedding（需实现）"""
        # TODO: 在后续任务中实现具体的 embedding 调用
        raise NotImplementedError("Embedding 方法将在 RAG 服务中实现")

    async def get_chunks_by_doc(self, doc_id: str) -> List[Dict[str, Any]]:
        """获取文档的所有分块"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT chunk_index, content
                FROM knowledge_chunks
                WHERE parent_doc_id = $1
                ORDER BY chunk_index
                """,
                doc_id
            )
            return [dict(row) for row in rows]
```

**Step 3: 提交**

```bash
git add src/repositories/knowledge_repo.py
git commit -m "feat: add knowledge repository"
```

---

## Phase 4: 服务层

### Task 4.1: 创建 RAG 服务

**Files:**
- Create: `src/services/rag_service.py`

**Step 1: 创建服务文件**

```bash
touch src/services/rag_service.py
```

**Step 2: 编写 RAG 服务类**

```python
# src/services/rag_service.py
"""
RAG 检索增强生成服务

提供知识库检索和文档管理功能
"""

import logging
from typing import List, Dict, Any, Optional

from langchain_community.embeddings import DashScopeEmbeddings
from langchain.text_splitter import RecursiveCharacterTextSplitter

from src.repositories.knowledge_repo import KnowledgeRepository
from src.models.diagnosis_schemas import KnowledgeDocumentCreate, KnowledgeSearchRequest
from src.models.exceptions import RAGError
from src.config import settings

logger = logging.getLogger(__name__)


class RAGService:
    """RAG 检索服务"""

    def __init__(self, knowledge_repo: KnowledgeRepository):
        self.knowledge_repo = knowledge_repo
        self.embeddings = None
        self._init_embeddings()

    def _init_embeddings(self):
        """初始化 Embedding 客户端 (通义千问 DashScope)"""
        try:
            self.embeddings = DashScopeEmbeddings(
                model=settings.embedding_model,
                dashscope_api_key=settings.dashscope_api_key,
            )
            # 将 embeddings 注入到 repo
            self.knowledge_repo.embedding_client = self.embeddings
            logger.info(f"RAG service initialized with {settings.embedding_model}")
        except Exception as e:
            logger.warning(f"Failed to initialize embeddings: {e}")

    # ========== 文档管理 ==========

    async def create_document(
        self,
        doc: KnowledgeDocumentCreate,
        auto_chunk: bool = True
    ) -> str:
        """创建知识文档（自动分块和向量化）"""
        try:
            # 1. 创建文档记录
            doc_id = await self.knowledge_repo.create_document(doc)
            logger.info(f"Created document: {doc_id}")

            # 2. 自动分块和向量化
            if auto_chunk:
                chunks = self._split_text(doc.content)
                await self.knowledge_repo.create_chunks(doc_id, chunks)
                logger.info(f"Created {len(chunks)} chunks for {doc_id}")

            return doc_id
        except Exception as e:
            logger.error(f"Failed to create document: {e}")
            raise RAGError(f"创建文档失败: {str(e)}")

    def _split_text(self, text: str) -> List[Dict[str, Any]]:
        """文本分块（父子分块策略）"""
        # 子分块器：用于向量检索（小块，高精度）
        child_splitter = RecursiveCharacterTextSplitter(
            chunk_size=600,
            chunk_overlap=100,
            length_function=len,
        )

        chunks = []
        for idx, chunk in enumerate(child_splitter.split_text(text)):
            chunks.append({
                "content": chunk
            })

        return chunks

    async def get_document(self, doc_id: str) -> Optional[Dict[str, Any]]:
        """获取知识文档"""
        return await self.knowledge_repo.get_document(doc_id)

    async def delete_document(self, doc_id: str) -> bool:
        """删除知识文档"""
        return await self.knowledge_repo.delete_document(doc_id)

    async def list_documents(
        self,
        category: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """列出知识文档"""
        return await self.knowledge_repo.list_documents(category, limit)

    # ========== 语义检索 ==========

    async def search(
        self,
        query: str,
        top_k: int = 5,
        category: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """语义检索"""
        try:
            results = await self.knowledge_repo.vector_search(
                query=query,
                top_k=top_k,
                category=category
            )

            # 格式化结果
            formatted = []
            for r in results:
                formatted.append({
                    "doc_id": r["doc_id"],
                    "title": r["title"],
                    "category": r["category"],
                    "content": r["content"],
                    "distance": float(r.get("distance", 0))
                })

            return formatted
        except Exception as e:
            logger.error(f"Search failed: {e}")
            raise RAGError(f"检索失败: {str(e)}")

    # ========== 重建索引 ==========

    async def rebuild_index(self, doc_id: Optional[str] = None) -> Dict[str, int]:
        """重建向量索引"""
        # 如果指定 doc_id，只重建该文档
        if doc_id:
            doc = await self.get_document(doc_id)
            if not doc:
                raise RAGError(f"文档 {doc_id} 不存在")

            chunks = self._split_text(doc["content"])
            await self.knowledge_repo.create_chunks(doc_id, chunks)
            return {"rebuilt": 1}

        # 否则重建所有文档
        docs = await self.list_documents(limit=1000)
        count = 0
        for doc in docs:
            try:
                chunks = self._split_text(doc["content"])
                await self.knowledge_repo.create_chunks(doc["doc_id"], chunks)
                count += 1
            except Exception as e:
                logger.error(f"Failed to rebuild {doc['doc_id']}: {e}")

        return {"rebuilt": count}
```

**Step 3: 提交**

```bash
git add src/services/rag_service.py
git commit -m "feat: add RAG service"
```

---

### Task 4.2: 创建回调服务

**Files:**
- Create: `src/services/callback_service.py`

**Step 1: 创建服务文件**

```bash
touch src/services/callback_service.py
```

**Step 2: 编写回调服务类**

```python
# src/services/callback_service.py
"""
回调服务

处理向 SpringBoot 后端的回调请求
"""

import logging
from typing import Optional
import asyncio

import httpx

from src.models.diagnosis_schemas import CallbackRequest
from src.models.exceptions import CallbackError
from src.config import settings

logger = logging.getLogger(__name__)


class CallbackService:
    """回调服务"""

    def __init__(self):
        self.timeout = settings.springboot_callback_timeout
        self.retry_max = settings.springboot_callback_retry_max

    async def send_callback(
        self,
        url: str,
        request: CallbackRequest
    ) -> bool:
        """发送回调请求（带重试）"""
        last_error = None

        for attempt in range(1, self.retry_max + 1):
            try:
                async with httpx.AsyncClient(timeout=self.timeout) as client:
                    response = await client.post(
                        url,
                        json=request.model_dump(exclude_none=True),
                        headers={"Content-Type": "application/json"}
                    )

                    if response.status_code in (200, 201, 202):
                        logger.info(f"Callback succeeded: {request.task_id}")
                        return True
                    else:
                        last_error = f"HTTP {response.status_code}: {response.text}"

            except httpx.TimeoutException:
                last_error = f"Timeout after {self.timeout}s"
            except httpx.HTTPError as e:
                last_error = str(e)
            except Exception as e:
                last_error = str(e)

            # 指数退避
            if attempt < self.retry_max:
                wait_time = 5 * (2 ** (attempt - 1))  # 5s, 10s, 20s
                logger.warning(f"Callback attempt {attempt} failed: {last_error}, retrying in {wait_time}s")
                await asyncio.sleep(wait_time)

        # 所有重试失败
        logger.error(f"Callback failed after {self.retry_max} attempts: {last_error}")
        raise CallbackError(f"回调失败: {last_error}")

    async def send_callback_safe(
        self,
        url: str,
        request: CallbackRequest
    ) -> bool:
        """安全发送回调（不抛出异常）"""
        try:
            return await self.send_callback(url, request)
        except Exception as e:
            logger.error(f"Safe callback failed: {e}")
            return False
```

**Step 3: 提交**

```bash
git add src/services/callback_service.py
git commit -m "feat: add callback service"
```

---

### Task 4.3: 创建诊断 Agent

**Files:**
- Create: `src/agents/diagnosis_agent.py`

**Step 1: 创建 Agent 文件**

```bash
mkdir -p src/agents
touch src/agents/diagnosis_agent.py
```

**Step 2: 编写诊断 Agent 类**

```python
# src/agents/diagnosis_agent.py
"""
钻井液诊断 Agent

基于 LangChain create_agent 构建诊断分析 Agent
"""

import logging
from typing import AsyncIterator, Dict, Any

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage
from langchain.agents import create_agent

from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    DiagnosisResult,
)
from src.models.exceptions import DiagnosisError, LLMError
from src.config import settings, get_llm_config

logger = logging.getLogger(__name__)


class DiagnosisAgent:
    """钻井液诊断 Agent"""

    def __init__(self, checkpointer=None):
        self.checkpointer = checkpointer
        self.model = ChatOpenAI(
            **get_llm_config(),
            streaming=True,
            temperature=0.3  # 诊断分析需要更稳定的输出
        )
        self.agent = None

    async def initialize(self):
        """初始化 Agent"""
        try:
            await self.checkpointer.setup() if self.checkpointer else None
            self._build_agent()
            logger.info("DiagnosisAgent initialized")
        except Exception as e:
            logger.error(f"Failed to initialize DiagnosisAgent: {e}")
            raise DiagnosisError(f"Agent 初始化失败: {str(e)}")

    async def cleanup(self):
        """清理资源"""
        if self.checkpointer:
            await self.checkpointer.close()
            logger.info("DiagnosisAgent cleaned up")

    def _build_agent(self):
        """构建诊断 Agent"""
        # 定义诊断专用工具
        from src.tools.diagnosis_tools import (
            analyze_trend,
            search_knowledge,
            format_prescription
        )

        tools = [
            analyze_trend,
            search_knowledge,
            format_prescription
        ]

        # 系统提示词
        system_prompt = """你是一位钻井液性能诊断专家。你的职责是：

1. 分析钻井液采样数据，识别异常趋势
2. 基于历史数据和知识库，诊断问题原因
3. 提供具体的处置措施和配药方案
4. 评估风险等级并提供趋势预测

请使用提供的工具进行分析：
- analyze_trend: 分析参数趋势
- search_knowledge: 检索处置知识
- format_prescription: 生成配药方案

输出应专业、准确、可操作。"""

        self.agent = create_agent(
            model=self.model,
            tools=tools,
            system_prompt=system_prompt,
            checkpointer=self.checkpointer,
        )

    async def analyze(
        self,
        request: DiagnosisRequest
    ) -> AsyncIterator[DiagnosisEvent]:
        """执行诊断分析（流式输出）"""
        task_id = request.task_id

        try:
            # 发送开始事件
            yield DiagnosisEvent.start(
                task_id=task_id,
                well_id=request.well_id,
                samples_count=len(request.samples)
            )

            # 构建分析提示
            prompt = self._build_analysis_prompt(request)

            # 发送思考事件
            yield DiagnosisEvent.thinking(
                task_id=task_id,
                content=f"正在分析 {len(request.samples)} 条采样数据...",
                step="data_analysis"
            )

            # 调用 Agent
            config = {
                "configurable": {
                    "thread_id": task_id
                }
            }

            full_response = ""
            async for event in self.agent.astream(
                {"messages": [HumanMessage(content=prompt)]},
                config=config,
                stream_mode="messages",
            ):
                if isinstance(event, tuple) and len(event) >= 1:
                    message = event[0]
                    if isinstance(message, AIMessage):
                        if message.content:
                            full_response += message.content
                            # 发送思考事件
                            yield DiagnosisEvent.thinking(
                                task_id=task_id,
                                content=message.content,
                                step="reasoning"
                            )

            # 解析结果
            result = self._parse_result(full_response, request)
            yield DiagnosisEvent(
                type="result",
                task_id=task_id,
                result=result
            )

            # 完成
            yield DiagnosisEvent(
                type="done",
                task_id=task_id,
                status="SUCCESS"
            )

        except Exception as e:
            logger.error(f"Analysis failed: {e}")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="ANALYSIS_FAILED",
                message=str(e)
            )

    def _build_analysis_prompt(self, request: DiagnosisRequest) -> str:
        """构建分析提示词"""
        # 格式化采样数据
        samples_text = "\n".join([
            f"- {s.sample_time.strftime('%H:%M')}: 密度={s.density}, PV={s.plastic_viscosity}, YP={s.yield_point}"
            for s in request.samples[:5]  # 只显示前5条
        ])

        prompt = f"""请分析以下钻井液数据：

**井号**: {request.well_id}
**预警类型**: {request.alert_type}
**当前值**: {request.alert_threshold.current_value}
**阈值**: {request.alert_threshold.threshold}

**采样数据**（最近{len(request.samples)}条）:
{samples_text}

**上下文**:
- 当前深度: {request.context.current_depth}m
- 岩性: {request.context.formation_type}
- 钻井阶段: {request.context.drilling_phase}

请执行以下分析：
1. 趋势分析：使用 analyze_trend 工具分析主要参数趋势
2. 知识检索：使用 search_knowledge 检索相关处置措施
3. 生成配药方案：使用 format_prescription 生成具体方案

最后给出诊断结论和风险等级评估。"""

        return prompt

    def _parse_result(self, response: str, request: DiagnosisRequest) -> DiagnosisResult:
        """解析 Agent 响应为结构化结果"""
        # TODO: 实现更智能的解析逻辑
        # 这里先返回一个基础结构

        from src.models.diagnosis_schemas import (
            DiagnosisConclusion,
            TreatmentMeasure,
            Prescription
        )

        return DiagnosisResult(
            diagnosis=DiagnosisConclusion(
                summary="待实现智能解析",
                cause="待分析",
                risk_level="MEDIUM"
            ),
            trend_analysis=[],
            measures=[
                TreatmentMeasure(
                    step=1,
                    action="待完善",
                    priority="MEDIUM"
                )
            ],
            prescription=Prescription()
        )
```

**Step 3: 提交**

```bash
git add src/agents/diagnosis_agent.py
git commit -m "feat: add diagnosis agent"
```

---

### Task 4.4: 创建诊断工具

**Files:**
- Create: `src/tools/diagnosis_tools.py`

**Step 1: 创建工具文件**

```bash
touch src/tools/diagnosis_tools.py
```

**Step 2: 编写诊断工具**

```python
# src/tools/diagnosis_tools.py
"""
钻井液诊断专用工具集

提供给 Agent 使用的诊断分析工具
"""

from typing import Annotated, List, Dict, Any
from datetime import datetime, timedelta

from langchain.tools import tool


@tool
def analyze_trend(
    samples: Annotated[List[Dict[str, Any]], "采样数据列表，包含 field 和 value"],
    field: Annotated[str, "要分析的字段名，如 'density', 'plastic_viscosity'"] = "density"
) -> str:
    """分析钻井液参数趋势。

    计算指定字段的变化趋势、变化率和加速度。

    Args:
        samples: 采样数据列表，每个元素包含 sample_time 和指定字段的值
        field: 要分析的字段名

    Returns:
        趋势分析结果的字符串描述
    """
    if not samples or len(samples) < 2:
        return "数据不足，无法分析趋势"

    # 按时间排序（最早的在前）
    sorted_samples = sorted(samples, key=lambda x: x.get("sample_time", ""))

    # 提取字段值
    values = []
    for s in sorted_samples:
        if field in s:
            val = s[field]
            if isinstance(val, (int, float)):
                values.append(val)

    if len(values) < 2:
        return f"字段 {field} 数据不足"

    # 计算趋势
    first_val = values[0]
    last_val = values[-1]
    change = last_val - first_val
    change_rate = abs(change / first_val) if first_val != 0 else 0

    # 判断趋势方向
    if abs(change) < 0.01:
        trend = "stable"
        trend_cn = "稳定"
    elif change > 0:
        trend = "rising"
        trend_cn = "上升"
    else:
        trend = "falling"
        trend_cn = "下降"

    # 计算时间跨度
    if "sample_time" in sorted_samples[0] and "sample_time" in sorted_samples[-1]:
        time_span = sorted_samples[-1]["sample_time"] - sorted_samples[0]["sample_time"]
        duration_str = f"{time_span.total_seconds() / 60:.0f}分钟"
    else:
        duration_str = "未知"

    result = f"""趋势分析结果（{field}）:
- 趋势: {trend_cn}
- 起始值: {first_val:.3f}
- 结束值: {last_val:.3f}
- 变化量: {change:+.3f}
- 变化率: {change_rate*100:.1f}%
- 时间跨度: {duration_str}"""

    return result


@tool
def search_knowledge(
    query: Annotated[str, "检索查询语句"],
    category: Annotated[str, "知识分类，如 'density', 'viscosity', 'gel'"] = "density",
    top_k: Annotated[int, "返回结果数量"] = 5
) -> str:
    """检索专家知识库。

    根据查询语句和分类从知识库中检索相关的处置措施。

    Args:
        query: 检索查询语句
        category: 知识分类
        top_k: 返回结果数量

    Returns:
        检索结果摘要
    """
    # TODO: 集成实际的 RAG 服务
    # 这里返回模拟数据

    mock_results = {
        "density": [
            "密度偏高处置：加水稀释，通常加水量为 5-10%",
            "密度偏低处置：加重材料如重晶石",
        ],
        "viscosity": [
            "塑性黏度偏高：加水稀释或使用降黏剂",
            "动切力偏低：提高膨润土含量或增粘剂"
        ],
        "gel": [
            "切力偏高：使用降切剂",
            "切力偏低：提高膨润土含量"
        ]
    }

    results = mock_results.get(category, ["未找到相关知识"])

    output = f"知识检索结果（分类: {category}, 查询: {query}）:\n"
    for i, r in enumerate(results, 1):
        output += f"{i}. {r}\n"

    return output


@tool
def format_prescription(
    measures: Annotated[str, "处置措施描述"],
    density: Annotated[float, "当前钻井液密度"] = 1.2,
    plastic_viscosity: Annotated[float, "当前塑性黏度"] = 20
) -> str:
    """生成配药方案。

    根据处置措施和当前参数生成具体的配药方案。

    Args:
        measures: 处置措施描述
        density: 当前密度
        plastic_viscosity: 当前塑性黏度

    Returns:
        配药方案详情
    """
    # 简化规则引擎
    prescription = {
        "稀释水": "0%",
        "降黏剂": "0%",
        "加重剂": "0%",
        "搅拌时间": "30分钟"
    }

    if "密度高" in measures or "密度偏高" in measures:
        prescription["稀释水"] = "8%"
        prescription["搅拌时间"] = "45分钟"

    if "黏度高" in measures or "塑性黏度高" in measures:
        prescription["降黏剂"] = "0.3%"
        prescription["稀释水"] = "5%"

    if "密度低" in measures:
        prescription["加重剂"] = "重晶石 2%"

    output = "配药方案:\n"
    for k, v in prescription.items():
        output += f"- {k}: {v}\n"

    return output


# 导出所有工具
__all__ = ["analyze_trend", "search_knowledge", "format_prescription"]
```

**Step 3: 提交**

```bash
git add src/tools/diagnosis_tools.py
git commit -m "feat: add diagnosis tools"
```

---

### Task 4.5: 创建诊断服务

**Files:**
- Create: `src/services/diagnosis_service.py`

**Step 1: 创建服务文件**

```bash
touch src/services/diagnosis_service.py
```

**Step 2: 编写诊断服务类**

```python
# src/services/diagnosis_service.py
"""
诊断服务

整合诊断 Agent、RAG 服务和回调服务
"""

import logging
from typing import AsyncIterator, Optional

from src.agents.diagnosis_agent import DiagnosisAgent
from src.services.rag_service import RAGService
from src.services.callback_service import CallbackService
from src.repositories.diagnosis_repo import DiagnosisRepository
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    DiagnosisResult,
    CallbackRequest,
)
from src.models.exceptions import DiagnosisError
from src.config import settings
from datetime import datetime

logger = logging.getLogger(__name__)


# 全局服务实例
diagnosis_service = None


class DiagnosisService:
    """诊断服务类"""

    def __init__(
        self,
        agent: DiagnosisAgent,
        rag_service: RAGService,
        callback_service: CallbackService,
        repo: DiagnosisRepository
    ):
        self.agent = agent
        self.rag_service = rag_service
        self.callback_service = callback_service
        self.repo = repo

    async def initialize(self):
        """初始化服务"""
        await self.agent.initialize()
        logger.info("DiagnosisService initialized")

    async def cleanup(self):
        """清理资源"""
        await self.agent.cleanup()
        logger.info("DiagnosisService cleaned up")

    async def analyze(
        self,
        request: DiagnosisRequest
    ) -> AsyncIterator[DiagnosisEvent]:
        """执行诊断分析（完整流程）"""
        task_id = request.task_id

        try:
            # 1. 创建任务记录
            await self.repo.create_task(request, status="PROCESSING")

            # 2. 执行 Agent 分析
            result: Optional[DiagnosisResult] = None
            event_count = 0

            async for event in self.agent.analyze(request):
                # 发送事件
                yield event

                # 记录事件到数据库
                if event.type != "start":
                    await self.repo.save_event(
                        task_id,
                        event.type,
                        event.model_dump(exclude_none=True),
                        event_count
                    )
                    event_count += 1

                # 保存结果
                if event.type == "result" and event.result:
                    result = event.result
                    await self.repo.save_result(
                        task_id,
                        result
                    )

                # 完成
                if event.type == "done":
                    status = event.status or "SUCCESS"
                    await self.repo.update_task_status(
                        task_id,
                        status,
                        datetime.now()
                    )

            # 3. 发送回调（如果有回调地址）
            if request.callback_url and result:
                await self._send_callback(
                    request.callback_url,
                    task_id,
                    request.well_id,
                    result
                )

        except Exception as e:
            logger.error(f"Diagnosis analysis failed: {e}")
            await self.repo.update_task_status(task_id, "FAILED")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="DIAGNOSIS_FAILED",
                message=str(e)
            )

    async def _send_callback(
        self,
        callback_url: str,
        task_id: str,
        well_id: str,
        result: DiagnosisResult
    ):
        """发送结果回调"""
        callback_req = CallbackRequest(
            task_id=task_id,
            well_id=well_id,
            status="SUCCESS",
            completed_at=datetime.now(),
            result=result
        )

        success = await self.callback_service.send_callback_safe(
            callback_url,
            callback_req
        )

        if not success:
            logger.warning(f"Callback to {callback_url} failed, but result was saved")
```

**Step 3: 提交**

```bash
git add src/services/diagnosis_service.py
git commit -m "feat: add diagnosis service"
```

---

## Phase 5: API 层

### Task 5.1: 创建诊断路由

**Files:**
- Create: `src/api/routes/diagnosis.py`

**Step 1: 创建路由文件**

```bash
touch src/api/routes/diagnosis.py
```

**Step 2: 编写诊断路由**

```python
# src/api/routes/diagnosis.py
"""
诊断路由

处理钻井液诊断分析 API 端点
"""

import logging
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from typing import AsyncIterator

from src.api.dependencies import get_user_id
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    CallbackRequest,
    KnowledgeDocumentCreate,
    KnowledgeDocumentResponse,
    KnowledgeSearchRequest,
)
from src.models.exceptions import AppException
from src.services.diagnosis_service import diagnosis_service
from src.services.rag_service import RAGService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/diagnosis", tags=["diagnosis"])


async def sse_generator(events: AsyncIterator[DiagnosisEvent]) -> AsyncIterator[str]:
    """SSE 事件生成器"""
    try:
        async for event in events:
            yield event.to_sse()
    except AppException as e:
        yield DiagnosisEvent.error(
            task_id="",
            error_code=e.code,
            message=e.message
        ).to_sse()


@router.post("/analyze")
async def analyze_diagnosis(
    request: DiagnosisRequest,
    user_id: str = Depends(get_user_id)
):
    """预警诊断分析接口（SSE 流式）"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    return StreamingResponse(
        sse_generator(diagnosis_service.analyze(request)),
        media_type="text/event-stream"
    )


@router.post("/callback")
async def diagnosis_callback(
    callback: CallbackRequest,
    user_id: str = Depends(get_user_id)
):
    """结果回调接口（SpringBoot 调用）"""
    # 这个端点用于 SpringBoot 主动查询或确认回调结果
    # 实际回调由 diagnosis_service 发送
    return {
        "status": "callback_received",
        "task_id": callback.task_id
    }


@router.get("/{task_id}")
async def get_diagnosis_result(
    task_id: str,
    user_id: str = Depends(get_user_id)
):
    """查询诊断任务状态和结果"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    task = await diagnosis_service.repo.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    result = await diagnosis_service.repo.get_result(task_id)

    return {
        "task": task,
        "result": result
    }


# ========== 知识库管理端点 ==========

@router.post("/knowledge/documents", response_model=dict)
async def create_knowledge_document(
    doc: KnowledgeDocumentCreate,
    user_id: str = Depends(get_user_id)
):
    """创建知识文档"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    doc_id = await diagnosis_service.rag_service.create_document(doc)
    return {"doc_id": doc_id, "status": "created"}


@router.get("/knowledge/documents/{doc_id}")
async def get_knowledge_document(
    doc_id: str,
    user_id: str = Depends(get_user_id)
):
    """获取知识文档"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    doc = await diagnosis_service.rag_service.get_document(doc_id)
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    return doc


@router.delete("/knowledge/documents/{doc_id}")
async def delete_knowledge_document(
    doc_id: str,
    user_id: str = Depends(get_user_id)
):
    """删除知识文档"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    success = await diagnosis_service.rag_service.delete_document(doc_id)
    if not success:
        raise HTTPException(status_code=404, detail="Document not found")
    return {"status": "deleted"}


@router.post("/knowledge/search")
async def search_knowledge(
    request: KnowledgeSearchRequest,
    user_id: str = Depends(get_user_id)
):
    """语义检索知识库"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    results = await diagnosis_service.rag_service.search(
        query=request.query,
        top_k=request.top_k,
        category=request.category
    )
    return {"results": results}


@router.post("/knowledge/rebuild")
async def rebuild_knowledge_index(
    doc_id: str = None,
    user_id: str = Depends(get_user_id)
):
    """重建向量索引"""
    if not diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    result = await diagnosis_service.rag_service.rebuild_index(doc_id)
    return result
```

**Step 3: 提交**

```bash
git add src/api/routes/diagnosis.py
git commit -m "feat: add diagnosis API routes"
```

---

### Task 5.2: 集成到主应用

**Files:**
- Modify: `src/api/main.py`

**Step 1: 添加诊断路由导入**

```python
# src/api/main.py - 添加导入
from src.api.routes.diagnosis import router as diagnosis_router
```

**Step 2: 注册诊断路由**

```python
# src/api/main.py - 在 app.include_router(chat_router) 后添加
app.include_router(diagnosis_router)
```

**Step 3: 添加诊断服务初始化**

```python
# src/api/main.py - 添加导入
from src.services.diagnosis_service import DiagnosisService
from src.agents.diagnosis_agent import DiagnosisAgent
from src.services.rag_service import RAGService
from src.services.callback_service import CallbackService
from src.repositories.diagnosis_repo import DiagnosisRepository
from src.repositories.knowledge_repo import KnowledgeRepository
from src.config import settings
```

```python
# src/api/main.py - 修改 lifespan 函数
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    # 启动
    await pg_repo.connect()

    # 初始化 ChatService
    await chat_service.initialize()
    logging.info("ChatService initialized with LangGraph")

    # 初始化 DiagnosisService
    diagnosis_agent = DiagnosisAgent(checkpointer=None)
    diagnosis_rag = RAGService(KnowledgeRepository(pg_repo.pool))
    diagnosis_callback = CallbackService()
    diagnosis_repo = DiagnosisRepository(pg_repo.pool)

    global diagnosis_service
    diagnosis_service = DiagnosisService(
        agent=diagnosis_agent,
        rag_service=diagnosis_rag,
        callback_service=diagnosis_callback,
        repo=diagnosis_repo
    )
    await diagnosis_service.initialize()
    logging.info("DiagnosisService initialized")

    # 启动同步服务
    if settings.redis_stream_sync_enabled:
        sync_service.pg_repo = pg_repo
        sync_service.graph = chat_service.agent
        asyncio.create_task(sync_service.start())
        logging.info("SyncService started")

    yield

    # 关闭
    await sync_service.stop()
    await diagnosis_service.cleanup()
    await chat_service.cleanup()
    await pg_repo.disconnect()
    logging.info("Application shutdown")
```

**Step 4: 提交**

```bash
git add src/api/main.py
git commit -m "feat: integrate diagnosis service into main application"
```

---

## Phase 6: 测试

### Task 6.1: 编写单元测试

**Files:**
- Create: `tests/models/test_diagnosis_schemas.py`
- Create: `tests/repositories/test_diagnosis_repo.py`
- Create: `tests/repositories/test_knowledge_repo.py`
- Create: `tests/services/test_rag_service.py`
- Create: `tests/services/test_diagnosis_service.py`
- Create: `tests/api/test_diagnosis_routes.py`

**Step 1: 创建模型测试**

```python
# tests/models/test_diagnosis_schemas.py
"""测试诊断数据模型"""

import pytest
from datetime import datetime
from src.models.diagnosis_schemas import (
    AlertThreshold,
    DrillingFluidSample,
    DiagnosisContext,
    DiagnosisRequest,
    DiagnosisEvent,
)


def test_alert_threshold():
    """测试预警阈值模型"""
    threshold = AlertThreshold(
        field="density",
        condition="greater_than",
        threshold=1.30,
        current_value=1.35
    )
    assert threshold.field == "density"
    assert threshold.condition == "greater_than"


def test_drilling_fluid_sample():
    """测试采样数据模型"""
    sample = DrillingFluidSample(
        id="SF-001",
        well_id="WELL-001",
        sample_time=datetime.now(),
        formation="砂岩",
        outlet_temp=58.5,
        density=1.22,
        gel_10s=3.5,
        gel_10m=8.2,
        rpm_3=5,
        rpm_6=8,
        rpm_100=45,
        rpm_200=75,
        rpm_300=95,
        rpm_600=160,
        plastic_viscosity=65,
        yield_point=15,
        flow_behavior_index=0.72,
        consistency_coefficient=2.8,
        apparent_viscosity=80,
        yield_plastic_ratio=0.23
    )
    assert sample.density == 1.22


def test_diagnosis_request():
    """测试诊断请求模型"""
    request = DiagnosisRequest(
        well_id="WELL-001",
        alert_type="DENSITY_HIGH",
        alert_triggered_at=datetime.now(),
        alert_threshold=AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        ),
        samples=[
            DrillingFluidSample(
                id="SF-001",
                well_id="WELL-001",
                sample_time=datetime.now(),
                formation="砂岩",
                outlet_temp=58.5,
                density=1.22,
                gel_10s=3.5,
                gel_10m=8.2,
                rpm_3=5,
                rpm_6=8,
                rpm_100=45,
                rpm_200=75,
                rpm_300=95,
                rpm_600=160,
                plastic_viscosity=65,
                yield_point=15,
                flow_behavior_index=0.72,
                consistency_coefficient=2.8,
                apparent_viscosity=80,
                yield_plastic_ratio=0.23
            )
        ]
    )
    assert request.well_id == "WELL-001"
    assert len(request.samples) == 1


def test_diagnosis_event_sse():
    """测试诊断事件 SSE 格式"""
    event = DiagnosisEvent.start(
        task_id="TASK-001",
        well_id="WELL-001",
        samples_count=20
    )
    sse = event.to_sse()
    assert "data:" in sse
    assert "start" in sse
```

**Step 2: 运行模型测试**

```bash
uv run pytest tests/models/test_diagnosis_schemas.py -v
```

**Step 3: 提交**

```bash
git add tests/models/test_diagnosis_schemas.py
git commit -m "test: add diagnosis schemas unit tests"
```

---

### Task 6.2: 编写集成测试

**Files:**
- Create: `tests/integration/test_diagnosis_e2e.py`

**Step 1: 创建集成测试**

```python
# tests/integration/test_diagnosis_e2e.py
"""诊断系统端到端测试"""

import pytest
import asyncio
from datetime import datetime

from httpx import AsyncClient, ASGITransport
from src.api.main import app
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    AlertThreshold,
    DrillingFluidSample,
)


@pytest.mark.asyncio
async def test_diagnosis_analyze_flow():
    """测试完整的诊断分析流程"""

    # 创建测试请求
    request = DiagnosisRequest(
        well_id="TEST-WELL-001",
        alert_type="DENSITY_HIGH",
        alert_triggered_at=datetime.now(),
        alert_threshold=AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        ),
        samples=[
            DrillingFluidSample(
                id=f"SF-{i:03d}",
                well_id="TEST-WELL-001",
                sample_time=datetime.now(),
                formation="砂岩",
                outlet_temp=58.0 + i * 0.1,
                density=1.20 + i * 0.01,
                gel_10s=3.0 + i * 0.1,
                gel_10m=7.0 + i * 0.2,
                rpm_3=4 + i,
                rpm_6=7 + i,
                rpm_100=40 + i,
                rpm_200=70 + i,
                rpm_300=90 + i,
                rpm_600=150 + i * 2,
                plastic_viscosity=60 + i,
                yield_point=12 + i * 0.5,
                flow_behavior_index=0.70,
                consistency_coefficient=2.5,
                apparent_viscosity=75 + i,
                yield_plastic_ratio=0.20 + i * 0.01
            )
            for i in range(5)
        ]
    )

    # 测试 API
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 注意：需要配置测试 API Key
        headers = {"X-API-Key": "test-key"}

        # 发起分析请求
        response = client.post(
            "/api/v1/diagnosis/analyze",
            json=request.model_dump(),
            headers=headers
        )

        # 注意：SSE 流式响应需要特殊处理
        # 这里简化测试，只检查端点可访问
        assert response.status_code in (200, 401, 503)  # 根据实际服务状态
```

**Step 2: 提交**

```bash
git add tests/integration/test_diagnosis_e2e.py
git commit -m "test: add diagnosis e2e integration test"
```

---

## Phase 7: 知识库初始化

### Task 7.1: 创建初始知识文档

**Files:**
- Create: `knowledge_base/measures/density/density-high.md`
- Create: `knowledge_base/measures/density/density-low.md`
- Create: `knowledge_base/measures/viscosity/pv-high.md`
- Create: `knowledge_base/measures/viscosity/yp-low.md`

**Step 1: 创建知识库目录**

```bash
mkdir -p knowledge_base/measures/{density,viscosity,gel}
```

**Step 2: 创建密度偏高处置文档**

```markdown
<!-- knowledge_base/measures/density/density-high.md -->
# 密度偏高处置措施

## 症状
- 钻井液密度持续上升
- 超过设计密度上限
- 可能导致井漏或卡钻

## 原因分析
1. 钻进过程中岩屑持续混入
2. 固控设备分离效率不足
3. 加重材料添加过量
4. 地层盐膏层污染

## 处置措施

### 立即措施
1. 停钻循环 15 分钟
2. 检查固控设备运行状态
3. 加水稀释，加水量 5-10%

### 后续处理
1. 提高振动筛筛网目数
2. 检查除砂器、除泥器
3. 必要时使用离心机

## 注意事项
- 稀释时应均匀加入，避免局部密度过低
- 监测流变参数变化
- 记录稀释前后的参数变化

## 预防措施
- 优化固控设备配置
- 控制钻速，减少岩屑产生
- 定期监测固控效率
```

**Step 3: 创建其他基础文档**

类似地创建 `density-low.md`, `pv-high.md`, `yp-low.md`。

**Step 4: 提交**

```bash
git add knowledge_base/
git commit -m "docs: add initial knowledge base documents"
```

---

## Phase 8: 文档与部署

### Task 8.1: 更新环境变量模板

**Files:**
- Modify: `.env.example`

**Step 1: 添加诊断系统配置**

```bash
# .env.example - 添加以下配置

# ========== 诊断系统配置 ==========
# Embedding 模型配置 (通义千问 DashScope)
DASHSCOPE_API_KEY=your_dashscope_api_key_here
EMBEDDING_MODEL=text-embedding-v3

# SpringBoot 回调配置
SPRINGBOOT_CALLBACK_TIMEOUT=30
SPRINGBOOT_CALLBACK_RETRY_MAX=3
```

**Step 2: 提交**

```bash
git add .env.example
git commit -m "docs: update environment variables for diagnosis system"
```

---

### Task 8.2: 创建数据库迁移脚本

**Files:**
- Create: `src/repositories/migrations/003_diagnosis_tables.sql`

**Step 1: 创建迁移文件**

```bash
# 复制 diagnosis_schema.sql 作为迁移文件
cp sql/diagnosis_schema.sql src/repositories/migrations/003_diagnosis_tables.sql
```

**Step 2: 提交**

```bash
git add src/repositories/migrations/003_diagnosis_tables.sql
git commit -m "feat: add diagnosis tables migration"
```

---

## Phase 9: 验证与优化

### Task 9.1: 端到端验证

**Step 1: 启动服务**

```bash
# 确保环境变量已配置
uv run uvicorn src.api.main:app --reload
```

**Step 2: 测试健康检查**

```bash
curl http://localhost:8000/health
```

**Step 3: 测试诊断接口（使用 curl 或 Postman）**

```bash
curl -X POST http://localhost:8000/api/v1/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key" \
  -d '{
    "well_id": "TEST-001",
    "alert_type": "DENSITY_HIGH",
    "alert_triggered_at": "2026-02-24T10:00:00Z",
    "alert_threshold": {
      "field": "density",
      "condition": "greater_than",
      "threshold": 1.30,
      "current_value": 1.35
    },
    "samples": [
      {
        "id": "SF-001",
        "well_id": "TEST-001",
        "sample_time": "2026-02-24T09:00:00Z",
        "formation": "砂岩",
        "outlet_temp": 58.5,
        "density": 1.22,
        "gel_10s": 3.5,
        "gel_10m": 8.2,
        "rpm_3": 5,
        "rpm_6": 8,
        "rpm_100": 45,
        "rpm_200": 75,
        "rpm_300": 95,
        "rpm_600": 160,
        "plastic_viscosity": 65,
        "yield_point": 15,
        "flow_behavior_index": 0.72,
        "consistency_coefficient": 2.8,
        "apparent_viscosity": 80,
        "yield_plastic_ratio": 0.23
      }
    ]
  }'
```

---

## 附录

### A. 依赖图

```
src/api/main.py
├── src/api/routes/diagnosis.py
│   └── src/services/diagnosis_service.py
│       ├── src/agents/diagnosis_agent.py
│       ├── src/services/rag_service.py
│       │   └── src/repositories/knowledge_repo.py
│       ├── src/services/callback_service.py
│       └── src/repositories/diagnosis_repo.py
├── src/models/diagnosis_schemas.py
├── src/models/exceptions.py
└── src/config.py
```

### B. 数据流

```
SpringBoot → FastAPI /diagnosis/analyze
    → DiagnosisService.analyze()
        → DiagnosisAgent.analyze()
            → create_agent.astream()
                → Tools (analyze_trend, search_knowledge, format_prescription)
        → DiagnosisRepository (save task/events/result)
        → CallbackService.send_callback()
    → SSE Response (thinking, retrieval, diagnosis, result)
```

### C. 测试命令

```bash
# 运行所有测试
uv run pytest

# 运行诊断相关测试
uv run pytest tests/models/test_diagnosis_schemas.py -v
uv run pytest tests/services/test_diagnosis_service.py -v

# 运行集成测试
uv run pytest tests/integration/test_diagnosis_e2e.py -v

# 代码检查
uv run ruff check src/

# 代码格式化
uv run ruff format src/
```

---

**计划完成时间**: 2026-02-24
**预计总任务数**: 30+
**预计总时长**: 6-8 小时（假设工程师熟悉 Python/FastAPI）
