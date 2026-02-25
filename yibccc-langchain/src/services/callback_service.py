# src/services/callback_service.py
"""
回调服务

处理向 SpringBoot 后端的回调请求
"""

import logging
from typing import Optional
import asyncio

import httpx

from src.models.diagnosis_schemas import CallbackRequest
from src.models.exceptions import CallbackError
from src.config import settings

logger = logging.getLogger(__name__)


class CallbackService:
    """回调服务"""

    def __init__(self):
        self.timeout = settings.springboot_callback_timeout
        self.retry_max = settings.springboot_callback_retry_max

    async def send_callback(
        self,
        url: str,
        request: CallbackRequest
    ) -> bool:
        """发送回调请求（带重试）"""
        last_error = None

        for attempt in range(1, self.retry_max + 1):
            try:
                async with httpx.AsyncClient(timeout=self.timeout) as client:
                    response = await client.post(
                        url,
                        json=request.model_dump(mode='json', exclude_none=True),
                        headers={"Content-Type": "application/json"}
                    )

                    if response.status_code in (200, 201, 202):
                        logger.info(f"Callback succeeded: {request.task_id}")
                        return True
                    else:
                        last_error = f"HTTP {response.status_code}: {response.text}"

            except httpx.TimeoutException:
                last_error = f"Timeout after {self.timeout}s"
            except httpx.HTTPError as e:
                last_error = str(e)
            except Exception as e:
                last_error = str(e)

            # 指数退避
            if attempt < self.retry_max:
                wait_time = 5 * (2 ** (attempt - 1))  # 5s, 10s, 20s
                logger.warning(f"Callback attempt {attempt} failed: {last_error}, retrying in {wait_time}s")
                await asyncio.sleep(wait_time)

        # 所有重试失败
        logger.error(f"Callback failed after {self.retry_max} attempts: {last_error}")
        raise CallbackError(f"回调失败: {last_error}")

    async def send_callback_safe(
        self,
        url: str,
        request: CallbackRequest
    ) -> bool:
        """安全发送回调（不抛出异常）"""
        try:
            return await self.send_callback(url, request)
        except Exception as e:
            logger.error(f"Safe callback failed: {e}")
            return False
