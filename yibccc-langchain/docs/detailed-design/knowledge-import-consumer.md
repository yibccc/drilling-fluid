# 知识库导入消费者 - 详细设计文档

> **实现状态**: 已完成
> **最后更新**: 2026-02-26
> **版本**: v1.0

## 实现状态概览

| 模块 | 状态 | 说明 |
|------|------|------|
| Redis Stream 消费者 | ✅ 完成 | KnowledgeImportConsumer |
| 父子分块策略 | ✅ 完成 | 3000字符父块 + 600字符子块 |
| pgvector 向量存储 | ✅ 完成 | 原生格式，支持 cosine 距离 |
| 状态同步 | ✅ 完成 | Redis 状态更新 |
| 应用启动集成 | ✅ 完成 | lifespan 自动启动 |
| 单元测试 | ✅ 完成 | 9 个测试用例 |

---

## 1. 功能定位

本模块是知识库系统的 Agent 端导入消费者，负责：

- 从 Redis Stream 消费文档导入任务
- 使用父子分块策略处理长文本
- 调用 DashScope API 生成向量
- 存储文档和分块到 PostgreSQL (pgvector)
- 实时更新导入状态到 Redis

## 2. 技术架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        Redis Stream                             │
│                  stream:knowledge_import                        │
│                   [生产者: SpringBoot]                            │
└──────────────────────────────┬──────────────────────────────────┘
                               │ XREADGROUP
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     FastAPI (Agent 服务)                         │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │        KnowledgeImportConsumer                             │  │
│  │  ┌─────────────┐    ┌──────────────┐    ┌─────────────┐  │  │
│  │  │ 消息消费      │───▶│ 父子分块      │───▶│ 向量化        │  │  │
│  │  │ XREADGROUP   │    │ Recursive    │    │ DashScope    │  │  │
│  │  │              │    │ TextSplitter │    │ Embeddings   │  │  │
│  │  └─────────────┘    └──────────────┘    └─────────────┘  │  │
│  │                                                   │          │  │
│  │                                              ┌───────▼───────│  │
│  │                                              │ Knowledge     │  │
│  │                                              │ Repository    │  │
│  │                                              │ (create_chunks│  │
│  │                                              │  + vector_     │  │
│  │                                              │   search)      │  │
│  │                                              └───────┬───────│  │
│  └──────────────────────────────────────────────┼───────┼───────┘  │
│                                                 │       │          │
│                                          ┌───────▼───────▼──────┐   │
│                                          │ PostgreSQL + pgvector│   │
│                                          │ knowledge_documents  │   │
│                                          │ knowledge_chunks     │   │
│                                          └──────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## 3. 分块策略

### 3.1 父子分块

**目的**: 保留文档结构的同时支持细粒度检索

```
原始文档 (5000 字)
    │
    ├─ 段落分割 (\n\n)
    │
    ├─ 父块 1 (2500 字)
    │   ├─ 子块 1-1 (600 字, overlap 100)
    │   ├─ 子块 1-2 (600 字, overlap 100)
    │   └─ ...
    │
    └─ 父块 2 (2500 字)
        ├─ 子块 2-1 (600 字, overlap 100)
        └─ ...
```

### 3.2 参数配置

| 层级 | 大小 | 重叠 | 说明 |
|------|------|------|------|
| 父分块 | 3000 字符 | 0 | 按段落分割 |
| 子分块 | 600 字符 | 100 字符 | RecursiveCharacterTextSplitter |

### 3.3 分块代码

```python
def _create_parent_chunks(self, text: str) -> List[str]:
    # 按双换行分段
    paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]

    # 合并小段落，确保父块约 3000 字符
    parent_chunks = []
    current_chunk = ""

    for para in paragraphs:
        if len(current_chunk) + len(para) > 3000:
            if current_chunk:
                parent_chunks.append(current_chunk)
            current_chunk = para
        else:
            current_chunk += "\n\n" + para if current_chunk else para

    if current_chunk:
        parent_chunks.append(current_chunk)

    return parent_chunks
```

## 4. 向量存储

### 4.1 pgvector 类型编解码器

```python
await conn.set_type_codec(
    'vector',
    encoder=lambda v: str(v),  # list[float] -> "[0.1, 0.2, ...]"
    decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
    schema='pg_catalog',
    format='text'
)
```

### 4.2 批量 Embedding

