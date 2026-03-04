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
        """创建父子分块 - 按段落分块，每个段落作为一个 page"""
        chunks = []

        # 按双换行分段（段落级）
        paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]

        # 为每个段落创建 chunk，使用 page=1 简化处理
        for idx, paragraph in enumerate(paragraphs):
            chunks.append({
                'text': paragraph,
                'page': 1,  # 简化为单页处理
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
        """使用父子模式对文档分块进行向量化并存储

        Args:
            doc_id: 文档 ID
            title: 文档标题
            category: 知识分类
            chunks: 分块字典列表，包含 text、page、position 字段
            base_metadata: 要包含在所有分块中的基础元数据
        """
        if not chunks:
            return

        base_metadata = base_metadata or {}
        timestamp = datetime.utcnow().isoformat()

        try:
            # 按页面分组分块（每个页面成为一个父分块）
            page_chunks: Dict[int, List[Dict[str, Any]]] = {}
            for chunk in chunks:
                page = chunk.get("page", 1)
                if page not in page_chunks:
                    page_chunks[page] = []
                page_chunks[page].append(chunk)

            # 创建父文档（页面）和子文档（段落）
            parent_documents: List[Document] = []
            child_documents_map: Dict[str, List[Document]] = {}

            for page, page_chunk_list in page_chunks.items():
                # 生成父分块 ID（页面级）
                parent_chunk_id = f"{doc_id}_page_{page}"

                # 合并此页面的所有文本作为父分块
                page_text = "\n".join([c.get("text", "") for c in page_chunk_list])

                # 创建父文档
                parent_doc = Document(
                    page_content=page_text,
                    metadata={
                        "chunk_id": parent_chunk_id,
                        "doc_id": doc_id,
                        "title": title,
                        "category": category,
                        "page": page,
                        "chunk_type": "parent",
                        **base_metadata,
                        "created_at": timestamp,
                    },
                )
                parent_documents.append(parent_doc)

                # 为此页面创建子文档（段落）
                child_documents: List[Document] = []
                for idx, chunk in enumerate(page_chunk_list):
                    child_chunk_id = f"{parent_chunk_id}_chunk_{idx}"
                    child_doc = Document(
                        page_content=chunk.get("text", ""),
                        metadata={
                            "chunk_id": child_chunk_id,
                            "doc_id": doc_id,
                            "title": title,
                            "category": category,
                            "page": chunk.get("page", page),
                            "position": chunk.get("position", idx),
                            "chunk_type": "child",
                            **base_metadata,
                            "created_at": timestamp,
                        },
                    )
                    child_documents.append(child_doc)

                child_documents_map[parent_chunk_id] = child_documents

            # 使用 VectorStoreService 存储父文档和子文档
            # 注意：这需要 VectorStoreService 使用正确的连接字符串初始化
            # 目前，我们使用代码库中的现有模式
            vector_store_service = VectorStoreService(settings.get_langchain_connection_string())

            await vector_store_service.add_parent_child_documents(
                parent_documents=parent_documents,
                child_documents_map=child_documents_map,
            )

        except Exception as e:
            raise KnowledgeBaseError(
                f"Failed to embed and store chunks: {str(e)}"
            )

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
