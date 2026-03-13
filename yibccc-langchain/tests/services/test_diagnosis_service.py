"""
诊断服务测试

测试诊断服务完整流程
"""

from datetime import datetime, timedelta
from unittest.mock import AsyncMock

import pytest

from src.models.diagnosis_schemas import (
    AlertThreshold,
    DiagnosisConclusion,
    DiagnosisContext,
    DiagnosisEvent,
    DiagnosisRequest,
    DiagnosisResult,
    DrillingFluidSample,
    Prescription,
    TreatmentMeasure,
)
from src.services.diagnosis_service import DiagnosisService


@pytest.fixture
def mock_agent():
    """模拟诊断 Agent"""
    agent = AsyncMock()
    agent.initialize = AsyncMock()
    agent.cleanup = AsyncMock()
    return agent


@pytest.fixture
def mock_repo():
    """模拟数据仓储"""
    repo = AsyncMock()
    repo.create_task = AsyncMock(return_value="TASK-001")
    repo.save_event = AsyncMock()
    repo.save_result = AsyncMock()
    repo.update_task_status = AsyncMock()
    return repo


@pytest.fixture
def diagnosis_service(mock_agent, mock_repo):
    """创建诊断服务实例"""
    return DiagnosisService(
        agent=mock_agent,
        repo=mock_repo,
    )


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
            current_value=1.35,
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
                yield_plastic_ratio=0.23,
            )
        ],
        context=DiagnosisContext(
            current_depth=2500.0,
            formation_type="砂岩",
            drilling_phase="钻进",
        ),
    )


@pytest.fixture
def sample_result():
    """测试诊断结果"""
    return DiagnosisResult(
        diagnosis=DiagnosisConclusion(
            summary="密度持续上升",
            cause="固相侵入",
            risk_level="MEDIUM",
        ),
        trend_analysis=[],
        measures=[
            TreatmentMeasure(
                step=1,
                action="加水稀释",
                priority="HIGH",
            )
        ],
        prescription=Prescription(
            dilution_water="8%",
            mixing_time="45分钟",
        ),
    )


