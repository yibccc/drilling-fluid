# src/services/vector_store_service.py

import logging
from typing import List, Optional
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
        )
        logger.info(f"VectorStoreService initialized with collection: knowledge_docs")

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
