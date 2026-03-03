# tests/services/test_vector_store_service.py

import pytest
from unittest.mock import AsyncMock, patch, MagicMock
from src.services.vector_store_service import VectorStoreService

@pytest.mark.asyncio
async def test_vector_store_service_initialization():
    """测试 VectorStoreService 初始化"""
    # Arrange
    mock_embeddings = MagicMock()

    # Act
    with patch('src.services.vector_store_service.DashScopeEmbeddings', return_value=mock_embeddings):
        with patch('src.services.vector_store_service.PGVector') as mock_pgvector:
            service = VectorStoreService("postgresql+psycopg://test")

            # Assert
            assert service.embeddings is not None
            mock_pgvector.assert_called_once()

@pytest.mark.asyncio
async def test_similarity_search():
    """测试语义检索"""
    # Arrange
    with patch('src.services.vector_store_service.DashScopeEmbeddings') as mock_embeddings:
        mock_store = AsyncMock()
        mock_store.asimilarity_search.return_value = [
            MagicMock(page_content="测试内容", metadata={"title": "测试"})
        ]

        with patch('src.services.vector_store_service.PGVector', return_value=mock_store):
            service = VectorStoreService("postgresql+psycopg://test")
            service.vector_store = mock_store

            # Act
            results = await service.similarity_search("测试查询", k=5)

            # Assert
            assert len(results) == 1
            mock_store.asimilarity_search.assert_called_once_with("测试查询", k=5, filter=None)
