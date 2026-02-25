"""
对话服务层 - 基于 LangChain 1.0.0 create_agent

处理对话逻辑、LLM 调用和消息管理
"""

import time
from dataclasses import dataclass
from datetime import datetime
from typing import AsyncIterator, Dict
from uuid import uuid4

import logging
import redis.asyncio as aioredis

from langchain.agents import create_agent
from langchain.messages import HumanMessage, AIMessage, ToolMessage
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.redis.aio import AsyncRedisSaver

from src.config import settings, get_llm_config
from src.models.schemas import ChatRequest, ChatResponse
from src.models.exceptions import LLMError, RedisCheckpointError
from src.tools import get_current_time
from langchain.agents.middleware import SummarizationMiddleware

@dataclass
class Context:
    """运行时上下文 - 用于中间件访问用户信息"""
    user_id: str


class ChatService:
    """对话服务类 - 基于 LangChain create_agent"""

    def __init__(self, checkpointer = None):
        self.checkpointer = checkpointer
        self._owns_checkpointer = checkpointer is None
        self._redis_client = None  # Redis Stream 同步客户端
        self.model = ChatOpenAI(
            **get_llm_config(),
            streaming=True,
            temperature=0.7
        )
        self.agent = None
        # 定义可用工具
        self.tools = [get_current_time]

    async def initialize(self):
        """异步初始化（在 startup 时调用）"""
        if self.checkpointer is None:
            try:
                self.checkpointer = AsyncRedisSaver(redis_url=settings.redis_url)
                # 关键：调用 setup() 创建 Redis 搜索索引
                await self.checkpointer.setup()
                logging.info("AsyncRedisSaver initialized with indexes")
            except Exception as e:
                raise RedisCheckpointError(str(e))

        # 初始化 Redis Stream 同步客户端（复用连接）
        if settings.redis_stream_sync_enabled:
            self._redis_client = await aioredis.from_url(settings.redis_url)
            logging.info("Redis client initialized for stream sync")

        self._build_agent()

    async def cleanup(self):
        """清理资源（在 shutdown 时调用）"""
        if self._owns_checkpointer and hasattr(self.checkpointer, 'aclose'):
            await self.checkpointer.aclose()
            logging.info("AsyncRedisSaver connection closed")

        if self._redis_client:
            await self._redis_client.aclose()
            logging.info("Redis stream client closed")

    def _build_agent(self):
        """使用 create_agent 构建对话 Agent"""
        self.agent = create_agent(
            model=self.model,
            tools=self.tools,
            system_prompt="You are a helpful assistant. You can help users with various tasks including answering questions and providing information.",
            checkpointer=self.checkpointer,
            middleware=[
                SummarizationMiddleware(
                    model=self.model,
                    trigger=("tokens", 4000),
                    keep=("messages", 3)
                )
            ],
            context_schema=Context,  # 支持运行时上下文
        )
        logging.info("Agent created with create_agent")

    async def chat_stream(
        self,
        request: ChatRequest,
        user_id: str
    ) -> AsyncIterator[ChatResponse]:
        """流式对话处理"""
        session_id = request.session_id or str(uuid4())

        config = {
            "configurable": {
                "thread_id": session_id
            }
        }

        # 发送开始事件
        yield ChatResponse.start(session_id=session_id)

        # 工具调用计时器字典
        tool_timings: Dict[str, float] = {}

        try:
            message_count = 0

            # 使用 create_agent 的 astream 方法
            async for event in self.agent.astream(
                {"messages": [HumanMessage(content=request.message)]},
                config=config,
                stream_mode="messages",
            ):
                # event 是 (message, metadata) 元组
                if isinstance(event, tuple) and len(event) >= 1:
                    message = event[0]

                    # 处理 AI 消息（包含工具调用）
                    if isinstance(message, AIMessage):
                        # 检测工具调用
                        if hasattr(message, 'tool_calls') and message.tool_calls:
                            for tc in message.tool_calls:
                                # tool_calls 是字典列表，使用键访问
                                call_id = str(tc['id'])
                                tool_name = tc['name']
                                tool_args = tc['args']

                                # 发送 calling 状态
                                yield ChatResponse.tool_call(
                                    call_id=call_id,
                                    name=tool_name,
                                    args=tool_args,
                                    status="calling"
                                )

                                # 发送 processing 状态
                                yield ChatResponse.tool_call(
                                    call_id=call_id,
                                    name=tool_name,
                                    args=tool_args,
                                    status="processing"
                                )

                                # 记录开始时间
                                tool_timings[call_id] = time.perf_counter()

                            # 跳过只有 tool_calls 的消息，不发送 token
                            continue

                        # 跳过空内容消息
                        if not message.content:
                            continue

                        # 直接发送每个片段的内容（每个片段都是独立的增量 token）
                        yield ChatResponse.token(content=message.content)
                        message_count += 1

                    # 处理工具结果消息
                    elif isinstance(message, ToolMessage):
                        tool_call_id = message.tool_call_id
                        if tool_call_id and tool_call_id in tool_timings:
                            # 计算执行时长
                            duration_ms = int((time.perf_counter() - tool_timings[tool_call_id]) * 1000)
                            del tool_timings[tool_call_id]

                            # 发送工具结果事件
                            yield ChatResponse.tool_result(
                                call_id=tool_call_id,
                                name=message.name,
                                result=str(message.content),
                                duration_ms=duration_ms
                            )

            # 触发后台同步
            if settings.redis_stream_sync_enabled:
                await self._trigger_sync(session_id, user_id, message_count)

            yield ChatResponse.end()

        except Exception as e:
            logging.error(f"Chat stream error: {e}")
            raise LLMError(f"LLM 调用失败: {str(e)}")

    async def _trigger_sync(self, session_id: str, user_id: str, message_count: int):
        """触发后台同步 - 发送消息到 Redis Stream（复用连接）"""
        if not self._redis_client:
            return

        try:
            await self._redis_client.xadd(
                settings.redis_stream_name,
                {
                    "thread_id": session_id,
                    "session_id": session_id,
                    "user_id": user_id,
                    "message_count": str(message_count),
                    "timestamp": datetime.utcnow().isoformat(),
                    "action": "checkpoint_updated"
                }
            )
        except Exception as e:
            # 同步失败不影响主流程
            logging.warning(f"Sync trigger failed: {e}")


# 全局实例（延迟初始化）
chat_service = ChatService()
