# 知识导入 PGVector 迁移设计文档

**日期**: 2026-03-03
**状态**: 已批准
**作者**: Claude Code

---

## 1. 背景和目标

### 1.1 问题背景

当前的知识导入系统使用两套数据存储：

1. **旧存储** (`knowledge_repo.py`):
   - `knowledge_documents` 表: 存储文档元数据
   - `knowledge_chunks` 表: 存储分块内容和向量

2. **新存储** (`vector_store_service.py`):
   - LangChain PGVector 自动管理表 (`langchain_pg_embedding`)
   - 支持 metadata JSONB 字段

当前 `knowledge_import_consumer.py` 仍调用旧存储层，需要迁移到新架构。

### 1.2 设计目标

1. **完全迁移到 PGVector**: 停止使用 `knowledge_documents` 和 `knowledge_chunks` 表
2. **保留 Chunk 级别查询**: 支持父子分块层级关系
3. **元数据统一管理**: 将所有元数据存储在 PGVector 的 metadata 字段
4. **向后兼容**: 提供平滑迁移路径，不影响现有数据

---

## 2. 新旧架构对比

### 2.1 旧架构 (当前)

```
┌─────────────────────────────────────────────────────────────┐
│                    KnowledgeImportConsumer                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              KnowledgeRepository (knowledge_repo.py)         │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │ knowledge_documents │    │      knowledge_chunks       │ │
│  │  - doc_id           │    │  - chunk_id                 │ │
│  │  - title            │    │  - doc_id (FK)              │ │
│  │  - content          │    │  - content                  │ │
│  │  - category         │    │  - embedding                │ │
│  │  - metadata (JSON)  │    │  - parent_chunk_id          │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 新架构 (目标)

```
┌─────────────────────────────────────────────────────────────┐
│                    KnowledgeImportConsumer                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│            VectorStoreService (vector_store_service.py)      │
│                         │                                   │
│                         ▼                                   │
│         ┌───────────────────────────────┐                   │
│         │     PGVector (LangChain)      │                   │
│         │  ┌─────────────────────────┐    │                   │
│         │  │  langchain_pg_embedding │    │                   │
│         │  │    - id (UUID)          │    │                   │
│         │  │    - document (文本)    │    │                   │
│         │  │    - embedding (向量)   │    │                   │
│         │  │    - cmetadata (JSONB)  │◄───┼── metadata 包含:  │
│         │  │                         │    │    - doc_id       │
│         │  │                         │    │    - chunk_id     │
│         │  │                         │    │    - parent_id    │
│         │  │                         │    │    - chunk_type   │
│         │  │                         │    │    - chunk_index  │
│         │  │                         │    │    - title        │
│         │  │                         │    │    - category     │
│         │  └─────────────────────────┘    │                   │
│         └───────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 详细的 Metadata 设计

### 3.1 Metadata Schema

所有 chunk 的 metadata 统一存储在 PGVector 的 `cmetadata` JSONB 字段中：

```python
{
    # === 文档标识 ===
    "doc_id": "doc_abc123",           # 原始文档ID (必需)
    "chunk_id": "chunk_001",          # 分块唯一ID (必需)

    # === 层级关系 ===
    "parent_chunk_id": "chunk_000",   # 父分块ID (子块有值，父块为null)
    "chunk_type": "child",            # "parent" | "child"
    "chunk_level": 1,                 # 层级: 0=父, 1=子
    "chunk_index": 0,                 # 在同级别中的索引
    "sibling_count": 5,               # 同级兄弟节点数

    # === 内容元数据 ===
    "title": "文档标题",
    "category": "drilling_fluid",   # 知识分类
    "source": "import",               # 来源: import | manual | api
    "content_type": "text",           # text | markdown | html

    # === 导入信息 ===
    "import_time": "2026-03-03T10:00:00Z",
    "file_record_id": "file_123",     # 关联文件记录ID
    "oss_path": "oss://bucket/path",  # OSS存储路径

    # === 扩展字段 ===
    "custom_tags": ["tag1", "tag2"],
    "language": "zh-CN"
}
```

### 3.2 父子 Chunk 存储示例

对于一个文档，先创建父 chunk，再为每个父 chunk 创建子 chunks：

```
文档: "钻井液技术指南.pdf"
  ├─ 父 Chunk 1: "第一章 概述..." (1000字符)
  │   ├─ 子 Chunk 1.1: "1.1 钻井液定义..." (300字符)
  │   ├─ 子 Chunk 1.2: "1.2 钻井液功能..." (350字符)
  │   └─ 子 Chunk 1.3: "1.3 钻井液分类..." (350字符)
  │
  └─ 父 Chunk 2: "第二章 配方设计..." (1200字符)
      ├─ 子 Chunk 2.1: "2.1 基础配方..." (400字符)
      └─ 子 Chunk 2.2: "2.2 添加剂选择..." (400字符)
```

