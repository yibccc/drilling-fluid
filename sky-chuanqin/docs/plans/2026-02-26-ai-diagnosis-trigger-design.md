# XXL-Job 触发 AI 诊断功能设计文档

> **创建时间**: 2026-02-26
> **设计目标**: 当 XXL-Job 定时任务检测到钻井液异常时，自动触发 AI 诊断分析，前端收到预警后可主动获取流式诊断结果。

---

## 1. 设计概述

### 1.1 当前问题

现有 `PollutionDetectionTest` 定时任务在检测到异常后：
- ✗ 没有调用 AI 诊断
- ✗ 仅记录日志，未推送预警
- ✗ 前端无法获取诊断分析结果

### 1.2 设计目标

```
XXL-Job 检测异常 → 触发 AI 诊断 → 缓存结果 → WebSocket 推送预警
                                                          ↓
                                              前端主动查询 SSE → 流式返回诊断结果
```

---

## 2. 架构设计

### 2.1 完整流程图

```
┌─────────────┐
│  XXL-Job    │ 定时执行
│  定时任务    │
└──────┬──────┘
       │
       ▼
┌──────────────────────────────────────┐
│ 1. 检测异常（钙污染/CO2/稳定性）        │
│ 2. 构造完整诊断请求                   │
│ 3. 调用 AI 诊断（同步，等待完成）      │
│ 4. 生成诊断任务 ID                    │
└──────────┬───────────────────────────┘
           │
           ▼
┌──────────────────────────────────────┐
│ 5. 存储预警信息到 Redis (15分钟TTL)  │
│    key: alert:{alertId}              │
│    value: {完整预警信息}              │
└──────────┬───────────────────────────┘
           │
           ▼
┌──────────────────────────────────────┐
│ 6. WebSocket 推送预警给前端           │
│    {                                │
│      type: "AI_DIAGNOSIS_ALERT",    │
│      alertId: "xxx",                 │
│      wellId: "well-001",             │
│      alertType: "HIGH_DENSITY",      │
│      status: "DIAGNOSING"            │
│    }                                │
└──────────────────────────────────────┘
                    │
                    │ 前端收到预警
                    ▼
┌──────────────────────────────────────┐
│ 7. 前端发起 SSE 请求                 │
│    GET /api/ai/diagnosis/stream     │
│       ?alertId=ALERT-xxx             │
└──────────┬───────────────────────────┘
           │
           ▼
┌──────────────────────────────────────┐
│ 8. SpringBoot 处理                 │
│    - 从 Redis 获取预警详情          │
│    - 复用 SSEForwardService          │
│    - 流式返回缓存的诊断结果          │
└──────────────────────────────────────┘
```

### 2.2 设计原则

1. **复用现有服务**: 使用已有的 `SSEForwardService`
2. **最小侵入**: 仅修改现有的三个检测任务
3. **解耦设计**: XXL-Job 不直接与前端通信，通过 WebSocket 和 Redis

---

## 3. 组件设计

### 3.1 修改 PollutionDetectionTest.java

```java
@Component
@Slf4j
public class PollutionDetectionTest {

    @Autowired
    private SSEForwardService sseForwardService;  // 新增：AI 诊断服务

    @Autowired
    private RedisTemplate redisTemplate;           // 新增：缓存存储

    private static final String ALERT_PREFIX = "alert:";
    private static final String DIAGNOSIS_PREFIX = "diagnosis:";
    private static final Duration ALERT_TTL = Duration.ofMinutes(15);

    @XxlJob("caPollutionDetectionJob")
    public void caPollutionDetection() {
        // ... 现有检测逻辑 ...

        if (isPolluted) {
            log.error("【定时检测】检测到钙污染，井ID：{}", wellId);

            // 新增：触发 AI 诊断
            triggerAiDiagnosis(wellId, "钙污染", result);
        }
    }

    /**
     * 触发 AI 诊断分析
     */
    private void triggerAiDiagnosis(String wellId, String alertType,
                                     Map<String, List<ParameterVO>> detectionResult) {
        String alertId = "ALERT-" + System.currentTimeMillis();

        try {
            // 1. 构造诊断请求
            DiagnosisRequest request = buildDiagnosisRequest(wellId, alertType, detectionResult);

            // 2. 同步调用 AI 诊断（超时5分钟）
            Flux<String> diagnosisStream = sseForwardService.forwardSSE(
                "/api/v1/diagnosis/analyze",
                request,
                Duration.ofMinutes(5)
            );

            // 3. 收集完整结果
            String fullResult = collectDiagnosisResult(diagnosisStream);

            // 4. 存储到 Redis
            storeDiagnosisResult(alertId, fullResult);

            // 5. WebSocket 推送预警
            sendAiDiagnosisAlert(alertType, wellId, wellLocation, alertId);

        } catch (Exception e) {
            log.error("AI 诊断失败: {}", e.getMessage(), e);
            // 存储失败状态
            storeDiagnosisFailure(alertId, e.getMessage());
        }
    }
}
```

