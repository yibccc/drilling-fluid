"""
同步服务 - Redis Stream 消费者

从 Redis Stream 消费 checkpoint 更新消息，同步到 PostgreSQL
"""

import asyncio
from uuid import uuid4
from typing import Optional
import logging
import redis.asyncio as aioredis

from src.config import settings
from src.models.exceptions import AppException
from src.models.schemas import ChatMessage, ToolCall
from src.utils import ensure_consumer_group, decode_redis_data


class SyncService:
    """Redis Stream 消费者服务"""

    # LangGraph 消息类型映射（使用原生类型）
    # 直接使用 LangGraph 的 message_type，不再转换
    MESSAGE_TYPE_MAP = {
        "human": "human",
        "ai": "ai",
        "system": "system",
        "tool": "tool",
    }

    def __init__(self, pg_repo=None, graph=None):
        self.pg_repo = pg_repo
        self.graph = graph  # 用于读取 checkpoint 数据的 CompiledGraph
        self._running = False
        self._consumer_name = f"worker-{uuid4().hex[:8]}"
        self._redis_client: Optional[aioredis.Redis] = None

    @staticmethod
    def _map_message_type(msg_type: str) -> str:
        """
        映射 LangGraph 消息类型到 ChatMessage.message_type

        LangGraph 原生类型直接使用：
        - 'human': HumanMessage（用户消息）
        - 'ai': AIMessage（AI消息，可能含 tool_calls）
        - 'system': SystemMessage（系统消息）
        - 'tool': ToolMessage（工具结果）
        """
        return SyncService.MESSAGE_TYPE_MAP.get(msg_type, "human")

    async def start(self):
        """启动消费者"""
        if self._running:
            return

        self._running = True
        self._redis_client = aioredis.from_url(settings.redis_url)

        # 确保存在 Consumer Group
        await ensure_consumer_group(
            self._redis_client,
            settings.redis_stream_name,
            settings.redis_consumer_group
        )

        logging.info(f"SyncService started: consumer={self._consumer_name}")

        # 启动多个 worker
        tasks = []
        for i in range(settings.redis_stream_workers):
            task = asyncio.create_task(
                self._worker(self._redis_client, worker_id=i)
            )
            tasks.append(task)

        # 等待所有 worker 完成
        await asyncio.gather(*tasks, return_exceptions=True)

    async def stop(self):
        """停止消费者"""
        self._running = False
        if self._redis_client:
            await self._redis_client.aclose()
        logging.info("SyncService stopped")

    async def _worker(self, redis_client: aioredis.Redis, worker_id: int):
        """Worker 线程"""
        consumer_name = f"{self._consumer_name}-{worker_id}"
        logging.info(f"Worker {worker_id} started as {consumer_name}")

        while self._running:
            try:
                # 从 Stream 读取消息
                messages = await redis_client.xreadgroup(
                    settings.redis_consumer_group,
                    consumer_name,
                    {settings.redis_stream_name: ">"},
                    count=1,
                    block=1000  # 1秒超时
                )

                if messages:
                    for stream, stream_messages in messages:
                        for msg_id, data in stream_messages:
                            await self._process_sync_message(data)
                            # ACK 消息
                            await redis_client.xack(
                                settings.redis_stream_name,
                                settings.redis_consumer_group,
                                msg_id
                            )

            except asyncio.CancelledError:
                break
            except Exception as e:
                logging.error(f"Worker {worker_id} error: {e}")
                await asyncio.sleep(1)

        logging.info(f"Worker {worker_id} stopped")

    async def _process_sync_message(self, data: dict):
        """处理同步消息 - 从 checkpoint 读取并写入 PostgreSQL"""
        try:
            decoded = await decode_redis_data(data)

            thread_id = decoded.get("thread_id")
            session_id = decoded.get("session_id")
            user_id = decoded.get("user_id")
            message_count = int(decoded.get("message_count", 0))

            logging.info(f"Syncing checkpoint: thread_id={thread_id}, messages={message_count}")

            if not self.graph or not self.pg_repo:
                logging.warning("Graph or pg_repo not configured, skipping sync")
                return

            # 从 checkpoint 读取消息历史
            config = {"configurable": {"thread_id": thread_id}}
            messages_synced = 0

            try:
                # 获取 checkpoint 状态 - 使用 CompiledGraph 的 aget_state 方法
                state_snapshot = await self.graph.aget_state(config)

                if state_snapshot and state_snapshot.values:
                    messages = state_snapshot.values.get("messages", [])

                    # 获取数据库中已有的消息数量
                    existing_count = await self.pg_repo.get_message_count(session_id)

                    # 保存会话
                    await self.pg_repo.save_session(
                        session_id=session_id,
                        user_id=user_id,
                        message_count=len(messages)
                    )

                    # 只保存新增的消息（跳过已保存的）
                    for msg in messages[existing_count:]:
                        # 获取消息类型（LangGraph 原生类型）
                        msg_type = msg.type if hasattr(msg, 'type') else "human"
                        mapped_type = self._map_message_type(msg_type)

                        # 提取 content
                        content = msg.content if hasattr(msg, 'content') else str(msg)

                        # 提取 tool_calls（仅 AIMessage 有）
                        tool_calls = []
                        if hasattr(msg, 'tool_calls') and msg.tool_calls:
                            for tc in msg.tool_calls:
                                tool_calls.append(
                                    {
                                        "id": tc.get("id", ""),
                                        "name": tc.get("name", ""),
                                        "arguments": tc.get("args", tc.get("arguments", {})),
                                    }
                                )

                        # 提取 tool_call_id（仅 ToolMessage 有）
                        tool_call_id = None
                        tool_name = None
                        if msg_type == "tool":
                            # ToolMessage 通过 tool_call_id 关联到 AIMessage
                            tool_call_id = getattr(msg, 'tool_call_id', None)
                            # 尝试从 content 或其他属性获取工具名
                            tool_name = getattr(msg, 'name', None)

                        # 提取 additional_kwargs（AIMessage 的元数据）
                        additional_kwargs = None
                        if hasattr(msg, 'response_metadata') and msg.response_metadata:
                            # 只有非空时才保存，避免空字典导致 JSONB 转换失败
                            additional_kwargs = {"response_metadata": msg.response_metadata}

                        # 创建 ChatMessage（使用新模型）
                        chat_message = ChatMessage(
                            message_type=mapped_type,
                            content=content,
                            tool_calls=[ToolCall(**tc) for tc in tool_calls] if tool_calls else [],
                            tool_call_id=tool_call_id,
                            tool_name=tool_name,
                            additional_kwargs=additional_kwargs,
                        )

                        await self.pg_repo.save_message(session_id, chat_message)
                        messages_synced += 1

                    logging.info(f"Synced {messages_synced} new messages for session {session_id} (total: {len(messages)})")
                else:
                    logging.warning(f"No checkpoint found for thread_id={thread_id}")

            except Exception as e:
                logging.error(f"Failed to read checkpoint: {e}")

        except Exception as e:
            logging.error(f"Process sync message failed: {e}")


# 全局实例
sync_service = SyncService()
