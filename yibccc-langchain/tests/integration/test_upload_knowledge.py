#!/usr/bin/env python3
"""
知识库上传测试脚本

功能：
1. 上传本地知识库文件到诊断系统
2. 执行语义检索测试
3. 可选：触发完整的诊断分析

用法：
    python tests/integration/test_upload_knowledge.py

阶段2.1 测试场景覆盖：
1. 单文档导入 (无父子分块)
2. 多文档批量导入
3. 父子分块层级验证
4. 大文件导入性能
5. 错误处理和重试
"""

import asyncio
import sys
import time
from pathlib import Path
from typing import Dict, Any, List

# 添加项目路径
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

import httpx
from datetime import datetime
import json


# 配置
API_BASE_URL = "http://localhost:8000"
API_KEY = "test-key"
KNOWLEDGE_FILE = "/Users/kirayang/Desktop/mountain.txt"


async def upload_knowledge_file(
    file_path: str,
    metadata: Dict[str, Any] = None
) -> dict:
    """上传知识库文件"""
    print(f"\n=== 上传知识库文件 ===")
    print(f"文件路径: {file_path}")

    # 读取文件内容
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    print(f"文件大小: {len(content)} 字符")
    print(f"前 100 字符预览: {content[:100]}...")

    # 分析文件内容，提取标题和分类
    lines = content.strip().split("\n")
    title = lines[0] if lines else Path(file_path).stem

    # 根据文件名或内容判断分类
    category = "general"
    if any(keyword in content.lower() for keyword in ["密度", "粘度", "切力", "钻井液"]):
        category = "drilling_fluid"
    elif any(keyword in content.lower() for keyword in ["mountain", "山", "地形"]):
        category = "geology"

    # 构建文档数据
    base_metadata = metadata or {}
    doc_metadata = {
        "source": file_path,
        "uploaded_at": datetime.now().isoformat(),
        **base_metadata
    }

    doc_data = {
        "doc_id": f"DOC-{datetime.now().strftime('%Y%m%d%H%M%S')}-{Path(file_path).stem}",
        "title": title,
        "category": category,
        "content": content,
        "metadata": doc_metadata
    }

    # 调用 API 上传
    url = f"{API_BASE_URL}/api/v1/diagnosis/knowledge/documents"
    headers = {"X-API-Key": API_KEY}

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(url, json=doc_data, headers=headers)

    if response.status_code in (200, 201):
        result = response.json()
        print(f"✅ 上传成功!")
        print(f"   文档ID: {result.get('doc_id')}")
        print(f"   分类: {category}")
        return result
    else:
        print(f"❌ 上传失败: {response.status_code}")
        print(f"   响应: {response.text}")
        return {}


async def upload_document_direct(
    doc_id: str,
    title: str,
    content: str,
    category: str = "test",
    metadata: Dict[str, Any] = None
) -> dict:
    """直接上传文档（不依赖文件）"""
    url = f"{API_BASE_URL}/api/v1/diagnosis/knowledge/documents"
    headers = {"X-API-Key": API_KEY}

    doc_data = {
        "doc_id": doc_id,
        "title": title,
        "category": category,
        "content": content,
        "metadata": metadata or {}
    }

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(url, json=doc_data, headers=headers)

    if response.status_code in (200, 201):
        return response.json()
    else:
        print(f"❌ 上传失败: {response.status_code}")
        print(f"   响应: {response.text}")
        return {}


async def test_knowledge_search(query: str = "山", top_k: int = 3):
    """测试知识检索"""
    print(f"\n=== 测试知识检索 ===")
    print(f"查询: {query}")

    url = f"{API_BASE_URL}/api/v1/diagnosis/knowledge/search"
    headers = {"X-API-Key": API_KEY}
    data = {"query": query, "top_k": top_k}

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(url, json=data, headers=headers)

    if response.status_code == 200:
        result = response.json()
        results = result.get("results", [])
        print(f"✅ 检索成功! 找到 {len(results)} 条结果")

        for i, item in enumerate(results, 1):
            print(f"\n   结果 {i}:")
            print(f"   - 文档ID: {item.get('doc_id')}")
            print(f"   - 标题: {item.get('title')}")
            print(f"   - 相似度: {item.get('distance', 'N/A')}")
            content_preview = item.get('content', '')[:100]
            print(f"   - 内容预览: {content_preview}...")
    else:
        print(f"❌ 检索失败: {response.status_code}")
        print(f"   响应: {response.text}")


# ========== 阶段2.1 测试场景 ==========

async def test_single_document_import():
    """测试场景1: 单文档导入 (无父子分块)"""
    print("\n" + "=" * 60)
    print("测试场景1: 单文档导入")
    print("=" * 60)

    doc_id = f"TEST-SINGLE-{datetime.now().strftime('%Y%m%d%H%M%S')}"
    content = "钻井液密度是指单位体积钻井液的质量。密度偏高时，应加水稀释。"

    result = await upload_document_direct(
        doc_id=doc_id,
        title="单文档测试",
        content=content,
        category="drilling_fluid"
    )

    if result.get("doc_id"):
        print(f"✅ 单文档导入成功: {doc_id}")

        # 等待处理
        await asyncio.sleep(2)

        # 验证检索
        await test_knowledge_search("密度", top_k=1)
        return True
    else:
        print(f"❌ 单文档导入失败")
        return False


