#!/usr/bin/env python3
"""
知识导入性能基准测试

测试指标：
- 单文档导入 (100 chunks) - 目标: 旧架构性能
- 批量导入 (1000 docs) - 目标: 旧架构性能
- 语义检索 latency - 目标: 旧架构性能

用法：
    python benchmarks/knowledge_import_benchmark.py
"""

import asyncio
import sys
import time
import statistics
from pathlib import Path
from typing import Dict, Any, List
from datetime import datetime

# 添加项目路径
sys.path.insert(0, str(Path(__file__).parent.parent))

import httpx
from langchain_core.documents import Document

# 配置
API_BASE_URL = "http://localhost:8000"
API_KEY = "test-key"

# 性能基线（旧架构预期值，可根据实际情况调整）
BASELINE_METRICS = {
    "single_doc_100_chunks_ms": 5000,  # 5秒
    "batch_10_docs_ms": 15000,  # 15秒
    "similarity_search_ms": 200,  # 200ms
}


async def measure_time_async(func, *args, **kwargs):
    """测量异步函数执行时间"""
    start = time.time()
    result = await func(*args, **kwargs)
    elapsed = time.time() - start
    return result, elapsed * 1000  # 返回毫秒


def generate_test_document(
    chunk_count: int = 10,
    title: str = "测试文档",
    category: str = "test"
) -> Dict[str, Any]:
    """生成测试文档"""
    # 生成包含多个段落的文档
    paragraphs = []
    for i in range(chunk_count):
        paragraphs.append(f"""
        第{i+1}段内容

        钻井液是钻井过程中的循环流体，具有多种功能：
        1. 冷却和润滑钻头
        2. 携带岩屑到地面
        3. 稳定井壁，防止坍塌
        4. 平衡地层压力
        5. 提供井眼信息

        密度是钻井液的重要参数，通常在1.0-2.0 g/cm³之间。
        当密度偏高时，应加水稀释；当密度偏低时，应加入重晶石等加重材料。

        粘度控制钻井液的流动性，需要根据钻进速度和井深进行调整。
        """)

    content = "\n".join(paragraphs)

    return {
        "doc_id": f"BENCHMARK-{datetime.now().strftime('%YMS%d%H%M%S')}-{title}",
        "title": title,
        "category": category,
        "content": content,
        "metadata": {
            "benchmark": True,
            "chunk_count": chunk_count,
            "created_at": datetime.now().isoformat()
        }
    }


async def upload_document(doc_data: Dict[str, Any]) -> dict:
    """上传单个文档"""
    url = f"{API_BASE_URL}/api/v1/diagnosis/knowledge/documents"
    headers = {"X-API-Key": API_KEY}

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(url, json=doc_data, headers=headers)

    if response.status_code in (200, 201):
        return response.json()
    else:
        print(f"  ❌ 上传失败: {response.status_code}")
        print(f"     响应: {response.text[:200]}")
        return {}


async def search_knowledge(query: str, top_k: int = 5) -> List[dict]:
    """执行知识检索"""
    url = f"{API_BASE_URL}/api/v1/diagnosis/knowledge/search"
    headers = {"X-API-Key": API_KEY}
    data = {"query": query, "top_k": top_k}

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(url, json=data, headers=headers)

    if response.status_code == 200:
        result = response.json()
        return result.get("results", [])
    else:
        print(f"  ❌ 检索失败: {response.status_code}")
        return []