**PGVector 中的存储格式**:

```python
# 父 Chunk 1
Document(
    page_content="第一章 概述...",
    metadata={
        "doc_id": "doc_guide_001",
        "chunk_id": "chunk_p1",
        "parent_chunk_id": None,
        "chunk_type": "parent",
        "chunk_level": 0,
        "chunk_index": 0,
        "title": "钻井液技术指南",
        "category": "drilling_fluid"
    }
)

# 子 Chunk 1.1
Document(
    page_content="1.1 钻井液定义...",
    metadata={
        "doc_id": "doc_guide_001",
        "chunk_id": "chunk_c1_1",
        "parent_chunk_id": "chunk_p1",  # 指向父 chunk
        "chunk_type": "child",
        "chunk_level": 1,
        "chunk_index": 0,
        "title": "钻井液技术指南",
        "category": "drilling_fluid"
    }
)
```

---

## 4. 代码修改方案

### 4.1 修改文件清单

| 文件 | 修改类型 | 修改内容 |
|------|----------|----------|
| `knowledge_import_consumer.py` | 修改 | 替换 `_embed_and_store_chunks` 方法，使用 `VectorStoreService` |
| `vector_store_service.py` | 扩展 | 添加批量插入和父子 chunk 查询方法 |
| `knowledge_repo.py` | 废弃/保留 | 标记为废弃或保留兼容层 |

### 4.2 核心代码变更

#### 4.2.1 `knowledge_import_consumer.py` 修改

**当前实现**:
```python
async def _embed_and_store_chunks(self, doc_id: str, chunks: List[Dict]):
    """向量化并存储分块"""
    from src.repositories.knowledge_repo import KnowledgeRepository
    repo = KnowledgeRepository(self.pool, self.embeddings)
    await repo.create_chunks(doc_id, chunks)
```

**新实现**:
```python
async def _embed_and_store_chunks(self, doc_id: str, title: str, category: str,
                                 chunks: List[Dict], metadata: Dict = None):
    """向量化并存储分块到 PGVector"""
    from src.services.vector_store_service import VectorStoreService
    from langchain_core.documents import Document

    # 初始化 vector store service
    vector_service = VectorStoreService(settings.get_langchain_connection_string())

    # 构建 LangChain Document 列表
    documents = []
    base_metadata = {
        "doc_id": doc_id,
        "title": title,
        "category": category,
        "source": "import",
        "import_time": datetime.now().isoformat(),
        **(metadata or {})
    }

    for i, chunk in enumerate(chunks):
        # 判断是父 chunk 还是子 chunk
        parent_index = chunk.get('parent_index')
        is_parent = chunk.get('is_parent', False)  # 需要分块时标记

        chunk_metadata = {
            **base_metadata,
            "chunk_id": f"{doc_id}_chunk_{i}",
            "chunk_index": i,
            "chunk_type": "parent" if is_parent else "child",
            "parent_chunk_id": f"{doc_id}_chunk_{parent_index}" if parent_index is not None and not is_parent else None,
        }

        doc = Document(
            page_content=chunk['content'],
            metadata=chunk_metadata
        )
        documents.append(doc)

    # 批量添加到 PGVector
    await vector_service.add_documents(documents)
    logger.info(f"存储 {len(documents)} chunks 到 PGVector: doc_id={doc_id}")
```

#### 4.2.2 `vector_store_service.py` 扩展

添加以下方法：

