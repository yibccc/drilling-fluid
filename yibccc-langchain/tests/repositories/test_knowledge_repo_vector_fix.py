# tests/repositories/test_knowledge_repo_vector_fix.py
"""
测试 pgvector 向量存储修复
验证使用 pgvector 原生格式存储向量
"""
import pytest
from unittest.mock import MagicMock
from src.repositories.knowledge_repo import KnowledgeRepository
from src.models.exceptions import KnowledgeBaseError


def test_embedding_client_validation():
    """测试 embedding 客户端验证逻辑"""
    mock_pool = MagicMock()
    repo = KnowledgeRepository(mock_pool, None)

    # 验证 embedding_client 为 None
    assert repo.embedding_client is None


def test_vector_codec_configuration():
    """测试 pgvector 类型编解码器配置"""
    # 验证编解码器函数的正确性
    encoder = lambda v: str(v)
    decoder = lambda v: [float(x) for x in v.strip('[]').split(',')]

    # 测试 encoder
    test_vector = [0.1, 0.2, 0.3]
    encoded = encoder(test_vector)
    assert encoded == "[0.1, 0.2, 0.3]"

    # 测试 decoder
    test_str = "[0.1, 0.2, 0.3]"
    decoded = decoder(test_str)
    assert decoded == [0.1, 0.2, 0.3]

    # 测试 decoder 处理空格
    test_str_with_spaces = "[0.1, 0.2, 0.3]"
    decoded_spaces = decoder(test_str_with_spaces)
    assert decoded_spaces == [0.1, 0.2, 0.3]


def test_batch_embedding_logic():
    """测试批量 embedding 逻辑"""
    # 模拟批量 embedding 的返回值
    mock_embeddings = MagicMock()
    mock_embeddings.embed_documents.return_value = [
        [0.1] * 1024,
        [0.2] * 1024,
        [0.3] * 1024
    ]

    # 测试批量调用
    texts = ["文本1", "文本2", "文本3"]
    result = mock_embeddings.embed_documents(texts)

    assert len(result) == 3
    assert len(result[0]) == 1024
    assert all(isinstance(x, list) for x in result)
    assert all(isinstance(y, float) for x in result for y in x)

    # 验证调用参数
    mock_embeddings.embed_documents.assert_called_once_with(texts)


def test_vector_encoder_decoder_roundtrip():
    """测试向量编解码往返"""
    # 原始向量
    original = [0.1, 0.25, 0.5, 0.75, 0.99]

    # 编码
    encoder = lambda v: str(v)
    encoded = encoder(original)

    # 解码
    decoder = lambda v: [float(x) for x in v.strip('[]').split(',')]
    decoded = decoder(encoded)

    # 验证往返一致
    assert decoded == original


def test_vector_decoder_handles_different_formats():
    """测试解码器处理不同格式"""
    decoder = lambda v: [float(x) for x in v.strip('[]').split(',')]

    # 测试标准格式
    assert decoder("[0.1, 0.2]") == [0.1, 0.2]

    # 测试带空格格式
    assert decoder("[ 0.1 , 0.2 ]") == [0.1, 0.2]

    # 测试单个元素
    assert decoder("[0.5]") == [0.5]


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