class TestDiagnosisService:
    """DiagnosisService 测试"""

    def test_init(self, mock_agent, mock_repo):
        """测试初始化"""
        service = DiagnosisService(
            agent=mock_agent,
            repo=mock_repo,
        )

        assert service.agent == mock_agent
        assert service.repo == mock_repo

    @pytest.mark.asyncio
    async def test_initialize(self, diagnosis_service, mock_agent):
        """测试服务初始化"""
        await diagnosis_service.initialize()

        mock_agent.initialize.assert_called_once()

    @pytest.mark.asyncio
    async def test_cleanup(self, diagnosis_service, mock_agent):
        """测试服务清理"""
        await diagnosis_service.cleanup()

        mock_agent.cleanup.assert_called_once()

    @pytest.mark.asyncio
    async def test_analyze_success_flow(
        self,
        diagnosis_service,
        mock_repo,
        mock_agent,
        sample_request,
        sample_result,
    ):
        """测试完整的成功分析流程"""

        async def mock_analyze(request):
            yield DiagnosisEvent.start(
                task_id=request.task_id,
                well_id=request.well_id,
                samples_count=len(request.samples),
            )
            yield DiagnosisEvent.thinking(
                task_id=request.task_id,
                content="分析中...",
                step="analysis",
            )
            yield DiagnosisEvent(
                type="result",
                task_id=request.task_id,
                result=sample_result,
            )
            yield DiagnosisEvent(
                type="done",
                task_id=request.task_id,
                status="SUCCESS",
            )

        mock_agent.analyze = mock_analyze

        events = []
        async for event in diagnosis_service.analyze(sample_request):
            events.append(event)

        mock_repo.create_task.assert_called_once_with(sample_request, status="PROCESSING")
        assert [event.type for event in events] == ["start", "thinking", "result", "done"]
        assert mock_repo.save_event.call_count == 3
        mock_repo.save_result.assert_called_once_with(sample_request.task_id, sample_result)
        mock_repo.update_task_status.assert_called_once()

    @pytest.mark.asyncio
    async def test_analyze_agent_error(
        self,
        diagnosis_service,
        mock_repo,
        mock_agent,
        sample_request,
    ):
        """测试 Agent 错误处理"""

        async def mock_analyze(request):
            yield DiagnosisEvent.start(
                task_id=request.task_id,
                well_id=request.well_id,
                samples_count=1,
            )
            raise Exception("Agent error")

        mock_agent.analyze = mock_analyze

        events = []
        async for event in diagnosis_service.analyze(sample_request):
            events.append(event)

        error_events = [event for event in events if event.type == "error"]
        assert len(error_events) == 1
        mock_repo.update_task_status.assert_called_with(sample_request.task_id, "FAILED")

    @pytest.mark.asyncio
    async def test_analyze_event_sequence_numbers(
        self,
        diagnosis_service,
        mock_repo,
        mock_agent,
        sample_request,
        sample_result,
    ):
        """测试事件序号递增"""

        async def mock_analyze(request):
            yield DiagnosisEvent.thinking(task_id=request.task_id, content="1", step="1")
            yield DiagnosisEvent.thinking(task_id=request.task_id, content="2", step="2")
            yield DiagnosisEvent(
                type="result",
                task_id=request.task_id,
                result=sample_result,
            )
            yield DiagnosisEvent(type="done", task_id=request.task_id)

        mock_agent.analyze = mock_analyze

        async for _ in diagnosis_service.analyze(sample_request):
            pass

        save_calls = mock_repo.save_event.call_args_list
        assert save_calls[0][0][3] == 0
        assert save_calls[1][0][3] == 1
        assert save_calls[2][0][3] == 2

    @pytest.mark.asyncio
    async def test_analyze_preserves_result_in_done_event(
        self,
        diagnosis_service,
        mock_agent,
        sample_request,
        sample_result,
    ):
        """测试结果事件会落库"""

        async def mock_analyze(request):
            yield DiagnosisEvent(
                type="result",
                task_id=request.task_id,
                result=sample_result,
            )
            yield DiagnosisEvent(
                type="done",
                task_id=request.task_id,
                status="SUCCESS",
            )

        mock_agent.analyze = mock_analyze

        result_captured = None

        async def mock_save_result(task_id, result):
            nonlocal result_captured
            result_captured = result

        diagnosis_service.repo.save_result = mock_save_result

        async for _ in diagnosis_service.analyze(sample_request):
            pass

        assert result_captured is not None
        assert result_captured.diagnosis.summary == "密度持续上升"


class TestDiagnosisServiceEdgeCases:
    """边缘情况测试"""

    @pytest.mark.asyncio
    async def test_empty_trend_analysis(
        self,
        diagnosis_service,
        mock_agent,
        sample_request,
    ):
        """测试空趋势分析"""
        result = DiagnosisResult(
            diagnosis=DiagnosisConclusion(
                summary="简单诊断",
                cause="原因",
                risk_level="LOW",
            ),
            trend_analysis=None,
            measures=[],
            prescription=Prescription(),
        )

        async def mock_analyze(request):
            yield DiagnosisEvent(
                type="result",
                task_id=request.task_id,
                result=result,
            )
            yield DiagnosisEvent(type="done", task_id=request.task_id)

        mock_agent.analyze = mock_analyze

        async for _ in diagnosis_service.analyze(sample_request):
            pass

        diagnosis_service.repo.save_result.assert_called_once()

    @pytest.mark.asyncio
    async def test_multiple_samples_sorting(
        self,
        diagnosis_service,
        mock_agent,
        sample_request,
    ):
        """测试多个样本的时间排序"""
        now = datetime.now()
        sample_request.samples = [
            DrillingFluidSample(
                id=f"SF-{i:03d}",
                well_id="WELL-TEST",
                sample_time=now - timedelta(hours=i),
                formation="砂岩",
                outlet_temp=58.5,
                density=1.20 + i * 0.01,
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
                yield_plastic_ratio=0.23,
            )
            for i in range(5, 0, -1)
        ]

        async def mock_analyze(request):
            assert request.samples[0].id == "SF-005"
            assert request.samples[-1].id == "SF-001"
            yield DiagnosisEvent.start(
                task_id=request.task_id,
                well_id=request.well_id,
                samples_count=5,
            )
            yield DiagnosisEvent(type="done", task_id=request.task_id)

        mock_agent.analyze = mock_analyze

        async for _ in diagnosis_service.analyze(sample_request):
            pass
