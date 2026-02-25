"""
数据模型定义

Pydantic 模型用于请求/响应验证和序列化
"""

from pydantic import BaseModel, Field, field_validator
from typing import Literal, Optional
from datetime import datetime
from uuid import uuid4, UUID


class ToolCall(BaseModel):
    """工具调用信息（AIMessage.tool_calls 元素）"""
    id: str = Field(..., description="工具调用唯一标识（用于关联 ToolMessage）")
    name: str = Field(..., description="工具名称")
    arguments: dict = Field(default_factory=dict, description="工具参数")


class ToolCallResult(BaseModel):
    """工具执行结果（用于统计和展示）"""
    name: str = Field(..., description="工具名称")
    status: Literal["pending", "success", "failed"] = Field("success", description="执行状态")
    result: Optional[str] = Field(None, description="工具执行结果")
    error: Optional[str] = Field(None, description="错误信息")


class ToolCallData(BaseModel):
    """工具调用 SSE 事件数据（扩展 tool_data 结构）"""
    call_id: str = Field(..., description="工具调用唯一 ID（用于关联状态）")
    name: str = Field(..., description="工具名称")
    arguments: dict = Field(default_factory=dict, description="工具参数（详细模式）")
    status: Literal["calling", "processing", "result"] = Field(
        "calling", description="调用状态：calling=调用中, processing=执行中, result=有结果"
    )
    result: Optional[str] = Field(None, description="工具执行结果")
    error: Optional[str] = Field(None, description="错误信息")
    duration_ms: Optional[int] = Field(None, description="执行时长（毫秒）")


class ChatMessage(BaseModel):
    """
    对话消息（支持 LangGraph 消息结构）

    消息类型 (message_type):
    - 'human': HumanMessage（用户输入）
    - 'ai': AIMessage（AI回复，可能包含 tool_calls）
    - 'system': SystemMessage（系统提示）
    - 'tool': ToolMessage（工具执行结果）
    """
    message_type: Literal["human", "ai", "system", "tool"] = Field(
        ..., description="消息类型（LangGraph 原生类型）"
    )
    content: Optional[str] = Field(None, description="消息内容")
    tool_calls: list[ToolCall] = Field(default_factory=list, description="AI消息的工具调用列表")
    tool_call_id: Optional[str] = Field(None, description="ToolMessage 关联的 tool_call.id")
    tool_name: Optional[str] = Field(None, description="工具名称（ToolMessage 专用）")
    tool_status: Optional[Literal["pending", "success", "failed"]] = Field(None, description="工具执行状态")
    tool_error: Optional[str] = Field(None, description="工具执行错误信息")
    additional_kwargs: Optional[dict] = Field(None, description="AI消息的额外参数")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="消息时间戳")

    @field_validator("content")
    @classmethod
    def validate_content(cls, v, info):
        """验证消息有效性"""
        # 对于 tool 类型，content 必须存在
        if info.data.get("message_type") == "tool" and v is None:
            raise ValueError("ToolMessage 必须有 content（工具执行结果）")
        # 对于 ai 类型，content 和 tool_calls 至少一个存在
        if info.data.get("message_type") == "ai":
            if v is None and not info.data.get("tool_calls"):
                raise ValueError("AIMessage 必须有 content 或 tool_calls")
        return v


class ChatRequest(BaseModel):
    """对话请求"""
    session_id: Optional[str] = Field(None, description="会话 ID，首次为空")
    message: str = Field(..., min_length=1, description="用户消息")
    stream: bool = Field(True, description="是否流式返回")


class ChatResponse(BaseModel):
    """SSE 事件响应"""
    type: Literal["start", "token", "tool_call", "tool_result", "end", "error"] = Field(
        ..., description="事件类型"
    )
    session_id: Optional[str] = Field(None, description="会话 ID")
    content: Optional[str] = Field(None, description="内容（token/error 消息）")
    tool_data: Optional[ToolCallData] = Field(None, description="工具调用信息（扩展结构）")
    error_code: Optional[str] = Field(None, description="错误代码")

    def to_sse(self) -> str:
        """转换为 SSE 格式"""
        return f"data: {self.model_dump_json(exclude_none=True)}\n\n"

    @classmethod
    def start(cls, session_id: str) -> "ChatResponse":
        """创建开始事件"""
        return cls(type="start", session_id=session_id)

    @classmethod
    def token(cls, content: str) -> "ChatResponse":
        """创建 token 事件"""
        return cls(type="token", content=content)

    @classmethod
    def tool_call(
        cls,
        call_id: str,
        name: str,
        args: dict,
        status: Literal["calling", "processing"] = "calling"
    ) -> "ChatResponse":
        """创建工具调用事件（calling 或 processing 状态）"""
        return cls(
            type="tool_call",
            tool_data=ToolCallData(
                call_id=call_id,
                name=name,
                arguments=args,
                status=status
            )
        )

    @classmethod
    def tool_result(
        cls,
        call_id: str,
        name: str,
        result: str,
        duration_ms: int,
        error: Optional[str] = None
    ) -> "ChatResponse":
        """创建工具结果事件（result 状态）"""
        return cls(
            type="tool_result",
            tool_data=ToolCallData(
                call_id=call_id,
                name=name,
                status="result",
                result=result,
                duration_ms=duration_ms,
                error=error
            )
        )

    @classmethod
    def end(cls, reason: str = "stop") -> "ChatResponse":
        """创建结束事件"""
        return cls(type="end", content=reason)

    @classmethod
    def error(cls, code: str, message: str) -> "ChatResponse":
        """创建错误事件"""
        return cls(type="error", error_code=code, content=message)
