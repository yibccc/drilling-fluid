"""
诊断路由测试

测试钻井液诊断分析 API 端点
"""

import pytest
import json
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient
from fastapi import FastAPI

from src.api.routes.diagnosis import router
from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    AlertThreshold,
    DrillingFluidSample,
    DiagnosisContext,
    DiagnosisEvent,
    DiagnosisResult,
    DiagnosisConclusion,
    TreatmentMeasure,
    Prescription,
)


@pytest.fixture
def app():
    """创建测试应用"""
    app = FastAPI()
    app.include_router(router)
    return app


@pytest.fixture
def client(app):
    """创建测试客户端"""
    return TestClient(app)


@pytest.fixture
def sample_request_data():
    """测试请求数据"""
    now = datetime.now()
    return {
        "well_id": "WELL-TEST",
        "alert_type": "DENSITY_HIGH",
        "alert_triggered_at": now.isoformat(),
        "alert_threshold": {
            "field": "density",
            "condition": "greater_than",
            "threshold": 1.30,
            "current_value": 1.35
        },
        "samples": [
            {
                "id": "SF-001",
                "well_id": "WELL-TEST",
                "sample_time": now.isoformat(),
                "formation": "砂岩",
                "outlet_temp": 58.5,
                "density": 1.22,
                "gel_10s": 3.5,
                "gel_10m": 8.2,
                "rpm_3": 5,
                "rpm_6": 8,
                "rpm_100": 45,
                "rpm_200": 75,
                "rpm_300": 95,
                "rpm_600": 160,
                "plastic_viscosity": 65,
                "yield_point": 15,
                "flow_behavior_index": 0.72,
                "consistency_coefficient": 2.8,
                "apparent_viscosity": 80,
                "yield_plastic_ratio": 0.23
            }
        ],
        "context": {
            "current_depth": 2500.0,
            "formation_type": "砂岩",
            "drilling_phase": "钻进"
        },
        "stream": True
    }


