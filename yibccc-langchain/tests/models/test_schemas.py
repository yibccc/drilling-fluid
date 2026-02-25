"""测试 Pydantic 模型"""
import pytest
from datetime import datetime
from src.models.schemas import ToolCall, ToolCallData, ChatMessage, ChatRequest, ChatResponse


# ===== ToolCallData 模型测试 =====

def test_tool_call_data_creation():
    """测试工具调用数据模型创建"""
    data = ToolCallData(
        call_id="test-call-123",
        name="get_current_time",
        arguments={"timezone": "Asia/Shanghai"},
        status="calling"
    )
    assert data.call_id == "test-call-123"
    assert data.name == "get_current_time"
    assert data.arguments == {"timezone": "Asia/Shanghai"}
    assert data.status == "calling"
    assert data.result is None
    assert data.duration_ms is None


def test_tool_call_data_result_status():
    """测试工具调用结果状态"""
    data = ToolCallData(
        call_id="test-call-456",
        name="search",
        status="result",
        result="找到结果",
        duration_ms=125
    )
    assert data.status == "result"
    assert data.result == "找到结果"
    assert data.duration_ms == 125


def test_tool_call_data_with_error():
    """测试带错误的工具调用数据"""
    data = ToolCallData(
        call_id="test-call-789",
        name="failing_tool",
        status="result",
        error="工具执行失败",
        duration_ms=50
    )
    assert data.error == "工具执行失败"
    assert data.status == "result"


# ===== ChatResponse 类方法测试 =====

def test_chat_response_tool_call_calling():
    """测试工具调用事件 - calling 状态"""
    resp = ChatResponse.tool_call(
        call_id="call-123",
        name="get_current_time",
        args={"timezone": "Asia/Shanghai"},
        status="calling"
    )
    assert resp.type == "tool_call"
    assert resp.tool_data is not None
    assert resp.tool_data.call_id == "call-123"
    assert resp.tool_data.name == "get_current_time"
    assert resp.tool_data.status == "calling"


def test_chat_response_tool_call_processing():
    """测试工具调用事件 - processing 状态"""
    resp = ChatResponse.tool_call(
        call_id="call-456",
        name="search",
        args={"query": "test"},
        status="processing"
    )
    assert resp.type == "tool_call"
    assert resp.tool_data.status == "processing"


def test_chat_response_tool_call_default_status():
    """测试工具调用事件 - 默认 calling 状态"""
    resp = ChatResponse.tool_call(
        call_id="call-789",
        name="get_time",
        args={}
    )
    assert resp.tool_data.status == "calling"


def test_chat_response_tool_result_success():
    """测试工具结果事件 - 成功"""
    resp = ChatResponse.tool_result(
        call_id="call-123",
        name="get_current_time",
        result="2026-02-12 10:30:00",
        duration_ms=125
    )
    assert resp.type == "tool_result"
    assert resp.tool_data is not None
    assert resp.tool_data.call_id == "call-123"
    assert resp.tool_data.name == "get_current_time"
    assert resp.tool_data.result == "2026-02-12 10:30:00"
    assert resp.tool_data.duration_ms == 125
    assert resp.tool_data.status == "result"
    assert resp.tool_data.error is None


def test_chat_response_tool_result_with_error():
    """测试工具结果事件 - 带错误"""
    resp = ChatResponse.tool_result(
        call_id="call-456",
        name="failing_tool",
        result="",
        duration_ms=50,
        error="超时"
    )
    assert resp.tool_data.error == "超时"
    assert resp.tool_data.status == "result"


def test_chat_response_to_sse_with_tool_data():
    """测试 SSE 格式转换 - 带工具数据"""
    resp = ChatResponse.tool_call(
        call_id="call-123",
        name="get_time",
        args={},
        status="calling"
    )
    sse = resp.to_sse()
    assert "data: " in sse
    assert '"type":"tool_call"' in sse
    assert '"call_id":"call-123"' in sse
    assert '"name":"get_time"' in sse


def test_tool_call_creation():
    """测试工具调用模型创建"""
    tool = ToolCall(id="call-1", name="search", arguments={"query": "test"})
    assert tool.name == "search"
    assert tool.arguments == {"query": "test"}
    assert tool.id == "call-1"


def test_tool_call_with_result():
    """测试工具调用模型（带 result 为 AIMessage 保留字段，此处仅测试基础结构）"""
    tool = ToolCall(id="call-2", name="search", arguments={"query": "test"})
    assert tool.id == "call-2"


def test_chat_message_user():
    """测试用户消息"""
    msg = ChatMessage(
        message_type="human",
        content="你好",
        timestamp=datetime.now()
    )
    assert msg.message_type == "human"
    assert msg.content == "你好"
    assert msg.tool_calls == []


def test_chat_message_assistant_with_tools():
    """测试带工具调用的助手消息"""
    msg = ChatMessage(
        message_type="ai",
        content="让我帮你查询",
        tool_calls=[ToolCall(id="call-3", name="search", arguments={"query": "test"})],
        timestamp=datetime.now()
    )
    assert len(msg.tool_calls) == 1
    assert msg.tool_calls[0].name == "search"


def test_chat_request():
    """测试对话请求"""
    req = ChatRequest(message="你好")
    assert req.message == "你好"
    assert req.session_id is None
    assert req.stream is True


def test_chat_request_with_session():
    """测试带会话 ID 的请求"""
    req = ChatRequest(
        session_id="uuid-xxx",
        message="你好",
        stream=False
    )
    assert req.session_id == "uuid-xxx"
    assert req.stream is False


def test_chat_response():
    """测试 SSE 响应模型"""
    resp = ChatResponse(type="start", session_id="uuid-xxx")
    assert resp.type == "start"
    assert resp.session_id == "uuid-xxx"


def test_chat_response_token():
    """测试 token 响应"""
    resp = ChatResponse(type="token", content="你好")
    assert resp.type == "token"
    assert resp.content == "你好"


def test_chat_response_error():
    """测试错误响应"""
    resp = ChatResponse(
        type="error",
        error_code="AUTH_FAILED",
        content="Invalid API Key"
    )
    assert resp.type == "error"
    assert resp.error_code == "AUTH_FAILED"
