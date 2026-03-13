"""
API 依赖测试
"""

import pytest
from fastapi import HTTPException

from src.api.dependencies import get_user_id


@pytest.mark.asyncio
async def test_get_user_id_requires_auth_when_internal_key_missing():
    """配置内部 Key 时，没有内部头也不能回退到 dev-user"""
    with pytest.raises(HTTPException) as exc_info:
        await get_user_id(x_internal_api_key=None, x_api_key=None)

    assert exc_info.value.status_code == 401
    assert exc_info.value.detail == "Missing authentication"
