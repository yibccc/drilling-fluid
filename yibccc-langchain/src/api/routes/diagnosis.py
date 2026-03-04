# src/api/routes/diagnosis.py
"""
诊断路由

处理钻井液诊断分析 API 端点
"""

import logging
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from typing import AsyncIterator
from datetime import datetime, timezone

from src.api.dependencies import get_user_id
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    CallbackRequest,
    KnowledgeDocumentCreate,
)
from src.models.exceptions import AppException
import src.services.diagnosis_service as diagnosis_service_module
from src.services.vector_store_service import VectorStoreService
from src.config import settings
from sqlalchemy import make_url
from langchain_core.documents import Document

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/diagnosis", tags=["diagnosis"])


def get_vector_store() -> VectorStoreService:
    """获取 VectorStoreService 实例"""
    db_url = make_url(settings.database_url)
    return VectorStoreService(connection_string=str(db_url))


async def sse_generator(events: AsyncIterator[DiagnosisEvent]) -> AsyncIterator[str]:
    """SSE 事件生成器"""
    try:
        async for event in events:
            yield event.to_sse()
    except AppException as e:
        yield DiagnosisEvent.error(
            task_id="",
            error_code=e.code,
            message=e.message
        ).to_sse()


@router.post("/analyze")
async def analyze_diagnosis(
    request: DiagnosisRequest,
    user_id: str = Depends(get_user_id)
):
    """预警诊断分析接口（SSE 流式）"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    return StreamingResponse(
        sse_generator(diagnosis_service_module.diagnosis_service.analyze(request)),
        media_type="text/event-stream"
    )


@router.post("/callback")
async def diagnosis_callback(
    callback: CallbackRequest,
    user_id: str = Depends(get_user_id)
):
    """结果回调接口（SpringBoot 调用）"""
    # 这个端点用于 SpringBoot 主动查询或确认回调结果
    # 实际回调由 diagnosis_service 发送
    return {
        "status": "callback_received",
        "task_id": callback.task_id
    }


@router.get("/{task_id}")
async def get_diagnosis_result(
    task_id: str,
    user_id: str = Depends(get_user_id)
):
    """查询诊断任务状态和结果"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    task = await diagnosis_service_module.diagnosis_service.repo.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    result = await diagnosis_service_module.diagnosis_service.repo.get_result(task_id)

    return {
        "task": task,
        "result": result
    }


# ========== 知识库管理端点 ==========

@router.post("/knowledge/documents")
async def create_knowledge_document(
    doc: KnowledgeDocumentCreate,
    user_id: str = Depends(get_user_id)
):
    """创建知识文档切片（同步创建）"""
    vector_store = get_vector_store()

    # 使用 text splitter 对内容进行分块
    from src.utils import create_text_splitter
    splitter = create_text_splitter()
    chunks = splitter.split_text(doc.content)

    # 为每个分块创建 Document 对象
    documents = []
    for idx, chunk_text in enumerate(chunks):
        document = Document(
            page_content=chunk_text,
            metadata={
                "doc_id": doc.doc_id,
                "title": doc.title,
                "category": doc.category,
                "author": doc.author,
                "created_by": user_id,
                "chunk_index": idx,
                "chunk_type": "child",
                "created_at": datetime.now(timezone.utc).isoformat(),
            }
        )
        documents.append(document)

    # 添加到向量库
    await vector_store.add_documents(documents)

    return {
        "doc_id": doc.doc_id,
        "chunk_count": len(chunks),
        "status": "created"
    }


@router.delete("/knowledge/documents/{doc_id}")
async def delete_knowledge_document(
    doc_id: str,
    user_id: str = Depends(get_user_id)
):
    """删除知识文档切片"""
    vector_store = get_vector_store()

    # 删除该文档的所有 chunks
    deleted_count = await vector_store.delete_by_doc_id(doc_id)

    return {
        "doc_id": doc_id,
        "deleted_count": deleted_count,
        "status": "deleted"
    }
