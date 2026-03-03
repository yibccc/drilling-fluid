# 知识库重构设计文档 - Two-step RAG Chain

**项目:** yibccc-langchain
**日期:** 2025-01-03
**作者:** AI Assistant
**状态:** 待批准

---

## 1. 概述

### 1.1 背景

当前项目的知识库实现使用直接 SQL 查询 pgvector，代码复杂且未遵循 LangChain 最佳实践。需要重构为 LangChain 推荐的方式，提升代码可维护性和可扩展性。

### 1.2 目标

- **代码简化**: 使用 LangChain PGVector 抽象层替代直接 SQL
- **可维护性**: 遵循 LangChain 最佳实践，便于升级
- **功能扩展**: 支持更多 LangChain 生态功能

### 1.3 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| RAG 方式 | Two-step Chain | 单次 LLM 调用，延迟低 |
| 向量存储 | PGVector (LangChain) | 保持 pgvector 数据 |
| Embedding | DashScopeEmbeddings | 原生支持，无需更换 |
| 实施方式 | 快速重构 | 直接替换，一步到位 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Two-step RAG Chain 架构                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  SpringBoot / 前端                                                          │
│       │ POST /api/v1/diagnosis/analyze                                     │
│       ▼                                                                     │
│  DiagnosisService → DiagnosisAgent                                         │
│       │                                                                     │
│       ├─→ Step 1: RetrievalMiddleware (自动检索)                          │
│       │    ├─ 提取用户查询                                                │
│       │    ├─ 调用 VectorStoreService.similarity_search()                 │
│       │    └─ 注入知识库上下文到 system message                            │
│       │                                                                     │
│       ├─→ Step 2: LLM 单次调用 (检索+回答)                                  │
│       │    └─ 返回结构化诊断结果                                           │
│       │                                                                     │
│       └─→ VectorStoreService (PGVector)                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 关键组件

| 组件 | 文件 | 职责 |
|------|------|------|
| VectorStoreService | services/vector_store_service.py | LangChain PGVector 封装 |
| RetrievalMiddleware | agents/diagnosis_middleware.py | 自动检索中间件 |
| DiagnosisAgent | agents/diagnosis_agent.py | Two-step Agent |
| 数据迁移脚本 | scripts/migrate_to_pgvector.py | 数据迁移 |

---

## 3. 数据流设计

### 3.1 State 转换

```
原始请求 → State → 中间件增强 → LLM 调用 → 结构化输出
  ↓          ↓          ↓           ↓          ↓
Request  messages  messages+context  result   DiagnosisResult
```

### 3.2 元数据过滤

使用 LangChain 的 `filter` 参数实现分类过滤：

```python
results = await vector_store.similarity_search(
    query="密度偏高怎么办",
    k=5,
    filter={"category": "density"}  # 元数据过滤
)
```

---

## 4. 错误处理

### 4.1 降级策略

| 场景 | 处理方式 |
|------|---------|
| 向量库连接失败 | 不注入上下文，LLM 基于通用知识 |
| 检索结果为空 | 提示 LLM 说明无法检索 |
| LLM 超时 | 返回超时错误，建议重试 |

### 4.2 重试机制

```python
async def _safe_search(self, query: str, filter: dict):
    max_retries = 2
    for attempt in range(max_retries):
        try:
            return await self.vector_store.similarity_search(...)
        except Exception as e:
            if attempt < max_retries - 1:
                await asyncio.sleep(1 * (attempt + 1))
            else:
                return []  # 降级
```

---

## 5. 实施计划

### 5.1 阶段划分

| 阶段 | 内容 | 工期 |
|------|------|------|
| Phase 1 | 基础设施搭建 | 1-2天 |
| Phase 2 | 中间件和 Agent 重构 | 2-3天 |
| Phase 3 | 数据迁移 | 1天 |
| Phase 4 | API 层更新和测试 | 1-2天 |
| Phase 5 | 上线和监控 | 1天 |

### 5.2 文件变更

**新增:**
- src/services/vector_store_service.py
- src/agents/diagnosis_middleware.py
- tests/middleware/test_retrieval_middleware.py
- tests/integration/test_vector_store_service.py
- tests/e2e/test_diagnosis_flow.py
- scripts/migrate_to_pgvector.py

**修改:**
- src/agents/diagnosis_agent.py
- src/services/diagnosis_service.py
- src/tools/diagnosis_tools.py
- src/config.py

**删除:**
- src/repositories/knowledge_repo.py
- src/services/rag_service.py

---

## 6. 回退策略

### 6.1 配置开关

```python
USE_LANGCHAIN_VECTORSTORE: bool = True  # 控制新旧实现
```

### 6.2 数据备份

- 保留旧表 knowledge_documents / knowledge_chunks
- 新表 langchain_pg_* 独立存在
- 可通过配置切换

---

## 7. 测试策略

### 7.1 测试金字塔

```
     ▲▲▲
    ▲▲  集成测试
   ▲▲    E2E测试
单元测试
```

### 7.2 测试覆盖

- 中间件注入上下文 ✅
- 空检索降级处理 ✅
- 重试机制验证 ✅
- 完整诊断流程 ✅

---

## 8. 监控指标

| 指标 | 目标值 |
|------|--------|
| 检索响应时间 | p95 < 500ms |
| 检索成功率 | > 99% |
| LLM 调用成功率 | > 98% |
| 端到端响应时间 | p95 < 10s |

---

## 9. 批准

**设计审批:**

- [ ] 架构设计
- [ ] 组件设计
- [ ] 数据流设计
- [ ] 错误处理设计
- [ ] 测试策略
- [ ] 实施计划

**批准后，将调用 writing-plans skill 创建详细实施计划。**
