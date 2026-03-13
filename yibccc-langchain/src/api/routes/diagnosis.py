# src/api/routes/diagnosis.py
"""
诊断路由

处理钻井液诊断分析 API 端点
"""

import logging
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from typing import AsyncIterator

from src.api.dependencies import get_user_id
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    KnowledgeSearchRequest,
)
from src.models.exceptions import AppException
import src.services.diagnosis_service as diagnosis_service_module
from src.services.vector_store_service import VectorStoreService
from src.config import settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/diagnosis", tags=["diagnosis"])


def get_vector_store() -> VectorStoreService:
    """获取 VectorStoreService 实例"""
    return VectorStoreService(connection_string=settings.get_langchain_connection_string())


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


@router.post("/knowledge/search")
async def search_knowledge(
    request: KnowledgeSearchRequest,
    user_id: str = Depends(get_user_id)
):
    """知识召回调试接口"""
    vector_store = get_vector_store()

    retrieval_filter = {"chunk_type": "child"}
    if request.category:
        retrieval_filter["category"] = request.category
    if request.doc_id:
        retrieval_filter["doc_id"] = request.doc_id

    docs = await vector_store.similarity_search(
        query=request.query,
        k=request.top_k,
        filter=retrieval_filter
    )

    results = []
    for doc in docs:
        metadata = doc.metadata or {}
        results.append({
            "doc_id": metadata.get("doc_id"),
            "title": metadata.get("title"),
            "category": metadata.get("category"),
            "chunk_id": metadata.get("chunk_id"),
            "parent_chunk_id": metadata.get("parent_chunk_id"),
            "chunk_type": metadata.get("chunk_type"),
            "content_preview": doc.page_content[:300],
        })

    return {
        "query": request.query,
        "doc_id": request.doc_id,
        "category": request.category,
        "top_k": request.top_k,
        "results": results
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