```python
async def _embed_batch(self, texts: List[str]) -> List[List[float]]:
    def sync_embed_batch():
        return self.embedding_client.embed_documents(texts)

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, sync_embed_batch)
```

**性能优化**: 批量调用比单个调用快 3-5 倍

### 4.3 向量搜索

```sql
SELECT DISTINCT ON (kd.doc_id)
    kd.doc_id, kd.title, kd.content,
    MIN(kc.embedding <-> $1) as distance
FROM knowledge_documents kd
JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
WHERE kd.category = $2
GROUP BY kd.doc_id, kd.title, kd.content
ORDER BY distance
LIMIT $3;
```

**距离函数**: `<->` (cosine 距离)

## 5. 消费者配置

### 5.1 Redis Stream 配置

| 配置项 | 值 | 说明 |
|--------|-----|------|
| Stream 名称 | `stream:knowledge_import` | |
| Consumer Group | `group:knowledge_workers` | |
| Block 时间 | 1000 ms | 1 秒超时 |
| Count | 1 | 每次取 1 条消息 |

### 5.2 启动配置

```python
# 在 src/api/main.py 的 lifespan 中启动
_knowledge_import_consumer = KnowledgeImportConsumer(pg_repo.pool)
asyncio.create_task(_knowledge_import_consumer.start())
```

## 6. 状态管理

### 6.1 状态流转

```
PARSING → CHUNKING → EMBEDDING → COMPLETED
    ↓
  FAILED
```

### 6.2 Redis 键设计

| 键模式 | 说明 | 示例值 |
|--------|------|--------|
| `knowledge:status:{docId}` | 导入状态 | "COMPLETED" |
| `knowledge:chunks:{docId}` | 分块数量 | "15" |
| `knowledge:error:{docId}` | 错误信息 | "Tika 解析失败" |

## 7. 数据库表结构

### 7.1 knowledge_documents

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| doc_id | VARCHAR(100) | 文档唯一标识 |
| title | VARCHAR(500) | 文档标题 |
| category | VARCHAR(50) | 分类 |
| subcategory | VARCHAR(100) | 子分类 |
| content | TEXT | 完整内容 |
| metadata | JSONB | 元数据 |
| chunk_count | INT | 分块数量 |
| import_status | VARCHAR(20) | 导入状态 |
| created_at | TIMESTAMPTZ | 创建时间 |

### 7.2 knowledge_chunks

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| parent_doc_id | VARCHAR(100) | 父文档 ID |
| chunk_index | INT | 分块索引 |
| content | TEXT | 分块内容 |
| embedding | vector(1024) | 1024维向量 |
| created_at | TIMESTAMPTZ | 创建时间 |

## 8. 测试

### 8.1 单元测试

```bash
cd yibccc-langchain

# 向量存储测试
uv run pytest tests/repositories/test_knowledge_repo_vector_fix.py -v

# 消费者测试
uv run pytest tests/services/test_knowledge_import_consumer.py -v
```

### 8.2 测试用例

| 测试 | 说明 |
|------|------|
| `test_vector_storage_with_native_format` | pgvector 原生格式存储 |
| `test_parent_chunk_creation` | 父分块创建 |
| `test_parent_chunk_merging` | 小段落合并 |
| `test_parent_chunk_size_limit` | 大小限制 |

## 9. 错误处理

### 9.1 异常类型

| 异常 | 处理 |
|------|------|
| 文档不存在 | 记录日志，ACK 消息 |
| Tika 解析失败 | 更新状态 FAILED，ACK 消息 |
| Embedding API 失败 | 更新状态 FAILED，ACK 消息 |
| 数据库连接错误 | 等待重试 |

### 9.2 重试机制

- 消费失败: ACK 消息（避免死循环）
- 数据库错误: 依赖 asyncpg 连接池重试
- API 错误: 快速失败，记录日志

## 10. 性能指标

| 指标 | 目标值 |
|------|--------|
| 文档解析速度 | > 10 MB/s |
| 分块速度 | > 100 chunks/s |
| Embedding 速度 | ~ 100 docs/min (批量) |
| 向量存储速度 | > 1000 chunks/s |

## 11. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `services/knowledge_import_consumer.py` | 导入消费者 |
| `repositories/knowledge_repo.py` | 知识库仓储 |
| `api/main.py` | 应用入口（集成启动） |
| `tests/services/test_knowledge_import_consumer.py` | 消费者测试 |
| `tests/repositories/test_knowledge_repo_vector_fix.py` | 向量测试 |
