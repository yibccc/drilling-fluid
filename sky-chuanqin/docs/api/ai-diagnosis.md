# AI 诊断 API 文档

## 概述

AI 诊断 API 提供钻井液异常智能诊断分析功能。当 XXL-Job 定时任务检测到污染时，会自动触发 AI 诊断，并通过 WebSocket 推送预警，前端可通过 SSE 获取流式诊断结果。

---

## API 端点

### 1. 发起诊断分析

**端点**: `POST /api/ai/diagnosis/analyze`

**说明**: 发起 AI 诊断分析，返回 SSE 格式的流式响应

**请求体**:
```json
{
  "well_id": "well-001",
  "alert_type": "钙污染",
  "alert_triggered_at": "2026-02-26T10:00:00",
  "stream": true
}
```

**返回**: SSE 事件流

---

### 2. 查询诊断结果（通过 taskId）

**端点**: `GET /api/ai/diagnosis/{taskId}`

**说明**: 通过任务 ID 查询诊断结果（转发到 Agent 服务）

**参数**:
- `taskId`: 任务 ID

**返回**: SSE 事件流

---

### 3. 查询缓存诊断结果（新增）

**端点**: `GET /api/ai/diagnosis/stream?alertId={alertId}`

**说明**: 当前端收到 WebSocket 预警后，调用此接口获取缓存的诊断结果

**参数**:
- `alertId`: 预警ID（从 WebSocket 消息中获取）

**返回**: SSE 流式响应

**示例**:
```javascript
// WebSocket 监听
websocket.onmessage = (event) => {
  const alert = JSON.parse(event.data);
  if (alert.type === 'AI_DIAGNOSIS_ALERT') {
    // 获取诊断结果
    const eventSource = new EventSource(
      `/api/ai/diagnosis/stream?alertId=${alert.alertId}`
    );
    eventSource.onmessage = (e) => console.log(e.data);
  }
};
```

---

## WebSocket 消息格式

### AI_DIAGNOSIS_ALERT 预警消息

当 XXL-Job 检测到污染并触发 AI 诊断后，会推送以下格式的 WebSocket 消息：

```json
{
  "type": "AI_DIAGNOSIS_ALERT",
  "alertId": "ALERT-1234567890",
  "wellId": "well-001",
  "wellLocation": "井位置信息",
  "alertType": "钙污染",
  "severity": "HIGH",
  "triggeredAt": 1708900800000,
  "status": "COMPLETED",
  "diagnosisUrl": "/api/ai/diagnosis/stream?alertId=ALERT-1234567890"
}
```

**字段说明**:
- `type`: 消息类型，固定为 `AI_DIAGNOSIS_ALERT`
- `alertId`: 预警 ID，用于查询诊断结果
- `wellId`: 井 ID
- `wellLocation`: 井位置
- `alertType`: 预警类型（钙污染、二氧化碳污染、钻井液稳定性问题）
- `severity`: 严重程度
- `triggeredAt`: 触发时间戳
- `status`: 诊断状态（COMPLETED、FAILED、ERROR）
- `diagnosisUrl`: 诊断结果查询 URL

---

## 配置参数

在 `application.yml` 中配置：

```yaml
diagnosis:
  alert-cache-ttl: 15      # 预警信息缓存时间（分钟）
  result-cache-ttl: 15     # 诊断结果缓存时间（分钟）
  timeout-minutes: 5       # 诊断超时时间（分钟）
  enabled: true            # 是否启用 AI 诊断
```

---

## 完整流程

1. XXL-Job 定时任务检测到污染
2. 自动调用 `AiDiagnosisTriggerService` 触发 AI 诊断
3. 诊断结果缓存到 Redis（TTL 15 分钟）
4. 通过 WebSocket 推送 `AI_DIAGNOSIS_ALERT` 消息
5. 前端收到消息后，使用 `alertId` 查询缓存结果

---

## 错误处理

当诊断结果不存在或已过期时，返回：

```
data: {"type":"error","content":"诊断结果不存在或已过期"}
```

当诊断失败时，返回：

```
data: {"type":"error","content":"诊断失败: <错误信息>"}
```
