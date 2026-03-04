# 知识导入 PGVector 迁移实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将知识导入消费者从旧的 `KnowledgeRepository` 迁移到新的 `VectorStoreService`，实现完全基于 PGVector 的知识存储。

**Architecture:** 修改 `VectorStoreService` 添加父子分块支持，重写 `KnowledgeImportConsumer._embed_and_store_chunks` 方法使用 PGVector 存储，通过 metadata 维护层级关系。

**Tech Stack:** Python 3.11, FastAPI, LangChain 1.0.0, PGVector, PostgreSQL + pgvector, DashScope Embeddings

---

## Prerequisites

在开始实施前，请确保：

1. **数据库已就绪**: PostgreSQL 已安装 pgvector 扩展
2. **依赖已安装**: `uv sync` 已完成
3. **环境变量配置正确**: `.env` 中包含数据库连接信息和 DashScope API Key
4. **现有测试通过**: 运行 `pytest tests/ -v` 确保基准测试通过

---

## Task 1: 扩展 VectorStoreService 添加父子分块支持

**Files:**
- Modify: `src/services/vector_store_service.py`
- Test: `tests/services/test_vector_store_service.py`

### Step 1: 编写新增方法的测试

在 `tests/services/test_vector_store_service.py` 中添加：

```python
import pytest
from datetime import datetime
from unittest.mock import AsyncMock, patch
from langchain_core.documents import Document


class TestVectorStoreServiceParentChild:
    """父子分块功能测试"""

    @pytest.fixture
    def sample_parent_chunks(self):
        """示例父分块"""
        return [
            {"content": "第一章 概述\n\n钻井液是钻井工程中的关键..."},
            {"content": "第二章 配方设计\n\n钻井液配方设计需要考虑..."}
        ]

    @pytest.fixture
    def sample_child_chunks_map(self):
        """示例子分块映射"""
        return {
            0: [
                {"content": "1.1 钻井液定义"},
                {"content": "1.2 钻井液功能"}
            ],
            1: [
                {"content": "2.1 基础配方"},
                {"content": "2.2 添加剂选择"}
            ]
        }

    @pytest.mark.asyncio
    async def test_add_parent_child_documents_success(
        self, vector_store_service, sample_parent_chunks, sample_child_chunks_map
    ):
        """测试成功添加父子分块文档"""
        # Mock vector_store.aadd_documents
        mock_ids = ["id_1", "id_2", "id_3", "id_4", "id_5", "id_6"]
        vector_store_service.vector_store.aadd_documents = AsyncMock(return_value=mock_ids)

        result = await vector_store_service.add_parent_child_documents(
            doc_id="doc_test_001",
            title="钻井液技术指南",
            category="drilling_fluid",
            parent_chunks=sample_parent_chunks,
            child_chunks_map=sample_child_chunks_map,
            base_metadata={"source": "test"}
        )

        # 验证返回的 IDs
        assert len(result) == 6  # 2 parents + 4 children
        assert result == mock_ids

        # 验证 aadd_documents 被调用
        vector_store_service.vector_store.aadd_documents.assert_called_once()
        call_args = vector_store_service.vector_store.aadd_documents.call_args
        documents = call_args[0][0]

        # 验证文档数量
        assert len(documents) == 6

        # 验证父文档结构
        parent_docs = [d for d in documents if d.metadata.get("chunk_type") == "parent"]
        assert len(parent_docs) == 2
        assert parent_docs[0].metadata["chunk_level"] == 0
        assert parent_docs[0].metadata["chunk_index"] == 0
        assert parent_docs[0].metadata["parent_chunk_id"] is None

        # 验证子文档结构
        child_docs = [d for d in documents if d.metadata.get("chunk_type") == "child"]
        assert len(child_docs) == 4
        assert child_docs[0].metadata["chunk_level"] == 1
        assert child_docs[0].metadata["parent_chunk_id"] is not None

    @pytest.mark.asyncio
    async def test_add_parent_child_documents_empty(self, vector_store_service):
        """测试添加空文档列表"""
        vector_store_service.vector_store.aadd_documents = AsyncMock(return_value=[])

        result = await vector_store_service.add_parent_child_documents(
            doc_id="doc_empty",
            title="空文档",
            category="test",
            parent_chunks=[],
            child_chunks_map={}
        )

        assert result == []
        vector_store_service.vector_store.aadd_documents.assert_not_called()
```

