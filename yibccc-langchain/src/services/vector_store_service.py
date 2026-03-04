# src/services/vector_store_service.py

import logging
from typing import List, Optional, Dict
from langchain_postgres import PGVector
from langchain_community.embeddings import DashScopeEmbeddings
from langchain_core.documents import Document
from src.config import settings

logger = logging.getLogger(__name__)


class VectorStoreService:
    """向量存储服务 - 使用 LangChain PGVector 集成"""

    def __init__(self, connection_string: str):
        self.embeddings = DashScopeEmbeddings(
            model=settings.embedding_model,
            dashscope_api_key=settings.dashscope_api_key,
        )

        # LangChain PGVector 自动处理表结构
        self.vector_store = PGVector(
            embeddings=self.embeddings,
            collection_name="knowledge_docs",
            connection=connection_string,
            use_jsonb=True,
            async_mode=True,
        )
        logger.info(f"VectorStoreService 已初始化，使用 collection: knowledge_docs")

    async def add_documents(self, docs: List[Document]) -> List[str]:
        """添加文档到向量库"""
        logger.info(f"Adding {len(docs)} documents to vector store")
        return await self.vector_store.aadd_documents(docs)

    async def similarity_search(
        self,
        query: str,
        k: int = 5,
        filter: Optional[dict] = None
    ) -> List[Document]:
        """语义检索"""
        logger.debug(f"Searching: query='{query}', k={k}, filter={filter}")
        return await self.vector_store.asimilarity_search(query, k=k, filter=filter)

    async def delete(self, ids: List[str]) -> bool:
        """删除文档"""
        return await self.vector_store.adelete(ids)

    async def add_parent_child_documents(
        self,
        parent_documents: List[Document],
        child_documents_map: Dict[str, List[Document]],
    ) -> Dict[str, List[str]]:
        """添加父文档及其子分块

        Args:
            parent_documents: 父文档列表（页面）
            child_documents_map: 键映射 parent_id -> 子分块列表

        Returns:
            字典，映射 parent_id -> 插入的分块 ID 列表
        """
        result: Dict[str, List[str]] = {}

        # 首先添加所有父文档
        if parent_documents:
            parent_ids = await self.vector_store.aadd_documents(parent_documents)
            for i, doc in enumerate(parent_documents):
                parent_id = doc.metadata.get("chunk_id") or parent_ids[i]
                result[parent_id] = []

        # 然后添加子文档，建立父关系
        for parent_id, child_docs in child_documents_map.items():
            if not child_docs:
                continue

            # 为每个子文档设置元数据中的 parent_chunk_id
            for doc in child_docs:
                doc.metadata["parent_chunk_id"] = parent_id

            child_ids = await self.vector_store.aadd_documents(child_docs)

            # 跟踪插入的 ID
            for i, doc in enumerate(child_docs):
                chunk_id = doc.metadata.get("chunk_id") or child_ids[i]
                if parent_id not in result:
                    result[parent_id] = []
                result[parent_id].append(chunk_id)

        return result

    async def query_by_parent_chunk(
        self,
        parent_chunk_id: str,
        limit: int = 100,
    ) -> List[Document]:
        """查询给定父分块的所有子分块

        Args:
            parent_chunk_id: 父分块 ID
            limit: 最大结果数

        Returns:
            子文档分块列表
        """
        # 使用元数据过滤器查询 parent_chunk_id
        docs = await self.vector_store.asimilarity_search(
            query="query",  # 避免某些模型空字符串报错
            k=limit,
            filter={"parent_chunk_id": parent_chunk_id},
        )

        return docs

    async def query_by_doc_id(
        self,
        doc_id: str,
        limit: int = 1000,
    ) -> List[Document]:
        """查询文档的所有分块

        Args:
            doc_id: 文档 ID
            limit: 最大结果数

        Returns:
            所有文档分块列表（包括父分块和子分块）
        """
        # 使用元数据过滤器查询 doc_id
        docs = await self.vector_store.asimilarity_search(
            query="query",  # 避免某些模型空字符串报错
            k=limit,
            filter={"doc_id": doc_id},
        )

        return docs

    async def get_document_by_chunk_id(self, chunk_id: str) -> Optional[Document]:
        """通过 chunk_id 获取文档"""
        docs = await self.vector_store.asimilarity_search(
            query="query", # 避免某些模型空字符串报错
            k=1,
            filter={"chunk_id": chunk_id}
        )
        return docs[0] if docs else None

    async def delete_by_doc_id(self, doc_id: str) -> int:
        """删除文档的所有分块

        Args:
            doc_id: 文档 ID

        Returns:
            删除的分块数量
        """
        # 首先查询该文档的所有分块以获取它们的 ID
        docs = await self.query_by_doc_id(doc_id)

        if not docs:
            return 0

        # 从元数据中提取分块 ID
        chunk_ids = []
        for doc in docs:
            chunk_id = doc.metadata.get("chunk_id")
            if chunk_id:
                chunk_ids.append(chunk_id)

        # 按照 ID 删除
        if chunk_ids:
            await self.delete(chunk_ids)

        return len(chunk_ids)
