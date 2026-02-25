"""端到端集成测试"""
import pytest
from httpx import AsyncClient, ASGITransport
from src.api.main import app


@pytest.mark.asyncio
async def test_full_conversation_flow():
    """测试完整对话流程"""
    # Mock LLM response - 适配新的 LangGraph 架构
    from src.services.chat_service import chat_service
    from unittest.mock import AsyncMock, MagicMock, patch

    # 创建 mock 的 graph.astream
    async def mock_astream(state, config, stream_mode="values"):
        """Mock LangGraph astream"""
        from langchain_core.messages import AIMessage
        mock_chunk = {"messages": [AIMessage(content="你好！我是 AI 助手。")]}
        yield mock_chunk

    # Mock graph 对象
    mock_graph = MagicMock()
    mock_graph.astream = mock_astream
    mock_graph.aget_state = AsyncMock(return_value=MagicMock(
        values={"messages": [
            MagicMock(type="user", content="你好，我是小明"),
            MagicMock(type="assistant", content="你好！我是 AI 助手。")
        ]}
    ))

    # 设置 chat_service 的 graph
    object.__setattr__(chat_service, 'graph', mock_graph)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. 开始对话
        response = await client.post(
            "/api/v1/chat/stream",
            json={"message": "你好，我是小明"},
            headers={"X-API-Key": "test-key"}
        )

        assert response.status_code == 200
        assert "text/event-stream" in response.headers.get("content-type", "")

        # 2. 解析 SSE 事件
        events = []
        async for line in response.aiter_lines():
            if line.startswith("data: "):
                import json
                data = json.loads(line[6:])
                events.append(data)
                if data.get("type") == "end":
                    break

        # 3. 验证事件序列
        assert events[0]["type"] == "start"
        assert events[0]["session_id"] is not None

        session_id = events[0]["session_id"]

        # 4. 获取历史
        history_response = await client.get(
            f"/api/v1/chat/history/{session_id}",
            headers={"X-API-Key": "test-key"}
        )
        assert history_response.status_code == 200
        history = history_response.json()
        assert len(history["messages"]) >= 2  # user + assistant
