#!/usr/bin/env python3
"""
知识库上传测试脚本

功能：
1. 上传本地知识库文件到诊断系统
2. 执行语义检索测试
3. 可选：触发完整的诊断分析

用法：
    python tests/integration/test_upload_knowledge.py
"""

import asyncio
import sys
from pathlib import Path

# 添加项目路径
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

import httpx
from datetime import datetime


# 配置
API_BASE_URL = "http://localhost:8000"
API_KEY = "test-key"
KNOWLEDGE_FILE = "/Users/kirayang/Desktop/mountain.txt"


async def upload_knowledge_file(file_path: str) -> dict:
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
    doc_data = {
        "doc_id": f"DOC-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        "title": title,
        "category": category,
        "content": content,
        "metadata": {
            "source": file_path,
            "uploaded_at": datetime.now().isoformat()
        }
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
    print("知识库上传与联调测试")
    print("=" * 60)

    # 1. 上传知识库文件
    result = await upload_knowledge_file(KNOWLEDGE_FILE)

    if result:
        # 2. 等待向量索引创建
        print("\n等待向量索引创建...")
        await asyncio.sleep(2)

        # 3. 测试检索
        await test_knowledge_search("山")
        await test_knowledge_search("密度")
    else:
        print("\n⚠️ 上传失败，跳过检索测试")

    # 4. 测试诊断分析
    print("\n是否运行诊断分析测试? (y/n): ", end="")
    # 自动选择 n 进行简化测试
    choice = "n"

    if choice.lower() == 'y':
        await test_diagnosis_analysis()

    print("\n" + "=" * 60)
    print("测试完成!")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
