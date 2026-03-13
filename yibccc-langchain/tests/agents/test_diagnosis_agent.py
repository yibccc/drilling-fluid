# tests/agents/test_diagnosis_agent.py

import pytest
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch
from src.agents.diagnosis_agent import DiagnosisAgent
from src.agents.diagnosis_middleware import RetrievalMiddleware
from src.models.diagnosis_schemas import DiagnosisRequest, AlertThreshold, DrillingFluidSample


@pytest.mark.asyncio
async def test_agent_uses_middleware():
    """测试 Agent 正确使用中间件"""
    # Arrange
    mock_vector_store = AsyncMock()
    mock_vector_store.similarity_search = AsyncMock(return_value=[])

    agent = DiagnosisAgent(checkpointer=None, vector_store_service=mock_vector_store)

    # Act - 调用 _build_agent 来初始化中间件
    with patch('src.agents.diagnosis_agent.create_agent') as mock_create_agent:
        agent._build_agent()

    # Assert
    assert agent.retrieval_middleware is not None
    assert isinstance(agent.retrieval_middleware, RetrievalMiddleware)
    _, kwargs = mock_create_agent.call_args
    assert kwargs["middleware"] == [agent.retrieval_middleware]


@pytest.mark.asyncio
async def test_analyze_uses_streamed_final_state_without_extra_ainvoke():
    """测试 analyze 从流式状态获取最终结果，不再额外调用 ainvoke"""
    structured_output = MagicMock(
        summary="密度偏高",
        cause="固相增加",
        risk_level="MEDIUM",
        trend_outlook="继续观察",
        trend_analysis=[],
        measures=[],
        prescription=MagicMock(
            dilution_water="8%",
            viscosity_reducer=None,
            mixing_time="45分钟",
            other_agents=None,
        ),
    )

    agent = DiagnosisAgent(checkpointer=None, vector_store_service=None)
    agent.agent = AsyncMock()

    async def fake_astream(*args, **kwargs):
        yield "messages", (MagicMock(text="分析中"), {})
        yield "values", {"structured_response": structured_output}

    agent.agent.astream = fake_astream
    agent.agent.ainvoke = AsyncMock()

    request = DiagnosisRequest(
        alert_id="alert-001",
        task_id="TASK-001",
        well_id="well-001",
        alert_type="density_high",
        alert_triggered_at=datetime(2024, 1, 1, 10, 0, 0),
        alert_threshold=AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        ),
        samples=[
            DrillingFluidSample(
                id="sample-001",
                well_id="well-001",
                sample_time=datetime(2024, 1, 1, 10, 0, 0),
                formation="砂岩",
                outlet_temp=80.0,
                density=1.25,
                gel_10s=3.0,
                gel_10m=8.0,
                rpm_3=5.0,
                rpm_6=8.0,
                rpm_100=45.0,
                rpm_200=80.0,
                rpm_300=120.0,
                rpm_600=180.0,
                plastic_viscosity=15.0,
                yield_point=8.0,
                flow_behavior_index=0.8,
                consistency_coefficient=50.0,
                apparent_viscosity=45.0,
                yield_plastic_ratio=0.53
            )
        ],
        context=None,
        stream=True
    )

    events = []
    async for event in agent.analyze(request):
        events.append(event)

    assert [event.type for event in events][-2:] == ["result", "done"]
    agent.agent.ainvoke.assert_not_called()


def test_build_analysis_prompt_allows_none_context():
    """测试 context 为空时也能正常构建提示词"""
    agent = DiagnosisAgent(checkpointer=None, vector_store_service=None)

    request = DiagnosisRequest(
        alert_id="alert-001",
        task_id="TASK-001",
        well_id="well-001",
        alert_type="density_high",
        alert_triggered_at=datetime(2024, 1, 1, 10, 0, 0),
        alert_threshold=AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        ),
        samples=[
            DrillingFluidSample(
                id="sample-001",
                well_id="well-001",
                sample_time=datetime(2024, 1, 1, 10, 0, 0),
                formation="砂岩",
                outlet_temp=80.0,
                density=1.25,
                gel_10s=3.0,
                gel_10m=8.0,
                rpm_3=5.0,
                rpm_6=8.0,
                rpm_100=45.0,
                rpm_200=80.0,
                rpm_300=120.0,
                rpm_600=180.0,
                plastic_viscosity=15.0,
                yield_point=8.0,
                flow_behavior_index=0.8,
                consistency_coefficient=50.0,
                apparent_viscosity=45.0,
                yield_plastic_ratio=0.53
            )
        ],
        context=None,
        stream=True
    )

    prompt = agent._build_analysis_prompt(request)

    assert "当前深度: 未提供" in prompt
    assert "岩性: 未提供" in prompt
    assert "钻井阶段: 未提供" in prompt
    assert "自动注入的知识库上下文" in prompt
    assert "search_knowledge" not in prompt