class TestDiagnosisRoutes:
    """诊断路由测试"""

    def test_router_prefix(self):
        """测试路由前缀"""
        assert router.prefix == "/api/v1/diagnosis"
        assert router.tags == ["diagnosis"]

    @pytest.mark.asyncio
    async def test_analyze_diagnosis_missing_service(self, client, sample_request_data):
        """测试诊断服务未初始化"""
        with patch("src.api.routes.diagnosis.diagnosis_service", None):
            response = client.post(
                "/api/v1/diagnosis/analyze",
                json=sample_request_data,
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 503
            assert "not initialized" in response.json()["detail"]

    @pytest.mark.asyncio
    async def test_analyze_diagnosis_sse_stream(self, client, sample_request_data):
        """测试 SSE 流式响应"""
        # 模拟服务
        mock_service = AsyncMock()

        async def mock_analyze(request):
            yield DiagnosisEvent.start(
                task_id=request.task_id,
                well_id=request.well_id,
                samples_count=len(request.samples)
            )
            yield DiagnosisEvent(
                type="done",
                task_id=request.task_id,
                status="SUCCESS"
            )

        mock_service.analyze = mock_analyze

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/analyze",
                json=sample_request_data,
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            assert response.headers["content-type"] == "text/event-stream; charset=utf-8"

    @pytest.mark.asyncio
    async def test_analyze_diagnosis_sse_content(self, client, sample_request_data):
        """测试 SSE 事件内容"""
        mock_service = AsyncMock()

        async def mock_analyze(request):
            yield DiagnosisEvent.start(
                task_id="TASK-001",
                well_id="WELL-TEST",
                samples_count=1
            )
            yield DiagnosisEvent.thinking(
                task_id="TASK-001",
                content="分析中...",
                step="analysis"
            )
            yield DiagnosisEvent(
                type="done",
                task_id="TASK-001",
                status="SUCCESS"
            )

        mock_service.analyze = mock_analyze

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/analyze",
                json=sample_request_data,
                headers={"X-API-Key": "test-key"}
            )

            content = response.text
            # 验证 SSE 格式
            assert "data:" in content
            assert '"type":"start"' in content
            assert '"type":"thinking"' in content
            assert '"type":"done"' in content

    @pytest.mark.asyncio
    async def test_analyze_diagnosis_error_event(self, client, sample_request_data):
        """测试错误事件处理"""
        mock_service = AsyncMock()

        async def mock_analyze(request):
            from src.models.exceptions import DiagnosisError
            raise DiagnosisError("分析失败")

        mock_service.analyze = mock_analyze

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/analyze",
                json=sample_request_data,
                headers={"X-API-Key": "test-key"}
            )

            content = response.text
            assert '"type":"error"' in content

    @pytest.mark.asyncio
    async def test_get_diagnosis_result_not_found(self, client):
        """测试获取不存在的诊断结果"""
        mock_service = AsyncMock()
        mock_service.repo = AsyncMock()
        mock_service.repo.get_task = AsyncMock(return_value=None)

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.get(
                "/api/v1/diagnosis/NON-EXISTENT",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 404

    @pytest.mark.asyncio
    async def test_get_diagnosis_result_success(self, client):
        """测试成功获取诊断结果"""
        mock_service = AsyncMock()
        mock_service.repo = AsyncMock()

        mock_task = {
            "task_id": "TASK-001",
            "well_id": "WELL-TEST",
            "status": "SUCCESS"
        }
        mock_result = {
            "task_id": "TASK-001",
            "diagnosis": {"summary": "密度上升", "risk_level": "MEDIUM"}
        }

        mock_service.repo.get_task = AsyncMock(return_value=mock_task)
        mock_service.repo.get_result = AsyncMock(return_value=mock_result)

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.get(
                "/api/v1/diagnosis/TASK-001",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["task"]["task_id"] == "TASK-001"
            assert data["result"]["diagnosis"]["summary"] == "密度上升"


class TestKnowledgeManagementRoutes:
    """知识库管理路由测试"""

    @pytest.mark.asyncio
    async def test_create_knowledge_document(self, client):
        """测试创建知识文档"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()

        doc_data = {
            "doc_id": "DOC-001",
            "title": "密度偏高处置",
            "category": "density",
            "subcategory": "high",
            "content": "# 密度偏高\n\n密度偏高时...",
            "metadata": {"author": "专家A"}
        }

        mock_rag.create_document = AsyncMock(return_value="DOC-001")
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/knowledge/documents",
                json=doc_data,
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["doc_id"] == "DOC-001"
            assert data["status"] == "created"

    @pytest.mark.asyncio
    async def test_get_knowledge_document(self, client):
        """测试获取知识文档"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()

        mock_doc = {
            "doc_id": "DOC-001",
            "title": "密度偏高处置",
            "category": "density",
            "content": "测试内容",
            "chunk_count": 3
        }

        mock_rag.get_document = AsyncMock(return_value=mock_doc)
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.get(
                "/api/v1/diagnosis/knowledge/documents/DOC-001",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["doc_id"] == "DOC-001"

    @pytest.mark.asyncio
    async def test_get_knowledge_document_not_found(self, client):
        """测试获取不存在的文档"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()
        mock_rag.get_document = AsyncMock(return_value=None)
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.get(
                "/api/v1/diagnosis/knowledge/documents/NON-EXISTENT",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_knowledge_document(self, client):
        """测试删除知识文档"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()
        mock_rag.delete_document = AsyncMock(return_value=True)
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.delete(
                "/api/v1/diagnosis/knowledge/documents/DOC-001",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["status"] == "deleted"

    @pytest.mark.asyncio
    async def test_delete_knowledge_document_not_found(self, client):
        """测试删除不存在的文档"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()
        mock_rag.delete_document = AsyncMock(return_value=False)
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.delete(
                "/api/v1/diagnosis/knowledge/documents/NON-EXISTENT",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 404

    @pytest.mark.asyncio
    async def test_search_knowledge(self, client):
        """测试语义检索"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()

        mock_results = [
            {
                "doc_id": "DOC-001",
                "title": "密度偏高处置",
                "category": "density",
                "content": "测试内容",
                "distance": 0.123
            }
        ]

        mock_rag.search = AsyncMock(return_value=mock_results)
        mock_service.rag_service = mock_rag

        search_request = {
            "query": "密度偏高怎么处理",
            "category": "density",
            "top_k": 5
        }

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/knowledge/search",
                json=search_request,
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert len(data["results"]) == 1
            assert data["results"][0]["doc_id"] == "DOC-001"

    @pytest.mark.asyncio
    async def test_rebuild_knowledge_index(self, client):
        """测试重建向量索引"""
        mock_service = AsyncMock()
        mock_rag = AsyncMock()

        mock_rag.rebuild_index = AsyncMock(return_value={"rebuilt": 5})
        mock_service.rag_service = mock_rag

        with patch("src.api.routes.diagnosis.diagnosis_service", mock_service):
            response = client.post(
                "/api/v1/diagnosis/knowledge/rebuild",
                headers={"X-API-Key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["rebuilt"] == 5

    @pytest.mark.asyncio
    async def test_callback_endpoint(self, client):
        """测试回调端点"""
        callback_data = {
            "task_id": "TASK-001",
            "well_id": "WELL-001",
            "status": "SUCCESS"
        }

        response = client.post(
            "/api/v1/diagnosis/callback",
            json=callback_data,
            headers={"X-API-Key": "test-key"}
        )

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "callback_received"
        assert data["task_id"] == "TASK-001"


class TestRequestValidation:
    """请求验证测试"""

    def test_invalid_api_key(self, client, sample_request_data):
        """测试无效 API Key"""
        response = client.post(
            "/api/v1/diagnosis/analyze",
            json=sample_request_data,
            headers={"X-API-Key": "invalid-key"}
        )

        assert response.status_code == 401

    def test_missing_api_key(self, client, sample_request_data):
        """测试缺少 API Key"""
        response = client.post(
            "/api/v1/diagnosis/analyze",
            json=sample_request_data
        )

        assert response.status_code == 401

    def test_invalid_json_schema(self, client):
        """测试无效的 JSON Schema"""
        response = client.post(
            "/api/v1/diagnosis/analyze",
            json={"invalid": "data"},
            headers={"X-API-Key": "test-key"}
        )

        assert response.status_code == 422  # Validation error

    def test_empty_samples(self, client):
        """测试空采样数据"""
        request_data = {
            "well_id": "WELL-TEST",
            "alert_type": "DENSITY_HIGH",
            "alert_triggered_at": datetime.now().isoformat(),
            "alert_threshold": {
                "field": "density",
                "condition": "greater_than",
                "threshold": 1.30,
                "current_value": 1.35
            },
            "samples": []  # 空数组
        }

        response = client.post(
            "/api/v1/diagnosis/analyze",
            json=request_data,
            headers={"X-API-Key": "test-key"}
        )

        assert response.status_code == 422
