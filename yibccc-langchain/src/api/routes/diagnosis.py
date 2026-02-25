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
    CallbackRequest,
    KnowledgeDocumentCreate,
    KnowledgeDocumentResponse,
    KnowledgeSearchRequest,
)
from src.models.exceptions import AppException
import src.services.diagnosis_service as diagnosis_service_module
from src.services.rag_service import RAGService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/diagnosis", tags=["diagnosis"])


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

@router.post("/knowledge/documents", response_model=dict)
async def create_knowledge_document(
    doc: KnowledgeDocumentCreate,
    user_id: str = Depends(get_user_id)
):
    """创建知识文档"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    doc_id = await diagnosis_service_module.diagnosis_service.rag_service.create_document(doc)
    return {"doc_id": doc_id, "status": "created"}


@router.get("/knowledge/documents/{doc_id}")
async def get_knowledge_document(
    doc_id: str,
    user_id: str = Depends(get_user_id)
):
    """获取知识文档"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    doc = await diagnosis_service_module.diagnosis_service.rag_service.get_document(doc_id)
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    return doc


@router.delete("/knowledge/documents/{doc_id}")
async def delete_knowledge_document(
    doc_id: str,
    user_id: str = Depends(get_user_id)
):
    """删除知识文档"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    success = await diagnosis_service_module.diagnosis_service.rag_service.delete_document(doc_id)
    if not success:
        raise HTTPException(status_code=404, detail="Document not found")
    return {"status": "deleted"}


@router.post("/knowledge/search")
async def search_knowledge(
    request: KnowledgeSearchRequest,
    user_id: str = Depends(get_user_id)
):
    """语义检索知识库"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    results = await diagnosis_service_module.diagnosis_service.rag_service.search(
        query=request.query,
        top_k=request.top_k,
        category=request.category
    )
    return {"results": results}


@router.post("/knowledge/rebuild")
async def rebuild_knowledge_index(
    doc_id: str = None,
    user_id: str = Depends(get_user_id)
):
    """重建向量索引"""
    if not diagnosis_service_module.diagnosis_service:
        raise HTTPException(status_code=503, detail="Diagnosis service not initialized")

    result = await diagnosis_service_module.diagnosis_service.rag_service.rebuild_index(doc_id)
    return result
