# AI 诊断链路修复实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 AI 诊断链路：统一使用 alertId 作为缓存 Key，删除冗余代码，实现完整链路

**Architecture:** 前端发起请求 → FastAPI 流式返回 SSE → 同时缓存到 Redis (key=alertId) → 前端断连后可从 Redis 拉取

**Tech Stack:** FastAPI (Python 3.11), Redis, SSE

---

## Task 1: DiagnosisRequest 添加 alert_id 字段

**Files:**
- Modify: `yibccc-langchain/src/models/diagnosis_schemas.py`

**Step 1: 查看当前 DiagnosisRequest 定义**

```bash
# 先读取文件查看当前结构
cat yibccc-langchain/src/models/diagnosis_schemas.py | head -100
```

**Step 2: 修改 DiagnosisRequest 添加 alert_id**

在 `DiagnosisRequest` 类中添加 `alert_id` 字段（放在 task_id 之前或之后）：

```python
class DiagnosisRequest(BaseModel):
    alert_id: str = Field(..., description="预警ID，用于缓存和查询")
    task_id: str = Field(default_factory=lambda: f"TASK-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:6].upper()}")
    well_id: str
    alert_type: str
    alert_threshold: AlertThreshold
    samples: List[SampleData]
    context: DiagnosisContext
    callback_url: Optional[str] = None
```

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/models/diagnosis_schemas.py && git commit -m "feat: 添加 alert_id 字段到 DiagnosisRequest"
```

---

## Task 2: 创建 Redis 缓存服务

**Files:**
- Create: `yibccc-langchain/src/services/diagnosis_cache_service.py`

**Step 1: 创建缓存服务**

```python
# yibccc-langchain/src/services/diagnosis_cache_service.py
"""诊断结果缓存服务"""

import logging
from typing import Optional
import redis.asyncio as redis
from src.config import settings

logger = logging.getLogger(__name__)

DIAGNOSIS_PREFIX = "diagnosis:"


class DiagnosisCacheService:
    """诊断结果 Redis 缓存服务"""

    def __init__(self):
        self.redis_url = settings.get_redis_url()
        self._client: Optional[redis.Redis] = None

    async def get_client(self) -> redis.Redis:
        if self._client is None:
            self._client = redis.from_url(self.redis_url, decode_responses=True)
        return self._client

    async def close(self):
        if self._client:
            await self._client.close()
            self._client = None

    async def save_diagnosis(self, alert_id: str, content: str, ttl_minutes: int = 60):
        """保存诊断结果到缓存"""
        key = f"{DIAGNOSIS_PREFIX}{alert_id}"
        client = await self.get_client()
        await client.set(key, content, ex=ttl_minutes * 60)
        logger.info(f"缓存诊断结果: alert_id={alert_id}, length={len(content)}")

    async def get_diagnosis(self, alert_id: str) -> Optional[str]:
        """从缓存获取诊断结果"""
        key = f"{DIAGNOSIS_PREFIX}{alert_id}"
        client = await self.get_client()
        return await client.get(key)

    async def delete_diagnosis(self, alert_id: str):
        """删除缓存"""
        key = f"{DIAGNOSIS_PREFIX}{alert_id}"
        client = await self.get_client()
        await client.delete(key)


# 全局实例
diagnosis_cache_service = DiagnosisCacheService()
```

**Step 2: 提交**

```bash
cd yibccc-langchain && git add src/services/diagnosis_cache_service.py && git commit -m "feat: 添加诊断结果 Redis 缓存服务"
```

---

## Task 3: 修改 DiagnosisService 集成缓存

**Files:**
- Modify: `yibccc-langchain/src/services/diagnosis_service.py`

**Step 1: 导入缓存服务**

在文件顶部添加导入：
```python
from src.services.diagnosis_cache_service import diagnosis_cache_service
```

**Step 2: 修改 analyze 方法添加缓存逻辑**

在 `analyze` 方法中，诊断完成后缓存结果：

```python
async def analyze(self, request: DiagnosisRequest) -> AsyncIterator[DiagnosisEvent]:
    task_id = request.task_id
    alert_id = request.alert_id  # 新增

    # 用于收集完整结果
    full_result = []

    try:
        # ... 现有逻辑 ...

        # 发送事件时收集
        async for event in self.agent.analyze(request):
            yield event
            # 收集完整结果用于缓存
            full_result.append(event.to_sse())

            # ... 现有逻辑 ...

        # 3. 缓存结果到 Redis
        if full_result:
            cache_content = "".join(full_result)
            await diagnosis_cache_service.save_diagnosis(
                alert_id=alert_id,
                content=cache_content,
                ttl_minutes=60
            )

    except Exception as e:
        # ... 现有逻辑 ...
