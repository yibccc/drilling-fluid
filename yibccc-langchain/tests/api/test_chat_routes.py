"""测试聊天路由"""
import pytest
from httpx import AsyncClient, ASGITransport
from unittest.mock import AsyncMock, MagicMock
from src.api.main import app


@pytest.mark.asyncio
async def test_health_check():
    """测试健康检查"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_chat_stream_with_valid_api_key():
    """测试流式对话 - 有效 API Key"""
    # Mock LangGraph response
    from src.services.chat_service import chat_service

    async def mock_astream(state, config, stream_mode="values"):
        """Mock LangGraph astream"""
        from langchain_core.messages import AIMessage
        mock_chunk = {"messages": [AIMessage(content="你好！")]}
        yield mock_chunk

    # Mock graph 对象
    mock_graph = MagicMock()
    mock_graph.astream = mock_astream

    # 设置 chat_service 的 graph
    object.__setattr__(chat_service, 'graph', mock_graph)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"},
            headers={"X-API-Key": "test-key"}
        )
        assert response.status_code == 200
        assert "text/event-stream" in response.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_chat_stream_without_api_key():
    """测试流式对话 - 无 API Key"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"}
        )
        assert response.status_code == 422  # FastAPI returns 422 for missing required header


@pytest.mark.asyncio
async def test_chat_stream_invalid_api_key():
    """测试流式对话 - 无效 API Key"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好"},
            headers={"X-API-Key": "invalid-key"}
        )
        assert response.status_code == 401
