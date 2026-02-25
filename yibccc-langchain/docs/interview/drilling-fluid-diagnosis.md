# 钻井液诊断系统 - 面试话术

## 一句话总结

> "基于 LangChain Agent + RAG 技术构建智能诊断系统，通过 PostgreSQL + pgvector 存储专家知识库，使用 DeepSeek LLM 进行实时分析，以 SSE 流式方式返回诊断过程和处置方案。"

---

## 项目背景

**业务场景：**
钻井液性能实时监测是石油钻井过程中的关键环节，传统方式依赖人工分析，存在以下问题：
- 响应慢 - 从预警到处置可能需要数小时
- 依赖经验 - 新工程师难以快速做出准确判断
- 知识散落 - 专家经验分散在文档、案例中，难以传承

**技术方案价值：**
- **实时响应** - 从预警到诊断结论秒级输出
- **知识传承** - RAG 技术将专家经验沉淀为可检索的知识库
- **辅助决策** - 提供结构化的处置措施和配药方案

---

## 核心架构设计

### 技术选型决策

| 组件 | 技术选型 | 决策理由 |
|------|---------|---------|
| LLM 编排 | LangChain create_agent | 官方支持，工具调用能力强，适合构建专家 Agent |
| LLM 模型 | DeepSeek | 中文理解能力强，成本低于 GPT-4 |
| Embedding | 通义千问 text-embedding-v3 | 1024 维向量，中文语义理解优秀 |
| 向量检索 | PostgreSQL + pgvector | 无需额外组件，HNSW 索引性能优异 |
| 流式输出 | Server-Sent Events (SSE) | 单向推送，适合实时事件流 |
| 异步框架 | asyncio + FastAPI | 高并发处理，I/O 密集型场景 |

**为什么选择 LangChain 而不是直接调用 LLM API？**

1. **工具调用能力** - Agent 可以自主决策调用 `analyze_trend`、`search_knowledge` 等工具
2. **状态管理** - LangGraph 自动管理对话状态，支持多轮分析
3. **流式输出** - 天然支持流式响应，用户体验更好
4. **可扩展性** - 新增诊断规则只需添加工具，不需要改 Prompt

---

## RAG 架构设计

### 为什么需要 RAG？

**问题：** 纯 LLM 存在知识幻觉和时效性问题

**解决方案：** 检索增强生成 (RAG)

```
用户预警 → Agent 分析
    ↓
提取关键参数 (density=1.35, viscosity=80...)
    ↓
向量检索专家知识库
    ↓
Top-5 相关文档 (密度偏高处置、固相控制...)
    ↓
构造 Prompt: "基于以下专家知识分析..."
    ↓
LLM 生成诊断结论
```

### 向量检索实现

**文本分块策略：**
```python
RecursiveCharacterTextSplitter(
    chunk_size=600,      # 每块 600 字符
    chunk_overlap=100,   # 重叠 100 字符保证语义连贯
)
```

**为什么这样分块？**
- 600 字符约 150-200 个汉字，涵盖一个完整知识点
- 100 字符重叠保证上下文不丢失
- 递归分割保证在句子边界切分

**pgvector HNSW 索引配置：**
```sql
CREATE INDEX idx_chunks_embedding_hnsw
ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
```

**参数解释：**
- `m = 16`: 每个节点连接 16 个邻居，平衡召回率和性能
- `ef_construction = 64`: 构建时采样 64 个候选，保证索引质量
- `vector_cosine_ops`: 余弦相似度，适合文本向量

### 知识库表设计

**父子文档结构：**

```
knowledge_documents (父文档)
    ├── doc_id: "DOC-001"
    ├── title: "密度偏高处置措施"
    └── content: "完整文档内容..."

knowledge_chunks (子分块)
    ├── parent_doc_id: "DOC-001"
    ├── chunk_index: 0
    ├── content: "症状：钻井液密度持续上升..."
    └── embedding: [0.123, 0.456, ...] (1024维向量)
```

**为什么分表存储？**
1. **检索精度** - 小分块向量相似度更高
2. **存储效率** - 只对分块建向量索引，父文档不建
3. **查询灵活** - 可先检索分块，再返回完整文档

