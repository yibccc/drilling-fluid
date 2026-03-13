"""
Knowledge Import Consumer
从 Redis Stream 消费导入任务，处理分块和向量化
"""

import asyncio
import json
import logging
from uuid import uuid4
from typing import List, Dict, Any, Optional

import redis.asyncio as aioredis

from src.config import settings
from src.models.exceptions import KnowledgeBaseError
from src.services.vector_store_service import VectorStoreService
from src.utils import (
    create_embeddings_client,
    ensure_consumer_group,
    decode_value,
)
from langchain_core.documents import Document
from datetime import datetime


logger = logging.getLogger(__name__)


class KnowledgeImportConsumer:
    """Knowledge Import Consumer"""

    MAX_RETRIES = 3
    RETRY_DELAY_SECONDS = 0.1

    def __init__(self, pool=None, redis_client: aioredis.Redis = None):
        """
        初始化消费者
        :param pool: 数据库连接池（已废弃，保留以兼容 lifespan）
        :param redis_client: Redis 客户端
        """
        self.pool = pool
        self._redis_client = redis_client
        self.stream_name = "stream:knowledge_import"
        self.consumer_group = "group:knowledge_workers"
        self.consumer_name = f"worker-{uuid4().hex[:8]}"
        self.running = False
        self.embeddings = None

    async def start(self):
        """启动消费者"""
        if self.running:
            return

        self.running = True

        # 初始化 Redis 客户端
        if self._redis_client is None:
            self._redis_client = aioredis.from_url(settings.redis_url)

        # 创建消费组（如果不存在）
        await ensure_consumer_group(
            self._redis_client,
            self.stream_name,
            self.consumer_group
        )

        # 初始化 embeddings
        self.embeddings = create_embeddings_client()

        logger.info(f"知识导入消费者启动: {self.consumer_name}")

        # 启动 worker
        await self._worker()

    async def stop(self):
        """停止消费者"""
        self.running = False
        if self._redis_client:
            await self._redis_client.aclose()
        logger.info("知识导入消费者停止")

    async def _worker(self):
        """Worker 线程"""
        logger.info(f"Worker started as {self.consumer_name}")

        while self.running:
            try:
                # 读取消息（阻塞1秒）
                messages = await self._redis_client.xreadgroup(
                    self.consumer_group,
                    self.consumer_name,
                    {self.stream_name: '>'},
                    count=1,
                    block=1000
                )

                if messages:
                    for stream, stream_messages in messages:
                        for message_id, data in stream_messages:
                            try:
                                await self._process_import(message_id, data)
                            except Exception as e:
                                logger.error(f"处理消息失败: {e}", exc_info=True)
                                # ACK 消息避免重复处理
                                await self._redis_client.xack(
                                    self.stream_name,
                                    self.consumer_group,
                                    message_id
                                )

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"读取消息失败: {e}", exc_info=True)
                await asyncio.sleep(1)

        logger.info(f"Worker stopped")

    async def _process_import(self, message_id: str, data: dict):
        """处理单个导入任务"""
        doc_id = decode_value(data.get(b'doc_id', b''))
        title = decode_value(data.get(b'title', b''))
        content = decode_value(data.get(b'content', b''))
        category = decode_value(data.get(b'category', b'default'))

        # 提取 OSS 相关字段
        oss_path = decode_value(data.get(b'oss_path', b''))
        file_record_id = decode_value(data.get(b'file_record_id', b''))

        # 解析 metadata JSON
        metadata_json = decode_value(data.get(b'metadata', b'{}'))
        try:
            metadata = json.loads(metadata_json) if metadata_json else {}
        except json.JSONDecodeError:
            metadata = {}

        logger.info(f"开始处理导入: docId={doc_id}")

        # 1. 更新状态：CHUNKING
        await self._update_import_status(doc_id, "CHUNKING")

        # 2. 分块
        all_chunks = self._create_chunks(content)
        logger.info(f"文档分块完成: docId={doc_id}, chunks={len(all_chunks)}")

        for attempt in range(1, self.MAX_RETRIES + 1):
            try:
                # 3. 更新状态：EMBEDDING
                await self._update_import_status(doc_id, "EMBEDDING")

                # 4. 向量化并存储（Parent-Child 逻辑）
                await self._embed_and_store_chunks(
                    doc_id=doc_id,
                    title=title,
                    category=category,
                    chunks=all_chunks,
                    base_metadata={
                        "oss_path": oss_path,
                        "file_record_id": file_record_id,
                        **metadata
                    }
                )

                # 5. 更新状态：COMPLETED
                await self._update_import_status(doc_id, "COMPLETED", chunk_count=len(all_chunks))

                # 6. ACK 消息
                await self._redis_client.xack(self.stream_name, self.consumer_group, message_id)

                logger.info(f"导入完成: docId={doc_id}, chunks={len(all_chunks)}")
                return

            except Exception as e:
                logger.error(
                    f"导入失败: docId={doc_id}, attempt={attempt}/{self.MAX_RETRIES}, error={e}",
                    exc_info=True
                )

                if attempt < self.MAX_RETRIES:
                    await self._update_import_status(doc_id, "RETRYING", error=str(e))
                    await asyncio.sleep(self.RETRY_DELAY_SECONDS * attempt)
                    continue

                await self._update_import_status(doc_id, "FAILED", error=str(e))
                await self._redis_client.xack(self.stream_name, self.consumer_group, message_id)
                return

    def _create_chunks(self, text: str) -> List[Dict[str, Any]]:
        """使用 RecursiveCharacterTextSplitter 创建子分块"""
        from src.utils.common import create_text_splitter
        splitter = create_text_splitter()
        texts = splitter.split_text(text)
        
        chunks = []
        for idx, t in enumerate(texts):
            chunks.append({
                'text': t,
                'page': 1,
                'position': idx,
            })
        return chunks

    async def _embed_and_store_chunks(
        self,
        doc_id: str,
        title: str,
        category: str,
        chunks: List[Dict[str, Any]],
        base_metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """实现 Parent-Child 存储逻辑：1个父文档(全文) + N个子分块"""
        if not chunks:
            return

        base_metadata = base_metadata or {}
        timestamp = datetime.utcnow().isoformat()
        
        # 1. 构建父文档 (全文)
        full_content = "\n\n".join([c['text'] for c in chunks])
        parent_chunk_id = f"{doc_id}_parent"
        
        parent_doc = Document(
            page_content=full_content,
            metadata={
                "chunk_id": parent_chunk_id,
                "doc_id": doc_id,
                "title": title,
                "category": category,
                "chunk_type": "parent",
                **base_metadata,
                "created_at": timestamp,
            }
        )

        # 2. 构建子分块文档
        child_documents = []
        for idx, chunk in enumerate(chunks):
            child_chunk_id = f"{parent_chunk_id}_child_{idx}"
            child_doc = Document(
                page_content=chunk['text'],
                metadata={
                    "chunk_id": child_chunk_id,
                    "parent_chunk_id": parent_chunk_id,
                    "doc_id": doc_id,
                    "title": title,
                    "category": category,
                    "position": chunk['position'],
                    "chunk_type": "child",
                    **base_metadata,
                    "created_at": timestamp,
                }
            )
            child_documents.append(child_doc)

        try:
            # 3. 写入向量库
            vector_store_service = VectorStoreService(settings.get_langchain_connection_string())
            await vector_store_service.add_parent_child_documents(
                parent_documents=[parent_doc],
                child_documents_map={parent_chunk_id: child_documents}
            )
            logger.info(f"异步任务存储完成: docId={doc_id}, parentId={parent_chunk_id}, children={len(child_documents)}")
            
        except Exception as e:
            logger.error(f"存储向量失败: {e}")
            raise KnowledgeBaseError(f"Failed to embed and store chunks: {str(e)}")

    async def _update_import_status(self, doc_id: str, status: str,
                                   chunk_count: int = None, error: str = None):
        """更新导入状态到 Redis"""
        status_key = f"knowledge:status:{doc_id}"
        await self._redis_client.set(status_key, status)

        if chunk_count is not None:
            count_key = f"knowledge:chunks:{doc_id}"
            await self._redis_client.set(count_key, str(chunk_count))

        if error:
            error_key = f"knowledge:error:{doc_id}"
            await self._redis_client.set(error_key, error)


async def main():
    """主函数"""
    from src.config import settings

    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )

    # 创建消费者 (不再需要 DB Pool)
    consumer = KnowledgeImportConsumer()

    try:
        await consumer.start()
    except KeyboardInterrupt:
        await consumer.stop()


if __name__ == "__main__":
    asyncio.run(main())


if __name__ == "__main__":
    asyncio.run(main())
