"""
API 依赖注入

处理 API Key 验证和请求上下文
"""

from fastapi import Header, HTTPException, status
from src.config import settings
from src.models.exceptions import AuthenticationError


async def verify_api_key(x_api_key: str = Header(..., alias="X-API-Key")) -> str:
    """验证 API Key"""
    if not settings.validate_api_key(x_api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API Key"
        )
    return x_api_key


async def get_user_id(x_api_key: str = Header(..., alias="X-API-Key")) -> str:
    """从 API Key 获取用户 ID（简化版，实际应从数据库或 JWT 解析）"""
    if not settings.validate_api_key(x_api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid API Key"
        )

    # 简化实现：使用 API Key 作为 user_id
    # 生产环境应使用用户系统
    return f"user:{x_api_key[:8]}"
