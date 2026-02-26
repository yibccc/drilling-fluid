# AI 集成详细设计文档

## 1. 概述

### 1.1 系统架构

本项目实现了 SpringBoot 后端与 FastAPI Agent 服务的集成，采用混合架构：
- **SpringBoot (2.7.3)**: 作为中间层处理 JWT 认证和 SSE 转发
- **FastAPI Agent**: 处理 LLM/Agent/RAG 能力
- **通信方式**: SSE (Server-Sent Events) 流式传输

### 1.2 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 2.7.3 |
| 响应式编程 | Spring WebFlux | 2.7.3 |
| HTTP 客户端 | WebClient | - |
| 流式传输 | SSE | - |
| Java 版本 | Java SE | 11 |
| LLM | DeepSeek API | - |
| 向量数据库 | PostgreSQL + pgvector | - |

## 2. 系统设计

### 2.1 整体架构图

```
┌─────────┐      ┌──────────────┐      ┌─────────────┐
│ Vue 前端 │ ───> │ SpringBoot   │ ───> │ FastAPI     │
│         │ SSE  │ 中间层        │ HTTP │ Agent 服务   │
└─────────┘      │ Port: 18080  │      │ Port: 8000   │
                 │              │      │             │
                 │ - JWT 认证   │      │ - LangChain  │
                 │ - SSE 转发    │      │ - DeepSeek   │
                 │ - 权限控制    │      │ - RAG        │
                 └──────────────┘      └─────────────┘
```

### 2.2 核心组件设计

#### 2.2.1 AgentWebClientConfig

**职责**: 配置用于与 FastAPI Agent 服务通信的 WebClient

**配置项**:
- `agent.base-url`: Agent 服务地址
- `agent.api-key`: API 认证密钥
- 内存缓冲区: 10MB (用于 SSE 流)
- 默认请求头: `Content-Type: application/json`, `X-API-Key`

```java
@Configuration
public class AgentWebClientConfig {
    @Bean
    public WebClient agentWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(10 * 1024 * 1024))
            .build();

        return WebClient.builder()
            .baseUrl(agentBaseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-API-Key", apiKey)
            .exchangeStrategies(strategies)
            .build();
    }
}
```

#### 2.2.2 SSEForwardService

**职责**: SSE 流式转发服务

**核心方法**: `forwardSSE(String uri, Object request, Duration timeout)`

**流程**:
1. 接收前端请求
2. 通过 WebClient 转发到 FastAPI
3. 将响应流透传给前端
4. 处理超时和错误

**错误处理**:
- `WebClientRequestException`: 返回友好错误消息
- 超时: 使用 Flux.timeout() 处理

#### 2.2.3 ChatController

**端点**: `POST /api/ai/chat/stream`

**请求格式**:
```json
{
  "message": "用户消息",
  "session_id": "会话ID (可选)",
  "stream": true
}
```

**响应格式 (SSE)**:
```
data: {"type":"start","session_id":"..."}
data: {"type":"token","content":"你"}
data: {"type":"token","content":"好"}
...
data: {"type":"end"}
```

#### 2.2.4 DiagnosisController

**端点**:
- `POST /api/ai/diagnosis/analyze` - 诊断分析
- `GET /api/ai/diagnosis/{taskId}` - 查询结果

**请求格式**:
```json
{
  "well_id": "井号",
  "alert_type": "预警类型",
  "alert_triggered_at": "触发时间",
  "alert_threshold": {
    "field": "字段名",
    "condition": "条件",
    "threshold": 阈值,
    "current_value": 当前值
  },
  "samples": [/* 钻井液样本数据 */],
  "stream": true
}
```

### 2.3 数据模型设计

#### 2.3.1 SSE 事件类型

```java
public enum SSEEventType {
    // 通用事件
    START, END, ERROR,

    // 对话事件
    TOKEN, TOOL_CALL, TOOL_RESULT,

    // 诊断事件
    THINKING, TREND_ANALYSIS, RETRIEVAL,
    DIAGNOSIS, PRESCRIPTION, RESULT, DONE
}
```

#### 2.3.2 DTO 字段映射

| Java 字段 | JSON 字段 | 说明 |
|-----------|-----------|------|
| sessionId | session_id | 会话ID |
| alertType | alert_type | 预警类型 |
| wellId | well_id | 井号ID |
| currentValue | current_value | 当前值 |

## 3. 接口设计

### 3.1 对话接口

**请求**:
```http
POST /api/ai/chat/stream HTTP/1.1
Content-Type: application/json

{
  "message": "你好",
  "session_id": "session-123",
  "stream": true
}
```

**响应** (SSE 流式):
```
Content-Type: text/event-stream

data: {"type":"start","session_id":"xxx"}

data: {"type":"token","content":"你"}

data: {"type":"token","content":"好"}

data: {"type":"end"}
```

### 3.2 诊断接口

