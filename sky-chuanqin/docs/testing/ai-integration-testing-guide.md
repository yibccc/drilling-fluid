# AI 集成测试指南

## 前置条件

1. FastAPI Agent 服务运行在 `http://localhost:8000`
2. SpringBoot 应用运行在端口 `18080`
3. 有效的 JWT 令牌（或使用开发模式的 permitAll）

## 启动服务

### 1. 启动 FastAPI Agent 服务

```bash
cd yibccc-langchain
uv run uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000
```

### 2. 启动 SpringBoot 服务

```bash
cd sky-chuanqin/sky-server
mvn spring-boot:run
```

## 测试端点

### 对话流式测试

```bash
curl -N -X POST http://localhost:18080/api/ai/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好，请介绍一下钻井液的基本性能",
    "stream": true
  }'
```

预期响应：
```
data: {"type":"start","session_id":"..."}
data: {"type":"token","content":"你"}
data: {"type":"token","content":"好"}
...
data: {"type":"end"}
```

### 诊断分析测试

```bash
curl -N -X POST http://localhost:18080/api/ai/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "well_id": "well-001",
    "alert_type": "HIGH_DENSITY",
    "alert_triggered_at": "2026-02-26T10:00:00",
    "stream": true
  }'
```

预期响应：
```
data: {"type":"start","task_id":"..."}
data: {"type":"thinking","content":"分析中..."}
...
data: {"type":"done"}
```

### 获取诊断结果测试

```bash
curl -N -X GET http://localhost:18080/api/ai/diagnosis/{taskId}
```

### 回调测试

```bash
curl -X POST http://localhost:18080/api/ai/callback/diagnosis \
  -H "Content-Type: application/json" \
  -d '{
    "task_id": "task-001",
    "status": "completed",
    "result": "test result"
  }'
```

预期：`Callback received`

## 故障排除

### Agent 服务不可用

错误：`Agent 服务暂时不可用，请稍后重试`

解决方案：
1. 检查 FastAPI Agent 服务是否运行：`curl http://localhost:8000/health`
2. 检查 application.yml 中的 agent.base-url 配置
3. 检查网络连接

### 认证错误

错误：`401 Unauthorized`

解决方案：
1. 检查 JWT 令牌有效性
2. 验证 SecurityConfig 在开发模式下放行 `/api/ai/**`
3. 检查 Authorization 头格式：`Bearer <token>`

### SSE 流超时

错误：流在 2 分钟（对话）或 5 分钟（诊断）后断开

解决方案：
1. 调整 ChatController 和 DiagnosisController 中的超时值
2. 检查 Agent 服务的处理时间
3. 考虑使用回调 URL 进行长时间运行的分析
