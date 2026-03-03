# tests/scripts/test_migrate.py

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


@pytest.mark.asyncio
async def test_migrate_converts_data_format():
    """测试数据格式转换"""
    from scripts.migrate_to_pgvector import migrate_existing_data

    # Arrange
    mock_conn = AsyncMock()
    mock_old_docs = [
        {
            "doc_id": "test_001",
            "title": "测试文档",
            "content": "测试内容",
            "category": "density",
            "created_at": "2025-01-01T00:00:00Z"
        }
    ]

    # Mock 数据库查询
    async def mock_fetch(*args, **kwargs):
        return mock_old_docs

    mock_conn.fetch = mock_fetch

    # Act
    with patch('asyncpg.connect', return_value=mock_conn):
        documents = await migrate_existing_data()

    # Assert
    assert len(documents) == 1
    assert documents[0].page_content == "测试内容"
    assert documents[0].metadata["doc_id"] == "test_001"
    assert documents[0].metadata["category"] == "density"
