# tests/integration/test_vector_store_service.py

"""
向量存储服务集成测试

注意：这些测试需要真实的数据库连接和 pgvector 扩展。
运行这些测试前需要：
1. 安装 pgvector 扩展: CREATE EXTENSION IF NOT EXISTS vector;
2. 设置测试数据库连接

运行方式: uv run pytest tests/integration/ -v -m integration
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from langchain_core.documents import Document


@pytest.mark.integration
@pytest.mark.asyncio
async def test_vector_store_initializes():
    """测试向量存储服务初始化"""
    # 使用 mock 验证初始化逻辑
    with patch('src.services.vector_store_service.DashScopeEmbeddings'):
        with patch('src.services.vector_store_service.PGVector') as mock_pgvector:
            mock_store = MagicMock()
            mock_store.aadd_documents = AsyncMock(return_value=["doc1"])
            mock_store.asimilarity_search = AsyncMock(return_value=[])
            mock_store.adelete = AsyncMock(return_value=True)
            mock_pgvector.return_value = mock_store

            from src.services.vector_store_service import VectorStoreService

            service = VectorStoreService("postgresql+psycopg://test")

            # 验证初始化
            assert service.embeddings is not None
            assert service.vector_store is not None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_vector_store_add_and_search_mock():
    """测试添加和检索流程（使用 mock）"""
    with patch('src.services.vector_store_service.DashScopeEmbeddings'):
        with patch('src.services.vector_store_service.PGVector') as mock_pgvector:
            # Mock PGVector 行为
            mock_store = MagicMock()

            test_doc = Document(
                page_content="密度偏高时，应加水稀释",
                metadata={"doc_id": "test_001", "category": "density", "title": "密度处理指南"}
            )

            mock_store.aadd_documents = AsyncMock(return_value=["test_001"])
            mock_store.asimilarity_search = AsyncMock(return_value=[test_doc])
            mock_store.adelete = AsyncMock(return_value=True)
            mock_pgvector.return_value = mock_store

            from src.services.vector_store_service import VectorStoreService

            service = VectorStoreService("postgresql+psycopg://test")

            # 测试添加文档
            docs = [
                Document(
                    page_content="密度偏高时，应加水稀释",
                    metadata={"doc_id": "test_001", "category": "density", "title": "密度处理指南"}
                )
            ]

            doc_ids = await service.add_documents(docs)
            assert len(doc_ids) == 1
            assert doc_ids[0] == "test_001"

            # 测试检索
            results = await service.similarity_search("密度过高", k=5, filter={"category": "density"})
            assert len(results) == 1
            assert "密度偏高" in results[0].page_content
