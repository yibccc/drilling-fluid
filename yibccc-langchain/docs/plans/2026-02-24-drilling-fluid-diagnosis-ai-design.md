# 钻井液性能实时检测与自动化分析系统 - AI 功能设计文档

**日期**: 2026-02-24
**项目**: yibccc-langchain
**版本**: 1.0.0
**状态**: 已批准

---

## 1. 概述

### 1.1 项目背景

本设计描述了在现有 FastAPI + LangChain Agent 服务基础上，为**钻井液性能实时检测与自动化分析系统**添加 AI 功能的完整方案。

### 1.2 核心功能

- **预警诊断分析**：基于最近 20 条钻井液采样数据进行时序分析，生成诊断结论和处置建议
- **RAG 专家知识库**：使用 pgvector + HNSW 索引实现处置措施知识检索
- **流式响应**：通过 SSE 推送分析过程，实时展示 Agent 思考路径
- **结构化输出**：最终返回诊断结论、趋势分析、处置措施和配药方案

### 1.3 技术栈

| 组件 | 技术 |
|------|------|
| Web 框架 | FastAPI >= 0.115.0 |
| LLM 编排 | LangChain >= 1.0.0, LangGraph >= 0.2.0 |
| 向量检索 | PostgreSQL + pgvector (HNSW) |
| 数据库 | PostgreSQL (asyncpg) |
| 缓存 | Redis (AsyncRedisSaver) |
| 文档解析 | SpringBoot + Apache Tika |

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SpringBoot 后端                                 │
│  ┌─────────────────┐         ┌──────────────────────────────────────────┐  │
│  │   XXL-Job       │         │            WebSocket Manager             │  │
│  │   定时任务       │         │      (按井号 well_group 分组管理连接)       │  │
│  └────────┬────────┘         └──────────────────┬───────────────────────┘  │
│           │                                     │                          │
│           │ 预警触发                            │ 调用接口/接收回调          │
│           ▼                                     ▼                          │
│  ┌─────────────────┐         ┌──────────────────────────────────────────┐  │
│  │  预警检测服务     │────────│              HTTP Client                 │  │
│  │  (阈值判断)      │         │    /api/v1/diagnosis/analyze            │  │
│  └─────────────────┘         └──────────────────┬───────────────────────┘  │
└─────────────────────────────────────────────────┼──────────────────────────┘
                                                  │
                                ┌─────────────────┼─────────────────┐
                                │                 │                 │
                                │ SSE Stream      │ 回调接口         │ HTTP
                                │ (分析过程)       │ (结构化结果)     │ (查询/管理)
                                ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         FastAPI Agent 服务 (yibccc-langchain)               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         API 层                                       │   │
│  │  POST /api/v1/diagnosis/analyze     - 预警诊断分析 (SSE)             │   │
│  │  POST /api/v1/diagnosis/callback    - 结果回调 (SpringBoot 调用)     │   │
│  │  GET  /api/v1/diagnosis/{task_id}   - 查询任务状态                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                         │
│  ┌─────────────────────────────────▼─────────────────────────────────┐   │
│  │                         Agent 编排层                                │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    钻井液诊断 Agent                          │   │   │
│  │  │  输入: {well_id, alert_data, samples[20], context}          │   │   │
│  │  │  流式输出: {type: "thinking", content: "..."}                │   │   │
│  │  │  最终输出: {diagnosis, measures[], prescription}             │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                         │
│  ┌─────────────────────────────────▼─────────────────────────────────┐   │
│  │                         服务层                                      │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │   │
│  │  │ diagnosis_service│  │  rag_service     │  │ callback_service│  │   │
│  │  │    (诊断分析)     │  │   (知识检索)      │  │   (回调SpringBoot)│ │   │
│  │  └──────────────────┘  └──────────────────┘  └─────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                         │
│  ┌─────────────────────────────────▼─────────────────────────────────┐   │
│  │                         数据访问层                                   │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │   │
│  │  │   Redis          │  │  PostgreSQL      │  │   pgvector      │  │   │
│  │  │  - 任务状态缓存   │  │  - 诊断历史       │  │  - 向量检索      │  │   │
│  │  │  - 会话上下文     │  │  - 处置记录       │  │  - HNSW索引     │  │   │
│  │  └──────────────────┘  └──────────────────┘  └─────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心流程