**请求**:
```http
POST /api/ai/diagnosis/analyze HTTP/1.1
Content-Type: application/json

{
  "well_id": "well-001",
  "alert_type": "HIGH_DENSITY",
  "alert_triggered_at": "2026-02-26T10:00:00",
  "alert_threshold": {
    "field": "density",
    "condition": "greater_than",
    "threshold": 1.5,
    "current_value": 1.8
  },
  "samples": [{
    "id": "sample-001",
    "wellId": "well-001",
    "sampleTime": "2026-02-26T10:00:00",
    "formation": "sandstone",
    "density": 1.8
    // ... 其他字段
  }],
  "stream": true
}
```

**响应** (SSE 流式):
```
Content-Type: text/event-stream

data: {"type":"start","task_id":"TASK-xxx","well_id":"well-001"}

data: {"type":"thinking","content":"正在分析...","step":"data_analysis"}

data: {"type":"diagnosis","content":"密度偏高，建议..."}

data: {"type":"done"}
```

## 4. 安全设计

### 4.1 认证流程

```
┌──────────┐    Token?    ┌─────────────┐    No    ┌─────────┐
│  前端    │ ───────────> │ SpringBoot  │ ───────> │ 返回401 │
└──────────┘               └─────────────┘          └─────────┘
                                  │ Yes
                                  ▼
                           ┌─────────────┐
                           │ JWT 验证     │
                           │ 权限检查     │
                           └─────────────┘
                                  │
                                  ▼
                           ┌─────────────┐
                           │ 转发请求到   │
                           │ FastAPI     │
                           │ (添加API Key)│
                           └─────────────┘
```

### 4.2 API Key 配置

**SpringBoot 配置** (application.yml):
```yaml
agent:
  base-url: ${AGENT_BASE_URL:http://localhost:8000}
  api-key: ${AGENT_API_KEY:test-key}
```

**FastAPI 配置** (.env):
```
API_KEYS=test-key,prod-key-123
```

### 4.3 CORS 配置

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of("*"));
    configuration.setAllowedMethods(Arrays.asList("GET", "POST", ...));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    // ...
}
```

## 5. 错误处理

### 5.1 错误码设计

| 错误类型 | HTTP 状态 | SSE 事件 |
|----------|-----------|----------|
| Agent 不可达 | 500 | `{"type":"error","content":"Agent 服务暂时不可用"}` |
| 认证失败 | 401 | 标准错误响应 |
| 请求超时 | 504 | 连接关闭 |
| 参数错误 | 400 | 验证错误消息 |

### 5.2 超时配置

| 接口 | 超时时间 |
|------|----------|
| 对话接口 | 2 分钟 |
| 诊断分析 | 5 分钟 |
| 查询结果 | 30 秒 |

## 6. 性能优化

### 6.1 缓冲区配置

```java
ExchangeStrategies strategies = ExchangeStrategies.builder()
    .codecs(configurer -> configurer
        .defaultCodecs()
        .maxInMemorySize(10 * 1024 * 1024)) // 10MB
    .build();
```

### 6.2 连接池配置

WebClient 默认使用 Reactor Netty 连接池，支持：
- 连接复用
- 并发请求
- 背压控制

## 7. 监控指标

### 7.1 关键指标

| 指标 | 说明 |
|------|------|
| 请求响应时间 | 端到端延迟 |
| Token 生成速率 | tokens/秒 |
| 错误率 | 4xx/5xx 比例 |
| 并发连接数 | 活跃 SSE 连接数 |

### 7.2 日志记录

```java
log.info("对话流式请求: sessionId={}, message={}",
    request.getSessionId(), request.getMessage());

log.debug("转发的 SSE 行: {}", line);

log.error("SSE 转发错误: {}", error.getMessage(), error);
```

## 8. 部署配置

### 8.1 环境变量

```bash
# Agent 服务配置
export AGENT_BASE_URL=http://agent-service:8000
export AGENT_API_KEY=production-api-key

# SpringBoot 配置
export SERVER_PORT=18080
export SPRING_PROFILES_ACTIVE=prod
```

### 8.2 健康检查

```bash
# 检查 SpringBoot
curl http://localhost:18080/actuator/health

# 检查 FastAPI
curl http://localhost:8000/health
```

## 9. 故障排查

### 9.1 常见问题

**问题1**: 401 Unauthorized
- **原因**: API Key 不匹配
- **解决**: 检查 application.yml 和 FastAPI .env 中的 API_KEY 配置

**问题2**: 连接超时
- **原因**: Agent 服务不可达
- **解决**: 检查网络连接和防火墙配置

**问题3**: SSE 流中断
- **原因**: 超时或网络问题
- **解决**: 调整超时配置或实现重连机制

### 9.2 调试命令

```bash
# 查看日志
tail -f /var/log/springboot/application.log

# 检查端口占用
lsof -i :18080
lsof -i :8000

# 测试连接
curl -v http://localhost:18080/api/ai/chat/stream
```

## 10. 扩展性考虑

### 10.1 水平扩展

- SpringBoot: 无状态设计，支持多实例部署
- FastAPI: 通过负载均衡器分发请求

### 10.2 功能扩展

- 支持多 Agent 服务
- 添加请求队列
- 实现结果缓存
- 添加速率限制
