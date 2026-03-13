# src/services/diagnosis_service.py
"""
诊断服务

整合诊断 Agent 和结果持久化
"""

import asyncio
import logging
from typing import AsyncIterator

from src.agents.diagnosis_agent import DiagnosisAgent
from src.repositories.diagnosis_repo import DiagnosisRepository
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    DiagnosisResult,
)
from datetime import datetime

logger = logging.getLogger(__name__)


# 全局服务实例
diagnosis_service = None


class DiagnosisService:
    """诊断服务类"""

    def __init__(
        self,
        agent: DiagnosisAgent,
        repo: DiagnosisRepository
    ):
        self.agent = agent
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

            # 2. 执行 Agent 分析（带超时控制）
            event_count = 0

            try:
                async with asyncio.timeout(300):  # 5分钟超时
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
                            await self.repo.save_result(
                                task_id,
                                event.result
                            )

                        # 完成
                        if event.type == "done":
                            status = event.status or "SUCCESS"
                            await self.repo.update_task_status(
                                task_id,
                                status,
                                datetime.now()
                            )

            except asyncio.TimeoutError:
                logger.error(f"Diagnosis timeout: {task_id}")
                await self.repo.update_task_status(task_id, "FAILED")
                yield DiagnosisEvent.error(
                    task_id=task_id,
                    error_code="TIMEOUT",
                    message="诊断分析超时，请稍后重试"
                )
                return

        except Exception as e:
            logger.error(f"Diagnosis analysis failed: {e}")
            await self.repo.update_task_status(task_id, "FAILED")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="DIAGNOSIS_FAILED",
                message=str(e)
            )
