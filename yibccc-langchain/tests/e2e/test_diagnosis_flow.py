# tests/e2e/test_diagnosis_flow.py

"""
端到端测试 - 诊断流程

注意：这些测试需要完整的服务栈和数据库连接。
运行方式: uv run pytest tests/e2e/ -v -m e2e
"""

import pytest
import json
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch
from httpx import AsyncClient, ASGITransport
from src.api.main import app


@pytest.mark.e2e
@pytest.mark.asyncio
async def test_diagnosis_health_check():
    """测试健康检查端点"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Act
        response = await client.get("/health")

        # Assert
        assert response.status_code == 200
        data = response.json()
        assert "status" in data


@pytest.mark.e2e
@pytest.mark.asyncio
async def test_diagnosis_validation_error():
    """测试输入验证错误"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Arrange - 无效请求（空 samples 列表会导致验证错误）
        request = {
            "task_id": "test-task-002",
            "well_id": "TEST001",
            "alert_type": "密度偏高",
            "alert_triggered_at": "2025-01-03T10:00:00Z",
            "alert_threshold": {
                "field": "density",
                "condition": "greater_than",
                "threshold": 1.25,
                "current_value": 1.32
            },
            "samples": [],  # 空列表
            "context": {"category": "density"}
        }

        # Act
        response = await client.post("/api/v1/diagnosis/analyze", json=request)

        # Assert - 应该返回验证错误
        assert response.status_code == 422  # Unprocessable Entity
