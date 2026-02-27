# tests/services/test_knowledge_import_consumer.py
"""
测试知识库导入消费者
"""
import pytest
from unittest.mock import MagicMock, AsyncMock
from src.services.knowledge_import_consumer import KnowledgeImportConsumer


def test_parent_chunk_creation():
    """测试父分块创建"""
    mock_pool = MagicMock()
    consumer = KnowledgeImportConsumer(mock_pool)

    # 测试短文本（单段）
    text = "This is a short paragraph."
    chunks = consumer._create_parent_chunks(text)
    assert len(chunks) == 1
    assert chunks[0] == text

    # 测试多段文本
    # 注意：由于段落很短，会被合并
    text = "Paragraph 1\n\nParagraph 2\n\nParagraph 3"
    chunks = consumer._create_parent_chunks(text)
    # 短段落会被合并成一个块
    assert len(chunks) >= 1
    assert all("Paragraph" in chunk for chunk in chunks)


def test_parent_chunk_merging():
    """测试父分块合并小段落"""
    mock_pool = MagicMock()
    consumer = KnowledgeImportConsumer(mock_pool)

    # 创建多个小段落（应该被合并）
    paragraphs = "\n\n".join([f"Small paragraph {i}" for i in range(10)])
    chunks = consumer._create_parent_chunks(paragraphs)

    # 由于每个段落都很短，应该被合并成少数几个父块
    assert len(chunks) < 10
    assert all(len(chunk) > 0 for chunk in chunks)


def test_parent_chunk_size_limit():
    """测试父分块大小限制"""
    mock_pool = MagicMock()
    consumer = KnowledgeImportConsumer(mock_pool)

    # 创建一个很长的文本（超过 3000 字符）
    long_text = "A" * 4000 + "\n\n" + "B" * 4000
    chunks = consumer._create_parent_chunks(long_text)

    # 应该被分成多个块
    assert len(chunks) >= 2


def test_create_chunks_structure():
    """测试分块结构"""
    mock_pool = MagicMock()
    consumer = KnowledgeImportConsumer(mock_pool)

    text = "First paragraph\n\nSecond paragraph\n\nThird paragraph"

    # Mock _create_parent_chunks 返回固定结果
    consumer._create_parent_chunks = lambda t: ["First paragraph", "Second paragraph", "Third paragraph"]

    # _create_chunks 现在是同步方法
    chunks = consumer._create_chunks(text)

    # 验证结构
    assert len(chunks) > 0
    assert all('content' in c for c in chunks)
    assert all('parent_index' in c for c in chunks)
    assert all('chunk_index' in c for c in chunks)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
