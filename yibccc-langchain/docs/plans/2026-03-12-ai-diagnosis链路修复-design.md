# AI 诊断链路修复设计方案

## 背景

之前的 AI 诊断链路存在以下问题：
1. task_id 与 alertId 混用，导致缓存无法正确查询
2. 回调机制未实现且不需要
3. 存在冗余代码和空实现

## 目标

清理冗余代码，统一使用 alertId 作为缓存 Key，实现完整的诊断链路：
- 前端发起请求 → SSE 流式返回 → 同时缓存到 Redis
- 前端断连重连 → 从 Redis 拉取结果

## 架构

```
前端发起请求 (带 alertId)
       ↓
FastAPI /api/v1/diagnosis/analyze
       ↓
DiagnosisService.analyze()
       ↓
┌─────────────────────────────────────┐
│  1. 流式返回 SSE 给前端             │
│  2. 同时缓存结果到 Redis (key=alertId)│
└─────────────────────────────────────┘
       ↓
前端断连重连 → GET /api/v1/diagnosis/stream?alert_id=xxx → 从 Redis 拉取
```

## 修改内容

### 1. FastAPI 层

#### 1.1 DiagnosisRequest 添加 alert_id
文件：`src/models/diagnosis_schemas.py`

```python
class DiagnosisRequest(BaseModel):
    alert_id: str = Field(..., description="预警ID，用于缓存和查询")
    task_id: str = Field(default_factory=...)  # 保留，内部使用
    well_id: str
    alert_type: str
    # ... 其他字段
```

#### 1.2 DiagnosisService 缓存逻辑修改
文件：`src/services/diagnosis_service.py`

- 缓存 key 改为 `alert_id`
- 诊断完成后同时缓存完整结果到 Redis

#### 1.3 新增 /stream 端点
文件：`src/api/routes/diagnosis.py`

```python
@router.get("/stream")
async def get_diagnosis_stream(
    alert_id: str = Query(..., description="预警ID"),
    user_id: str = Depends(get_user_id)
):
    """从 Redis 拉取缓存的诊断结果"""
    # 从 Redis 获取并返回 SSE 流
```

#### 1.4 删除冗余端点
- 删除 `/api/v1/diagnosis/callback` 端点（空实现）

### 2. SpringBoot 层

#### 2.1 删除空实现
- 删除 `AgentCallbackService.java`
- 删除 `AgentCallbackController.java`

#### 2.2 修改查询接口
文件：`DiagnosisController.java`

- `getCachedDiagnosis` 端点保持不变（已正确使用 alertId）
- 确认 SSE 格式处理正确

## 数据流

```
1. 前端请求:
   POST /api/v1/diagnosis/analyze
   {
     "alert_id": "alert-123",
     "well_id": "well-001",
     "alert_type": "density_high",
     ...
   }

2. FastAPI 缓存:
   Redis key: "diagnosis:alert-123"
   Value: SSE 格式的完整诊断结果

3. 前端拉取:
   GET /api/v1/diagnosis/stream?alert_id=alert-123
```

## 测试方案

### 功能测试
1. 正常诊断流程：前端发起请求 → 收到完整 SSE 结果 → Redis 中有缓存
2. 断线重连：模拟断连 → 再次请求 → 从 Redis 拉取结果
3. 边界情况：alert_id 不存在、过期等

### 集成测试
1. SpringBoot → FastAPI 完整调用
2. SSE 流式传输验证