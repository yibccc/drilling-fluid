"""
回调服务测试

测试向 SpringBoot 后端的回调请求
"""

import pytest
from unittest.mock import AsyncMock, patch
from datetime import datetime

from src.services.callback_service import CallbackService
from src.models.diagnosis_schemas import CallbackRequest, DiagnosisResult, DiagnosisConclusion, Prescription, TreatmentMeasure
from src.models.exceptions import CallbackError


@pytest.fixture
def callback_service():
    """创建回调服务实例"""
    return CallbackService()


@pytest.fixture
def sample_callback_request():
    """测试回调请求"""
    return CallbackRequest(
        task_id="TASK-TEST-001",
        well_id="WELL-TEST",
        status="SUCCESS",
        completed_at=datetime.now(),
        result=DiagnosisResult(
            diagnosis=DiagnosisConclusion(
                summary="密度持续上升",
                cause="固相侵入",
                risk_level="MEDIUM"
            ),
            measures=[],
            prescription=Prescription()
        )
    )


class TestCallbackService:
    """CallbackService 测试"""

    def test_init(self):
        """测试初始化"""
        service = CallbackService()
        assert service.timeout == 30  # 默认值
        assert service.retry_max == 3

    @pytest.mark.asyncio
    async def test_send_callback_success(self, callback_service, sample_callback_request):
        """测试成功发送回调"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 200
            mock_response.text = "OK"

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            result = await callback_service.send_callback(
                "http://example.com/callback",
                sample_callback_request
            )

            assert result is True
            mock_client.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_send_callback_202_accepted(self, callback_service, sample_callback_request):
        """测试回调返回 202（已接受）"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 202

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            result = await callback_service.send_callback(
                "http://example.com/callback",
                sample_callback_request
            )

            assert result is True

    @pytest.mark.asyncio
    async def test_send_callback_timeout_then_retry(self, callback_service, sample_callback_request):
        """测试超时后重试成功"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 200

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client

            # 第一次超时，第二次成功
            call_count = [0]
            async def mock_post(*args, **kwargs):
                call_count[0] += 1
                if call_count[0] == 1:
                    raise Exception("Timeout")
                return mock_response

            mock_client.post = mock_post
            mock_client_class.return_value = mock_client

            with patch("asyncio.sleep") as mock_sleep:
                result = await callback_service.send_callback(
                    "http://example.com/callback",
                    sample_callback_request
                )

                assert result is True
                # 验证重试了一次
                assert call_count[0] == 2
                # 验证等待时间（第一次重试 5 秒）
                mock_sleep.assert_called_once_with(5)

    @pytest.mark.asyncio
    async def test_send_callback_all_retries_failed(self, callback_service, sample_callback_request):
        """测试所有重试都失败"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(side_effect=Exception("Connection refused"))
            mock_client_class.return_value = mock_client

            with pytest.raises(CallbackError, match="回调失败"):
                await callback_service.send_callback(
                    "http://example.com/callback",
                    sample_callback_request
                )

            # 验证重试了 3 次
            assert mock_client.post.call_count == 3

    @pytest.mark.asyncio
    async def test_send_callback_http_error(self, callback_service, sample_callback_request):
        """测试 HTTP 错误响应"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 500
            mock_response.text = "Internal Server Error"

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            with pytest.raises(CallbackError, match="回调失败"):
                await callback_service.send_callback(
                    "http://example.com/callback",
                    sample_callback_request
                )

    @pytest.mark.asyncio
    async def test_send_callback_safe_success(self, callback_service, sample_callback_request):
        """测试安全回调成功"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 200

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            result = await callback_service.send_callback_safe(
                "http://example.com/callback",
                sample_callback_request
            )

            assert result is True

    @pytest.mark.asyncio
    async def test_send_callback_safe_failure(self, callback_service, sample_callback_request):
        """测试安全回调失败（不抛出异常）"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(side_effect=Exception("Error"))
            mock_client_class.return_value = mock_client

            result = await callback_service.send_callback_safe(
                "http://example.com/callback",
                sample_callback_request
            )

            assert result is False
            # 不会抛出异常

    @pytest.mark.asyncio
    async def test_exponential_backoff_timing(self, callback_service, sample_callback_request):
        """测试指数退避时序"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(side_effect=Exception("Error"))
            mock_client_class.return_value = mock_client

            sleep_calls = []
            async def mock_sleep(duration):
                sleep_calls.append(duration)

            with patch("asyncio.sleep", side_effect=mock_sleep):
                with pytest.raises(CallbackError):
                    await callback_service.send_callback(
                        "http://example.com/callback",
                        sample_callback_request
                    )

            # 验证等待时间: 5s, 10s (第三次失败后不需要等待)
            assert sleep_calls == [5, 10]

    @pytest.mark.asyncio
    async def test_request_json_serialization(self, callback_service, sample_callback_request):
        """测试请求 JSON 序列化"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 200

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            await callback_service.send_callback(
                "http://example.com/callback",
                sample_callback_request
            )

            # 验证请求体
            call_args = mock_client.post.call_args
            json_data = call_args.kwargs.get("json", call_args[1].get("json"))
            assert json_data is not None
            assert json_data["task_id"] == "TASK-TEST-001"
            assert json_data["well_id"] == "WELL-TEST"
            assert json_data["status"] == "SUCCESS"

    @pytest.mark.asyncio
    async def test_request_headers(self, callback_service, sample_callback_request):
        """测试请求头设置"""
        with patch("httpx.AsyncClient") as mock_client_class:
            mock_response = AsyncMock()
            mock_response.status_code = 200

            mock_client = AsyncMock()
            mock_client.__aenter__.return_value = mock_client
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_client_class.return_value = mock_client

            await callback_service.send_callback(
                "http://example.com/callback",
                sample_callback_request
            )

            # 验证请求头
            call_args = mock_client.post.call_args
            headers = call_args.kwargs.get("headers", call_args[1].get("headers"))
            assert headers is not None
            assert headers["Content-Type"] == "application/json"