---

## Agent 设计与实现

### 结构化输出方案

**问题：** DeepSeek API 不支持 OpenAI 的 `json_schema` 格式

**解决方案：** 使用 LangChain `ToolStrategy`

```python
from langchain.agents import create_agent
from langchain.agents.structured_output import ToolStrategy

# 定义结构化输出 Schema
class LLMDiagnosisOutput(BaseModel):
    summary: str
    cause: str
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    trend_analysis: List[LLMTrendAnalysis]
    measures: List[LLMTreatmentMeasure]
    prescription: LLMPrescription

# 使用 ToolStrategy 创建 Agent
self.agent = create_agent(
    model=self.model,
    tools=tools,
    response_format=ToolStrategy(LLMDiagnosisOutput),
)
```

**优势：**
- 兼容性好：使用通用的 function calling 机制
- 自动协调：Agent 自动处理工具调用和结构化输出的顺序
- 类型安全：Pydantic 自动验证 LLM 输出

### 流式输出实现

**同时获取 LLM 思考和工具调用：**

```python
async for stream_mode, data in self.agent.astream(
    {"messages": [HumanMessage(content=prompt)]},
    config=config,
    stream_mode=["messages", "updates"],  # 关键：多个模式
):
    if stream_mode == "messages":
        # LLM token（思考内容）
        token, metadata = data
        if token.text:
            yield DiagnosisEvent.thinking(content=token.text, step="reasoning")

    elif stream_mode == "updates":
        # 工具调用和状态更新
        for node_name, update in data.items():
            if "messages" in update:
                # 处理工具调用...
                yield DiagnosisEvent.thinking(content="调用工具: ...", step="tool_call")
```

**SSE 事件细分：**

| step | 说明 | 示例内容 |
|------|------|----------|
| `data_analysis` | 数据分析准备 | "正在分析 5 条采样数据..." |
| `analyzing` | AI 分析中 | "正在分析数据并生成诊断结果..." |
| `tool_call` | 调用工具 | "调用工具: analyze_trend(...)" |
| `tool_result` | 工具返回 | "工具 analyze_trend 返回: ..." |
| `reasoning` | AI 思考内容 | "密度从 1.20 上升到 1.35..." |
| `structuring` | 生成结构化结果 | "正在生成结构化诊断结果..." |

### 系统提示词工程

```
你是一位钻井液性能诊断专家。你的职责是：

1. 分析钻井液采样数据，识别异常趋势
2. 基于历史数据和知识库，诊断问题原因
3. 提供具体的处置措施和配药方案
4. 评估风险等级并提供趋势预测

请使用提供的工具进行分析：
- analyze_trend: 分析参数趋势
- search_knowledge: 检索处置知识
- format_prescription: 生成配药方案

分析完成后，你需要返回一个结构化的诊断结果，包含以下内容：
- summary: 诊断总结
- cause: 问题原因
- risk_level: 风险等级（LOW/MEDIUM/HIGH/CRITICAL）
- trend_analysis: 趋势分析列表
- measures: 处置措施列表
- prescription: 配药方案

注意：所有数值必须基于实际数据，不要编造。如果某项数据无法确定，可以留空。

输出应专业、准确、可操作。
```

**提示词设计要点：**
1. **角色定位** - 明确专家身份，设定输出基调
2. **任务拆解** - 4 个核心步骤，引导 Agent 按流程分析
3. **工具引导** - 明确告知可用工具，避免 Agent 乱编
4. **输出要求** - 专业、准确、可操作，设定质量标准

### 工具设计 (LangChain Tools)

#### analyze_trend - 趋势分析工具

**核心逻辑：**
```python
1. 提取字段值（density/viscosity/gel...）
2. 计算变化量 = 结束值 - 起始值
3. 计算变化率 = abs(变化量 / 起始值)
4. 判断趋势方向
   - |变化量| < 0.01 → 稳定
   - 变化量 > 0 → 上升
   - 变化量 < 0 → 下降
5. 返回结构化报告
```

