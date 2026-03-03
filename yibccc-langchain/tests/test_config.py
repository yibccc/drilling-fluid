# tests/test_config.py

from src.config import settings


def test_langchain_vectorstore_config():
    """测试 LangChain 向量存储配置"""
    assert hasattr(settings, 'USE_LANGCHAIN_VECTORSTORE')
    assert isinstance(settings.USE_LANGCHAIN_VECTORSTORE, bool)


def test_langchain_connection_string():
    """测试 LangChain 连接字符串方法"""
    assert hasattr(settings, 'get_langchain_connection_string')
    conn_str = settings.get_langchain_connection_string()
    assert isinstance(conn_str, str)
    assert 'postgresql' in conn_str