### Step 2: 运行测试确认失败

```bash
pytest tests/services/test_vector_store_service.py::TestVectorStoreServiceParentChild -v
```

**Expected:** FAIL with "AttributeError: 'VectorStoreService' object has no attribute 'add_parent_child_documents'"

### Step 3: 实现 `add_parent_child_documents` 方法

在 `src/services/vector_store_service.py` 中添加：

```python
async def add_parent_child_documents(
    self,
    doc_id: str,
    title: str,
    category: str,
    parent_chunks: List[Dict],
    child_chunks_map: Dict[int, List[Dict]],
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
```

### Step 4: 运行测试确认通过

```bash
pytest tests/services/test_vector_store_service.py::TestVectorStoreServiceParentChild -v
```

**Expected:** PASS - 所有测试通过

### Step 5: 提交更改

```bash
git add src/services/vector_store_service.py tests/services/test_vector_store_service.py
git commit -m "feat(vector_store): add parent-child document chunking support

- Add add_parent_child_documents() method for hierarchical chunking
- Support parent and child chunk metadata with level, index, parent_id
- Add comprehensive tests for parent-child chunking

Enables migration from old knowledge_repo to PGVector-based storage"
```

---

## Task 2: 修改 KnowledgeImportConsumer 使用 PGVector

**Files:**
- Modify: `src/services/knowledge_import_consumer.py`
- Test: `tests/services/test_knowledge_import_consumer.py`

### Step 1: 编写修改后的测试

在 `tests/services/test_knowledge_import_consumer.py` 中更新：

```python
import pytest
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch
import redis.asyncio as aioredis


class TestKnowledgeImportConsumerPGVector:
    """测试 KnowledgeImportConsumer 使用 PGVector"""

    @pytest.fixture
    def mock_redis(self):
        """Mock Redis 客户端"""
        redis_mock = AsyncMock(spec=aioredis.Redis)
        redis_mock.xreadgroup = AsyncMock(return_value=[])
        redis_mock.xack = AsyncMock(return_value=True)
        redis_mock.set = AsyncMock(return_value=True)
        return redis_mock

    @pytest.fixture
    def mock_vector_store_service(self):
        """Mock VectorStoreService"""
        with patch('src.services.knowledge_import_consumer.VectorStoreService') as mock:
            service_instance = AsyncMock()
            service_instance.add_parent_child_documents = AsyncMock(return_value=["id_1", "id_2"])
            mock.return_value = service_instance
            yield mock, service_instance

    @pytest.mark.asyncio
    async def test_embed_and_store_chunks_calls_pgvector(
        self, mock_redis, mock_vector_store_service
    ):
        """测试 _embed_and_store_chunks 调用 PGVector 而非 knowledge_repo"""
        from src.services.knowledge_import_consumer import KnowledgeImportConsumer

        # Mock 连接池
        mock_pool = AsyncMock()

        # 创建消费者
        consumer = KnowledgeImportConsumer(mock_pool, mock_redis)

        # 准备测试数据
        chunks = [
            {"content": "第一章 概述...", "parent_index": None},
            {"content": "1.1 钻井液定义", "parent_index": 0},
            {"content": "1.2 钻井液功能", "parent_index": 0}
        ]

        base_metadata = {
            "oss_path": "oss://test/path",
            "file_record_id": "file_123"
        }

        # 调用方法
        await consumer._embed_and_store_chunks(
            doc_id="doc_test_001",
            title="钻井液技术指南",
            category="drilling_fluid",
            chunks=chunks,
            base_metadata=base_metadata
        )

        # 验证 VectorStoreService 被实例化
        mock_vector_store_service[0].assert_called_once()

        # 验证 add_parent_child_documents 被调用
        mock_vector_store_service[1].add_parent_child_documents.assert_called_once()

        # 验证调用参数
        call_args = mock_vector_store_service[1].add_parent_child_documents.call_args
        assert call_args[1]["doc_id"] == "doc_test_001"
        assert call_args[1]["title"] == "钻井液技术指南"
        assert call_args[1]["category"] == "drilling_fluid"
        assert call_args[1]["base_metadata"]["oss_path"] == "oss://test/path"

    @pytest.mark.asyncio
    async def test_embed_and_store_chunks_organizes_parent_child(
        self, mock_redis, mock_vector_store_service
    ):
        """测试 _embed_and_store_chunks 正确组织父子分块"""
        from src.services.knowledge_import_consumer import KnowledgeImportConsumer

        mock_pool = AsyncMock()
        consumer = KnowledgeImportConsumer(mock_pool, mock_redis)

        # 混合父子分块（按 _create_chunks 的输出格式）
        chunks = [
            {"content": "父块1内容", "parent_index": 0, "chunk_index": 0},  # 实际上这个是父块
            {"content": "子块1.1", "parent_index": 0, "chunk_index": 1},
            {"content": "子块1.2", "parent_index": 0, "chunk_index": 2},
            {"content": "父块2内容", "parent_index": 1, "chunk_index": 3},  # 第二个父块
            {"content": "子块2.1", "parent_index": 1, "chunk_index": 4},
        ]

        await consumer._embed_and_store_chunks(
            doc_id="doc_test",
            title="测试文档",
            category="test",
            chunks=chunks,
            base_metadata={}
        )

        # 验证调用参数中的 parent_chunks 和 child_chunks_map
        call_args = mock_vector_store_service[1].add_parent_child_documents.call_args[1]

        # 应该有 2 个父块
        assert len(call_args["parent_chunks"]) == 2

        # 第一个父块应该有 2 个子块
        assert len(call_args["child_chunks_map"][0]) == 2

        # 第二个父块应该有 1 个子块
        assert len(call_args["child_chunks_map"][1]) == 1
```