**为什么要用工具而不是让 LLM 直接计算？**
- **准确性** - 数学计算是确定性的，不应依赖 LLM
- **可解释** - 每步计算可追溯，便于审计
- **性能** - 工具执行毫秒级，LLM 计算慢且不可靠

#### search_knowledge - 知识检索工具

**实现方式：**
```python
@tool
def search_knowledge(query: str, category: str = "density", top_k: int = 5):
    """调用 RAGService 进行语义检索"""
    return await rag_service.search(query, top_k, category)
```

**为什么封装为工具？**
- Agent 可以自主决定检索时机和查询内容
- 支持多轮检索（先查密度、再查黏度...）
- 检索结果可作为后续分析的上下文

#### format_prescription - 配药方案工具

**规则引擎实现：**
```python
if "密度高" in measures:
    prescription["稀释水"] = "8%"
    prescription["搅拌时间"] = "45分钟"

if "黏度高" in measures:
    prescription["降黏剂"] = "0.3%"
    prescription["稀释水"] = "5%"
```

**为什么不用 LLM 生成配药方案？**
- **安全性** - 配药涉及生产安全，需精确控制
- **可执行** - 规则引擎输出可直接指导操作
- **合规** - 配方需符合行业标准，不能随意生成

---

## SSE 流式输出设计

### 为什么用 SSE 而不是 WebSocket？

| 对比项 | SSE | WebSocket |
|--------|-----|-----------|
| 协议 | HTTP | 独立协议 |
| 方向 | 服务端推送 | 双向通信 |
| 复杂度 | 简单，原生支持 | 需要握手、心跳 |
| 断线重连 | 浏览器自动重连 | 需要自己实现 |
| 适用场景 | 单向事件流 | 实时聊天、游戏 |

**我们的场景：**
- 服务端单向推送分析过程
- 不需要客户端频繁发送消息
- SSE 实现简单，浏览器原生支持

### SSE 事件类型定义

```python
type: Literal[
    "start",           # 分析开始
    "thinking",        # AI 思考中（流式输出 LLM 响应）
    "trend_analysis",  # 趋势分析完成
    "retrieval",       # 知识检索完成
    "diagnosis",       # 诊断结论
    "prescription",    # 配药方案
    "result",          # 完整结果
    "done",            # 分析完成
    "error"            # 错误
]
```

**事件流示例：**
```
data: {"type":"start","content":"开始分析井号 WELL-001 的 5 条采样数据"}

data: {"type":"thinking","content":"正在分析密度参数...","step":"data_analysis"}

data: {"type":"thinking","content":"密度从 1.20 上升到 1.35，变化率 12.5%","step":"reasoning"}

data: {"type":"retrieval","docs_found":3,"sources":["密度偏高处置","固相控制"]}

data: {"type":"diagnosis","summary":"密度持续上升","cause":"固相侵入","risk_level":"MEDIUM"}

data: {"type":"prescription","action":"加水稀释 8%","prescription":{"dilution_water":"8%"}}

data: {"type":"done","status":"SUCCESS"}
```

**用户体验价值：**
- **实时反馈** - 用户看到分析过程，不用等待
- **信任建立** - 透明化 AI 思考路径
- **交互友好** - 可随时中断或调整分析参数

---

## 数据模型设计

### 诊断请求模型

```python
class DiagnosisRequest(BaseModel):
    task_id: str                        # 任务ID（自动生成）
    well_id: str                        # 井号
    alert_type: str                     # 预警类型
    alert_triggered_at: datetime        # 预警触发时间
    alert_threshold: AlertThreshold     # 阈值配置
    samples: List[DrillingFluidSample]  # 采样数据（1-20条）
    context: DiagnosisContext           # 上下文信息
    callback_url: Optional[str]         # 回调URL（可选）
    stream: bool = True                 # 是否流式返回
```

**为什么限制 1-20 条采样数据？**
- **性能考虑** - 向量检索和 LLM 处理时间与数据量正相关
- **业务需求** - 20 条数据足以分析趋势（假设每 10 分钟一条，覆盖 3.3 小时）
- **用户体验** - 数据过多会让诊断时间变长

### 诊断结果模型

