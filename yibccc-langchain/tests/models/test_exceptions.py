"""测试异常类"""
import pytest
from src.models.exceptions import (
    AppException,
    AuthenticationError,
    LLMError,
)


def test_app_exception():
    """测试基础异常"""
    exc = AppException("基础错误")
    assert str(exc) == "基础错误"
    assert isinstance(exc, Exception)


def test_authentication_error():
    """测试认证错误"""
    exc = AuthenticationError("Invalid API Key")
    assert str(exc) == "Invalid API Key"
    assert isinstance(exc, AppException)


def test_llm_error():
    """测试 LLM 错误"""
    exc = LLMError("DeepSeek API 超时")
    assert isinstance(exc, AppException)
