# AI 诊断链路修复实施计划（修正版）

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 AI 诊断链路：前端 → SpringBoot (鉴权+缓存) → FastAPI，缓存由 SpringBoot 负责

**Architecture:** 前端 → SpringBoot → FastAPI (诊断) → SpringBoot 缓存 Redis → 前端从 SpringBoot 拉取

**Tech Stack:** FastAPI (Python 3.11), SpringBoot (Java), Redis, SSE

---

## 第一阶段: FastAPI 修改

### Task 1: 添加内部 API Key 验证

**Files:**
- Modify: `yibccc-langchain/src/api/dependencies.py`

**Step 1: 查看当前 dependencies.py**

```bash
cat yibccc-langchain/src/api/dependencies.py
```

**Step 2: 添加内部 API Key 验证**

```python
from fastapi import Header, HTTPException, Depends

# 内部 API Key（在 .env 中配置）
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "")

async def get_user_id(
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-Api-Key"),
    x_user_id: Optional[str] = Header(None, alias="X-User-Id")
) -> str:
    """获取用户 ID：优先使用内部 API Key，其次使用用户 ID"""
    # 内部 API Key 验证
    if INTERNAL_API_KEY and x_internal_api_key == INTERNAL_API_KEY:
        return "internal"  # 内部调用

    # 正常用户鉴权
    if not x_user_id:
        raise HTTPException(status_code=401, detail="Missing authentication")

    return x_user_id
```

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/api/dependencies.py && git commit -m "feat: 添加内部 API Key 验证支持"
```

---

### Task 2: 确认 DiagnosisRequest 包含 alert_id

**Files:**
- Modify: `yibccc-langchain/src/models/diagnosis_schemas.py`

**Step 1: 查看当前结构**

如果已有 alert_id 字段，跳过此任务。

如果没有，添加：

```python
class DiagnosisRequest(BaseModel):
    alert_id: str = Field(..., description="预警ID，用于缓存和查询")
    # ... 其他字段
```

**Step 2: 提交**

```bash
cd yibccc-langchain && git add src/models/diagnosis_schemas.py && git commit -m "feat: 确认 DiagnosisRequest 包含 alert_id"
```

---

### Task 3: 删除冗余端点

**Files:**
- Modify: `yibccc-langchain/src/api/routes/diagnosis.py`

**Step 1: 删除 /callback 端点**

删除 callback 端点（约第 66-77 行）：

```python
@router.post("/callback")
async def diagnosis_callback(...)
```

**Step 2: 删除 /stream 端点（如果已添加）**

SpringBoot 负责缓存，FastAPI 不需要 /stream 端点。

**Step 3: 提交**

```bash
cd yibccc-langchain && git add src/api/routes/diagnosis.py && git commit -m "refactor: 删除无用的 /callback 和 /stream 端点"
```

---

## 第二阶段: SpringBoot 修改

### Task 4: 添加内部 API Key 配置

**Files:**
- Modify: `sky-chuanqin/sky-server/.../config/DiagnosisProperties.java`

**Step 1: 添加配置**

```java
@ConfigurationProperties(prefix = "diagnosis")
public class DiagnosisProperties {
    private boolean enabled = true;
    private int timeoutMinutes = 5;
    private int resultCacheTtl = 60; // 诊断结果缓存时间(分钟)
    private int alertCacheTtl = 30;  // 预警信息缓存时间(分钟)
    private String internalApiKey;   // 内部 API Key
    // getters/setters
}
```

**Step 2: 在 application.yml 中配置**

```yaml
diagnosis:
  enabled: true
  timeout-minutes: 5
  result-cache-ttl: 60
  internal-api-key: your-secure-internal-key
```

**Step 3: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "feat: 添加内部 API Key 配置"
```

---

### Task 5: SSEForwardService 添加内部 API Key

**Files:**
- Modify: `sky-chuanqin/sky-server/.../service/ai/SSEForwardService.java`

**Step 1: 添加请求头**

```java
public Flux<String> forwardSSE(String uri, Object request, Duration timeout) {
    log.info("转发 SSE 请求到: {}", uri);

    WebClient.RequestBodySpec requestBodySpec = agentWebClient.post()
            .uri(uri)
            .header("X-Internal-Api-Key", properties.getInternalApiKey());  // 添加内部 API Key

    if (request != null) {
        requestBodySpec.body(BodyInserters.fromValue(request));
    }

    return requestBodySpec
            .retrieve()
            // ...
}
```