```
1. 预警触发 → SpringBoot 检测到阈值超标 → 调用 Agent 分析接口
2. 流式分析 → Agent 通过 SSE 推送分析过程（思考、检索、推理）
3. 结果回调 → Agent 完成分析 → 调用 SpringBoot 回调接口传递结构化结果
4. 前端推送 → SpringBoot 通过 WebSocket 推送给前端
```

---

## 3. API 接口设计

### 3.1 预警诊断分析接口（SSE 流式）

**端点**: `POST /api/v1/diagnosis/analyze`

**请求**:
```json
{
  "task_id": "TASK-20250224-WELL001-001",
  "well_id": "WELL-001",
  "alert_type": "DENSITY_HIGH",
  "alert_triggered_at": "2025-02-24T10:30:00Z",
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
      "sample_time": "2025-02-24T09:00:00Z",
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
    "... 最近20条数据，时间降序排列 ..."
  ],
  "context": {
    "current_depth": 2450,
    "formation_type": "砂岩",
    "drilling_phase": "三开",
    "recent_operations": ["起钻", "循环"]
  },
  "callback_url": "https://springboot-backend/api/internal/diagnosis/callback",
  "stream": true
}
```

**响应** (Server-Sent Events):
```
event: start
data: {"task_id":"TASK-20250224-WELL001-001","well_id":"WELL-001","samples_count":20,"started_at":"2025-02-24T10:30:01Z"}

event: thinking
data: {"content":"正在分析最近20条钻井液数据（采样时间: 09:00-10:30），检测到密度持续上升...","step":"data_analysis"}

event: trend_analysis
data: {"field":"density","analysis":{"trend":"rising","from":1.22,"to":1.35,"rate":0.13,"duration":"1.5小时","acceleration":"increasing"}}

event: retrieval
data: {"query":"密度持续上升 塑性黏度升高 处置措施","docs_found":5,"sources":["API规范13.2节","处置手册D-05","案例库CASE-237"]}

event: thinking
data: {"content":"根据20条历史数据分析，密度和塑性黏度呈加速上升趋势，判断为固相含量持续累积...","step":"reasoning"}

event: diagnosis
data: {"summary":"钻井液密度和塑性黏度持续上升，呈加速趋势","cause":"钻进过程中岩屑持续混入，固控设备分离效率不足","risk_level":"HIGH"}

event: prescription
data: {"action":"recommend_prescription","content":{"dilution_water":"8%","viscosity_reducer":"0.3%","mixing_time":"45min"}}

event: result
data: {"diagnosis":{"summary":"...","cause":"...","risk_level":"HIGH"},"measures":[{...}],"prescription":{...}}

event: done
data: {"task_id":"TASK-20250224-WELL001-001","completed_at":"2025-02-24T10:30:20Z","status":"SUCCESS"}
```

### 3.2 结果回调接口

**Agent 发送回调** → SpringBoot:
```json
{
  "task_id": "TASK-20250224-WELL001-001",
  "well_id": "WELL-001",
  "status": "SUCCESS",
  "completed_at": "2025-02-24T10:30:15Z",
  "result": {
    "diagnosis": {
      "summary": "钻井液密度和塑性黏度持续上升，呈加速趋势",
      "cause": "钻进过程中岩屑持续混入，固控设备分离效率不足",
      "risk_level": "HIGH",
      "trend_outlook": "如不及时处理，预计1小时内密度可能突破1.40"
    },
    "measures": [
      {"step": 1, "action": "停钻循环", "duration": "15min", "priority": "HIGH"},
      {"step": 2, "action": "加水稀释", "amount": "8%", "priority": "HIGH"}
    ],
    "prescription": {
      "dilution_water": "8%",
      "viscosity_reducer": "0.3%",
      "mixing_time": "45min"
    }
  }
}
```

