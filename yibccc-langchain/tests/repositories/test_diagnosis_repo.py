"""
诊断数据仓储测试

测试诊断任务、结果、事件的数据库操作
"""

import pytest
import asyncpg
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch

from src.repositories.diagnosis_repo import DiagnosisRepository
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisResult,
    DiagnosisConclusion,
    TrendAnalysis,
    TreatmentMeasure,
    Prescription,
    AlertThreshold,
    DrillingFluidSample,
    DiagnosisContext,
)


@pytest.fixture
def mock_pool():
    """模拟数据库连接池"""
    pool = AsyncMock(spec=asyncpg.Pool)
    conn = AsyncMock(spec=asyncpg.Connection)

    # 模拟 acquire 返回连接
    pool.acquire.return_value.__aenter__.return_value = conn

    return pool, conn


@pytest.fixture
def sample_request():
    """测试诊断请求"""
    return DiagnosisRequest(
        alert_id="ALERT-TEST-001",
        task_id="TASK-TEST-001",
        well_id="WELL-TEST",
        alert_type="DENSITY_HIGH",
        alert_triggered_at=datetime.now(),
        alert_threshold=AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        ),
        samples=[
            DrillingFluidSample(
                id="SF-001",
                well_id="WELL-TEST",
                sample_time=datetime.now(),
                formation="砂岩",
                outlet_temp=58.5,
                density=1.22,
                gel_10s=3.5,
                gel_10m=8.2,
                rpm_3=5,
                rpm_6=8,
                rpm_100=45,
                rpm_200=75,
                rpm_300=95,
                rpm_600=160,
                plastic_viscosity=65,
                yield_point=15,
                flow_behavior_index=0.72,
                consistency_coefficient=2.8,
                apparent_viscosity=80,
                yield_plastic_ratio=0.23
            )
        ],
        context=DiagnosisContext(
            current_depth=2500.0,
            formation_type="砂岩",
            drilling_phase="钻进"
        )
    )


@pytest.fixture
def sample_result():
    """测试诊断结果"""
    return DiagnosisResult(
        diagnosis=DiagnosisConclusion(
            summary="密度持续上升",
            cause="固相侵入",
            risk_level="MEDIUM"
        ),
        trend_analysis=[
            TrendAnalysis(
                field="density",
                trend="rising",
                from_value=1.200,
                to_value=1.350,
                rate=0.125,
                duration="120分钟"
            )
        ],
        measures=[
            TreatmentMeasure(
                step=1,
                action="加水稀释 8%",
                priority="HIGH"
            )
        ],
        prescription=Prescription(
            dilution_water="8%",
            mixing_time="45分钟"
        ),
        references=["密度偏高处置措施文档"]
    )


