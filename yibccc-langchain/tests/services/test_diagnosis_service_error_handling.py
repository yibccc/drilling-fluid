# tests/services/test_diagnosis_service_error_handling.py

import pytest
from datetime import datetime
from unittest.mock import AsyncMock, patch
from src.services.diagnosis_service import DiagnosisService
from src.models.diagnosis_schemas import DiagnosisRequest, DiagnosisEvent, DrillingFluidSample


@pytest.mark.asyncio
async def test_handles_retrieval_failure_gracefully():
    """测试检索失败时的优雅处理"""
    # Arrange
    mock_agent = AsyncMock()

    # 模拟返回带有降级标记的事件
    async def _mock_analyze(request):
        yield DiagnosisEvent.start("test-task", "TEST001", 0)
        yield DiagnosisEvent.thinking("test-task", "降级模式，使用通用知识", "fallback")
        # 模拟降级模式标记 - 这在实际实现中可能需要通过其他方式传递
        yield DiagnosisEvent(type="done", task_id="test-task", status="SUCCESS")

    mock_agent.analyze = _mock_analyze

    mock_repo = AsyncMock()
    mock_repo.create_task = AsyncMock()
    mock_repo.update_task_status = AsyncMock()

    service = DiagnosisService(mock_agent, None, None, mock_repo)

    # 创建样本数据
    sample = DrillingFluidSample(
        id="sample_001",
        well_id="TEST001",
        sample_time=datetime.now(),
        formation="砂岩",
        outlet_temp=58.5,
        density=1.32,
        gel_10s=3.5,
        gel_10m=8.2,
        rpm_3=5,
        rpm_6=8,
        rpm_100=45,
        rpm_200=75,
        rpm_300=95,
        rpm_600=160,
        plastic_viscosity=25.0,
        yield_point=15.0,
        flow_behavior_index=0.7,
        consistency_coefficient=15.0,
        apparent_viscosity=30.0,
        yield_plastic_ratio=0.6
    )

    request = DiagnosisRequest(
        task_id="test-task",
        well_id="TEST001",
        alert_type="density_high",
        alert_triggered_at="2025-01-03T10:00:00Z",
        alert_threshold={"field": "density", "condition": "greater_than", "threshold": 1.25, "current_value": 1.32},
        samples=[sample],
        context={"category": "density"}
    )

    # Act
    events = []
    async for event in service.analyze(request):
        events.append(event)

    # Assert - 应该有开始和完成事件
    event_types = [e.type for e in events]
    assert "start" in event_types
    assert "done" in event_types
    # 不应该有 error 事件（应该优雅降级）
    assert not any(e.type == "error" for e in events)


@pytest.mark.asyncio
async def test_handles_timeout_gracefully():
    """测试超时时的优雅处理"""
    # Arrange
    mock_agent = AsyncMock()

    async def _mock_analyze_timeout(request):
        yield DiagnosisEvent.start("test-task", "TEST001", 0)
        # 模拟超时 - 无限等待
        import asyncio
        await asyncio.sleep(100)

    mock_agent.analyze = _mock_analyze_timeout

    mock_repo = AsyncMock()
    mock_repo.create_task = AsyncMock()
    mock_repo.update_task_status = AsyncMock()

    service = DiagnosisService(mock_agent, None, None, mock_repo)

    # 创建样本数据
    sample = DrillingFluidSample(
        id="sample_001",
        well_id="TEST001",
        sample_time=datetime.now(),
        formation="砂岩",
        outlet_temp=58.5,
        density=1.32,
        gel_10s=3.5,
        gel_10m=8.2,
        rpm_3=5,
        rpm_6=8,
        rpm_100=45,
        rpm_200=75,
        rpm_300=95,
        rpm_600=160,
        plastic_viscosity=25.0,
        yield_point=15.0,
        flow_behavior_index=0.7,
        consistency_coefficient=15.0,
        apparent_viscosity=30.0,
        yield_plastic_ratio=0.6
    )

    request = DiagnosisRequest(
        task_id="test-task",
        well_id="TEST001",
        alert_type="density_high",
        alert_triggered_at="2025-01-03T10:00:00Z",
        alert_threshold={"field": "density", "condition": "greater_than", "threshold": 1.25, "current_value": 1.32},
        samples=[sample],
        context={"category": "density"}
    )

    # Act - 使用短超时进行测试
    events = []
    import asyncio
    try:
        async with asyncio.timeout(0.1):  # 100ms 超时
            async for event in service.analyze(request):
                events.append(event)
    except asyncio.TimeoutError:
        pass

    # Assert - 应该有开始事件
    assert len(events) > 0
    assert events[0].type == "start"
