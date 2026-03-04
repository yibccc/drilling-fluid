import asyncio
import logging
from httpx import AsyncClient, ASGITransport
from datetime import datetime

logging.basicConfig(level=logging.INFO)

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from src.api.main import app, lifespan

async def test_agent_e2e_sync():
    print("=== 测试同步上传切片与Agent端到端 ===")
    async with lifespan(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            # 1. 同步上传知识库文档
            print("\n1. 正在同步上传知识文档...")
            doc_data = {
                "doc_id": f"TEST-DOC-{datetime.now().strftime('%Y%m%d%H%M%S')}",
                "title": "测试钻井液密度问题",
                "category": "drilling_fluid",
                "content": "当钻井液密度偏高时，这通常是由岩屑混入或加重材料过多引起的。处理方法包括：使用离心机清除固相，或者加水稀释。如果处理不当，可能导致卡钻。",
                "metadata": {"source": "e2e_test"}
            }
            headers = {"X-API-Key": "test-key"}
            response = await client.post("/api/v1/diagnosis/knowledge/documents", json=doc_data, headers=headers)
            
            if response.status_code not in (200, 201):
                print(f"❌ 上传失败: {response.status_code}, {response.text}")
                return
            result = response.json()
            print(f"✅ 上传成功! 文档ID: {result.get('doc_id')}, 产生切片数: {result.get('chunk_count')}")

            # 2. 触发 Agent 诊断分析
            print("\n2. 触发 Agent 诊断分析...")
            request = {
                "task_id": f"TEST-TASK-{datetime.now().strftime('%Y%m%d%H%M%S')}",
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
                "stream": False # 如果 API 支持非流式，否则我们需要读取 SSE
            }

            print("发送请求并读取流式响应...")
            async with client.stream("POST", "/api/v1/diagnosis/analyze", json=request, headers=headers) as stream_response:
                if stream_response.status_code != 200:
                    print(f"❌ 诊断请求失败: {stream_response.status_code}")
                    text = await stream_response.aread()
                    print(text.decode('utf-8'))
                    return
                    
                print("--- 开始接收事件 ---")
                async for line in stream_response.aiter_lines():
                    if line.startswith("data: "):
                        data = line[6:]
                        print(f"事件数据: {data[:100]}...")
                print("--- 结束接收事件 ---")
                print("✅ 链路测试完成!")

if __name__ == "__main__":
    asyncio.run(test_agent_e2e_sync())
