"""
PostgreSQL 仓储层

处理 PostgreSQL 连接和持久化操作
"""

from uuid import uuid4
from datetime import datetime
from typing import Optional
import json

import asyncpg

from src.config import settings
from src.models.schemas import ChatMessage
from src.models.exceptions import AppException


class PostgreSQLRepository:
    """PostgreSQL 仓储类"""

    def __init__(self):
        self._pool: Optional[asyncpg.Pool] = None

    async def connect(self):
        """建立数据库连接池"""
        self._pool = await asyncpg.create_pool(
            settings.get_pg_dsn(),
            min_size=5,
            max_size=20
        )

    async def disconnect(self):
        """关闭数据库连接"""
        if self._pool:
            await self._pool.close()

    @property
    def pool(self) -> asyncpg.Pool:
        """获取连接池"""
        if self._pool is None:
            raise AppException("PostgreSQL 未连接")
        return self._pool

    async def save_session(self, session_id: str, user_id: str, message_count: int = 0):
        """保存会话"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO chat_sessions (id, user_id, message_count)
                VALUES ($1, $2, $3)
                ON CONFLICT (id) DO UPDATE
                SET updated_at = NOW(), message_count = $3
                """,
                session_id, user_id, message_count
            )

    async def get_session(self, session_id: str) -> Optional[dict]:
        """获取会话"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM chat_sessions WHERE id = $1",
                session_id
            )
            return dict(row) if row else None

    async def save_message(self, session_id: str, message: ChatMessage):
        """
        保存消息（支持 Schema v3.0）

        LangGraph 消息类型映射：
        - HumanMessage → message_type='human'
        - AIMessage → message_type='ai' (可能包含 tool_calls)
        - ToolMessage → message_type='tool' (通过 tool_call_id 关联)
        - SystemMessage → message_type='system'
        """
        message_id = str(uuid4())

        # 构建 tool_calls JSON（仅 AIMessage 有值）
        # 使用 json.dumps 转换为字符串，asyncpg 会自动处理 JSONB 类型
        # message.tool_calls 是 ToolCall 对象列表（Pydantic 模型）
        tool_calls_json = None
        if message.tool_calls:
            tool_calls_json = json.dumps([
                {"id": tc.id, "name": tc.name, "args": tc.arguments}
                for tc in message.tool_calls
            ])

        # 处理 additional_kwargs：空字典转为 None
        # 使用 json.dumps 转换为字符串，asyncpg 会自动处理 JSONB 类型
        additional_kwargs_clean = message.additional_kwargs
        if additional_kwargs_clean is not None and len(additional_kwargs_clean) == 0:
            additional_kwargs_clean = None
        elif additional_kwargs_clean is not None:
            additional_kwargs_clean = json.dumps(additional_kwargs_clean)

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """
                    INSERT INTO chat_messages (
                        id, session_id, message_type, content,
                        tool_calls, tool_call_id, tool_name, tool_status, tool_error,
                        additional_kwargs, created_at
                    )
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW())
                    """,
                    message_id,
                    session_id,
                    message.message_type,
                    message.content,
                    tool_calls_json,
                    message.tool_call_id,
                    message.tool_name,
                    message.tool_status,
                    message.tool_error,
                    additional_kwargs_clean,
                )
                # 更新会话消息计数
                await conn.execute(
                    """
                    UPDATE chat_sessions
                    SET message_count = message_count + 1, updated_at = NOW()
                    WHERE id = $1
                    """,
                    session_id
                )

    async def get_message_count(self, session_id: str) -> int:
        """获取会话消息数量"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT COUNT(*) as count FROM chat_messages
                WHERE session_id = $1
                """,
                session_id
            )
            return row["count"] if row else 0

    async def get_messages(self, session_id: str, limit: int = 100) -> list:
        """获取会话消息"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM chat_messages
                WHERE session_id = $1
                ORDER BY created_at ASC
                LIMIT $2
                """,
                session_id, limit
            )
            return [dict(row) for row in rows]

    async def get_user_sessions(self, user_id: str, limit: int = 50) -> list:
        """获取用户会话列表"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM chat_sessions
                WHERE user_id = $1
                ORDER BY updated_at DESC
                LIMIT $2
                """,
                user_id, limit
            )
            return [dict(row) for row in rows]


# 全局实例
pg_repo = PostgreSQLRepository()