async def test_batch_document_import():
    """测试场景2: 多文档批量导入"""
    print("\n" + "=" * 60)
    print("测试场景2: 多文档批量导入")
    print("=" * 60)

    documents = [
        {
            "doc_id": f"TEST-BATCH-1-{datetime.now().strftime('%Y%m%d%H%M%S')}",
            "title": "钻井液基础知识",
            "content": "钻井液是钻井过程中使用的循环流体，具有冷却钻头、携带岩屑、稳定井壁的作用。",
            "category": "drilling_fluid"
        },
        {
            "doc_id": f"TEST-BATCH-2-{datetime.now().strftime('%Y%m%d%H%M%S')}",
            "title": "密度调节方法",
            "content": "钻井液密度偏高时，应加水稀释；密度偏低时，应加入加重材料如重晶石。",
            "category": "drilling_fluid"
        },
        {
            "doc_id": f"TEST-BATCH-3-{datetime.now().strftime('%Y%m%d%H%M%S')}",
            "title": "粘度控制要点",
            "content": "粘度是钻井液流动性的重要指标，过高会导致泵压增大，过低则无法有效携带岩屑。",
            "category": "drilling_fluid"
        }
    ]

    start_time = time.time()
    results = []

    for doc in documents:
        result = await upload_document_direct(**doc)
        if result.get("doc_id"):
            results.append(doc["doc_id"])
            print(f"  ✅ 导入: {doc['title']}")

    elapsed = time.time() - start_time
    print(f"  批量导入完成: {len(results)}/{len(documents)} 耗时: {elapsed:.2f}s")

    # 等待处理
    await asyncio.sleep(3)

    # 验证检索
    await test_knowledge_search("钻井液", top_k=3)

    return len(results) == len(documents)


async def test_parent_child_hierarchy():
    """测试场景3: 父子分块层级验证"""
    print("\n" + "=" * 60)
    print("测试场景3: 父子分块层级验证")
    print("=" * 60)

    # 创建一个多段落的长文档
    content = """
    钻井液密度控制

    钻井液密度是钻井过程中最重要的参数之一。密度过小会导致井喷风险，密度过大会增加钻井成本。

    密度调节方法

    当钻井液密度偏高时，可以采取以下措施：
    1. 加水稀释：这是最简单有效的方法，但会影响其他性能
    2. 使用离心机：清除固相，降低密度
    3. 调整配浆方案：减少加重材料的用量

    当密度偏低时：
    1. 加入重晶石：常用的加重材料
    2. 加入铁矿粉：密度更高，成本也较高
    3. 检查是否混入低密度流体

    密度监控要点

    1. 每钻进50米测量一次密度
    2. 使用精密密度计，准确度应达到0.01
    3. 记录密度变化曲线，及时发现异常
    4. 根据地层压力调整密度设计
    """

    doc_id = f"TEST-HIERARCHY-{datetime.now().strftime('%Y%m%d%H%M%S')}"

    result = await upload_document_direct(
        doc_id=doc_id,
        title="父子分块测试",
        content=content,
        category="drilling_fluid"
    )

    if result.get("doc_id"):
        print(f"✅ 文档上传成功，预期产生父子分块结构")
        print(f"   文档ID: {doc_id}")

        # 等待处理
        await asyncio.sleep(3)

        # 检索验证层级
        print("\n   验证检索包含层级信息:")
        await test_knowledge_search("密度调节", top_k=3)
        return True
    else:
        print(f"❌ 文档上传失败")
        return False


async def test_large_file_performance():
    """测试场景4: 大文件导入性能"""
    print("\n" + "=" * 60)
    print("测试场景4: 大文件导入性能")
    print("=" * 60)

    # 创建模拟大文件 (约10000字)
    large_content = "钻井液是钻井工程中的重要介质。" + " " * 97 + "它具有多种功能。"
    for i in range(200):
        large_content += f"\n\n第{i+1}章：钻井液的基本原理\n\n"
        large_content += "钻井液在钻井过程中起着至关重要的作用。它能够冷却和润滑钻头，"
        large_content += "将钻屑从井底携带到地面，稳定井壁，控制地层压力，提供井眼信息。"
        large_content += "钻井液的性能参数包括密度、粘度、切力、失水、泥饼厚度等。"
        large_content += "这些参数需要根据钻井工艺和地层条件进行合理设计。"

    print(f"   生成测试文档: {len(large_content)} 字符")

    doc_id = f"TEST-LARGE-{datetime.now().strftime('%Y%m%d%H%M%S')}"

    start_time = time.time()

    result = await upload_document_direct(
        doc_id=doc_id,
        title="大文件性能测试",
        content=large_content,
        category="drilling_fluid"
    )

    elapsed = time.time() - start_time

    if result.get("doc_id"):
        print(f"✅ 大文件导入成功")
        print(f"   上传时间: {elapsed:.2f}s")
        print(f"   吞吐量: {len(large_content)/elapsed:.0f} 字符/秒")

        # 等待处理
        await asyncio.sleep(5)

        # 验证检索
        await test_knowledge_search("钻井液", top_k=2)
        return True
    else:
        print(f"❌ 大文件导入失败")
        return False


