"""
SyncService 测试
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import redis.asyncio as aioredis

from src.services.sync_service import SyncService


@pytest.fixture
def mock_pg_repo():
    """Mock PostgreSQL Repository"""
    repo = MagicMock()
    repo.save_session = AsyncMock()
    repo.save_message = AsyncMock()
    return repo


@pytest.fixture
def mock_graph():
    """Mock CompiledGraph"""
    graph = MagicMock()
    # Mock aget_state to return a state snapshot with messages
    mock_state = MagicMock()
    mock_state.values = {
        "messages": [
            MagicMock(type="user", content="Hello"),
            MagicMock(type="assistant", content="Hi there!")
        ]
    }
    graph.aget_state = AsyncMock(return_value=mock_state)
    return graph


@pytest.fixture
def sync_service(mock_pg_repo, mock_graph):
    """创建 SyncService 实例"""
    service = SyncService(pg_repo=mock_pg_repo, graph=mock_graph)
    return service


@pytest.mark.asyncio
async def test_ensure_consumer_group(sync_service):
    """测试启动时创建 Consumer Group"""
    with patch("redis.asyncio.from_url") as mock_redis:
        mock_client = MagicMock()
        mock_redis.return_value = mock_client
        mock_client.xgroup_create = AsyncMock()

        await sync_service._ensure_consumer_group(mock_client)

        mock_client.xgroup_create.assert_called_once()


@pytest.mark.asyncio
async def test_decode_message_data(sync_service):
    """测试消息数据解码"""
    data = {
        b"thread_id": b"test-thread",
        b"user_id": b"user-1"
    }

    decoded = await sync_service._decode_data(data)

    assert decoded["thread_id"] == "test-thread"
    assert decoded["user_id"] == "user-1"


@pytest.mark.asyncio
async def test_process_sync_message(sync_service, mock_pg_repo, mock_graph):
    """测试处理同步消息"""
    data = {
        b"thread_id": b"test-thread-123",
        b"session_id": b"test-session-456",
        b"user_id": b"user-1",
        b"message_count": b"2"
    }

    await sync_service._process_sync_message(data)

    # 验证调用了 save_session
    mock_pg_repo.save_session.assert_called_once_with(
        session_id="test-session-456",
        user_id="user-1",
        message_count=2
    )

    # 验证调用了 save_message 两次（两条消息）
    assert mock_pg_repo.save_message.call_count == 2


@pytest.mark.asyncio
async def test_role_mapping():
    """测试 LangGraph 角色到 ChatMessage 角色的映射"""
    from src.services.sync_service import SyncService

    # 测试各种角色映射
    assert SyncService._map_role("human") == "user"
    assert SyncService._map_role("ai") == "assistant"
    assert SyncService._map_role("system") == "system"
    assert SyncService._map_role("tool") == "tool"
    assert SyncService._map_role("user") == "user"
    assert SyncService._map_role("assistant") == "assistant"
    # 未知角色默认为 user
    assert SyncService._map_role("unknown") == "user"