```python
class DiagnosisResult(BaseModel):
    diagnosis: DiagnosisConclusion      # 诊断结论
    trend_analysis: List[TrendAnalysis] # 趋势分析
    measures: List[TreatmentMeasure]    # 处置措施
    prescription: Prescription          # 配药方案
    references: List[str]               # 参考文档
```

**结构化输出的好处：**
- SpringBoot 后端可直接解析各字段
- 支持按需展示（只显示结论，或显示完整方案）
- 便于数据统计和分析

---

## 异常处理与容错

### LLM 调用失败

**降级策略：**
```python
try:
    async for event in self.agent.analyze(request):
        yield event
except LLMError as e:
    yield DiagnosisEvent.error(
        task_id=task_id,
        error_code="LLM_ERROR",
        message="AI 分析服务暂时不可用，请稍后重试"
    )
```

### 向量检索失败

**降级策略：**
```python
try:
    results = await self.knowledge_repo.vector_search(query)
except KnowledgeBaseError:
    # 降级到基于规则的检索
    results = self._rule_based_search(query)
```

### 回调发送失败

**重试策略：**
```python
for attempt in range(1, retry_max + 1):
    try:
        response = await httpx.AsyncClient().post(url, json=data)
        if response.status_code == 200:
            return True
    except Exception:
        wait_time = 5 * (2 ** (attempt - 1))  # 5s, 10s, 20s
        await asyncio.sleep(wait_time)
```

**指数退避的好处：**
- 避免短时间内频繁重试冲击服务
- 给 SpringBoot 后端恢复时间
- 3 次重试基本能覆盖临时故障

---

## 性能优化

### 向量检索优化

**HNSW 索引调优：**
```sql
-- 调整 ef_runtime 参数（查询时召回率）
SET hnsw.ef_search = 100;  -- 默认 40，提高召回率

-- 查询时限制返回数量
SELECT ... ORDER BY distance LIMIT 5;
```

### Embedding 缓存

**优化策略：**
```python
# 对相同文本缓存 Embedding 结果
@lru_cache(maxsize=1000)
async def _embed_text(self, text: str) -> List[float]:
    return await self.embedding_client.embed_query(text)
```

**为什么有效？**
- 知识库文档相对固定，Embedding 可复用
- 减少对 DashScope API 的调用，降低成本

### 数据库连接池

```python
self.pool = await asyncpg.create_pool(
    dsn=database_url,
    min_size=10,
    max_size=50,
    command_timeout=60,
)
```

**连接池大小选择：**
- `min_size=10`: 保证基本并发能力
- `max_size=50`: 峰值时扩容，避免资源耗尽

---

## 面试追问应对

### Q: 为什么选择 DeepSeek 而不是 GPT-4？

**A:** 主要考虑三点：
1. **成本优势** - DeepSeek 价格约为 GPT-4 的 1/10，高频调用场景成本敏感
2. **中文能力** - 国内钻井场景，DeepSeek 对中文专业术语理解更好
3. **响应速度** - DeepSeek API 平均响应时间 1-2 秒，GPT-4 需要 3-5 秒

### Q: RAG 检索不准怎么办？

**A:** 我们有多个优化策略：
1. **混合检索** - 向量检索 + 关键词检索（BM25），提高召回率
2. **重排序** - 对检索结果用 Cross-Encoder 重排序，提高精确度
3. **知识库质量** - 文档由专家编写和审核，保证源头质量

### Q: 如何保证诊断结论的安全性？

**A:** 三层保障：
1. **专家审核** - 知识库文档由领域专家审核
2. **规则引擎** - 配药方案使用规则引擎，不是 LLM 生成
3. **风险评级** - 系统给出风险等级（LOW/MEDIUM/HIGH/CRITICAL），高风险需要人工确认

### Q: SSE 断线了怎么办？

**A:**
1. **客户端重连** - 浏览器自动重连，可指定 `last_event_id` 续传
2. **服务端缓存** - 任务结果持久化到数据库，支持通过 `/api/v1/diagnosis/{task_id}` 查询
3. **回调机制** - 支持配置 callback_url，分析完成后主动通知

### Q: 如何评估诊断效果？

