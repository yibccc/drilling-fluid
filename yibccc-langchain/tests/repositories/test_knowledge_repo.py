"""
知识库数据仓储测试

测试知识文档和向量分块的数据库操作
"""

import pytest
import asyncpg
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch
from typing import List

from src.repositories.knowledge_repo import KnowledgeRepository
from src.models.diagnosis_schemas import KnowledgeDocumentCreate
from src.models.exceptions import KnowledgeBaseError


@pytest.fixture
def mock_pool():
    """模拟数据库连接池"""
    pool = AsyncMock(spec=asyncpg.Pool)
    conn = AsyncMock(spec=asyncpg.Connection)

    # 模拟 acquire 返回连接
    pool.acquire.return_value.__aenter__.return_value = conn

    return pool, conn


@pytest.fixture
def mock_embedding_client():
    """模拟 Embedding 客户端"""
    client = AsyncMock()
    client.embed_query.return_value = [0.1] * 1024  # 通义千问 text-embedding-v3 维度
    return client


@pytest.fixture
def sample_document():
    """测试知识文档"""
    return KnowledgeDocumentCreate(
        doc_id="DOC-TEST-001",
        title="密度偏高处置措施",
        category="density",
        subcategory="high",
        content="# 密度偏高处置\n\n密度偏高时，应该采取以下措施：\n1. 加水稀释 5-10%\n2. 持续循环监测\n3. 调整钻井液配方",
        metadata={"author": "专家A", "version": "1.0"}
    )