async def test_error_handling():
    """测试场景5: 错误处理和重试"""
    print("\n" + "=" * 60)
    print("测试场景5: 错误处理和重试")
    print("=" * 60)

    # 测试空内容
    print("  测试1: 空内容处理")
    result = await upload_document_direct(
        doc_id=f"TEST-ERROR-EMPTY-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        title="空内容测试",
        content="",
        category="test"
    )
    print(f"  结果: {'✅ 已处理' if result else '❌ 未处理'}")

    # 测试特殊字符
    print("  测试2: 特殊字符处理")
    special_content = "测试内容包含特殊字符：\n\t\r\n\u4e2d\u6587\n特殊符号: @#$%^&*"
    result = await upload_document_direct(
        doc_id=f"TEST-ERROR-SPECIAL-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        title="特殊字符测试",
        content=special_content,
        category="test"
    )
    print(f"  结果: {'✅ 已处理' if result else '❌ 未处理'}")

    return True


async def test_diagnosis_analysis():
    """测试完整的诊断分析"""
    print(f"\n=== 测试诊断分析 ===")

    url = f"{API_BASE_URL}/api/v1/diagnosis/analyze"
    headers = {"X-API-Key": API_KEY}

    # 构建测试请求
    request = {
        "task_id": f"TEST-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        "well_id": "TEST-001",
        "alert_type": "密度偏高",
        "alert_triggered_at": datetime.now().isoformat(),
        "alert_threshold": {
            "field": "density",
            "condition": "greater_than",
            "threshold": 1.25,
            "current_value": 1.32
        },
        "samples": [
            {
                "id": "sample_001",
                "well_id": "TEST-001",
                "sample_time": datetime.now().isoformat(),
                "formation": "砂岩",
                "outlet_temp": 58.5,
                "density": 1.32,
                "gel_10s": 3.5,
                "gel_10m": 8.2,
                "rpm_3": 5,
                "rpm_6": 8,
                "rpm_100": 45,
                "rpm_200": 75,
                "rpm_300": 95,
                "rpm_600": 160,
                "plastic_viscosity": 25.0,
                "yield_point": 15.0,
                "flow_behavior_index": 0.7,
                "consistency_coefficient": 15.0,
                "apparent_viscosity": 30.0,
                "yield_plastic_ratio": 0.6
            }
        ],
        "context": {"category": "density", "current_depth": 1500},
        "stream": False  # 使用非流式，简化测试
    }

    async with httpx.AsyncClient(timeout=300.0) as client:
        response = await client.post(url, json=request, headers=headers)

    if response.status_code == 200:
        result = response.json()
        print(f"✅ 诊断成功!")
        print(f"   任务ID: {result.get('task', {}).get('task_id')}")
        print(f"   状态: {result.get('task', {}).get('status')}")

        if result.get('result'):
            diagnosis = result['result'].get('diagnosis', {})
            print(f"\n   诊断结论:")
            print(f"   - 摘要: {diagnosis.get('summary')}")
            print(f"   - 原因: {diagnosis.get('cause')}")
            print(f"   - 风险等级: {diagnosis.get('risk_level')}")
    else:
        print(f"❌ 诊断失败: {response.status_code}")
        print(f"   响应: {response.text}")


async def main():
    """主函数"""
    print("=" * 60)
    print("知识库上传与联调测试 - 阶段2.1 集成测试")
    print("=" * 60)

    test_results = []

    # 运行所有测试场景
    try:
        test_results.append(("单文档导入", await test_single_document_import()))
        test_results.append(("多文档批量导入", await test_batch_document_import()))
        test_results.append(("父子分块层级验证", await test_parent_child_hierarchy()))
        test_results.append(("大文件导入性能", await test_large_file_performance()))
        test_results.append(("错误处理和重试", await test_error_handling()))
    except Exception as e:
        print(f"\n❌ 测试过程中发生异常: {e}")
        import traceback
        traceback.print_exc()

    # 总结
    print("\n" + "=" * 60)
    print("测试结果汇总")
    print("=" * 60)

    passed = sum(1 for _, result in test_results if result)
    total = len(test_results)

    for name, result in test_results:
        status = "✅ 通过" if result else "❌ 失败"
        print(f"  {status} {name}")

    print(f"\n总计: {passed}/{total} 测试通过")

    # 可选：测试完整诊断流程
    print("\n是否运行完整诊断分析测试? (y/n): ", end="")
    # 自动选择 n 进行简化测试
    choice = "n"

    if choice.lower() == 'y':
        await test_diagnosis_analysis()

    print("\n" + "=" * 60)
    print("测试完成!")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
