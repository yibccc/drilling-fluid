"""
知识库导入消费者
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
from src.utils import (
    create_embeddings_client,
    create_text_splitter,
    ensure_consumer_group,
    decode_value,
)


logger = logging.getLogger(__name__)


class KnowledgeImportConsumer:
    """知识库导入消费者"""

    def __init__(self, pool, redis_client: aioredis.Redis = None):
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

        try:
            logger.info(f"开始处理导入: docId={doc_id}")

            # 1. 创建文档记录（包含 OSS 信息）
            await self._create_document(doc_id, title, content, category, oss_path, file_record_id, metadata)

            # 2. 更新状态：CHUNKING
            await self._update_import_status(doc_id, "CHUNKING")

            # 3. 父子分块
            all_chunks = self._create_chunks(content)

            logger.info(f"文档分块完成: docId={doc_id}, chunks={len(all_chunks)}")

            # 4. 更新状态：EMBEDDING
            await self._update_import_status(doc_id, "EMBEDDING")

            # 5. 向量化并存储
            await self._embed_and_store_chunks(doc_id, all_chunks)

            # 6. 更新状态：COMPLETED
            await self._update_import_status(doc_id, "COMPLETED", chunk_count=len(all_chunks))

            # 7. ACK 消息
            await self._redis_client.xack(self.stream_name, self.consumer_group, message_id)

            logger.info(f"导入完成: docId={doc_id}, chunks={len(all_chunks)}")

        except Exception as e:
            logger.error(f"导入失败: docId={doc_id}, error={e}", exc_info=True)
            await self._update_import_status(doc_id, "FAILED", error=str(e))
            await self._redis_client.xack(self.stream_name, self.consumer_group, message_id)

    async def _create_document(self, doc_id: str, title: str, content: str, category: str,
                                oss_path: str = None, file_record_id: str = None, metadata: dict = None):
        """创建文档记录"""
        async with self.pool.acquire() as conn:
            # 检查文档是否已存在
            existing = await conn.fetchval(
                "SELECT doc_id FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )

            if existing:
                logger.info(f"文档已存在，跳过创建: docId={doc_id}")
                return

            # 构建 metadata，包含 OSS 信息
            doc_metadata = metadata or {}
            if oss_path:
                doc_metadata["oss_path"] = oss_path
            if file_record_id:
                doc_metadata["file_record_id"] = file_record_id
            doc_metadata["source"] = "import"

            # 创建新文档
            await conn.execute(
                """
                INSERT INTO knowledge_documents
                (doc_id, title, category, content, import_status, metadata)
                VALUES ($1, $2, $3, $4, $5, $6)
                """,
                doc_id,
                title or "Untitled",
                category,
                content,
                "CHUNKING",
                json.dumps(doc_metadata)
            )

    def _create_chunks(self, text: str) -> List[Dict[str, Any]]:
        """创建父子分块"""
        chunks = []

        # 先创建父分块（按段落）
        parent_chunks = self._create_parent_chunks(text)

        for parent_idx, parent_chunk in enumerate(parent_chunks):
            # 子分块器
            child_splitter = create_text_splitter()
            child_chunks = child_splitter.split_text(parent_chunk)

            for child_idx, child_content in enumerate(child_chunks):
                chunks.append({
                    'content': child_content,
                    'parent_index': parent_idx,
                    'chunk_index': len(chunks)
                })

        return chunks

    def _create_parent_chunks(self, text: str) -> List[str]:
        """创建父分块（按章节/段落）"""
        # 按双换行分段（段落级）
        paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]

        # 合并小段落，确保父块约 2000-3000 字符
        parent_chunks = []
        current_chunk = ""

        for para in paragraphs:
            if len(current_chunk) + len(para) > 3000:
                if current_chunk:
                    parent_chunks.append(current_chunk)
                current_chunk = para
            else:
                current_chunk += "\n\n" + para if current_chunk else para

        if current_chunk:
            parent_chunks.append(current_chunk)

        return parent_chunks

    async def _embed_and_store_chunks(self, doc_id: str, chunks: List[Dict]):
        """向量化并存储分块"""
        # 复用现有的 knowledge_repo
        from src.repositories.knowledge_repo import KnowledgeRepository

        repo = KnowledgeRepository(self.pool, self.embeddings)
        await repo.create_chunks(doc_id, chunks)

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