class TestKnowledgeRepository:
    """KnowledgeRepository 测试"""

    def test_init(self, mock_pool):
        """测试初始化"""
        pool, _ = mock_pool
        repo = KnowledgeRepository(pool)
        assert repo.pool == pool
        assert repo.embedding_client is None

    def test_init_with_embedding(self, mock_pool, mock_embedding_client):
        """测试初始化（带 Embedding 客户端）"""
        pool, _ = mock_pool
        repo = KnowledgeRepository(pool, embedding_client=mock_embedding_client)
        assert repo.embedding_client == mock_embedding_client

    # ========== 知识文档操作测试 ==========

    @pytest.mark.asyncio
    async def test_create_document(self, mock_pool, sample_document):
        """测试创建知识文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        doc_id = await repo.create_document(sample_document)

        assert doc_id == "DOC-TEST-001"
        conn.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_document_duplicate(self, mock_pool, sample_document):
        """测试创建重复文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        # 模拟唯一性约束冲突
        conn.execute.side_effect = asyncpg.UniqueViolationError("Duplicate doc_id")

        with pytest.raises(KnowledgeBaseError, match="已存在"):
            await repo.create_document(sample_document)

    @pytest.mark.asyncio
    async def test_get_document(self, mock_pool):
        """测试获取知识文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        # 模拟查询结果
        mock_row = {
            "id": "uuid-123",
            "doc_id": "DOC-TEST-001",
            "title": "密度偏高处置措施",
            "category": "density",
            "subcategory": "high",
            "content": "测试内容",
            "metadata": {"author": "专家A"},
            "chunk_count": 5,
            "created_at": datetime.now()
        }
        conn.fetchrow.return_value = mock_row

        result = await repo.get_document("DOC-TEST-001")

        assert result is not None
        assert result["doc_id"] == "DOC-TEST-001"
        assert result["title"] == "密度偏高处置措施"

    @pytest.mark.asyncio
    async def test_get_document_not_found(self, mock_pool):
        """测试获取不存在的文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        conn.fetchrow.return_value = None

        result = await repo.get_document("NON-EXISTENT")

        assert result is None

    @pytest.mark.asyncio
    async def test_delete_document(self, mock_pool):
        """测试删除知识文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)
        conn.execute.return_value = "DELETE 1"

        result = await repo.delete_document("DOC-TEST-001")

        assert result is True
        # 验证先删除分块再删除文档
        assert conn.execute.call_count == 2

    @pytest.mark.asyncio
    async def test_delete_document_not_found(self, mock_pool):
        """测试删除不存在的文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)
        conn.execute.return_value = "DELETE 0"

        result = await repo.delete_document("NON-EXISTENT")

        assert result is False

    @pytest.mark.asyncio
    async def test_list_documents(self, mock_pool):
        """测试列出知识文档"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        mock_rows = [
            {"doc_id": "DOC-001", "title": "文档1", "category": "density"},
            {"doc_id": "DOC-002", "title": "文档2", "category": "density"},
        ]
        conn.fetch.return_value = mock_rows

        results = await repo.list_documents(category="density", limit=100)

        assert len(results) == 2
        conn.fetch.assert_called_once()

    @pytest.mark.asyncio
    async def test_list_documents_all(self, mock_pool):
        """测试列出所有文档（不指定分类）"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        mock_rows = [{"doc_id": "DOC-001"}]
        conn.fetch.return_value = mock_rows

        results = await repo.list_documents()

        assert len(results) == 1
        # 验证 SQL 不包含 WHERE category
        call_args = conn.fetch.call_args
        sql = call_args[0][0]
        assert "WHERE category" not in sql

    # ========== 向量分块操作测试 ==========

    @pytest.mark.asyncio
    async def test_create_chunks_no_embedding_client(self, mock_pool, sample_document):
        """测试创建分块（无 Embedding 客户端）"""
        pool, _ = mock_pool
        repo = KnowledgeRepository(pool)

        chunks = [{"content": "测试内容"}]

        with pytest.raises(KnowledgeBaseError, match="Embedding 客户端未配置"):
            await repo.create_chunks("DOC-TEST-001", chunks)

    @pytest.mark.asyncio
    async def test_create_chunks_success(self, mock_pool, sample_document, mock_embedding_client):
        """测试成功创建分块"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool, embedding_client=mock_embedding_client)

        chunks = [
            {"content": "第一块内容"},
            {"content": "第二块内容"},
        ]

        count = await repo.create_chunks("DOC-TEST-001", chunks)

        assert count == 2
        # 验证删除旧分块、插入新分块、更新计数
        assert conn.execute.call_count == 3

    @pytest.mark.asyncio
    async def test_vector_search_no_embedding_client(self, mock_pool):
        """测试向量搜索（无 Embedding 客户端）"""
        pool, _ = mock_pool
        repo = KnowledgeRepository(pool)

        with pytest.raises(KnowledgeBaseError, match="Embedding 客户端未配置"):
            await repo.vector_search("测试查询")

    @pytest.mark.asyncio
    async def test_vector_search_with_category(self, mock_pool, mock_embedding_client):
        """测试向量搜索（带分类过滤）"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool, embedding_client=mock_embedding_client)

        mock_rows = [
            {
                "doc_id": "DOC-001",
                "title": "密度偏高处置",
                "category": "density",
                "content": "测试内容",
                "distance": 0.123
            }
        ]
        conn.fetch.return_value = mock_rows

        results = await repo.vector_search(
            query="密度偏高",
            top_k=5,
            category="density"
        )

        assert len(results) == 1
        assert results[0]["doc_id"] == "DOC-001"

        # 验证 SQL 包含分类过滤
        call_args = conn.fetch.call_args
        sql = call_args[0][0]
        assert "WHERE kd.category = $" in sql

    @pytest.mark.asyncio
    async def test_vector_search_no_category(self, mock_pool, mock_embedding_client):
        """测试向量搜索（不带分类）"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool, embedding_client=mock_embedding_client)

        mock_rows = []
        conn.fetch.return_value = mock_rows

        results = await repo.vector_search(query="测试查询", top_k=3)

        assert len(results) == 0

        # 验证 SQL 不包含分类过滤
        call_args = conn.fetch.call_args
        sql = call_args[0][0]
        assert "GROUP BY kd.doc_id" in sql

    @pytest.mark.asyncio
    async def test_embed_text(self, mock_pool, mock_embedding_client):
        """测试文本 embedding 生成"""
        pool, _ = mock_pool
        repo = KnowledgeRepository(pool, embedding_client=mock_embedding_client)

        embedding = await repo._embed_text("测试文本")

        assert isinstance(embedding, list)
        assert len(embedding) == 1024
        mock_embedding_client.embed_query.assert_called_once_with("测试文本")

    @pytest.mark.asyncio
    async def test_get_chunks_by_doc(self, mock_pool):
        """测试获取文档的所有分块"""
        pool, conn = mock_pool
        repo = KnowledgeRepository(pool)

        mock_rows = [
            {"chunk_index": 0, "content": "第一块"},
            {"chunk_index": 1, "content": "第二块"},
        ]
        conn.fetch.return_value = mock_rows

        chunks = await repo.get_chunks_by_doc("DOC-TEST-001")

        assert len(chunks) == 2
        # 验证按索引排序
        call_args = conn.fetch.call_args
        sql = call_args[0][0]
        assert "ORDER BY chunk_index" in sql


@pytest.mark.integration
class TestKnowledgeRepositoryIntegration:
    """集成测试（需要真实数据库和 pgvector 扩展）"""

    @pytest.fixture
    async def real_repo(self, pg_pool):
        """使用真实数据库连接的仓储"""
        from src.config import settings
        import asyncpg

        pool = await asyncpg.create_pool(
            host=settings.pg_host,
            port=settings.pg_port,
            user=settings.pg_user,
            password=settings.pg_password,
            database=settings.pg_database
        )

        # 确保表和扩展存在
        async with pool.acquire() as conn:
            await conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
            await conn.execute("""
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

                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    parent_doc_id VARCHAR(100) NOT NULL,
                    chunk_index INT NOT NULL,
                    content TEXT NOT NULL,
                    embedding vector(1024),
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );
            """)

        # 使用 mock embedding 客户端
        mock_client = AsyncMock()
        mock_client.embed_query.return_value = [0.1] * 1024

        repo = KnowledgeRepository(pool, embedding_client=mock_client)
        yield repo
        await pool.close()

    @pytest.mark.asyncio
    async def test_create_and_get_document(self, real_repo, sample_document):
        """测试创建并获取文档"""
        doc_id = await real_repo.create_document(sample_document)

        doc = await real_repo.get_document(doc_id)
        assert doc is not None
        assert doc["doc_id"] == doc_id
        assert doc["title"] == "密度偏高处置措施"

    @pytest.mark.asyncio
    async def test_create_chunks_and_search(self, real_repo, sample_document):
        """测试创建分块并搜索"""
        # 先创建文档
        await real_repo.create_document(sample_document)

        # 创建分块
        chunks = [{"content": "测试分块1"}, {"content": "测试分块2"}]
        count = await real_repo.create_chunks("DOC-TEST-001", chunks)

        assert count == 2

        # 验证可以获取分块
        retrieved_chunks = await real_repo.get_chunks_by_doc("DOC-TEST-001")
        assert len(retrieved_chunks) == 2