### Step 2: 运行测试确认失败

```bash
pytest tests/services/test_knowledge_import_consumer.py::TestKnowledgeImportConsumerPGVector -v
```

**Expected:** FAIL - 因为 `_embed_and_store_chunks` 还没有修改

### Step 3: 修改 `KnowledgeImportConsumer`

在 `src/services/knowledge_import_consumer.py` 中进行以下修改：

**3.1 添加新导入（文件顶部）**

```python
# 在现有导入后添加
from src.services.vector_store_service import VectorStoreService
from langchain_core.documents import Document
from datetime import datetime
```

**3.2 重写 `_embed_and_store_chunks` 方法**

将第 240-246 行的方法替换为：

```python
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
        # chunks 格式: [{"content": "...", "parent_index": 0, "chunk_index": 0}, ...]
        # parent_index 表示该 chunk 属于哪个父块，None 表示父块本身

        parent_chunks = []
        child_chunks_map = {}  # parent_index -> child_chunks

        # 先识别所有父块（parent_index 为 None 或与 chunk_index 相同）
        parent_indices = set()
        for chunk in chunks:
            parent_idx = chunk.get('parent_index')
            if parent_idx is None:
                # 这是父块，其索引就是 chunk_index
                parent_indices.add(chunk.get('chunk_index', 0))
            else:
                parent_indices.add(parent_idx)

        # 按 parent_index 组织 chunks
        for chunk in chunks:
            parent_idx = chunk.get('parent_index')
            chunk_idx = chunk.get('chunk_index', 0)

            if parent_idx is None or chunk_idx not in [c.get('chunk_index') for c in chunks if c.get('parent_index') == parent_idx]:
                # 这是父块
                if chunk not in parent_chunks:
                    parent_chunks.append(chunk)
            else:
                # 这是子块
                if parent_idx not in child_chunks_map:
                    child_chunks_map[parent_idx] = []
                if chunk not in child_chunks_map[parent_idx]:
                    child_chunks_map[parent_idx].append(chunk)

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

**3.3 修改 `_process_import` 中的调用（约第 145 行）**

找到：
```python
await self._embed_and_store_chunks(doc_id, all_chunks)
```

替换为：
```python
await self._embed_and_store_chunks(
    doc_id=doc_id,
    title=title,
    category=category,
    chunks=all_chunks,
    base_metadata={
        "oss_path": oss_path,
        "file_record_id": file_record_id,
        **metadata
    }
)
```

### Step 4: 运行测试确认通过

```bash
pytest tests/services/test_knowledge_import_consumer.py::TestKnowledgeImportConsumerPGVector -v
```

**Expected:** PASS - 所有测试通过

### Step 5: 提交更改

```bash
git add src/services/knowledge_import_consumer.py tests/services/test_knowledge_import_consumer.py
git commit -m "refactor(knowledge_import): migrate to PGVector storage

