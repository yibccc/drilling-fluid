# AI 诊断链路修复设计方案（修正版）

## 背景

之前的 AI 诊断链路存在以下问题：
1. task_id 与 alertId 混用，导致缓存无法正确查询
2. 回调机制未实现且不需要
3. 存在冗余代码和空实现
4. 架构调整：前端不直接请求 FastAPI，通过 SpringBoot 转发

## 目标

1. 前端不直接访问 FastAPI，全部通过 SpringBoot 转发
2. SpringBoot 使用内部 API Key 调用 FastAPI
3. 统一使用 alertId 作为缓存 Key
4. 诊断完成后由 SpringBoot 缓存结果到 Redis
5. 前端断连重连时，从 SpringBoot 缓存拉取

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                         前端                                  │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    SpringBoot (鉴权 + 缓存)                    │
│  ┌─────────────────┐    ┌─────────────────────────────────┐  │
│  │ DiagnosisController │    │ DiagnosisCacheService (Redis) │  │
│  └────────┬────────┘    └─────────────────────────────────┘  │
│           │                                                   │
│           ▼ (携带 Internal API Key)                          │
└───────────┼─────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────┐
│                   FastAPI (诊断逻辑)                          │
│  ┌─────────────────┐    ┌─────────────────────────────────┐  │
│  │ /analyze 端点    │───▶│ DiagnosisAgent                 │  │
│  └─────────────────┘    └─────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 数据流

### 1. 发起诊断请求
```
前端 → POST /api/ai/diagnosis/analyze (带 alert_id)
  │
  ▼
SpringBoot:
  1. 鉴权
  2. 转发到 FastAPI /api/v1/diagnosis/analyze (带 X-Internal-Api-Key)
  │
  ▼
FastAPI:
  1. 验证 Internal API Key
  2. 执行诊断，返回 SSE 流
  │
  ▼
SpringBoot:
  1. 接收 SSE 流，返回给前端
  2. 收集完整结果
  3. 缓存到 Redis (key = alert_id)
```

### 2. 断连重连拉取
```
前端 → GET /api/ai/diagnosis/stream?alert_id=xxx
  │
  ▼
SpringBoot:
  1. 鉴权
  2. 从 Redis 拉取 (key = diagnosis:xxx)
  3. 返回 SSE 流
```

## 修改内容

### 1. FastAPI 层

#### 1.1 移除公开鉴权，改为内部 API Key 验证
- 修改依赖 `get_user_id`，支持内部 API Key 模式
- 添加 `X-Internal-Api-Key` 头验证

#### 1.2 DiagnosisRequest 确认 alert_id
- 确认已添加 `alert_id` 字段

#### 1.3 移除 /stream 端点
- 缓存逻辑移到 SpringBoot，删除 FastAPI 的 /stream 端点

#### 1.4 删除冗余代码
- 删除 /callback 端点

### 2. SpringBoot 层

#### 2.1 DiagnosisController
- 确认 /analyze 端点转发逻辑正确
- 确认 /stream 端点从 Redis 拉取逻辑正确
- 请求体添加 alert_id 字段

#### 2.2 SSEForwardService
- 添加内部 API Key 到请求头

#### 2.3 收集并缓存结果
- 诊断完成后收集完整 SSE 内容
- 缓存到 Redis

#### 2.4 清理
- 删除 AgentCallbackService.java
- 删除 AgentCallbackController.java
- 删除 AiDiagnosisTriggerService.java（后台不主动触发）

## 测试方案

### 功能测试
1. 正常诊断流程：前端 → SpringBoot → FastAPI → 返回结果 → 缓存 Redis
2. 断线重连：前端 → SpringBoot → Redis 拉取
3. 边界情况：alert_id 不存在、过期等

### 集成测试
1. SpringBoot → FastAPI 完整调用
2. 内部 API Key 验证
3. SSE 流式传输验证