### 3.3 事件类型

| 事件类型 | 说明 | 数据结构 |
|---------|------|---------|
| `start` | 分析开始 | `{task_id, well_id, samples_count, started_at}` |
| `thinking` | Agent 思考过程 | `{content, step}` |
| `trend_analysis` | 趋势分析结果 | `{field, analysis: {trend, from, to, rate, acceleration}}` |
| `retrieval` | 知识库检索 | `{query, docs_found, sources[]}` |
| `diagnosis` | 诊断结论 | `{summary, cause, risk_level, trend_outlook}` |
| `prescription` | 生成配药方案 | `{action, content}` |
| `result` | 最终完整结果 | `{diagnosis, trend_analysis, measures[], prescription}` |
| `done` | 分析完成 | `{task_id, completed_at, status, tokens_used}` |
| `error` | 错误信息 | `{code, message}` |

---

## 4. RAG 知识库设计

### 4.1 知识库结构

```
knowledge_base/
└── measures/                     # 处置措施库
    ├── density/                  # 密度异常处置
    │   ├── density-high.md       # 密度偏高处置
    │   ├── density-low.md        # 密度偏低处置
    │   └── density-fluctuation.md # 密度波动处置
    ├── viscosity/                # 黏度异常处置
    │   ├── pv-high.md            # 塑性黏度偏高
    │   ├── yp-low.md             # 动切力偏低
    │   └── ...
    └── gel/                      # 切力异常处置
        └── ...
```

### 4.2 文档处理流程

```
┌─────────────────────────────────────────────────────────────────┐
│                         SpringBoot 后端                         │
│  上传 PDF → Apache Tika 解析 → 提取文本和元数据                  │
└─────────────────────────────────────┬───────────────────────────┘
                                      │ POST /api/v1/knowledge/documents
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                         FastAPI Agent 服务                      │
│  接收文本 → 父子分块 → 向量化 → 存储 PostgreSQL + pgvector      │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 数据表结构

```sql
-- 知识文档表（父文档）
CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id VARCHAR(100) UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    category VARCHAR(50) NOT NULL,              -- density, viscosity, gel
    subcategory VARCHAR(100),                   -- high, low, fluctuation
    content TEXT NOT NULL,                      -- 完整文本内容
    metadata JSONB,                             -- 版本、来源等
    chunk_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 子分块表（用于向量检索）
CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_doc_id VARCHAR(100) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,                      -- 分块内容（500-800 tokens）
    embedding vector(1024),                     -- 向量
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- HNSW 索引
CREATE INDEX idx_chunks_embedding_hnsw
ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
```

### 4.4 父子 Chunk 策略

```python
from langchain.retrievers import ParentDocumentRetriever

# 子分块器：用于向量检索（小块，高精度）
child_splitter = RecursiveCharacterTextSplitter(
    chunk_size=600,
    chunk_overlap=100,
)

# 父文档：完整存储（大块，完整上下文）
parent_splitter = RecursiveCharacterTextSplitter(
    chunk_size=2000,
    chunk_overlap=200,
)

