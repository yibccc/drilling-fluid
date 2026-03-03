# scripts/migrate_to_pgvector.py

import asyncio
import logging
from typing import List
from langchain_core.documents import Document

logger = logging.getLogger(__name__)


async def migrate_existing_data() -> List[Document]:
    """将现有数据迁移到 LangChain PGVector 格式"""
    from src.config import settings
    import asyncpg

    # 连接数据库
    conn = await asyncpg.connect(settings.get_langchain_connection_string())

    try:
        # 1. 读取现有文档
        rows = await conn.fetch("SELECT * FROM knowledge_documents ORDER BY created_at")
        logger.info(f"Found {len(rows)} documents to migrate")

        # 2. 转换为 LangChain Document 格式
        documents = []
        for row in rows:
            doc = Document(
                page_content=row["content"],
                metadata={
                    "doc_id": row["doc_id"],
                    "title": row["title"],
                    "category": row["category"],
                    "subcategory": row.get("subcategory"),
                    "created_at": str(row["created_at"]) if row.get("created_at") else None
                }
            )
            documents.append(doc)

        logger.info(f"Converted {len(documents)} documents to LangChain format")
        return documents

    finally:
        await conn.close()


async def run_migration():
    """执行完整迁移"""
    logger.info("Starting data migration...")

    # 1. 迁移文档
    documents = await migrate_existing_data()

    # 2. 添加到向量库
    from src.services.vector_store_service import VectorStoreService
    from src.config import settings

    vector_store = VectorStoreService(settings.get_langchain_connection_string())
    doc_ids = await vector_store.add_documents(documents)

    logger.info(f"Migration complete! Migrated {len(doc_ids)} documents")
    logger.info(f"Document IDs: {doc_ids[:5]}...")  # 显示前5个


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(run_migration())
