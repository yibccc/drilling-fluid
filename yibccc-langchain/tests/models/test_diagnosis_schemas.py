"""
钻井液诊断系统数据模型测试

测试诊断相关的请求/响应模型
"""

import pytest
from datetime import datetime
from uuid import uuid4

from src.models.diagnosis_schemas import (
    AlertThreshold,
    DrillingFluidSample,
    DiagnosisContext,
    DiagnosisRequest,
    TrendAnalysis,
    DiagnosisConclusion,
    TreatmentMeasure,
    Prescription,
    DiagnosisResult,
    DiagnosisEvent,
    KnowledgeDocumentResponse,
    KnowledgeSearchRequest,
)


class TestAlertThreshold:
    """AlertThreshold 模型测试"""

    def test_create_alert_threshold(self):
        """测试创建预警阈值配置"""
        threshold = AlertThreshold(
            field="density",
            condition="greater_than",
            threshold=1.30,
            current_value=1.35
        )
        assert threshold.field == "density"
        assert threshold.condition == "greater_than"
        assert threshold.threshold == 1.30
        assert threshold.current_value == 1.35


class TestDrillingFluidSample:
    """DrillingFluidSample 模型测试"""

    def test_create_sample(self):
        """测试创建采样数据"""
        sample = DrillingFluidSample(
            id="SF-001",
            well_id="WELL-001",
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
        assert sample.id == "SF-001"
        assert sample.well_id == "WELL-001"
        assert sample.density == 1.22


class TestDiagnosisContext:
    """DiagnosisContext 模型测试"""

    def test_create_context(self):
        """测试创建诊断上下文"""
        context = DiagnosisContext(
            current_depth=2500.0,
            formation_type="砂岩",
            drilling_phase="钻进",
            recent_operations=["循环", "短起"]
        )
        assert context.current_depth == 2500.0
        assert context.formation_type == "砂岩"

    def test_default_context(self):
        """测试默认上下文"""
        context = DiagnosisContext()
        assert context.current_depth is None
        assert context.formation_type is None


class TestDiagnosisRequest:
    """DiagnosisRequest 模型测试"""

    @pytest.fixture
    def sample_request_data(self):
        """测试请求数据"""
        return {
            "alert_id": "ALERT-001",
            "well_id": "WELL-001",
            "alert_type": "DENSITY_HIGH",
            "alert_triggered_at": datetime.now(),
            "alert_threshold": AlertThreshold(
                field="density",
                condition="greater_than",
                threshold=1.30,
                current_value=1.35
            ),
            "samples": [
                DrillingFluidSample(
                    id="SF-001",
                    well_id="WELL-001",
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
            "context": DiagnosisContext(
                current_depth=2500.0,
                formation_type="砂岩",
                drilling_phase="钻进"
            )
        }

    def test_create_request(self, sample_request_data):
        """测试创建诊断请求"""
        request = DiagnosisRequest(**sample_request_data)
        assert request.well_id == "WELL-001"
        assert request.alert_type == "DENSITY_HIGH"
        assert len(request.samples) == 1
        assert request.task_id.startswith("TASK-")

    def test_samples_validation_sorting(self, sample_request_data):
        """测试采样数据按时间排序（最新的在前）"""
        now = datetime.now()
        # 创建三个不同时间的样本
        sample_request_data["samples"] = [
            DrillingFluidSample(
                id="SF-001",
                well_id="WELL-001",
                sample_time=now,  # 最新
                formation="砂岩",
                outlet_temp=58.5,
                density=1.20,
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
            ),
            DrillingFluidSample(
                id="SF-002",
                well_id="WELL-001",
                sample_time=datetime.fromtimestamp(now.timestamp() - 3600),  # 1小时前
                formation="砂岩",
                outlet_temp=58.0,
                density=1.25,
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
            ),
        ]

        request = DiagnosisRequest(**sample_request_data)
        # 验证排序：最新的在前
        assert request.samples[0].id == "SF-001"
        assert request.samples[1].id == "SF-002"

    def test_samples_validation_empty(self):
        """测试空采样数据验证"""
        with pytest.raises(ValueError):  # Pydantic raises ValidationError
            DiagnosisRequest(
                well_id="WELL-001",
                alert_type="DENSITY_HIGH",
                alert_triggered_at=datetime.now(),
                alert_threshold=AlertThreshold(
                    field="density",
                    condition="greater_than",
                    threshold=1.30,
                    current_value=1.35
                ),
                samples=[]
            )


class TestTrendAnalysis:
    """TrendAnalysis 模型测试"""

    def test_create_trend_analysis(self):
        """测试创建趋势分析结果"""
        trend = TrendAnalysis(
            field="density",
            trend="rising",
            from_value=1.200,
            to_value=1.350,
            rate=0.125,
            duration="120分钟",
            acceleration="increasing"
        )
        assert trend.field == "density"
        assert trend.trend == "rising"
        assert trend.acceleration == "increasing"


class TestDiagnosisConclusion:
    """DiagnosisConclusion 模型测试"""

    def test_create_conclusion(self):
        """测试创建诊断结论"""
        conclusion = DiagnosisConclusion(
            summary="密度持续上升",
            cause="固相侵入",
            risk_level="MEDIUM",
            trend_outlook="可能继续上升"
        )
        assert conclusion.summary == "密度持续上升"
        assert conclusion.risk_level == "MEDIUM"


class TestTreatmentMeasure:
    """TreatmentMeasure 模型测试"""

    def test_create_measure(self):
        """测试创建处置措施"""
        measure = TreatmentMeasure(
            step=1,
            action="加水稀释 8%",
            duration="45分钟",
            amount="8m³",
            priority="HIGH",
            notes="持续监测密度变化"
        )
        assert measure.step == 1
        assert measure.priority == "HIGH"


class TestPrescription:
    """Prescription 模型测试"""

    def test_create_prescription(self):
        """测试创建配药方案"""
        prescription = Prescription(
            dilution_water="8%",
            viscosity_reducer="0.3%",
            mixing_time="45分钟",
            other_agents={"重晶石": "2%"}
        )
        assert prescription.dilution_water == "8%"
        assert prescription.other_agents["重晶石"] == "2%"

    def test_default_prescription(self):
        """测试默认配药方案"""
        prescription = Prescription()
        assert prescription.dilution_water is None
        assert prescription.mixing_time is None


class TestDiagnosisResult:
    """DiagnosisResult 模型测试"""

    def test_create_result(self):
        """测试创建完整诊断结果"""
        result = DiagnosisResult(
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
        assert result.diagnosis.risk_level == "MEDIUM"
        assert len(result.trend_analysis) == 1
        assert len(result.measures) == 1


class TestDiagnosisEvent:
    """DiagnosisEvent 模型测试"""

    def test_create_start_event(self):
        """测试创建开始事件"""
        event = DiagnosisEvent.start(
            task_id="TASK-001",
            well_id="WELL-001",
            samples_count=5
        )
        assert event.type == "start"
        assert event.task_id == "TASK-001"
        assert event.well_id == "WELL-001"
        assert "5 条采样数据" in event.content

    def test_create_thinking_event(self):
        """测试创建思考事件"""
        event = DiagnosisEvent.thinking(
            task_id="TASK-001",
            content="正在分析数据...",
            step="data_analysis"
        )
        assert event.type == "thinking"
        assert event.step == "data_analysis"

    def test_create_error_event(self):
        """测试创建错误事件"""
        event = DiagnosisEvent.error(
            task_id="TASK-001",
            error_code="ANALYSIS_FAILED",
            message="分析失败"
        )
        assert event.type == "error"
        assert event.error_code == "ANALYSIS_FAILED"

    def test_to_sse_format(self):
        """测试 SSE 格式转换"""
        event = DiagnosisEvent(
            type="start",
            task_id="TASK-001",
            well_id="WELL-001",
            content="开始分析"
        )
        sse = event.to_sse()
        assert sse.startswith("data: {")
        assert sse.endswith("\n\n")
        assert '"type":"start"' in sse

    def test_all_event_types(self):
        """测试所有事件类型"""
        valid_types = [
            "start", "thinking", "trend_analysis", "retrieval",
            "diagnosis", "prescription", "result", "done", "error"
        ]
        for event_type in valid_types:
            event = DiagnosisEvent(type=event_type, task_id="TASK-001")
            assert event.type == event_type


class TestKnowledgeDocumentResponse:
    """KnowledgeDocumentResponse 模型测试"""

    def test_create_document_response(self):
        """测试创建知识文档响应"""
        doc = KnowledgeDocumentResponse(
            id=str(uuid4()),
            doc_id="DOC-001",
            title="密度偏高处置措施",
            category="density",
            subcategory="high",
            content="# 密度偏高处置\n\n...",
            metadata={"author": "专家A"},
            chunk_count=5,
            created_at=datetime.now()
        )
        assert doc.doc_id == "DOC-001"
        assert doc.chunk_count == 5


class TestKnowledgeSearchRequest:
    """KnowledgeSearchRequest 模型测试"""

    def test_create_search_request(self):
        """测试创建检索请求"""
        search = KnowledgeSearchRequest(
            query="密度偏高怎么处理",
            category="density",
            top_k=5
        )
        assert search.query == "密度偏高怎么处理"
        assert search.top_k == 5

    def test_default_search_request(self):
        """测试默认检索请求"""
        search = KnowledgeSearchRequest(
            query="测试查询"
        )
        assert search.top_k == 5
        assert search.category is None

    def test_top_k_validation(self):
        """测试 top_k 范围验证"""
        # 超出范围
        with pytest.raises(ValueError):
            KnowledgeSearchRequest(
                query="测试",
                top_k=25  # 超过 le=20
            )
