# 钻井液性能实时检测与自动化分析系统 - 详细设计文档

> **实现状态**: 核心功能已实现，测试覆盖完成
> **最后更新**: 2026-02-25
> **版本**: v1.1

## 实现状态概览

| 模块 | 状态 | 说明 |
|------|------|------|
| 数据模型 | ✅ 完成 | diagnosis_schemas.py |
| API 路由 | ✅ 完成 | /api/v1/diagnosis/* |
| 诊断服务 | ✅ 完成 | DiagnosisService + SSE 流式 |
| RAG 服务 | ✅ 完成 | 600字符分块 + HNSW 索引 |
| 回调服务 | ✅ 完成 | 指数退避重试机制 |
| Agent | ✅ 完成 | LangChain create_agent + ToolStrategy |
| 诊断工具 | ✅ 完成 | analyze_trend, search_knowledge, format_prescription |
| 结构化输出 | ✅ 完成 | ToolStrategy + Pydantic Literal 约束 |
| 数据库 | ✅ 完成 | diagnosis_schema.sql |
| 单元测试 | ✅ 完成 | 60+ 测试用例 |

### 实现亮点

1. **ToolStrategy 结构化输出** - 使用 LangChain `ToolStrategy` 替代 `with_structured_output()`，解决 DeepSeek API 不支持 json_schema 的问题
2. **流式输出优化** - 使用 `astream` + `stream_mode=["messages", "updates"]` 同时捕获 LLM 思考内容和工具调用
3. **Literal 类型约束** - 对枚举字段使用 `Literal` 类型约束，防止 LLM 生成无效值

---

### 1.1 功能定位

本系统是钻井液性能实时检测与自动化分析系统的 AI 诊断模块，负责：

- 接收钻井液实时监测数据异常预警
- 基于 LangChain Agent 进行智能诊断分析
- 通过 RAG 技术检索专家知识库
- 生成处置措施和配药方案
- 以 SSE 流式方式返回分析过程
- 回调通知 SpringBoot 后端分析结果

### 1.2 技术架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        SpringBoot 后端                           │
│                    (预警触发 + 结果接收)                          │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP REST / SSE
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                       FastAPI 服务层                             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │         /api/v1/diagnosis/analyze (SSE 流式)              │  │
│  │         /api/v1/diagnosis/{task_id} (查询结果)             │  │
│  │         /api/v1/diagnosis/knowledge/* (知识库管理)         │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      DiagnosisService                            │
│  ┌─────────────────────────────────────────────────────────────┤
│  │  1. 接收诊断请求                                              │
│  │  2. 创建任务记录 (PostgreSQL)                                 │
│  │  3. 调用 DiagnosisAgent 执行分析                             │
│  │  4. 记录事件流 (diagnosis_events)                             │
│  │  5. 保存结果 (diagnosis_results)                             │
│  │  6. 发送回调 (可选)                                           │
│  └─────────────────────────────────────────────────────────────┤
│                              │                                  │
│  ┌───────────────┬───────────┴───────────┬───────────────────┐  │
│  │               │                       │                   │  │
│  ▼               ▼                       ▼                   ▼  │
│  │   DiagnosisAgent  RAGService   CallbackService   DiagnosisRepo│
│  │   (LangChain)    (知识检索)      (HTTP回调)        (数据持久化)  │
│  └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                          Agent 工具层                             │
│  ┌─────────────────────────────────────────────────────────────┤
│  │  analyze_trend        - 钻井液参数趋势分析                     │
│  │  search_knowledge     - 检索专家知识库 (RAG)                  │
│  │  format_prescription  - 生成配药方案                          │
│  └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                          数据存储层                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │ diagnosis_tasks │  │ knowledge_docs  │  │knowledge_chunks │  │
│  │ diagnosis_res   │  │ (pgvector HNSW) │  │                 │  │
│  │ diagnosis_events│  └─────────────────┘  └─────────────────┘  │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 核心技术栈

| 组件 | 技术选型 | 版本 | 用途 |
|------|---------|------|------|
| LLM 编排 | LangChain | >=1.0.0 | Agent 框架 |
| LLM 模型 | DeepSeek | deepseek-chat | 诊断分析 |
| Embedding | 通义千问 DashScope | text-embedding-v3 | 向量化 |
| 向量数据库 | PostgreSQL + pgvector | 0.2.0+ | HNSW 索引 |
| 消息流 | LangGraph | >=0.2.0 | Agent 状态管理 |
| Web 框架 | FastAPI | >=0.115.0 | API 服务 |
| 异步运行时 | asyncio + uvloop | - | 异步处理 |

---

## 2. 数据模型设计

### 2.1 请求模型

#### DiagnosisRequest

```python
class DiagnosisRequest(BaseModel):
    task_id: str                      # 任务ID（自动生成）
    well_id: str                      # 井号
    alert_type: str                   # 预警类型（DENSITY_HIGH, VISCOSITY_LOW等）
    alert_triggered_at: datetime      # 预警触发时间
    alert_threshold: AlertThreshold   # 阈值配置
    samples: List[DrillingFluidSample] # 采样数据（1-20条）
    context: DiagnosisContext         # 上下文信息（可选）
    callback_url: Optional[str]       # 回调URL（可选）
    stream: bool = True               # 是否流式返回
```

#### AlertThreshold

```python
class AlertThreshold(BaseModel):
    field: str                        # 阈值字段名
    condition: Literal                # 阈值条件
    threshold: float                  # 阈值
    current_value: float              # 当前值
```

#### DrillingFluidSample

```python
class DrillingFluidSample(BaseModel):
    id: str                           # 样品ID
    well_id: str                      # 井号
    sample_time: datetime             # 采样时间
    formation: str                    # 地层岩性
    outlet_temp: float                # 出口温度
    density: float                    # 密度
    gel_10s: float                    # 10秒切力
    gel_10m: float                    # 10分钟切力
    rpm_3/6/100/200/300/600: float    # 各转速读数
    plastic_viscosity: float          # 塑性黏度
    yield_point: float                # 动切力
    flow_behavior_index: float        # 流性指数
    consistency_coefficient: float    # 稠度系数
    apparent_viscosity: float         # 表观黏度
    yield_plastic_ratio: float        # 动塑比
```

### 2.2 响应模型

#### DiagnosisResult

```python
class DiagnosisResult(BaseModel):
    diagnosis: DiagnosisConclusion    # 诊断结论
    trend_analysis: List[TrendAnalysis] # 趋势分析
    measures: List[TreatmentMeasure]  # 处置措施
    prescription: Prescription        # 配药方案
    references: List[str]             # 参考文档
```

#### DiagnosisConclusion

```python
class DiagnosisConclusion(BaseModel):
    summary: str                      # 结论摘要
    cause: str                        # 问题原因
    risk_level: Literal               # 风险等级
    trend_outlook: Optional[str]      # 趋势预测
```

#### TreatmentMeasure

```python
class TreatmentMeasure(BaseModel):
    step: int                         # 步骤序号
    action: str                       # 操作描述
    duration: Optional[str]           # 持续时间
    amount: Optional[str]             # 用量
    priority: Literal                 # 优先级
    notes: Optional[str]              # 备注
```

### 2.3 SSE 事件模型

#### DiagnosisEvent

```python
class DiagnosisEvent(BaseModel):
    type: Literal[
        "start",           # 分析开始
        "thinking",        # AI思考中
        "trend_analysis",  # 趋势分析
        "retrieval",       # 知识检索
        "diagnosis",       # 诊断结论
        "prescription",    # 配药方案
        "result",          # 完整结果
        "done",            # 分析完成
        "error"            # 错误
    ]
    task_id: Optional[str]
    well_id: Optional[str]
    content: Optional[str]
    # ... 其他字段（见源码）

    def to_sse(self) -> str:
        """转换为 SSE 格式: data: {json}\n\n"""
```

**SSE 流式输出示例：**

```
data: {"type":"start","task_id":"TASK-20260225-123456-AB12CD","well_id":"WELL-001","content":"开始分析井号 WELL-001 的 5 条采样数据"}

data: {"type":"thinking","task_id":"TASK-20260225-123456-AB12CD","content":"正在分析 5 条采样数据...","step":"data_analysis"}

data: {"type":"trend_analysis","task_id":"TASK-20260225-123456-AB12CD","field":"density","analysis":{"trend":"rising","rate":0.15}}

data: {"type":"retrieval","task_id":"TASK-20260225-123456-AB12CD","docs_found":3,"sources":["密度偏高处置","黏度控制"]}

data: {"type":"diagnosis","task_id":"TASK-20260225-123456-AB12CD","summary":"密度持续上升","cause":"固相侵入","risk_level":"MEDIUM"}

data: {"type":"prescription","task_id":"TASK-20260225-123456-AB12CD","action":"加水稀释 8%","prescription":{"dilution_water":"8%","mixing_time":"45分钟"}}

data: {"type":"done","task_id":"TASK-20260225-123456-AB12CD","status":"SUCCESS"}
```

---

## 3. 数据库设计

### 3.1 知识库表结构

#### knowledge_documents（父文档）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| doc_id | VARCHAR(100) UNIQUE | 文档ID（业务标识） |
| title | VARCHAR(500) | 文档标题 |
| category | VARCHAR(50) | 分类（density/viscosity/gel） |
| subcategory | VARCHAR(100) | 子分类 |
| content | TEXT | 完整内容 |
| metadata | JSONB | 元数据 |
| chunk_count | INT | 分块数量 |
| created_at | TIMESTAMPTZ | 创建时间 |

**索引：**
- `idx_knowledge_documents_category` - 分类查询
- `doc_id` - 唯一索引

#### knowledge_chunks（向量分块）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| parent_doc_id | VARCHAR(100) | 父文档ID |
| chunk_index | INT | 分块序号 |
| content | TEXT | 分块内容 |
| embedding | vector(1024) | 通义千问 embedding向量 |
| created_at | TIMESTAMPTZ | 创建时间 |

**索引：**
- `idx_chunks_embedding_hnsw` - HNSW 向量索引（余弦相似度）
- `idx_knowledge_chunks_parent_doc_id` - 文档分块查询

**HNSW 索引配置：**
```sql
CREATE INDEX idx_chunks_embedding_hnsw
ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
```

### 3.2 诊断任务表结构

#### diagnosis_tasks

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| task_id | VARCHAR(100) UNIQUE | 任务ID |
| well_id | VARCHAR(50) | 井号 |
| alert_type | VARCHAR(50) | 预警类型 |
| alert_triggered_at | TIMESTAMPTZ | 预警时间 |
| alert_threshold | JSONB | 阈值配置 |
| samples | JSONB | 采样数据 |
| context | JSONB | 上下文信息 |
| callback_url | TEXT | 回调地址 |
| status | VARCHAR(20) | 状态（PENDING/PROCESSING/SUCCESS/FAILED） |
| started_at | TIMESTAMPTZ | 开始时间 |
| completed_at | TIMESTAMPTZ | 完成时间 |
| created_at | TIMESTAMPTZ | 创建时间 |

#### diagnosis_results

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| task_id | VARCHAR(100) UNIQUE | 任务ID |
| diagnosis | JSONB | 诊断结论 |
| trend_analysis | JSONB | 趋势分析 |
| measures | JSONB | 处置措施 |
| prescription | JSONB | 配药方案 |
| references | JSONB | 参考资料 |
| rag_metadata | JSONB | RAG元数据 |
| llm_metadata | JSONB | LLM元数据 |

#### diagnosis_events

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| task_id | VARCHAR(100) | 任务ID |
| event_type | VARCHAR(20) | 事件类型 |
| event_data | JSONB | 事件数据 |
| sequence_num | INT | 序号 |
| created_at | TIMESTAMPTZ | 创建时间 |

---

## 4. 核心组件设计

### 4.1 DiagnosisAgent（诊断 Agent）

**职责：** 基于 LangChain `create_agent` 构建智能诊断分析 Agent

**结构化输出方案：** 使用 `ToolStrategy` 实现结构化输出

```python
from langchain.agents.structured_output import ToolStrategy
from langchain.agents import create_agent

# 定义结构化输出 Schema
class LLMDiagnosisOutput(BaseModel):
    summary: str
    cause: str
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    trend_analysis: List[LLMTrendAnalysis]
    measures: List[LLMTreatmentMeasure]
    prescription: LLMPrescription

# 创建 Agent
self.agent = create_agent(
    model=self.model,
    tools=tools,
    system_prompt=system_prompt,
    checkpointer=self.checkpointer,
    response_format=ToolStrategy(LLMDiagnosisOutput),  # 使用 ToolStrategy
)
```

**为什么使用 ToolStrategy：**
- DeepSeek API 不支持 OpenAI 的 `json_schema` 格式
- `ToolStrategy` 使用通用的 function calling 机制，兼容性更好
- 自动处理工具调用和结构化输出的协调

**核心方法：**

```python
async def analyze(
    self,
    request: DiagnosisRequest
) -> AsyncIterator[DiagnosisEvent]:
    """执行诊断分析（流式输出）"""
    # 1. 发送开始事件
    yield DiagnosisEvent.start(...)

    # 2. 构建分析提示词
    prompt = self._build_analysis_prompt(request)

    # 3. 使用 astream 获取流式更新（包含 LLM 思考和工具调用）
    async for stream_mode, data in self.agent.astream(
        {"messages": [HumanMessage(content=prompt)]},
        config=config,
        stream_mode=["messages", "updates"],  # 同时获取 token 和 state updates
    ):
        if stream_mode == "messages":
            # messages 模式: LLM token（思考内容）
            token, metadata = data
            if hasattr(token, "text") and token.text:
                yield DiagnosisEvent.thinking(content=token.text, step="reasoning")

        elif stream_mode == "updates":
            # updates 模式: 工具调用和状态更新
            for node_name, update in data.items():
                # 处理工具调用...
                yield DiagnosisEvent.thinking(content="调用工具: ...", step="tool_call")

    # 4. 获取最终状态和结构化输出
    final_state = await self.agent.ainvoke(...)
    structured_output = final_state.get("structured_response")

    # 5. 转换为 DiagnosisResult
    result = self._convert_to_diagnosis_result(structured_output)
    yield DiagnosisEvent(result=result)

    # 6. 完成
    yield DiagnosisEvent(status="SUCCESS")
```

### 4.2 RAGService（检索增强服务）

**职责：** 知识库管理和语义检索

**文本分块策略：**
```python
child_splitter = RecursiveCharacterTextSplitter(
    chunk_size=600,           # 每块最大字符数
    chunk_overlap=100,        # 块间重叠
    length_function=len,      # 长度计算函数
)
```

**向量检索流程：**
```
1. 接收查询文本
2. 使用 DashScopeEmbeddings 生成查询向量
3. PostgreSQL pgvector 执行余弦相似度搜索
4. 返回 Top-K 相关文档
```

**核心方法：**

```python
async def search(
    self,
    query: str,
    top_k: int = 5,
    category: Optional[str] = None
) -> List[Dict[str, Any]]:
    """语义检索"""
    # 1. 生成查询向量
    query_embedding = await self._embed_text(query)

    # 2. 向量搜索（SQL）
    rows = await conn.fetch("""
        SELECT DISTINCT ON (kd.doc_id)
            kd.doc_id, kd.title, kd.content,
            MIN(kc.embedding <-> $1) as distance
        FROM knowledge_documents kd
        JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
        WHERE kd.category = $2  -- 可选
        GROUP BY kd.doc_id, kd.title, kd.content
        ORDER BY distance
        LIMIT $3
    """, query_embedding, category, top_k)

    # 3. 格式化结果
    return [dict(row) for row in rows]
```

### 4.3 CallbackService（回调服务）

**职责：** 向 SpringBoot 后端发送分析结果

**重试策略：** 指数退避（Exponential Backoff）

```python
for attempt in range(1, retry_max + 1):
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(url, json=request)
            if response.status_code in (200, 201, 202):
                return True
    except Exception as e:
        if attempt < retry_max:
            wait_time = 5 * (2 ** (attempt - 1))  # 5s, 10s, 20s
            await asyncio.sleep(wait_time)
```

**配置参数：**
- `SPRINGBOOT_CALLBACK_TIMEOUT=30` - 请求超时（秒）
- `SPRINGBOOT_CALLBACK_RETRY_MAX=3` - 最大重试次数

### 4.4 诊断工具集（LangChain Tools）

#### analyze_trend

**功能：** 分析钻井液参数趋势

```python
@tool
def analyze_trend(
    samples: List[Dict[str, Any]],
    field: str = "density"
) -> str:
    """
    计算趋势方向（上升/下降/稳定）
    计算变化率和时间跨度
    """
    # 1. 按时间排序
    # 2. 提取字段值
    # 3. 计算变化量、变化率
    # 4. 判断趋势方向
    # 5. 返回分析报告
```

**输出示例：**
```
趋势分析结果（density）:
- 趋势: 上升
- 起始值: 1.200
- 结束值: 1.350
- 变化量: +0.150
- 变化率: 12.5%
- 时间跨度: 120分钟
```

#### search_knowledge

**功能：** 检索专家知识库

```python
@tool
def search_knowledge(
    query: str,
    category: str = "density",
    top_k: int = 5
) -> str:
    """调用 RAGService 进行语义检索"""
```

#### format_prescription

**功能：** 生成配药方案

```python
@tool
def format_prescription(
    measures: str,
    density: float = 1.2,
    plastic_viscosity: float = 20
) -> str:
    """
    基于规则引擎生成配药方案
    - 密度高 → 加水稀释 8%
    - 黏度高 → 降黏剂 0.3% + 加水 5%
    - 密度低 → 重晶石 2%
    """
```

---

## 5. API 接口设计

### 5.1 诊断分析接口

**POST** `/api/v1/diagnosis/analyze`

**请求：**
```json
{
  "well_id": "WELL-001",
  "alert_type": "DENSITY_HIGH",
  "alert_triggered_at": "2026-02-25T10:00:00Z",
  "alert_threshold": {
    "field": "density",
    "condition": "greater_than",
    "threshold": 1.30,
    "current_value": 1.35
  },
  "samples": [
    {
      "id": "SF-001",
      "well_id": "WELL-001",
      "sample_time": "2026-02-25T09:00:00Z",
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
  ],
  "context": {
    "current_depth": 2500.0,
    "formation_type": "砂岩",
    "drilling_phase": "钻进"
  },
  "callback_url": "http://springboot/api/diagnosis/callback",
  "stream": true
}
```

**响应：** SSE 流式事件（见 2.3 节）

### 5.2 查询诊断结果

**GET** `/api/v1/diagnosis/{task_id}`

**响应：**
```json
{
  "task": {
    "task_id": "TASK-20260225-123456-AB12CD",
    "well_id": "WELL-001",
    "status": "SUCCESS",
    "created_at": "2026-02-25T10:00:00Z",
    "completed_at": "2026-02-25T10:01:30Z"
  },
  "result": {
    "diagnosis": {
      "summary": "密度持续上升",
      "cause": "固相侵入",
      "risk_level": "MEDIUM"
    },
    "trend_analysis": [...],
    "measures": [...],
    "prescription": {...}
  }
}
```

### 5.3 知识库管理接口

#### 创建知识文档

**POST** `/api/v1/diagnosis/knowledge/documents`

**请求：**
```json
{
  "doc_id": "DOC-001",
  "title": "密度偏高处置措施",
  "category": "density",
  "subcategory": "high",
  "content": "# 密度偏高处置\n\n## 症状\n- 钻井液密度持续上升...",
  "metadata": {
    "author": "专家A",
    "version": "1.0"
  }
}
```

**响应：**
```json
{
  "doc_id": "DOC-001",
  "status": "created"
}
```

#### 语义检索

**POST** `/api/v1/diagnosis/knowledge/search`

**请求：**
```json
{
  "query": "密度偏高怎么处理",
  "category": "density",
  "top_k": 5
}
```

**响应：**
```json
{
  "results": [
    {
      "doc_id": "DOC-001",
      "title": "密度偏高处置措施",
      "category": "density",
      "content": "...",
      "distance": 0.123
    }
  ]
}
```

#### 其他接口

- `GET /api/v1/diagnosis/knowledge/documents/{doc_id}` - 获取文档
- `DELETE /api/v1/diagnosis/knowledge/documents/{doc_id}` - 删除文档
- `POST /api/v1/diagnosis/knowledge/rebuild` - 重建向量索引

---

## 6. 部署配置

### 6.1 环境变量

```bash
# ========== 诊断系统配置 ==========
# Embedding 模型配置 (通义千问 DashScope)
DASHSCOPE_API_KEY=sk-xxx
EMBEDDING_MODEL=text-embedding-v3

# SpringBoot 回调配置
SPRINGBOOT_CALLBACK_TIMEOUT=30
SPRINGBOOT_CALLBACK_RETRY_MAX=3
```

### 6.2 数据库初始化

```bash
# 1. 创建 pgvector 扩展
psql -U root -d yibccc_chat -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. 执行诊断系统 schema
psql -U root -d yibccc_chat -f docs/sql/diagnosis_schema.sql
```

### 6.3 依赖安装

```bash
# 已添加到 pyproject.toml
uv sync
```

---

## 7. 测试验证

### 7.1 单元测试

```bash
# 运行所有诊断模块测试（不依赖数据库）
uv run pytest tests/models/test_diagnosis_schemas.py \
               tests/tools/test_diagnosis_tools.py \
               tests/services/test_callback_service.py -v

# 运行集成测试（需要数据库）
uv run pytest tests/repositories/test_diagnosis_repo.py \
               tests/services/test_rag_service.py -v
```

### 7.2 健康检查

```bash
curl http://localhost:8000/health
```

### 7.2 诊断分析测试

```bash
curl -X POST http://localhost:8000/api/v1/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key" \
  -d @test_request.json
```

### 7.3 知识库测试

```bash
# 创建文档
curl -X POST http://localhost:8000/api/v1/diagnosis/knowledge/documents \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key" \
  -d @doc.json

# 语义检索
curl -X POST http://localhost:8000/api/v1/diagnosis/knowledge/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key" \
  -d '{"query": "密度偏高", "top_k": 5}'
```

---

## 8. 后续优化方向

### 8.1 短期优化

1. **智能结果解析** - 实现 `_parse_result` 方法，使用 LLM 结构化输出
2. **知识库扩充** - 导入更多专家文档和处置案例
3. ~~**测试覆盖** - 补充单元测试和集成测试~~ ✅ 已完成 (60+ 测试用例)

### 8.2 中期优化

1. **多轮对话** - 支持诊断过程中的追问和澄清
2. **历史学习** - 基于处置反馈优化诊断策略
3. **实时监控** - 对接实时数据流进行持续监控

### 8.3 长期优化

1. **多模态输入** - 支持图片、图表等非结构化数据
2. **预测性维护** - 基于历史数据预测异常趋势
3. **知识图谱** - 构建钻井液领域知识图谱

---

## 9. 故障排查

### 9.1 常见问题

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| Embedding 失败 | DASHSCOPE_API_KEY 未配置 | 检查 .env 配置 |
| 向量搜索慢 | HNSW 索引未创建 | 执行 CREATE INDEX |
| Agent 超时 | LLM 响应慢 | 增加 timeout 配置 |
| 回调失败 | SpringBoot 服务不可达 | 检查网络和 callback_url |

### 9.2 日志查看

```bash
# 查看应用日志
tail -f logs/app.log | grep "DiagnosisService"

# 查看错误日志
tail -f logs/error.log | grep "ERROR"
```

---

**文档版本：** v1.1
**最后更新：** 2026-02-25
**维护者：** AI Agent Team
**变更记录：**
- v1.1 (2026-02-25): 添加实现状态概览，更新测试覆盖说明
- v1.0 (2026-02-25): 初始版本
