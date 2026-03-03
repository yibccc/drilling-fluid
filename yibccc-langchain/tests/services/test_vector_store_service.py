# tests/services/test_vector_store_service.py

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


@pytest.mark.asyncio
async def test_vector_store_service_initialization():
    """测试 VectorStoreService 初始化"""
    # Arrange
    mock_embeddings = AsyncMock()
    mock_vector_store = MagicMock()
    mock_vector_store.aadd_documents = AsyncMock(return_value=["doc1", "doc2"])
    mock_vector_store.asimilarity_search = AsyncMock(return_value=[])
    mock_vector_store.adelete = AsyncMock(return_value=True)

    # Act
    with patch('src.services.vector_store_service.DashScopeEmbeddings', return_value=mock_embeddings):
        with patch('src.services.vector_store_service.PGVector', return_value=mock_vector_store):
            from src.services.vector_store_service import VectorStoreService
            service = VectorStoreService("postgresql+psycopg://test")

            # Assert
            assert service.embeddings is not None
            assert service.vector_store is not None