async def benchmark_single_document_import():
    """基准测试：单文档导入 (100 chunks)"""
    print("\n" + "=" * 70)
    print("基准测试 1: 单文档导入 (100 chunks)")
    print("=" * 70)

    doc_data = generate_test_document(
        chunk_count=100,
        title="单文档性能测试",
        category="benchmark"
    )

    print(f"文档大小: {len(doc_data['content'])} 字符")
    print(f"预期分块数: 100")

    _, elapsed_ms = await measure_time_async(upload_document, doc_data)

    print(f"\n✅ 导入完成")
    print(f"   耗时: {elapsed_ms:.2f} ms ({elapsed_ms/1000:.2f} 秒)")
    print(f"   吞吐量: {len(doc_data['content'])/(elapsed_ms/1000):.0f} 字符/秒")

    baseline = BASELINE_METRICS["single_doc_100_chunks_ms"]
    ratio = elapsed_ms / baseline

    print(f"   基线: {baseline} ms")
    print(f"   比率: {ratio:.2f}x {'✓' if ratio <= 1.5 else '✗'}")

    # 等待向量索引
    await asyncio.sleep(3)

    # 验证检索
    print(f"\n   验证检索...")
    _, search_ms = await measure_time_async(search_knowledge, "钻井液密度", 5)
    print(f"   检索耗时: {search_ms:.2f} ms")

    return {
        "single_doc_100_chunks_ms": elapsed_ms,
        "similarity_search_ms": search_ms
    }


async def benchmark_batch_import():
    """基准测试：批量导入 (10 docs)"""
    print("\n" + "=" * 70)
    print("基准测试 2: 批量导入 (10 docs)")
    print("=" * 70)

    batch_size = 10
    documents = []

    for i in range(batch_size):
        doc_data = generate_test_document(
            chunk_count=50,
            title=f"批量测试文档-{i+1}",
            category="benchmark"
        )
        documents.append(doc_data)

    total_chars = sum(len(d['content']) for d in documents)
    print(f"批量大小: {batch_size} 个文档")
    print(f"总字符数: {total_chars}")
    print(f"文档大小: {total_chars/batch_size:.0f} 字符/文档")

    start_time = time.time()
    results = []

    for i, doc_data in enumerate(documents, 1):
        result = await upload_document(doc_data)
        if result.get("doc_id"):
            results.append(doc_data["doc_id"])
            print(f"  [{i}/{batch_size}] ✅ {doc_data['title']}")

    elapsed_ms = (time.time() - start_time) * 1000

    print(f"\n✅ 批量导入完成")
    print(f"   成功: {len(results)}/{batch_size}")
    print(f"   总耗时: {elapsed_ms:.2f} ms ({elapsed_ms/1000:.2f} 秒)")
    print(f"   平均耗时: {elapsed_ms/batch_size:.2f} ms/文档")
    print(f"   吊吐量: {total_chars/(elapsed_ms/1000):.0f} 字符/秒")

    baseline = BASELINE_METRICS["batch_10_docs_ms"]
    ratio = elapsed_ms / baseline

    print(f"   基线: {baseline} ms")
    print(f"   比率: {ratio:.2f}x {'✓' if ratio <= 1.5 else '✗'}")

    return {"batch_10_docs_ms": elapsed_ms}


async def benchmark_search_latency():
    """基准测试：检索延迟"""
    print("\n" + "=" * 70)
    print("基准测试 3: 检索延迟 (100 次查询)")
    print("=" * 70)

    queries = [
        "钻井液密度",
        "粘度控制",
        "井壁稳定",
        "重晶石",
        "岩屑携带",
    ]

    total_queries = 100
    latencies = []

    for i in range(total_queries):
        query = queries[i % len(queries)]
        _, latency_ms = await measure_time_async(search_knowledge, query, 5)
        latencies.append(latency_ms)

        if (i + 1) % 20 == 0:
            print(f"  完成 {i+1}/{total_queries} 查询...")

    avg_latency = statistics.mean(latencies)
    p50_latency = statistics.median(latencies)
    p95_latency = statistics.quantiles(latencies, n=20)[18]  # 95th percentile
    p99_latency = statistics.quantiles(latencies, n=100)[98]  # 99th percentile

    print(f"\n✅ 检索延迟统计")
    print(f"   查询次数: {total_queries}")
    print(f"   平均延迟: {avg_latency:.2f} ms")
    print(f"   P50 延迟: {p50_latency:.2f} ms")
    print(f"   P95 延迟: {p95_latency:.2f} ms")
    print(f"   P99 延迟: {p99_latency:.2f} ms")

    baseline = BASELINE_METRICS["similarity_search_ms"]
    ratio = avg_latency / baseline

    print(f"   基线: {baseline} ms")
    print(f"   比率: {ratio:.2f}x {'✓' if ratio <= 1.5 else '✗'}")

    return {
        "search_avg_ms": avg_latency,
        "search_p50_ms": p50_latency,
        "search_p95_ms": p95_latency,
        "search_p99_ms": p99_latency
    }


