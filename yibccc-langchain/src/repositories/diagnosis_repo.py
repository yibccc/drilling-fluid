# src/repositories/diagnosis_repo.py
"""
诊断系统数据仓储

处理诊断任务、结果、事件的数据库操作
"""

import json
from uuid import UUID, uuid4
from datetime import datetime
from typing import Optional, List, Dict, Any

import asyncpg


def json_serializer(obj: Any) -> Any:
    """自定义 JSON 序列化器，处理 datetime 等特殊类型"""
    if isinstance(obj, datetime):
        return obj.isoformat()
    elif isinstance(obj, UUID):
        return str(obj)
    raise TypeError(f"Type {type(obj)} not serializable")

from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisResult,
    DiagnosisEvent,
)
from src.models.exceptions import AppException


class DiagnosisRepository:
    """诊断数据仓储类"""

    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    # ========== 诊断任务操作 ==========

    async def create_task(
        self,
        request: DiagnosisRequest,
        status: str = "PENDING"
    ) -> str:
        """创建诊断任务"""
        task_id = request.task_id
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_tasks
                (task_id, well_id, alert_type, alert_triggered_at,
                 alert_threshold, samples, context, callback_url, status, started_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                """,
                task_id,
                request.well_id,
                request.alert_type,
                request.alert_triggered_at,
                json.dumps(request.alert_threshold.model_dump()),
                json.dumps([s.model_dump(mode='json') for s in request.samples]),
                json.dumps(request.context.model_dump(mode='json') if request.context else {}),
                request.callback_url,
                status,
                datetime.now()
            )
        return task_id

    async def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取诊断任务"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM diagnosis_tasks WHERE task_id = $1",
                task_id
            )
            return dict(row) if row else None

    async def update_task_status(
        self,
        task_id: str,
        status: str,
        completed_at: Optional[datetime] = None
    ) -> bool:
        """更新任务状态"""
        async with self.pool.acquire() as conn:
            result = await conn.execute(
                """
                UPDATE diagnosis_tasks
                SET status = $1, completed_at = $2
                WHERE task_id = $3
                """,
                status,
                completed_at or datetime.now(),
                task_id
            )
            return result == "UPDATE 1"

    async def get_latest_task_by_well(self, well_id: str) -> Optional[Dict[str, Any]]:
        """获取井号最新的诊断任务"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT * FROM diagnosis_tasks
                WHERE well_id = $1
                ORDER BY created_at DESC
                LIMIT 1
                """,
                well_id
            )
            return dict(row) if row else None

    # ========== 诊断结果操作 ==========

    async def save_result(
        self,
        task_id: str,
        result: DiagnosisResult,
        rag_metadata: Optional[Dict[str, Any]] = None,
        llm_metadata: Optional[Dict[str, Any]] = None
    ) -> bool:
        """保存诊断结果"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_results
                (task_id, diagnosis, trend_analysis, measures, prescription,
                 "references", rag_metadata, llm_metadata)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (task_id) DO UPDATE
                SET diagnosis = $2, trend_analysis = $3, measures = $4,
                    prescription = $5, "references" = $6,
                    rag_metadata = $7, llm_metadata = $8
                """,
                task_id,
                json.dumps(result.diagnosis.model_dump(), default=json_serializer),
                json.dumps([t.model_dump() for t in result.trend_analysis], default=json_serializer) if result.trend_analysis else None,
                json.dumps([m.model_dump() for m in result.measures], default=json_serializer),
                json.dumps(result.prescription.model_dump(), default=json_serializer),
                json.dumps(result.references, default=json_serializer) if result.references else None,
                json.dumps(rag_metadata or {}, default=json_serializer),
                json.dumps(llm_metadata or {}, default=json_serializer)
            )
        return True

    async def get_result(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取诊断结果"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT * FROM diagnosis_results WHERE task_id = $1",
                task_id
            )
            return dict(row) if row else None

    # ========== 事件记录操作 ==========

    async def save_event(
        self,
        task_id: str,
        event_type: str,
        event_data: Dict[str, Any],
        sequence_num: int
    ) -> bool:
        """保存诊断事件"""
        async with self.pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO diagnosis_events
                (task_id, event_type, event_data, sequence_num)
                VALUES ($1, $2, $3, $4)
                """,
                task_id,
                event_type,
                json.dumps(event_data),
                sequence_num
            )
        return True

    async def get_events(
        self,
        task_id: str,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """获取诊断事件列表"""
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT * FROM diagnosis_events
                WHERE task_id = $1
                ORDER BY sequence_num ASC
                LIMIT $2
                """,
                task_id,
                limit
            )
            return [dict(row) for row in rows]