### 3.2 新增查询端点：DiagnosisController.java

```java
@RestController
@RequestMapping("/api/ai/diagnosis")
public class DiagnosisController {

    @Autowired
    private SSEForwardService sseForwardService;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 查询已有诊断结果（SSE 流式返回缓存的结果）
     * 前端收到 WebSocket 预警后调用此接口
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getCachedDiagnosis(
        @RequestParam String alertId
    ) {
        log.info("查询诊断结果: alertId={}", alertId);

        // 从 Redis 获取诊断结果
        String cachedResult = (String) redisTemplate.opsForValue()
            .get(DIAGNOSIS_PREFIX + alertId);

        if (cachedResult == null) {
            return Flux.just(
                "data: {\"type\":\"error\",\"content\":\"诊断结果不存在或已过期\"}\n\n"
            );
        }

        // 将缓存的 SSE 内容重新作为流返回
        return Flux.fromArray(cachedResult.split("\n"));
    }
}
```

---

## 4. 数据模型

### 4.1 WebSocket 预警消息

```json
{
  "type": "AI_DIAGNOSIS_ALERT",
  "alertId": "ALERT-1740567890123",
  "wellId": "well-001",
  "wellLocation": "XX区块-1号井",
  "alertType": "钙污染",
  "severity": "HIGH",
  "triggeredAt": "2026-02-26T16:30:00",
  "status": "COMPLETED",
  "message": "检测到钙污染，AI 诊断已完成",
  "diagnosisUrl": "/api/ai/diagnosis/stream?alertId=ALERT-1740567890123"
}
```

### 4.2 Redis 数据结构

```
# 预警信息（15分钟TTL）
key: alert:ALERT-1740567890123
value: {
  "alertId": "ALERT-1740567890123",
  "wellId": "well-001",
  "alertType": "钙污染",
  "triggeredAt": "2026-02-26T16:30:00",
  "status": "COMPLETED"
}

# 诊断结果（15分钟TTL）
key: diagnosis:ALERT-1740567890123
value: |
data: {"type":"start","task_id":"TASK-xxx"}
data: {"type":"thinking","content":"正在分析..."}
data: {"type":"diagnosis","content":"钙污染指标..."}
data: {"type":"done"}
```

---

## 5. 错误处理

### 5.1 Agent 服务不可用

```java
.onErrorResume(WebClientRequestException.class, e -> {
    log.error("Agent 服务不可用，仅记录预警");

    // 存储预警但不存储诊断结果
    storeAlertOnly(alertId, alertInfo);

    // WebSocket 推送预警（status: UNAVAILABLE）
    sendAiDiagnosisAlert(..., status: "UNAVAILABLE");

    return Flux.empty();
})
```

### 5.2 诊断超时

```java
.timeout(Duration.ofMinutes(5))
.doOnTimeout(() -> {
    log.warn("AI 诊断超时，alertId={}", alertId);
    updateDiagnosisStatus(alertId, "TIMEOUT");
})
```

---

## 6. 配置

### 6.1 application.yml 新增配置

```yaml
# AI 诊断配置
diagnosis:
  # 预警信息 TTL（分钟）
  alert-cache-ttl: 15
  # 诊断结果 TTL（分钟）
  result-cache-ttl: 15
  # 诊断超时时间（分钟）
  timeout-minutes: 5
  # 是否启用 AI 诊断（可快速关闭功能）
  enabled: true
```

---

## 7. 实施任务

| 任务 | 文件 | 说明 |
|------|------|------|
| 1 | `PollutionDetectionTest.java` | 添加 AI 诊断调用逻辑 |
| 2 | `DiagnosisController.java` | 新增 `/stream` 查询端点 |
| 3 | `application.yml` | 添加诊断配置 |
| 4 | WebSocket 消息格式 | 定义 `AI_DIAGNOSIS_ALERT` 类型 |
| 5 | 单元测试 | 测试诊断触发逻辑 |
| 6 | 集成测试 | 测试完整流程 |

---

## 8. 前端集成参考

### 8.1 WebSocket 监听

```javascript
websocket.onmessage = (event) => {
  const alert = JSON.parse(event.data);

  if (alert.type === 'AI_DIAGNOSIS_ALERT') {
    if (alert.status === 'COMPLETED') {
      // 诊断已完成，自动获取结果
      fetchDiagnosisResult(alert.alertId);
    } else {
      // 诊断中，显示加载状态
      showAnalyzingStatus(alert.alertId);
    }
  }
};
```

### 8.2 SSE 消费

```javascript
function fetchDiagnosisResult(alertId) {
  const eventSource = new EventSource(
    `/api/ai/diagnosis/stream?alertId=${alertId}`
  );

  eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);

    switch(data.type) {
      case 'start':
        console.log('诊断开始');
        break;
      case 'thinking':
        console.log('正在分析:', data.content);
        break;
      case 'diagnosis':
        console.log('诊断结果:', data.content);
        break;
      case 'done':
        console.log('诊断完成');
        eventSource.close();
        break;
    }
  };
}
```
