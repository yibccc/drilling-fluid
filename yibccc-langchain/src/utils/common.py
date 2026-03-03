"""
通用工具函数

提取重复代码，避免冗余
"""

import logging
from typing import Dict, Any

import redis.asyncio as aioredis
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from src.config import settings


logger = logging.getLogger(__name__)


# ========== 配置常量 ==========

TEXT_SPLITTER_CONFIG = {
    "chunk_size": 600,
    "chunk_overlap": 100,
    "length_function": len,
}


# ========== Embedding 客户端 ==========

def create_embeddings_client():
    """
    创建 Embedding 客户端 (通义千问 DashScope)

    Returns:
        DashScopeEmbeddings 实例
    """
    return DashScopeEmbeddings(
        model=settings.embedding_model,
        dashscope_api_key=settings.dashscope_api_key,
    )


# ========== 文本分块器 ==========

def create_text_splitter() -> RecursiveCharacterTextSplitter:
    """
    创建文本分块器

    Returns:
        RecursiveCharacterTextSplitter 实例
    """
    return RecursiveCharacterTextSplitter(**TEXT_SPLITTER_CONFIG)


# ========== Redis 辅助函数 ==========

async def ensure_consumer_group(
    redis_client: aioredis.Redis,
    stream_name: str,
    consumer_group: str
) -> bool:
    """
    确保 Redis Stream 消费组存在

    Args:
        redis_client: Redis 客户端
        stream_name: Stream 名称
        consumer_group: 消费组名称

    Returns:
        是否成功创建或已存在
    """
    try:
        await redis_client.xgroup_create(
            stream_name,
            consumer_group,
            id="0",
            mkstream=True
        )
        logger.info(f"Created consumer group '{consumer_group}' for stream '{stream_name}'")
        return True
    except Exception as e:
        error_str = str(e)
        if "BUSYGROUP" in error_str or "already exists" in error_str.lower():
            logger.info(f"Consumer group '{consumer_group}' already exists")
            return True
        else:
            logger.warning(f"Failed to create consumer group: {e}")
            # 尝试先创建 Stream
            try:
                await redis_client.xadd(stream_name, {"init": "true"})
                await redis_client.xgroup_create(
                    stream_name,
                    consumer_group,
                    id="0"
                )
                logger.info(f"Created stream and consumer group '{consumer_group}'")
                return True
            except Exception as e2:
                logger.error(f"Failed to create stream and consumer group: {e2}")
                return False


async def decode_redis_data(data: Dict[Any, Any]) -> Dict[str, Any]:
    """
    解码 Redis 中的字节数据

    Args:
        data: 包含 bytes 键或值的字典

    Returns:
        解码后的字典
    """
    decoded = {}
    for key, value in data.items():
        # 解码 key
        if isinstance(key, bytes):
            key = key.decode('utf-8')
        # 解码 value
        if isinstance(value, bytes):
            value = value.decode('utf-8')
        decoded[key] = value
    return decoded


def decode_value(v: Any) -> Any:
    """
    解码单个值（如果是 bytes）

    Args:
        v: 任意值

    Returns:
        解码后的值
    """
    if isinstance(v, bytes):
        return v.decode('utf-8')
    return v