def print_summary(results: Dict[str, Dict[str, float]]):
    """打印性能总结"""
    print("\n" + "=" * 70)
    print("性能基准测试总结")
    print("=" * 70)

    print("\n| 指标 | 实测值 | 基线 | 比率 | 状态 |")
    print("|------|--------|------|------|------|")

    all_passed = True

    # 单文档导入
    single_doc = results.get("single_doc", {})
    actual = single_doc.get("single_doc_100_chunks_ms")
    baseline = BASELINE_METRICS["single_doc_100_chunks_ms"]
    if actual:
        ratio = actual / baseline
        status = "✓ 通过" if ratio <= 1.5 else "✗ 失败"
        if ratio > 1.5:
            all_passed = False
        print(f"| 单文档导入 | {actual:.0f} ms | {baseline} ms | {ratio:.2f}x | {status} |")

    # 批量导入
    batch = results.get("batch", {})
    actual = batch.get("batch_10_docs_ms")
    baseline = BASELINE_METRICS["batch_10_docs_ms"]
    if actual:
        ratio = actual / baseline
        status = "✓ 通过" if ratio <= 1.5 else "✗ 失败"
        if ratio > 1.5:
            all_passed = False
        print(f"| 批量导入 | {actual:.0f} ms | {baseline} ms | {ratio:.2f}x | {status} |")

    # 检索延迟
    search = results.get("search", {})
    actual = search.get("search_avg_ms")
    baseline = BASELINE_METRICS["similarity_search_ms"]
    if actual:
        ratio = actual / baseline
        status = "✓ 通过" if ratio <= 1.5 else "✗ 失败"
        if ratio > 1.5:
            all_passed = False
        print(f"| 检索延迟 | {actual:.0f} ms | {baseline} ms | {ratio:.2f}x | {status} |")

    print("\n" + "-" * 70)

    if all_passed:
        print("✅ 所有性能指标达到目标！")
    else:
        print("⚠️  部分性能指标未达到目标，可能需要优化")

    print("\n说明：")
    print("- 基线值来自旧架构的预期性能")
    print("- 比率 <= 1.5x 表示性能可接受")
    print("- 比率 > 1.5x 表示需要进一步优化")


async def main():
    """主函数"""
    print("=" * 70)
    print("知识导入性能基准测试 - 阶段 2.3")
    print("=" * 70)
    print(f"API 地址: {API_BASE_URL}")
    print(f"测试时间: {datetime.now().isoformat()}")

    results = {}

    try:
        # 基准测试 1: 单文档导入
        single_results = await benchmark_single_document_import()
        results["single"] = single_results

        # 等待一段时间让系统稳定
        await asyncio.sleep(5)

        # 基准测试 2: 批量导入
        batch_results = await benchmark_batch_import()
        results["batch"] = batch_results

        # 等待向量索引建立
        print("\n等待向量索引建立...")
        await asyncio.sleep(10)

        # 基准测试 3: 检索延迟
        search_results = await benchmark_search_latency()
        results["search"] = search_results

    except KeyboardInterrupt:
        print("\n\n⚠️ 测试被中断")
    except Exception as e:
        print(f"\n\n❌ 测试失败: {e}")
        import traceback
        traceback.print_exc()

    # 打印总结
    print_summary(results)

    print("\n" + "=" * 70)
    print("测试完成!")
    print("=" * 70)


if __name__ == "__main__":
    asyncio.run(main())