```python
async def add_parent_child_documents(
    self,
    doc_id: str,
    title: str,
    category: str,
    parent_chunks: List[Dict],
    child_chunks_map: Dict[int, List[Dict]],  # parent_index -> child_chunks
    base_metadata: Dict = None
) -> List[str]:
    """
    批量添加父子分块文档

    Args:
        doc_id: 文档ID
        title: 文档标题
        category: 分类
        parent_chunks: 父分块列表
        child_chunks_map: 父分块索引到子分块列表的映射
        base_metadata: 基础元数据

    Returns:
        插入的文档ID列表
    """
    from langchain_core.documents import Document
    from datetime import datetime

    documents = []
    inserted_ids = []

    base_meta = {
        "doc_id": doc_id,
        "title": title,
        "category": category,
        "source": "import",
        "import_time": datetime.utcnow().isoformat(),
        **(base_metadata or {})
    }

    # 1. 创建父文档
    for parent_idx, parent_chunk in enumerate(parent_chunks):
        parent_id = f"{doc_id}_p{parent_idx}"
        parent_doc = Document(
            page_content=parent_chunk['content'],
            metadata={
                **base_meta,
                "chunk_id": parent_id,
                "chunk_type": "parent",
                "chunk_level": 0,
                "chunk_index": parent_idx,
                "parent_chunk_id": None,
                "child_count": len(child_chunks_map.get(parent_idx, []))
            }
        )
        documents.append(parent_doc)
        inserted_ids.append(parent_id)

        # 2. 创建该父文档的子文档
        child_chunks = child_chunks_map.get(parent_idx, [])
        for child_idx, child_chunk in enumerate(child_chunks):
            child_id = f"{doc_id}_p{parent_idx}_c{child_idx}"
            child_doc = Document(
                page_content=child_chunk['content'],
                metadata={
                    **base_meta,
                    "chunk_id": child_id,
                    "chunk_type": "child",
                    "chunk_level": 1,
                    "chunk_index": child_idx,
                    "parent_chunk_id": parent_id,
                    "sibling_count": len(child_chunks)
                }
            )
            documents.append(child_doc)
            inserted_ids.append(child_id)

    # 批量插入
    if documents:
        await self.add_documents(documents)
        logger.info(f"Added {len(documents)} documents (parents + children) for doc_id={doc_id}")

    return inserted_ids


async def query_by_parent_chunk(
    self,
    parent_chunk_id: str,
    k: int = 10
) -> List[Document]:
    """
    查询指定父分块的所有子分块

    Args:
        parent_chunk_id: 父分块ID
        k: 返回最大数量

    Returns:
        子分块文档列表
    """
    filter_dict = {
        "parent_chunk_id": parent_chunk_id,
        "chunk_type": "child"
    }

    # 使用空查询或父分块内容查询
    return await self.similarity_search(
        query="",
        k=k,
        filter=filter_dict
    )


async def query_by_doc_id(
    self,
    doc_id: str,
    chunk_type: Optional[str] = None,
    k: int = 100
) -> List[Document]:
    """
    查询指定文档的所有分块

    Args:
        doc_id: 文档ID
        chunk_type: 可选过滤 "parent" 或 "child"
        k: 返回最大数量

    Returns:
        分块文档列表
    """
    filter_dict = {"doc_id": doc_id}
    if chunk_type:
        filter_dict["chunk_type"] = chunk_type

    return await self.similarity_search(
        query="",
        k=k,
        filter=filter_dict
    )


async def delete_by_doc_id(self, doc_id: str) -> bool:
    """
    删除指定文档的所有分块

    Args:
        doc_id: 文档ID

    Returns:
        是否成功
    """
    try:
        # 查询所有 chunk_id
        docs = await self.query_by_doc_id(doc_id, k=10000)
        if not docs:
            return True

        ids = [doc.metadata.get("chunk_id") for doc in docs if doc.metadata.get("chunk_id")]
        if ids:
            await self.delete(ids)
            logger.info(f"Deleted {len(ids)} chunks for doc_id={doc_id}")
        return True
    except Exception as e:
        logger.error(f"Failed to delete doc_id={doc_id}: {e}")
        return False
```

---

## 3. 代码修改方案

### 3.1 修改文件清单

| 优先级 | 文件 | 修改类型 | 修改内容 |
|--------|------|----------|----------|
| P0 | `knowledge_import_consumer.py` | 修改 | 替换 `_embed_and_store_chunks` 方法 |
| P0 | `vector_store_service.py` | 扩展 | 添加父子分块批量插入方法 |
| P1 | `knowledge_repo.py` | 废弃 | 标记为废弃，保留兼容层 |
| P1 | 测试文件 | 更新 | 更新测试用例匹配新架构 |

### 3.2 关键代码变更详情

#### 3.2.1 `knowledge_import_consumer.py`

**变更点**:
1. 移除对 `KnowledgeRepository` 的导入
2. 新增 `VectorStoreService` 导入
3. 重写 `_embed_and_store_chunks` 方法
4. 调整 `_process_import` 中的调用参数

**关键变更代码**:

