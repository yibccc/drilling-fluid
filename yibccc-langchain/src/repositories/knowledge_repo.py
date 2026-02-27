# src/repositories/knowledge_repo.py
"""
知识库数据仓储

处理知识文档和向量分块的数据库操作
"""

import json
from uuid import UUID
from typing import Optional, List, Dict, Any

import asyncpg

from src.models.diagnosis_schemas import KnowledgeDocumentCreate
from src.models.exceptions import KnowledgeBaseError


class KnowledgeRepository:
    """知识库仓储类"""

    def __init__(self, pool: asyncpg.Pool, embedding_client=None):
        self.pool = pool
        self.embedding_client = embedding_client

    # ========== 知识文档操作 ==========

    async def create_document(self, doc: KnowledgeDocumentCreate) -> str:
        """创建知识文档（不含分块）"""
        async with self.pool.acquire() as conn:
            try:
                await conn.execute(
                    """
                    INSERT INTO knowledge_documents
                    (doc_id, title, category, subcategory, content, metadata)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    """,
                    doc.doc_id,
                    doc.title,
                    doc.category,
                    doc.subcategory,
                    doc.content,
                    json.dumps(doc.metadata or {})
                )
                return doc.doc_id
            except asyncpg.UniqueViolationError:
                raise KnowledgeBaseError(f"文档 {doc.doc_id} 已存在")

    async def get_document(self, doc_id: str) -> Optional[Dict[str, Any]]:
        """获取知识文档"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )
            return dict(row) if row else None

    async def delete_document(self, doc_id: str) -> bool:
        """删除知识文档及其分块"""
        async with self.pool.acquire() as conn:
            # 先删除分块
            await conn.execute(
                "DELETE FROM knowledge_chunks WHERE parent_doc_id = $1",
                doc_id
            )
            # 再删除文档
            result = await conn.execute(
                "DELETE FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )
            return result == "DELETE 1"

    async def list_documents(
        self,
        category: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """列出知识文档"""
        async with self.pool.acquire() as conn:
            if category:
                rows = await conn.fetch(
                    """
                    SELECT * FROM knowledge_documents
                    WHERE category = $1
                    ORDER BY created_at DESC
                    LIMIT $2
                    """,
                    category,
                    limit
                )
            else:
                rows = await conn.fetch(
                    """
                    SELECT * FROM knowledge_documents
                    ORDER BY created_at DESC
                    LIMIT $1
                    """,
                    limit
                )
            return [dict(row) for row in rows]

    # ========== 向量分块操作 ==========

    async def create_chunks(
        self,
        doc_id: str,
        chunks: List[Dict[str, Any]]
    ) -> int:
        """创建文档分块（含向量）- 使用 pgvector 原生格式"""
        if not self.embedding_client:
            raise KnowledgeBaseError("Embedding 客户端未配置")

        async with self.pool.acquire() as conn:
            # 注册 pgvector 类型编解码器 (vector 类型在 public schema)
            await conn.set_type_codec(
                'vector',
                encoder=lambda v: str(v),  # list[float] -> vector string
                decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
                schema='public',
                format='text'
            )

            async with conn.transaction():
                # 删除旧分块
                await conn.execute(
                    "DELETE FROM knowledge_chunks WHERE parent_doc_id = $1",
                    doc_id
                )

                # 批量生成 embedding（优化性能）
                texts = [chunk["content"] for chunk in chunks]
                embeddings = await self._embed_batch(texts)

                # 批量插入
                for idx, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
                    content = chunk["content"]
                    # 直接传递向量列表，让编解码器处理
                    await conn.execute(
                        """
                        INSERT INTO knowledge_chunks
                        (parent_doc_id, chunk_index, content, embedding)
                        VALUES ($1, $2, $3, $4)
                        """,
                        doc_id,
                        idx,
                        content,
                        embedding  # list[float]
                    )

                # 更新文档的分块计数
                await conn.execute(
                    "UPDATE knowledge_documents SET chunk_count = $1 WHERE doc_id = $2",
                    len(chunks),
                    doc_id
                )

        return len(chunks)

    async def _embed_batch(self, texts: List[str]) -> List[List[float]]:
        """批量生成 embedding（性能优化）"""
        import asyncio

        def sync_embed_batch():
            # 使用 LangChain 的批量 embedding
            return self.embedding_client.embed_documents(texts)

        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, sync_embed_batch)

    async def vector_search(
        self,
        query: str,
        top_k: int = 5,
        category: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """向量搜索 - 使用原生格式"""
        if not self.embedding_client:
            raise KnowledgeBaseError("Embedding 客户端未配置")

        # 生成查询向量
        query_embedding = await self._embed_text(query)

        async with self.pool.acquire() as conn:
            # 确保类型编解码器已注册
            await conn.set_type_codec(
                'vector',
                encoder=lambda v: str(v),
                decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
                schema='pg_catalog',
                format='text'
            )

            if category:
                rows = await conn.fetch(
                    """
                    SELECT DISTINCT ON (kd.doc_id)
                        kd.doc_id, kd.title, kd.category, kd.content,
                        MIN(kc.embedding <-> $1) as distance
                    FROM knowledge_documents kd
                    JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                    WHERE kd.category = $2
                    GROUP BY kd.doc_id, kd.title, kd.category, kd.content
                    ORDER BY distance
                    LIMIT $3
                    """,
                    query_embedding,  # 直接传递 list
                    category,
                    top_k
                )
            else:
                rows = await conn.fetch(
                    """
                    SELECT DISTINCT ON (kd.doc_id)
                        kd.doc_id, kd.title, kd.category, kd.content,
                        MIN(kc.embedding <-> $1) as distance
                    FROM knowledge_documents kd
                    JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                    GROUP BY kd.doc_id, kd.title, kd.category, kd.content
                    ORDER BY distance
                    LIMIT $2
                    """,
                    query_embedding,
                    top_k
                )

            return [dict(row) for row in rows]

    async def _embed_text(self, text: str) -> List[float]:
        """生成文本 embedding"""
        if not self.embedding_client:
            raise KnowledgeBaseError("Embedding 客户端未配置")

        # 使用 LangChain OpenAIEmbeddings 客户端
        # 注意：embed_query 是同步方法，需要在异步上下文中正确调用
        import asyncio

        def sync_embed():
            return self.embedding_client.embed_query(text)

        # 在线程池中执行同步操作
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, sync_embed)

    async def get_chunks_by_doc(self, doc_id: str) -> List[Dict[str, Any]]:
        """获取文档的所有分块"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT chunk_index, content
                FROM knowledge_chunks
                WHERE parent_doc_id = $1
                ORDER BY chunk_index
                """,
                doc_id
            )
            return [dict(row) for row in rows]
