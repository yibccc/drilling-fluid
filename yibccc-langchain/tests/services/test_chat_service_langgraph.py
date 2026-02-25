"""
ChatService LangGraph 集成测试
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from langgraph.checkpoint.memory import MemorySaver

from src.services.chat_service import ChatService
from src.models.schemas import ChatRequest


@pytest.fixture
async def mock_checkpointer():
    """Mock Checkpointer"""
    # 创建一个简单的 mock checkpointer
    from langgraph.checkpoint.memory import MemorySaver
    saver = MemorySaver()
    yield saver


@pytest.fixture
def chat_service(mock_checkpointer):
    """创建 ChatService 实例"""
    service = ChatService(checkpointer=mock_checkpointer)
    return service


@pytest.mark.asyncio
async def test_chat_service_has_checkpointer(chat_service, mock_checkpointer):
    """测试 ChatService 有 checkpointer"""
    assert chat_service.checkpointer is not None


@pytest.mark.asyncio
async def test_chat_service_initialize_builds_graph(chat_service, mock_checkpointer):
    """测试 initialize 方法构建 graph"""
    # 当已经有 checkpointer 时，initialize 不应该创建新的
    await chat_service.initialize()
    assert chat_service.graph is not None
    assert hasattr(chat_service.graph, 'checkpointer')
