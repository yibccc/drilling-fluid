"""
FastAPI 应用入口

初始化应用和路由
"""

import asyncio
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI

from src.api.routes.chat import router as chat_router
from src.api.routes.diagnosis import router as diagnosis_router
from src.services.chat_service import chat_service
from src.services.diagnosis_service import DiagnosisService
import src.services.diagnosis_service as diagnosis_service_module
from src.services.sync_service import sync_service
from src.services.knowledge_import_consumer import KnowledgeImportConsumer
from src.repositories.pg_repo import pg_repo
from src.config import settings


# 全局变量：知识导入消费者
_knowledge_import_consumer = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    global _knowledge_import_consumer

    print("=" * 50)
    print("LIFESPAN: Starting up...")
    print("=" * 50)
    logging.info("=== LIFESPAN: Starting up ===")

    # 启动
    await pg_repo.connect()

    # 初始化 ChatService (LangGraph + Redis Checkpointer)
    await chat_service.initialize()
    logging.info("ChatService initialized with LangGraph")

    # 初始化 DiagnosisService
    from src.agents.diagnosis_agent import DiagnosisAgent
    from src.services.callback_service import CallbackService
    from src.services.vector_store_service import VectorStoreService
    from src.repositories.diagnosis_repo import DiagnosisRepository

    # 初始化 VectorStoreService
    vector_store = VectorStoreService(
        connection_string=settings.get_langchain_connection_string()
    )

    diagnosis_agent = DiagnosisAgent(
        checkpointer=None,
        vector_store_service=vector_store
    )
    diagnosis_callback = CallbackService()
    diagnosis_repo = DiagnosisRepository(pg_repo.pool)

    # 设置全局 diagnosis_service
    diagnosis_service_module.diagnosis_service = DiagnosisService(
        agent=diagnosis_agent,
        callback_service=diagnosis_callback,
        repo=diagnosis_repo
    )
    await diagnosis_service_module.diagnosis_service.initialize()
    logging.info("DiagnosisService initialized")

    # 启动同步服务 - 注入依赖
    if settings.redis_stream_sync_enabled:
        # 注入 agent 和 pg_repo 到 SyncService
        sync_service.pg_repo = pg_repo
        sync_service.graph = chat_service.agent
        asyncio.create_task(sync_service.start())
        logging.info("SyncService started")

    # 启动知识导入消费者
    _knowledge_import_consumer = KnowledgeImportConsumer(pg_repo.pool)
    asyncio.create_task(_knowledge_import_consumer.start())
    logging.info("KnowledgeImportConsumer started")

    yield
    # 关闭
    if _knowledge_import_consumer:
        await _knowledge_import_consumer.stop()
    await sync_service.stop()
    await diagnosis_service_module.diagnosis_service.cleanup()
    await chat_service.cleanup()
    await pg_repo.disconnect()
    logging.info("Application shutdown")


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    lifespan=lifespan
)

app.include_router(chat_router)
app.include_router(diagnosis_router)


@app.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "app": settings.app_name,
        "version": settings.app_version
    }
