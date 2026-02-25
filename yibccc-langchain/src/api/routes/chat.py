"""
聊天路由

处理流式对话 API 端点（专注于 LLM 对话逻辑）
"""

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from typing import AsyncIterator

from src.api.dependencies import get_user_id
from src.models.schemas import ChatRequest, ChatResponse
from src.services.chat_service import chat_service
from src.models.exceptions import AppException


router = APIRouter(prefix="/api/v1/chat", tags=["chat"])


async def sse_generator(events: AsyncIterator[ChatResponse]) -> AsyncIterator[str]:
    """SSE 事件生成器"""
    try:
        async for event in events:
            yield event.to_sse()
    except AppException as e:
        yield ChatResponse.error(e.code, e.message).to_sse()


@router.post("/stream")
async def chat_stream(
    request: ChatRequest,
    user_id: str = Depends(get_user_id)
):
    """流式对话端点 - 核心接口"""
    return StreamingResponse(
        sse_generator(chat_service.chat_stream(request, user_id)),
        media_type="text/event-stream"
    )