# 检索流程：子块向量搜索 → 返回匹配的父文档
```

### 4.5 知识库 API

| 端点 | 方法 | 说明 | 调用者 |
|------|------|------|--------|
| `/api/v1/knowledge/documents` | POST | 创建知识文档 | SpringBoot |
| `/api/v1/knowledge/documents/{doc_id}` | GET | 查询文档 | SpringBoot |
| `/api/v1/knowledge/documents/{doc_id}` | DELETE | 删除文档 | SpringBoot |
| `/api/v1/knowledge/search` | POST | 语义检索 | Agent 内部 |
| `/api/v1/knowledge/rebuild` | POST | 重建向量索引 | 管理工具 |

---

## 5. 数据模型与存储

### 5.1 钻井液采样数据模型

```python
class DrillingFluidSample(BaseModel):
    """单条钻井液采样数据"""
    id: str
    well_id: str
    sample_time: datetime
    formation: str
    outlet_temp: float                        # 出口温度
    density: float                            # 钻井液密度
    gel_10s: float                            # 切力10秒
    gel_10m: float                            # 切力10分
    rpm_3: float                              # 3转速
    rpm_6: float                              # 6转速
    rpm_100: float                            # 100转速
    rpm_200: float                            # 200转速
    rpm_300: float                            # 300转速
    rpm_600: float                            # 600转速
    plastic_viscosity: float                  # 塑性黏度
    yield_point: float                        # 动切力
    flow_behavior_index: float                # 流性指数
    consistency_coefficient: float            # 稠度系数
    apparent_viscosity: float                 # 表观黏度
    yield_plastic_ratio: float                # 动塑比
```

### 5.2 核心数据表

```sql
-- 诊断任务表
CREATE TABLE diagnosis_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) UNIQUE NOT NULL,
    well_id VARCHAR(50) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    alert_triggered_at TIMESTAMPTZ NOT NULL,
    alert_threshold JSONB,
    samples JSONB NOT NULL,                   -- 20条采样数据
    context JSONB,
    callback_url TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 诊断结果表
CREATE TABLE diagnosis_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) UNIQUE NOT NULL,
    diagnosis JSONB NOT NULL,                 -- 诊断结论
    trend_analysis JSONB,                     -- 趋势分析
    measures JSONB,                           -- 处置措施
    prescription JSONB,                       -- 配药方案
    references JSONB,                         -- 参考文献
    rag_metadata JSONB,                       -- RAG 元数据
    llm_metadata JSONB,                       -- LLM 元数据
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 分析过程事件表
CREATE TABLE diagnosis_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    event_data JSONB NOT NULL,
    sequence_num INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 处置反馈表
