# tests/middleware/test_retrieval_middleware.py

import pytest
from unittest.mock import AsyncMock, MagicMock
from src.agents.diagnosis_middleware import RetrievalMiddleware


@pytest.mark.asyncio
async def test_middleware_injects_retrieval_context():
    """测试中间件正确注入检索上下文"""
    # Arrange
    mock_vector_store = AsyncMock()
    mock_docs = [MagicMock(
        page_content="密度偏高时，应加水稀释",
        metadata={"title": "密度处理指南"}
    )]
    mock_vector_store.similarity_search.return_value = mock_docs

    middleware = RetrievalMiddleware(mock_vector_store)
    state = {
        "messages": [MagicMock(content="密度偏高怎么办？")],
        "metadata": {"category": "density"}
    }
    mock_runtime = MagicMock()

    # Act
    result = await middleware.abefore_model(state, mock_runtime)

    # Assert
    assert result is not None
    assert "messages" in result
    assert len(result["messages"]) == 2
    assert result["messages"][0].content.startswith("你是一位钻井液性能诊断专家")
    assert "密度偏高时，应加水稀释" in result["messages"][0].content
