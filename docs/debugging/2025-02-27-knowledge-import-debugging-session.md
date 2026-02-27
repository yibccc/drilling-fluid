# 知识库导入功能调试会话记录

> **日期**: 2026-02-27
> **调试时长**: ~2小时
> **状态**: ✅ 问题已解决

---

## 问题描述

知识库文件导入功能在测试时出现以下问题：
1. 测试脚本显示 `文档 ID: null`
2. 状态查询返回 `{"importStatus":"UNKNOWN","docId":"null"}`
3. Python 消费者无任何日志输出
4. 导入状态始终停留在 `QUEUED`

---

## 问题根因分析

### 问题 1: docId 提取失败

**表现**:
```bash
文档 ID: null
状态: {"importStatus":"UNKNOWN","docId":"null"}
```

**根因**: API 响应使用 snake_case (`doc_id`)，测试脚本使用 camelCase (`docId`)

**证据**:
- DTO 定义: `@JsonProperty("doc_id") private String docId;`
- 测试脚本: `DOC_ID=$(echo "${RESPONSE}" | jq -r '.data.docId')`

**修复**:
```bash
# test_knowledge_import.sh:20
DOC_ID=$(echo "${RESPONSE}" | jq -r '.data.doc_id')  # 修正为 doc_id
```

### 问题 2: Redis 配置不一致

**表现**: Java 发送消息到 Redis Stream，但 Python 消费者无反应

**根因**: Java 和 Python 连接到不同的 Redis 实例

| 服务 | Redis Host | Database | Password |
|------|-----------|----------|----------|
| Java | 47.113.226.70 | 10 | F2345678. |
| Python | localhost | 0 | root |

**修复**: 统一使用本地 Redis
```yaml
# application-dev.yml
sky:
  redis:
    host: localhost
    port: 6379
    password: root
    database: 0
```

```bash
# .env
REDIS_URL=redis://:root@localhost:6379
REDIS_DB=0
```

### 问题 3: Python 日志未输出

**表现**: Python 服务运行但看不到任何应用日志

**根因**: Python `logging` 模块未配置，只有 Uvicorn 日志

**修复**:
```python
# main.py
import logging

def main():
    logging.basicConfig(
        level=logging.INFO,
        format='%(levelname)s - %(name)s - %(message)s'
    )
    uvicorn.run(...)
```

### 问题 4: await 同步方法

**表现**:
```
TypeError: object list can't be used in 'await' expression
```

**根因**: `_create_chunks` 是同步方法，但错误使用了 `await`

**修复**:
```python
# knowledge_import_consumer.py:140
all_chunks = self._create_chunks(content)  # 移除 await
```

### 问题 5: pgvector 类型 schema 错误

**表现**:
```
ValueError: unknown type: pg_catalog.vector
```

**根因**: `vector` 类型在 `public` schema，不是 `pg_catalog`

**验证**:
```sql
SELECT typname, typnamespace::regnamespace FROM pg_type WHERE typname = 'vector';
-- 结果: vector | public
```

**修复**:
```python
# knowledge_repo.py:114
await conn.set_type_codec(
    'vector',
    encoder=lambda v: str(v),
    decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
    schema='public',  # 修正为 public
    format='text'
)
```

---

## 修复后的完整流程

```
用户上传文件
    │
    ▼
Java: 生成 docId → PARSING
    │
    ▼
Java: Tika 解析 → 发送到 Redis Stream
    │
    ▼
Python: 消费者接收消息 → CHUNKING
    │
    ▼
Python: 父子分块 → EMBEDDING
    │
    ▼
Python: DashScope 向量化 → 存入 PostgreSQL
    │
    ▼
Python: 更新状态 → COMPLETED
    │
    ▼
用户查询状态 → 显示 COMPLETED
```

---

## 测试验证

### 测试脚本结果

```bash
=== 知识库文件导入测试 ===

1. 测试单文件上传...
响应: {"code":200,"msg":null,"data":{"doc_id":"DOC-1772187588393-B94A25FE",...}}
文档 ID: DOC-1772187588393-B94A25FE

2. 查询文档状态（轮询）...
第 1 次: PARSING
第 2 次: COMPLETED  ✓

3. 测试批量上传...
响应: {"total":2,"success":2,"failed":0}  ✓
```

### 数据库验证

```sql
-- 文档记录
SELECT * FROM knowledge_documents WHERE doc_id = 'DOC-1772187588393-B94A25FE';
-- 结果: 1 条记录，chunk_count = 1

-- 分块记录
SELECT * FROM knowledge_chunks WHERE parent_doc_id = 'DOC-1772187588393-B94A25FE';
-- 结果: 1 条分块，content_length = 394，embedding 不为空
```

---

## 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `shs/test_knowledge_import.sh` | 修正 docId 提取字段名 |
| `yibccc-langchain/main.py` | 添加 logging 配置 |
| `yibccc-langchain/.env` | 修正 Redis URL 为本地 |
| `yibccc-langchain/src/api/main.py` | 添加调试日志和端点 |
| `yibccc-langchain/src/services/knowledge_import_consumer.py` | 移除错误的 await |
| `yibccc-langchain/src/repositories/knowledge_repo.py` | 修正 pgvector schema |
| `sky-chuanqin/sky-server/src/main/resources/application-dev.yml` | 修正 Redis 为本地 |

---

## 遗留问题/优化建议

1. **数据库状态同步**: `knowledge_documents.import_status` 保持在 CHUNKING，可以考虑更新为 COMPLETED
2. **错误信息传递**: Redis 中可以存储更详细的错误信息
3. **性能监控**: 添加各阶段的耗时统计
4. **批量导入**: 支持更多文件格式的批量上传

---

## 参考资料

- 设计文档: `yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md`
- 实现计划: `sky-chuanqin/docs/plans/2026-02-26-knowledge-import-implementation.md`
- 数据库脚本: `yibccc-langchain/docs/sql/knowledge_import_schema.sql`