CREATE TABLE treatment_feedback (
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
```

### 5.3 Redis 缓存设计

```
# 任务状态缓存
diagnosis:task:{task_id}
  ├── status: "PROCESSING"
  ├── well_id: "WELL-001"
  ├── progress: 60
  └── current_step: "retrieval"

# 井号最近的诊断
diagnosis:well:latest:{well_id}
  ├── task_id: "TASK-20250224-WELL001-001"
  ├── status: "COMPLETED"
  └── risk_level: "HIGH"
```

---

## 6. 错误处理与容错机制

### 6.1 错误分类

| 错误类型 | 处理策略 | HTTP 状态码 |
|---------|---------|------------|
| 参数错误 | 直接拒绝，返回错误详情 | 400 |
| 认证错误 | 拒绝访问 | 401 |
| 业务错误 | 记录日志，返回友好提示 | 422 |
| LLM 错误 | 重试 3 次，失败后降级 | 500 |
| RAG 错误 | 降级为无 RAG 模式 | 500 |
| 回调错误 | 记录失败，支持手动查询 | - |

### 6.2 容错降级策略

```python
async def analyze_with_fallback(request: DiagnosisRequest):
    """
    多级降级诊断分析

    优先级1: 正常模式 (RAG + LLM)
    优先级2: 无 RAG 模式 (仅 LLM)
    优先级3: 缓存模式 (返回历史类似案例)
    """
    try:
        async for event in _analyze_normal(request):
            yield event
    except LLMServiceError:
        async for event in _analyze_without_rag(request):
            yield event
    except Exception:
        async for event in _analyze_from_history(request):
            yield event
```

### 6.3 回调重试机制

- 最大重试次数：3 次
- 重试延迟：指数退避（5s, 10s, 20s）
- 失败后记录到数据库，支持手动查询

---

## 7. 项目目录结构

```
yibccc-langchain/
├── src/
│   ├── api/
│   │   ├── routes/
│   │   │   ├── chat.py              # 现有对话路由
│   │   │   └── diagnosis.py         # 新增：诊断路由
│   │   └── dependencies.py
│   ├── services/
│   │   ├── chat_service.py          # 现有对话服务
│   │   ├── sync_service.py          # 现有同步服务
│   │   ├── diagnosis_service.py     # 新增：诊断服务
│   │   ├── rag_service.py           # 新增：RAG 服务
│   │   └── callback_service.py      # 新增：回调服务
│   ├── repositories/
│   │   ├── pg_repo.py               # 现有 PostgreSQL 仓储
│   │   ├── diagnosis_repo.py        # 新增：诊断仓储
│   │   └── knowledge_repo.py        # 新增：知识库仓储
│   ├── models/
│   │   ├── schemas.py               # 扩展：新增诊断相关模型
│   │   └── diagnosis_schemas.py     # 新增：诊断专用模型
│   ├── agents/
│   │   └── diagnosis_agent.py       # 新增：钻井液诊断 Agent
│   ├── prompts/
│   │   └── diagnosis_prompts.py     # 新增：诊断提示词模板
│   └── tools/
│       └── builtin.py               # 扩展：新增诊断工具
├── knowledge_base/                  # 新增：知识库目录
│   └── measures/
│       ├── density/
│       ├── viscosity/
│       └── gel/
├── sql/
│   ├── schema.sql                   # 现有表结构
│   └── diagnosis_schema.sql         # 新增：诊断相关表结构
└── docs/
    └── plans/
        └── 2026-02-24-drilling-fluid-diagnosis-ai-design.md
```

---

## 8. 技术依赖更新

```toml
# pyproject.toml 新增依赖
dependencies = [
    # 现有依赖...
    "pgvector>=0.2.0",              # PostgreSQL 向量扩展
    "langchain-community>=0.2.0",   # ParentDocumentRetriever
    "httpx>=0.25.0",                # 异步 HTTP 客户端（回调）
]
```

---

## 9. 实施检查清单

### Phase 1: 基础设施
- [ ] 创建数据库表结构
- [ ] 安装 pgvector 扩展
- [ ] 配置 Redis 缓存

### Phase 2: 知识库
- [ ] 实现 knowledge_repo.py
- [ ] 实现 rag_service.py
- [ ] 创建知识库管理接口

### Phase 3: 核心服务
- [ ] 实现 diagnosis_repo.py
- [ ] 实现 diagnosis_service.py
- [ ] 实现 callback_service.py
- [ ] 创建诊断 Agent

### Phase 4: API 层
- [ ] 实现 diagnosis.py 路由
- [ ] 实现 SSE 流式响应
- [ ] 实现回调接口

### Phase 5: 测试与优化
- [ ] 单元测试
- [ ] 集成测试
- [ ] 性能优化
- [ ] 监控告警

---

## 附录

### A. 钻井液参数说明

| 参数 | 说明 | 单位 |
|------|------|------|
| 出口温度 | 钻井液返出温度 | °C |
| 密度 | 钻井液密度 | g/cm³ |
| 切力10秒/10分 | 静切力 | Pa |
| 3/6/100/200/300/600转速 | 旋转粘度计读数 | - |
| 塑性黏度 | PV = φ600 - φ300 | mPa·s |
| 动切力 | YP = (φ300 - PV)×0.48 | Pa |
| 流性指数 | n = 3.32×log(φ300/φ600) | - |
| 稠度系数 | K = φ600/(1022^n) | Pa·s^n |
| 表观黏度 | AV = φ600/2 | mPa·s |
| 动塑比 | YP/PV | - |

### B. 风险等级定义

| 等级 | 说明 | 响应时间 |
|------|------|---------|
| LOW | 低风险，建议关注 | 30分钟内 |
| MEDIUM | 中等风险，需要处置 | 15分钟内 |
| HIGH | 高风险，立即处置 | 5分钟内 |
| CRITICAL | 危险，停钻处置 | 立即 |

---

**文档状态**: ✅ 已批准
**下一步**: 调用 writing-plans 技能创建详细实施计划
