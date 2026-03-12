"""
API 依赖注入

处理请求上下文
"""

import os
from typing import Optional

from fastapi import Header, HTTPException, status
from src.config import settings


# 内部 API Key（在 .env 中配置）
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "")


async def get_user_id(
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-Api-Key"),
    x_api_key: Optional[str] = Header(None, alias="X-API-Key")
) -> str:
    """获取用户 ID：优先使用内部 API Key，其次使用用户 API Key"""
    # 内部 API Key 验证
    if INTERNAL_API_KEY and x_internal_api_key == INTERNAL_API_KEY:
        return "internal"  # 内部调用

    # 正常用户 API Key 验证
    if not x_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing authentication"
        )

    if not settings.validate_api_key(x_api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API Key"
        )

    # 简化实现：使用 API Key 作为 user_id
    # 生产环境应使用用户系统
    return f"user:{x_api_key[:8]}"