**Step 2: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "feat: SSE 转发添加内部 API Key"
```

---

### Task 6: DiagnosisController 添加 alert_id

**Files:**
- Modify: `sky-chuanqin/sky-server/.../controller/ai/DiagnosisController.java`
- Modify: `sky-chuanqin/sky-server/.../controller/ai/dto/DiagnosisRequest.java`

**Step 1: 在 DiagnosisRequest DTO 中添加 alertId**

```java
public class DiagnosisRequest {
    private String alertId;  // 新增
    private String wellId;
    private String alertType;
    // ...
}
```

**Step 2: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "feat: DiagnosisRequest 添加 alertId 字段"
```

---

### Task 7: DiagnosisController 实现缓存逻辑

**Files:**
- Modify: `sky-chuanqin/sky-server/.../controller/ai/DiagnosisController.java`

**Step 1: 修改 /analyze 端点**

```java
@PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> analyze(@RequestBody DiagnosisRequest request) {
    log.info("诊断请求: alertId={}, wellId={}, alertType={}",
            request.getAlertId(), request.getWellId(), request.getAlertType());

    // 转发到 FastAPI，收集完整结果用于缓存
    return sseForwardService.forwardSSEWithCache(
            "/api/v1/diagnosis/analyze",
            request,
            Duration.ofMinutes(5),
            request.getAlertId()  // 用于缓存的 key
    );
}
```

**Step 2: 修改 /stream 端点（如果需要）**

/stream 端点已存在，逻辑正确。确认使用 alertId 作为查询参数。

**Step 3: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "feat: 诊断请求添加缓存逻辑"
```

---

### Task 8: SSEForwardService 添加缓存方法

**Files:**
- Modify: `sky-chuanqin/sky-server/.../service/ai/SSEForwardService.java`

**Step 1: 添加缓存方法**

```java
private final DiagnosisCacheService diagnosisCacheService;

/**
 * SSE 转发并缓存结果
 */
public Flux<String> forwardSSEWithCache(String uri, Object request,
                                        Duration timeout, String alertId) {
    // 1. 转发请求
    Flux<String> stream = forwardSSE(uri, request, timeout);

    // 2. 收集完整结果并缓存
    StringBuilder resultBuilder = new StringBuilder();

    return stream
            .doOnNext(resultBuilder::append)
            .doOnComplete(() -> {
                // 缓存完整结果
                diagnosisCacheService.saveDiagnosisResult(alertId, resultBuilder.toString());
            })
            .doOnError(e -> {
                log.error("诊断失败，alertId={}, error={}", alertId, e.getMessage());
            });
}
```

**Step 2: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "feat: SSE 转发添加缓存功能"
```

---

## 第三阶段: 清理

### Task 9: 删除无用的 Java 文件

**Files:**
- Delete: `sky-chuanqin/sky-server/.../service/ai/AgentCallbackService.java`
- Delete: `sky-chuanqin/sky-server/.../controller/ai/AgentCallbackController.java`
- Delete: `sky-chuanqin/sky-server/.../service/ai/AiDiagnosisTriggerService.java`

**Step 1: 删除文件**

```bash
rm sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/AgentCallbackService.java
rm sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/AgentCallbackController.java
rm sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/AiDiagnosisTriggerService.java
```

**Step 2: 提交**

```bash
cd sky-chuanqin && git add -A && git commit -m "refactor: 删除无用的回调和触发服务"
```

---

## 第四阶段: 测试

### Task 10: 集成测试

**Step 1: 配置**

在 SpringBoot 的 application.yml 中配置相同的 internal-api-key。

**Step 2: 启动服务**

```bash
# 启动 FastAPI
cd yibccc-langchain && uvicorn src.api.main:app --reload

# 启动 SpringBoot
cd sky-chuanqin && ./mvnw spring-boot:run
```

**Step 3: 测试诊断流程**

```bash
curl -X POST http://localhost:8080/api/ai/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "alertId": "alert-001",
    "wellId": "well-001",
    "alertType": "density_high",
    "alertThreshold": {
      "currentValue": 1.35,
      "threshold": 1.30,
      "unit": "g/cm³"
    },
    "samples": [
      {"sampleTime": "2024-01-01T10:00:00", "density": 1.25, "plasticViscosity": 15, "yieldPoint": 8}
    ],
    "context": {
      "currentDepth": 2500,
      "formationType": "砂岩",
      "drillingPhase": "钻进"
    }
  }'
```

**Step 4: 测试缓存拉取**

```bash
curl -N http://localhost:8080/api/ai/diagnosis/stream?alertId=alert-001
```

**Step 5: 验证 Redis**

```bash
redis-cli GET "diagnosis:alert-001"
```

---

## 执行选项

**Plan complete and saved to `docs/plans/2026-03-12-ai-diagnosis-impl.md`. Two execution options:**

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach?**