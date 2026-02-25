# src/services/diagnosis_service.py
"""
诊断服务

整合诊断 Agent、RAG 服务和回调服务
"""

import logging
from typing import AsyncIterator, Optional

from src.agents.diagnosis_agent import DiagnosisAgent
from src.services.rag_service import RAGService
from src.services.callback_service import CallbackService
from src.repositories.diagnosis_repo import DiagnosisRepository
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    DiagnosisResult,
    CallbackRequest,
)
from src.models.exceptions import DiagnosisError
from src.config import settings
from datetime import datetime

logger = logging.getLogger(__name__)


# 全局服务实例
diagnosis_service = None


class DiagnosisService:
    """诊断服务类"""

    def __init__(
        self,
        agent: DiagnosisAgent,
        rag_service: RAGService,
        callback_service: CallbackService,
        repo: DiagnosisRepository
    ):
        self.agent = agent
        self.rag_service = rag_service
        self.callback_service = callback_service
        self.repo = repo

    async def initialize(self):
        """初始化服务"""
        await self.agent.initialize()
        logger.info("DiagnosisService initialized")

    async def cleanup(self):
        """清理资源"""
        await self.agent.cleanup()
        logger.info("DiagnosisService cleaned up")

    async def analyze(
        self,
        request: DiagnosisRequest
    ) -> AsyncIterator[DiagnosisEvent]:
        """执行诊断分析（完整流程）"""
        task_id = request.task_id

        try:
            # 1. 创建任务记录
            await self.repo.create_task(request, status="PROCESSING")

            # 2. 执行 Agent 分析
            result: Optional[DiagnosisResult] = None
            event_count = 0

            async for event in self.agent.analyze(request):
                # 发送事件
                yield event

                # 记录事件到数据库
                if event.type != "start":
                    # 使用 model_dump_json 避免 datetime 序列化问题
                    import json
                    event_data = json.loads(event.model_dump_json(exclude_none=True))
                    await self.repo.save_event(
                        task_id,
                        event.type,
                        event_data,
                        event_count
                    )
                    event_count += 1

                # 保存结果
                if event.type == "result" and event.result:
                    result = event.result
                    await self.repo.save_result(
                        task_id,
                        result
                    )

                # 完成
                if event.type == "done":
                    status = event.status or "SUCCESS"
                    await self.repo.update_task_status(
                        task_id,
                        status,
                        datetime.now()
                    )

            # 3. 发送回调（如果有回调地址）
            if request.callback_url and result:
                await self._send_callback(
                    request.callback_url,
                    task_id,
                    request.well_id,
                    result
                )

        except Exception as e:
            logger.error(f"Diagnosis analysis failed: {e}")
            await self.repo.update_task_status(task_id, "FAILED")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="DIAGNOSIS_FAILED",
                message=str(e)
            )

    async def _send_callback(
        self,
        callback_url: str,
        task_id: str,
        well_id: str,
        result: DiagnosisResult
    ):
        """发送结果回调"""
        callback_req = CallbackRequest(
            task_id=task_id,
            well_id=well_id,
            status="SUCCESS",
            completed_at=datetime.now(),
            result=result
        )

        success = await self.callback_service.send_callback_safe(
            callback_url,
            callback_req
        )

        if not success:
            logger.warning(f"Callback to {callback_url} failed, but result was saved")
