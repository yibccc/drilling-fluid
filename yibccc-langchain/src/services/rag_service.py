# src/services/rag_service.py
"""
RAG 检索增强生成服务

提供知识库检索和文档管理功能
"""

import logging
from typing import List, Dict, Any, Optional

from langchain_community.embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from src.repositories.knowledge_repo import KnowledgeRepository
from src.models.diagnosis_schemas import KnowledgeDocumentCreate, KnowledgeSearchRequest
from src.models.exceptions import RAGError
from src.config import settings

logger = logging.getLogger(__name__)


class RAGService:
    """RAG 检索服务"""

    def __init__(self, knowledge_repo: KnowledgeRepository):
        self.knowledge_repo = knowledge_repo
        self.embeddings = None
        self._init_embeddings()

    def _init_embeddings(self):
        """初始化 Embedding 客户端 (通义千问 DashScope)"""
        try:
            self.embeddings = DashScopeEmbeddings(
                model=settings.embedding_model,
                dashscope_api_key=settings.dashscope_api_key,
            )
            # 将 embeddings 注入到 repo
            self.knowledge_repo.embedding_client = self.embeddings
            logger.info(f"RAG service initialized with {settings.embedding_model}")
        except Exception as e:
            logger.warning(f"Failed to initialize embeddings: {e}")

    # ========== 文档管理 ==========

    async def create_document(
        self,
        doc: KnowledgeDocumentCreate,
        auto_chunk: bool = True
    ) -> str:
        """创建知识文档（自动分块和向量化）"""
        try:
            # 1. 创建文档记录
            doc_id = await self.knowledge_repo.create_document(doc)
            logger.info(f"Created document: {doc_id}")

            # 2. 自动分块和向量化
            if auto_chunk:
                chunks = self._split_text(doc.content)
                await self.knowledge_repo.create_chunks(doc_id, chunks)
                logger.info(f"Created {len(chunks)} chunks for {doc_id}")

            return doc_id
        except Exception as e:
            logger.error(f"Failed to create document: {e}")
            raise RAGError(f"创建文档失败: {str(e)}")

    def _split_text(self, text: str) -> List[Dict[str, Any]]:
        """文本分块（父子分块策略）"""
        # 子分块器：用于向量检索（小块，高精度）
        child_splitter = RecursiveCharacterTextSplitter(
            chunk_size=600,
            chunk_overlap=100,
            length_function=len,
        )

        chunks = []
        for idx, chunk in enumerate(child_splitter.split_text(text)):
            chunks.append({
                "content": chunk
            })

        return chunks

    async def get_document(self, doc_id: str) -> Optional[Dict[str, Any]]:
        """获取知识文档"""
        return await self.knowledge_repo.get_document(doc_id)

    async def delete_document(self, doc_id: str) -> bool:
        """删除知识文档"""
        return await self.knowledge_repo.delete_document(doc_id)

    async def list_documents(
        self,
        category: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """列出知识文档"""
        return await self.knowledge_repo.list_documents(category, limit)

    # ========== 语义检索 ==========

    async def search(
        self,
        query: str,
        top_k: int = 5,
        category: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """语义检索"""
        try:
            results = await self.knowledge_repo.vector_search(
                query=query,
                top_k=top_k,
                category=category
            )

            # 格式化结果
            formatted = []
            for r in results:
                formatted.append({
                    "doc_id": r["doc_id"],
                    "title": r["title"],
                    "category": r["category"],
                    "content": r["content"],
                    "distance": float(r.get("distance", 0))
                })

            return formatted
        except Exception as e:
            logger.error(f"Search failed: {e}")
            raise RAGError(f"检索失败: {str(e)}")

    # ========== 重建索引 ==========

    async def rebuild_index(self, doc_id: Optional[str] = None) -> Dict[str, int]:
        """重建向量索引"""
        # 如果指定 doc_id，只重建该文档
        if doc_id:
            doc = await self.get_document(doc_id)
            if not doc:
                raise RAGError(f"文档 {doc_id} 不存在")

            chunks = self._split_text(doc["content"])
            await self.knowledge_repo.create_chunks(doc_id, chunks)
            return {"rebuilt": 1}

        # 否则重建所有文档
        docs = await self.list_documents(limit=1000)
        count = 0
        for doc in docs:
            try:
                chunks = self._split_text(doc["content"])
                await self.knowledge_repo.create_chunks(doc["doc_id"], chunks)
                count += 1
            except Exception as e:
                logger.error(f"Failed to rebuild {doc['doc_id']}: {e}")

        return {"rebuilt": count}
