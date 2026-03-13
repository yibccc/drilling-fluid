# tests/services/test_knowledge_import_consumer.py
"""
测试知识库导入消费者
"""

import pytest
from unittest.mock import AsyncMock

from src.services.knowledge_import_consumer import KnowledgeImportConsumer


@pytest.mark.asyncio
async def test_process_import_retries_before_marking_failed():
    """失败时应重试到达上限后再标记 FAILED 并 ACK"""
    redis_client = AsyncMock()
    consumer = KnowledgeImportConsumer(redis_client=redis_client)

    consumer._create_chunks = lambda text: [{"text": text, "position": 0, "page": 1}]
    consumer._update_import_status = AsyncMock()
    consumer._embed_and_store_chunks = AsyncMock(side_effect=RuntimeError("embedding failed"))

    data = {
        b"doc_id": b"DOC-001",
        b"title": b"Title",
        b"content": b"content",
        b"category": b"default",
        b"metadata": b"{}",
    }

    await consumer._process_import("1-0", data)

    assert consumer._embed_and_store_chunks.await_count == 3
    consumer._update_import_status.assert_any_await("DOC-001", "RETRYING", error="embedding failed")
    consumer._update_import_status.assert_any_await("DOC-001", "FAILED", error="embedding failed")
    redis_client.xack.assert_awaited_once_with(
        consumer.stream_name,
        consumer.consumer_group,
        "1-0",
    )


@pytest.mark.asyncio
async def test_process_import_succeeds_on_retry_without_failed_status():
    """重试成功时不应落到 FAILED"""
    redis_client = AsyncMock()
    consumer = KnowledgeImportConsumer(redis_client=redis_client)

    consumer._create_chunks = lambda text: [{"text": text, "position": 0, "page": 1}]
    consumer._update_import_status = AsyncMock()

    attempts = {"count": 0}

    async def flaky_embed(**kwargs):
        attempts["count"] += 1
        if attempts["count"] < 2:
            raise RuntimeError("transient failure")

    consumer._embed_and_store_chunks = AsyncMock(side_effect=flaky_embed)

    data = {
        b"doc_id": b"DOC-002",
        b"title": b"Title",
        b"content": b"content",
        b"category": b"default",
        b"metadata": b"{}",
    }

    await consumer._process_import("2-0", data)

    assert attempts["count"] == 2
    consumer._update_import_status.assert_any_await("DOC-002", "RETRYING", error="transient failure")
    consumer._update_import_status.assert_any_await("DOC-002", "COMPLETED", chunk_count=1)
    failed_calls = [
        call.args[1]
        for call in consumer._update_import_status.await_args_list
        if len(call.args) > 1
    ]
    assert "FAILED" not in failed_calls


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