class TestDiagnosisRepository:
    """DiagnosisRepository 测试"""

    def test_init(self, mock_pool):
        """测试初始化"""
        pool, _ = mock_pool
        repo = DiagnosisRepository(pool)
        assert repo.pool == pool

    # ========== 诊断任务操作测试 ==========

    @pytest.mark.asyncio
    async def test_create_task(self, mock_pool, sample_request):
        """测试创建诊断任务"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        await repo.create_task(sample_request, status="PROCESSING")

        # 验证 SQL 执行
        conn.execute.assert_called_once()
        call_args = conn.execute.call_args
        sql = call_args[0][0]
        assert "INSERT INTO diagnosis_tasks" in sql
        assert sample_request.task_id in call_args[0][1]

    @pytest.mark.asyncio
    async def test_get_task(self, mock_pool):
        """测试获取诊断任务"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        # 模拟查询结果
        mock_row = {
            "id": "uuid-123",
            "task_id": "TASK-TEST-001",
            "well_id": "WELL-TEST",
            "status": "SUCCESS",
            "created_at": datetime.now()
        }
        conn.fetchrow.return_value = mock_row

        result = await repo.get_task("TASK-TEST-001")

        # 验证 SQL 执行
        conn.fetchrow.assert_called_once()
        call_args = conn.fetchrow.call_args
        assert "SELECT * FROM diagnosis_tasks" in call_args[0][0]
        assert result["task_id"] == "TASK-TEST-001"

    @pytest.mark.asyncio
    async def test_get_task_not_found(self, mock_pool):
        """测试获取不存在的任务"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        # 模拟空结果
        conn.fetchrow.return_value = None

        result = await repo.get_task("NON-EXISTENT")

        assert result is None

    @pytest.mark.asyncio
    async def test_update_task_status(self, mock_pool):
        """测试更新任务状态"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)
        conn.execute.return_value = "UPDATE 1"

        result = await repo.update_task_status(
            "TASK-TEST-001",
            "SUCCESS",
            datetime.now()
        )

        assert result is True
        conn.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_latest_task_by_well(self, mock_pool):
        """测试获取井号最新任务"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        mock_row = {
            "task_id": "TASK-LATEST",
            "well_id": "WELL-TEST",
            "status": "PROCESSING"
        }
        conn.fetchrow.return_value = mock_row

        result = await repo.get_latest_task_by_well("WELL-TEST")

        # 验证 SQL 执行
        conn.fetchrow.assert_called_once()
        call_args = conn.fetchrow.call_args
        assert "ORDER BY created_at DESC" in call_args[0][0]
        assert result["task_id"] == "TASK-LATEST"

    # ========== 诊断结果操作测试 ==========

    @pytest.mark.asyncio
    async def test_save_result(self, mock_pool, sample_result):
        """测试保存诊断结果"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        result = await repo.save_result(
            "TASK-TEST-001",
            sample_result,
            rag_metadata={"docs_retrieved": 3},
            llm_metadata={"tokens_used": 1000}
        )

        assert result is True
        conn.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_save_result_with_upsert(self, mock_pool, sample_result):
        """测试保存诊断结果（UPSERT）"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        await repo.save_result("TASK-TEST-001", sample_result)

        # 验证 SQL 包含 ON CONFLICT
        call_args = conn.execute.call_args
        sql = call_args[0][0]
        assert "ON CONFLICT (task_id) DO UPDATE" in sql

    @pytest.mark.asyncio
    async def test_get_result(self, mock_pool):
        """测试获取诊断结果"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        mock_row = {
            "id": "uuid-123",
            "task_id": "TASK-TEST-001",
            "diagnosis": {"summary": "密度持续上升", "risk_level": "MEDIUM"},
            "trend_analysis": None,
            "measures": [{"step": 1, "action": "加水稀释"}],
            "prescription": {"dilution_water": "8%"},
            "references": None
        }
        conn.fetchrow.return_value = mock_row

        result = await repo.get_result("TASK-TEST-001")

        assert result is not None
        assert result["task_id"] == "TASK-TEST-001"

    # ========== 事件记录操作测试 ==========

    @pytest.mark.asyncio
    async def test_save_event(self, mock_pool):
        """测试保存诊断事件"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        event_data = {
            "type": "thinking",
            "content": "正在分析...",
            "step": "data_analysis"
        }

        result = await repo.save_event(
            "TASK-TEST-001",
            "thinking",
            event_data,
            sequence_num=1
        )

        assert result is True
        conn.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_events(self, mock_pool):
        """测试获取诊断事件列表"""
        pool, conn = mock_pool
        repo = DiagnosisRepository(pool)

        mock_rows = [
            {"id": "evt-1", "task_id": "TASK-TEST-001", "event_type": "start", "sequence_num": 0},
            {"id": "evt-2", "task_id": "TASK-TEST-001", "event_type": "thinking", "sequence_num": 1},
        ]
        conn.fetch.return_value = mock_rows

        events = await repo.get_events("TASK-TEST-001", limit=100)

        assert len(events) == 2
        # 验证按序号排序
        conn.fetch.assert_called_once()
        call_args = conn.fetch.call_args
        assert "ORDER BY sequence_num ASC" in call_args[0][0]


@pytest.mark.integration
class TestDiagnosisRepositoryIntegration:
    """集成测试（需要真实数据库）"""

    @pytest.fixture
    async def real_repo(self, diagnosis_pg_pool):
        """使用真实数据库连接的仓储"""
        repo = DiagnosisRepository(diagnosis_pg_pool)
        yield repo

    @pytest.mark.asyncio
    async def test_create_and_get_task(self, real_repo, sample_request):
        """测试创建并获取任务"""
        # 创建任务
        task_id = await real_repo.create_task(sample_request)

        # 获取任务
        task = await real_repo.get_task(task_id)
        assert task is not None
        assert task["task_id"] == task_id
        assert task["well_id"] == "WELL-TEST"

    @pytest.mark.asyncio
    async def test_save_and_get_result(self, real_repo, sample_result):
        """测试保存并获取结果"""
        # 先创建任务
        request = sample_request()
        await real_repo.create_task(request)

        # 保存结果
        await real_repo.save_result(request.task_id, sample_result)

        # 获取结果
        result = await real_repo.get_result(request.task_id)
        assert result is not None