```python
# 文件顶部导入修改
# 移除:
# from src.repositories.knowledge_repo import KnowledgeRepository

# 新增:
from src.services.vector_store_service import VectorStoreService
from langchain_core.documents import Document
from datetime import datetime

# 类初始化修改
async def _embed_and_store_chunks(
    self,
    doc_id: str,
    title: str,
    category: str,
    chunks: List[Dict],
    base_metadata: Dict = None
):
    """向量化并存储分块到 PGVector"""

    # 初始化 VectorStoreService
    vector_service = VectorStoreService(settings.get_langchain_connection_string())

    # 组织父子分块
    parent_chunks = []
    child_chunks_map = {}  # parent_index -> child_chunks

    for chunk in chunks:
        parent_index = chunk.get('parent_index')

        if parent_index is None:
            # 这是一个父 chunk
            parent_chunks.append(chunk)
        else:
            # 这是一个子 chunk
            if parent_index not in child_chunks_map:
                child_chunks_map[parent_index] = []
            child_chunks_map[parent_index].append(chunk)

    # 使用 VectorStoreService 批量插入
    await vector_service.add_parent_child_documents(
        doc_id=doc_id,
        title=title,
        category=category,
        parent_chunks=parent_chunks,
        child_chunks_map=child_chunks_map,
        base_metadata=base_metadata
    )

    logger.info(f"成功存储文档到 PGVector: doc_id={doc_id}, "
                f"parents={len(parent_chunks)}, children={sum(len(v) for v in child_chunks_map.values())}")
```

#### 3.2.2 `vector_store_service.py`

新增以下方法：

1. `add_parent_child_documents()` - 批量插入父子分块
2. `query_by_parent_chunk()` - 查询指定父分块的子分块
3. `query_by_doc_id()` - 查询指定文档的所有分块
4. `delete_by_doc_id()` - 删除指定文档的所有分块

详见第2章的代码示例。

---

## 4. 数据迁移策略

### 4.1 迁移方案选择

推荐采用**双写 + 后台迁移**策略：

1. **阶段1**: 新代码同时写入新旧存储（保证旧数据兼容）
2. **阶段2**: 后台脚本将旧数据迁移到新存储
3. **阶段3**: 验证新存储数据完整性
4. **阶段4**: 切换为只写新存储，废弃旧存储

### 4.2 迁移脚本设计

```python
# scripts/migrate_knowledge_to_pgvector.py

async def migrate_document(doc_id: str, vector_service: VectorStoreService):
    """迁移单个文档"""
    # 1. 从旧表读取文档
    # 2. 从旧表读取所有 chunks
    # 3. 重组父子关系
    # 4. 写入 PGVector
    # 5. 记录迁移状态

async def main():
    # 批量处理所有文档
    # 支持断点续传
    # 记录迁移日志
```

### 4.3 回滚策略

1. 迁移前完整备份旧表
2. 迁移脚本支持 `--dry-run` 模式
3. 保留旧表写入能力，可随时回滚
4. 监控新存储性能和错误率

---

## 5. 测试计划

### 5.1 单元测试

- `test_vector_store_service.py`: 新增父子分块插入、查询测试
- `test_knowledge_import_consumer.py`: 更新为 mock VectorStoreService

### 5.2 集成测试

- 端到端导入流程测试
- 父子层级关系验证
- 元数据完整性检查

### 5.3 性能测试

- 大批量导入性能基准
- 查询响应时间对比

---

## 6. 风险评估与缓解

| 风险 | 影响 | 可能性 | 缓解措施 |
|------|------|--------|----------|
| 数据丢失 | 高 | 低 | 迁移前完整备份，双写阶段保持旧存储 |
| 查询性能下降 | 中 | 中 | 添加适当索引，性能测试验证 |
| 元数据不兼容 | 中 | 中 | 详细的元数据映射表，测试覆盖 |
| 父子关系丢失 | 高 | 低 | 严格的 chunk_id 生成规则，验证查询 |

---

## 7. 后续工作

1. **创建实施计划**: 基于本设计文档创建详细实施计划
2. **代码实现**: 按优先级修改文件
3. **数据迁移**: 执行迁移脚本
4. **验证上线**: 灰度发布，监控指标
5. **清理旧表**: 确认稳定后删除旧表

---

## 附录

### A. 元数据字段完整清单

| 字段名 | 类型 | 是否必需 | 说明 |
|--------|------|----------|------|
| doc_id | string | 是 | 原始文档ID |
| chunk_id | string | 是 | 分块唯一ID |
| parent_chunk_id | string | 否 | 父分块ID |
| chunk_type | string | 是 | "parent" \| "child" |
| chunk_level | int | 是 | 层级: 0=父, 1=子 |
| chunk_index | int | 是 | 同级别索引 |
| title | string | 否 | 文档标题 |
| category | string | 否 | 分类 |
| source | string | 否 | 来源 |
| import_time | string | 否 | ISO8601时间 |

### B. 参考文档

- [LangChain PGVector 文档](https://python.langchain.com/docs/integrations/vectorstores/pgvector)
- [项目 PGVector 设计文档](./2026-03-03-knowledge-rag-refactor-design.md)