```

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/services/diagnosis_service.py && git commit -m "feat: 集成 Redis 缓存到诊断服务"
```

---

## Task 4: 新增 /stream 端点

**Files:**
- Modify: `yibccc-langchain/src/api/routes/diagnosis.py`

**Step 1: 添加导入**

```python
from src.services.diagnosis_cache_service import diagnosis_cache_service
```

**Step 2: 添加 /stream 端点**

在 `get_diagnosis_result` 之后添加：

```python
@router.get("/stream")
async def get_diagnosis_stream(
    alert_id: str = Query(..., description="预警ID"),
    user_id: str = Depends(get_user_id)
):
    """从 Redis 拉取缓存的诊断结果"""
    cached = await diagnosis_cache_service.get_diagnosis(alert_id)

    if not cached:
        raise HTTPException(
            status_code=404,
            detail=f"诊断结果不存在或已过期: alert_id={alert_id}"
        )

    # 将缓存的 SSE 内容作为流返回
    async def event_generator():
        for line in cached.split("\n"):
            if line.strip():
                yield line + "\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream"
    )
```

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/api/routes/diagnosis.py && git commit -m "feat: 新增 /stream 端点支持从 Redis 拉取诊断结果"
```

---

## Task 5: 删除冗余的 /callback 端点

**Files:**
- Modify: `yibccc-langchain/src/api/routes/diagnosis.py`

**Step 1: 删除 callback 端点**

删除以下代码（约第 66-77 行）：
```python
@router.post("/callback")
async def diagnosis_callback(
    callback: CallbackRequest,
    user_id: str = Depends(get_user_id)
):
    """结果回调接口（SpringBoot 调用）"""
    # 这个端点用于 SpringBoot 主动查询或确认回调结果
    # 实际回调由 diagnosis_service 发送
    return {
        "status": "callback_received",
        "task_id": callback.task_id
    }
```

**Step 2: 提交**

```bash
cd yibccc-langchain && git add src/api/routes/diagnosis.py && git commit -m "refactor: 删除无用的 /callback 端点"
```

---

## Task 6: 初始化时启动缓存服务

**Files:**
- Modify: `yibccc-langchain/src/api/main.py`

**Step 1: 查看当前启动逻辑**

读取 main.py 查看 lifespan 事件处理。

**Step 2: 添加缓存服务清理**

在 lifespan 关闭时清理 Redis 连接：

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时
    if diagnosis_service:
        await diagnosis_service.initialize()
    yield
    # 关闭时
    if diagnosis_service:
        await diagnosis_service.cleanup()
    await diagnosis_cache_service.close()  # 新增
```

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/api/main.py && git commit -m "feat: 添加缓存服务生命周期管理"
```

---

## Task 7: SpringBoot - 删除空实现

**Files:**
- Delete: `sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/AgentCallbackService.java`
- Delete: `sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/AgentCallbackController.java`

**Step 1: 删除空实现文件**

```bash
rm sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/AgentCallbackService.java
rm sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/AgentCallbackController.java
```

**Step 2: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "refactor: 删除无用的回调空实现代码"
```

---

## Task 8: 测试验证

**Step 1: 启动 FastAPI 服务**

```bash
cd yibccc-langchain && uvicorn src.api.main:app --reload
```

**Step 2: 测试诊断流程**

```bash
# 发起诊断请求（带 alert_id）
curl -X POST http://localhost:8000/api/v1/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "alert_id": "test-alert-001",
    "well_id": "well-001",
    "alert_type": "density_high",
    "alert_threshold": {
      "current_value": 1.35,
      "threshold": 1.30,
      "unit": "g/cm³"
    },
    "samples": [
      {"sample_time": "2024-01-01T10:00:00", "density": 1.25, "plastic_viscosity": 15, "yield_point": 8},
      {"sample_time": "2024-01-01T11:00:00", "density": 1.28, "plastic_viscosity": 18, "yield_point": 10},
      {"sample_time": "2024-01-01T12:00:00", "density": 1.32, "plastic_viscosity": 20, "yield_point": 12}
    ],
    "context": {
      "current_depth": 2500,
      "formation_type": "砂岩",
      "drilling_phase": "钻进"
    }
  }'
```

**Step 3: 测试从缓存拉取**

```bash
# 模拟断连后拉取
curl -N http://localhost:8000/api/v1/diagnosis/stream?alert_id=test-alert-001
```

**Step 4: 验证 Redis**

```bash
# 检查 Redis 中的缓存
redis-cli GET "diagnosis:test-alert-001"
```

---

## 执行选项

**Plan complete and saved to `docs/plans/2026-03-12-ai-diagnosis-impl.md`. Two execution options:**

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach?**