- Replace KnowledgeRepository with VectorStoreService
- Add support for hierarchical parent-child chunking
- Store all metadata in PGVector cmetadata field
- Update _embed_and_store_chunks to use new storage layer

BREAKING CHANGE: Documents now stored in langchain_pg_embedding table
instead of knowledge_chunks table"
```

---

## Task 3: 清理旧代码引用

**Files:**
- Modify: `src/services/knowledge_import_consumer.py` (移除旧导入)
- Delete: `src/repositories/knowledge_repo.py` (如果存在且不再需要)

### Step 1: 验证 KnowledgeRepository 导入已移除

确认 `src/services/knowledge_import_consumer.py` 中：
- ✅ 没有 `from src.repositories.knowledge_repo import KnowledgeRepository`
- ✅ 使用 `from src.services.vector_store_service import VectorStoreService`

### Step 2: 删除旧文件（可选）

如果 `src/repositories/knowledge_repo.py` 不再被任何地方使用：

```bash
# 检查是否还有其他地方使用
grep -r "KnowledgeRepository" src/ --include="*.py" | grep -v "__pycache__"

# 如果没有其他引用，可以删除
rm src/repositories/knowledge_repo.py
```

### Step 3: 提交更改

```bash
git add -A
git commit -m "cleanup: remove KnowledgeRepository references

- Remove old KnowledgeRepository import from knowledge_import_consumer
- Use VectorStoreService exclusively for document storage
- Remove deprecated knowledge_repo.py (no longer needed)"
```

---

## Task 4: 运行完整测试套件

### Step 1: 运行所有相关测试

```bash
# VectorStoreService 测试
pytest tests/services/test_vector_store_service.py -v

# KnowledgeImportConsumer 测试
pytest tests/services/test_knowledge_import_consumer.py -v

# 集成测试
pytest tests/integration/test_upload_knowledge.py -v
```

### Step 2: 运行类型检查

```bash
mypy src/services/vector_store_service.py src/services/knowledge_import_consumer.py
```

### Step 3: 提交最终更改

```bash
git add -A
git commit -m "test: update test suite for PGVector migration

- Add comprehensive tests for parent-child chunking
- Update KnowledgeImportConsumer tests for PGVector
- All tests passing, type checks passing"
```

---

## Verification Checklist

实施完成后，验证以下内容：

- [ ] `VectorStoreService.add_parent_child_documents()` 方法实现并测试通过
- [ ] `KnowledgeImportConsumer._embed_and_store_chunks()` 使用新的 PGVector 存储
- [ ] 父子分块层级关系正确存储在 metadata 中
- [ ] 所有单元测试通过
- [ ] 类型检查通过
- [ ] 集成测试通过（如果有）

---

## Rollback Instructions

如果出现问题，按以下步骤回滚：

1. **停止服务**:
   ```bash
   systemctl stop knowledge-consumer  # 或相应的停止命令
   ```

2. **回滚代码**:
   ```bash
   git log --oneline -5  # 查看最近的提交
   git revert <commit-hash-of-migration>
   ```

3. **恢复旧存储**:
   - 如果新存储已经写入了数据，需要导出并重新导入旧表
   - 或使用备份恢复

4. **重启服务**:
   ```bash
   systemctl start knowledge-consumer
   ```