**A:** 三个指标：
1. **准确率** - 诊断结论与专家人工判断的对比
2. **处置采纳率** - 现场工程师实际执行处置措施的比例
3. **问题解决率** - 按方案处置后问题解决的比例

### Q: Embedding 向量为什么选 1024 维？

**A:** 通义千问 text-embedding-v3 的默认维度。我们评估过：
- 512 维：检索速度快，但召回率下降
- 1024 维：精度和成本的平衡点，中文语义理解优秀
- 支持更灵活的维度选择（64-1024），可根据场景调整

---

## 关键亮点总结

1. **技术选型合理** - LangChain + DeepSeek + pgvector，成本和性能平衡
2. **架构设计清晰** - 分层架构，职责明确，易扩展
3. **用户体验优秀** - SSE 流式输出，实时反馈分析过程
4. **容错机制完善** - 降级 + 重试 + 回调，保证高可用
5. **知识可传承** - RAG 技术将专家经验系统化、可复用

---

## 简历写法

### 项目经历

**智能钻井液诊断系统 | 核心开发者**

**项目描述：**
基于 LangChain Agent + RAG 技术构建的智能诊断系统，为石油钻井场景提供实时性能分析和处置方案，将传统人工响应时间从小时级缩短至秒级。

**技术栈：**
LangChain 1.0、DeepSeek LLM、PostgreSQL + pgvector、FastAPI、SSE、asyncio

**核心工作：**

1. **Agent 架构设计**
   - 使用 LangChain `create_agent` + `ToolStrategy` 构建诊断 Agent，解决 DeepSeek API 不支持 json_schema 的问题
   - 设计专家 Prompt，引导 Agent 按流程分析（数据→趋势→检索→诊断→处方）
   - 使用 `astream` + `stream_mode=["messages", "updates"]` 同时捕获 LLM 思考内容和工具调用
   - 实现 SSE 流式输出，细分 6 种 step 类型（data_analysis, analyzing, tool_call, tool_result, reasoning, structuring）

2. **RAG 知识检索系统**
   - 设计父子文档结构，使用 RecursiveCharacterTextSplitter（600 字符/块，100 重叠）
   - PostgreSQL + pgvector 存储，配置 HNSW 索引（m=16, ef_construction=64），支持余弦相似度检索
   - 实现 Embedding 异步调用（run_in_executor），避免阻塞事件循环，检索耗时 <100ms

3. **数据模型与 API 设计**
   - 定义诊断请求/响应模型（Pydantic），支持 20 条采样数据、15 个流变性参数
   - 设计 8 个 REST API（分析、查询、知识库 CRUD），SSE 流式 + 降级查询双模式
   - 实现指数退避重试机制（5s/10s/20s），保证 SpringBoot 回调成功率

**项目成果：**
- 诊断响应时间从 2-4 小时（人工）缩短至 30-60 秒（AI）
- 知识库沉淀 100+ 专家文档，向量检索召回率达 85%
- 系统上线 3 个月，支撑 5 口井的实时诊断，处置采纳率 72%

**技术难点与解决：**

1. **LLM 输出不稳定**
   - 问题：同样输入，诊断结论可能不一致
   - 解决：工具化确定性计算（趋势分析、配药方案），LLM 只负责文本生成

2. **向量检索精度不足**
   - 问题：专业术语（"动塑比"）检索不准
   - 解决：混合检索（向量 + BM25）+ 专家优化文档描述

3. **SSE 断线重连**
   - 问题：网络波动导致分析中断
   - 解决：浏览器自动重连 + 服务端结果持久化 + 回调机制

---

## 可追问的技术点

1. **LangChain Agent 的工作原理** - 你理解 ReAct 框架吗？
2. **HNSW 索引原理** - m 和 ef_construction 参数怎么调优？
3. **SSE 和 WebSocket 的区别** - 什么场景选什么？
4. **Pydantic 数据验证** - Field Validator 怎么用？
5. **asyncio 异步编程** - run_in_executor 为什么必要？
6. **向量相似度计算** - 余弦相似度 vs 欧氏距离怎么选？
7. **RAG 评估指标** - 怎么衡量检索质量？
8. **Prompt Engineering** - 怎么设计专家系统 Prompt？
