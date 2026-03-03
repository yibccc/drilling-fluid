# tests/agents/test_diagnosis_agent.py

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from src.agents.diagnosis_agent import DiagnosisAgent
from src.agents.diagnosis_middleware import RetrievalMiddleware


@pytest.mark.asyncio
async def test_agent_uses_middleware():
    """测试 Agent 正确使用中间件"""
    # Arrange
    mock_vector_store = AsyncMock()
    mock_vector_store.similarity_search = AsyncMock(return_value=[])

    agent = DiagnosisAgent(checkpointer=None, vector_store_service=mock_vector_store)

    # Act - 调用 _build_agent 来初始化中间件
    with patch('src.agents.diagnosis_agent.create_agent'):
        agent._build_agent()

    # Assert
    assert agent.retrieval_middleware is not None
    assert isinstance(agent.retrieval_middleware, RetrievalMiddleware)
