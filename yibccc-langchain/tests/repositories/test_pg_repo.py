"""测试 PostgreSQL 仓储"""
import pytest
from datetime import datetime
from src.repositories.pg_repo import PostgreSQLRepository
from src.models.schemas import ChatMessage


@pytest.mark.asyncio
async def test_save_session(pg_repo: PostgreSQLRepository):
    """测试保存会话"""
    session_id = "00000000-0000-0000-0000-000000000001"
    await pg_repo.save_session(
        session_id=session_id,
        user_id="user_123",
        message_count=0
    )

    session = await pg_repo.get_session(session_id)
    assert session is not None
    assert session["user_id"] == "user_123"


@pytest.mark.asyncio
async def test_save_message(pg_repo: PostgreSQLRepository):
    """测试保存消息"""
    session_id = "00000000-0000-0000-0000-000000000002"
    await pg_repo.save_session(
        session_id=session_id,
        user_id="user_123",
        message_count=0
    )

    message = ChatMessage(
        role="user",
        content="测试消息",
        timestamp=datetime.now()
    )
    await pg_repo.save_message(session_id, message)

    messages = await pg_repo.get_messages(session_id)
    assert len(messages) == 1
    assert messages[0]["content"] == "测试消息"


@pytest.mark.asyncio
async def test_get_user_sessions(pg_repo: PostgreSQLRepository):
    """测试获取用户会话"""
    await pg_repo.save_session("00000000-0000-0000-0000-000000000003", "user_123", 0)
    await pg_repo.save_session("00000000-0000-0000-0000-000000000004", "user_123", 0)
    await pg_repo.save_session("00000000-0000-0000-0000-000000000005", "user_456", 0)

    sessions = await pg_repo.get_user_sessions("user_123")
    assert len(sessions) == 2
