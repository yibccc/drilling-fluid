# SpringBoot 对接 FastAPI Agent 系统设计文档

> **版本**: 1.0.0
> **创建日期**: 2026-02-25
> **项目**: drilling-fluid
> **设计模式**: 混合模式 (Spring Web + WebFlux)

---

## 目录

1. [概述](#概述)
2. [架构设计](#架构设计)
3. [组件设计](#组件设计)
4. [数据模型](#数据模型)
5. [配置](#配置)
6. [认证与安全](#认证与安全)
7. [错误处理](#错误处理)
8. [前端对接](#前端对接)
9. [测试](#测试)
10. [部署](#部署)
11. [实施计划](#实施计划)

---

## 概述

### 背景

钻井液性能实时检测系统需要集成 AI Agent 能力，实现：
- 智能流式对话（基于 LangChain Agent）
- 钻井液异常智能诊断分析（基于 RAG）

### 目标

将现有 SpringBoot 后端与 FastAPI Agent 服务对接，实现：
1. SpringBoot 作为中间层处理认证和业务逻辑
2. FastAPI 专注 AI 能力（LLM、Agent、RAG）
3. SSE 流式响应透传给 Vue 前端
4. 最小改动现有代码

### 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue + Fetch API | SSE 流式接收 |
| 中间层 | SpringBoot + WebFlux | 认证、转发、SSE 透传 |
| AI 服务 | FastAPI + LangChain | Agent 编排、RAG 检索 |
| 数据库 | MySQL + PostgreSQL | 业务数据 + AI 数据 |

---

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Vue 前端                                     │
└────────────────────────────┬────────────────────────────────────────┘
                             │
         ┌───────────────────┴───────────────────┐
         │                                       │
         ▼                                       ▼
┌──────────────────────┐              ┌──────────────────────┐
│   /api/* (传统)       │              │  /api/ai/* (新)      │
│                      │              │                      │
│  AlertsController    │              │  ChatController      │
│  DensityController   │              │  DiagnosisController │
│  (Spring Web MVC)    │              │  (WebFlux)            │
└──────────────────────┘              └──────────┬───────────┘
                                                  │
                                                  ▼
                                    ┌─────────────────────────┐
                                    │  WebClient (HTTP 客户端) │
                                    └──────────┬──────────────┘
                                               │
                                               ▼
                                    ┌─────────────────────────┐
                                    │   FastAPI Agent         │
                                    │   (yibccc-langchain)    │
                                    │   - SSE 流式响应         │
                                    └─────────────────────────┘
```

### 职责划分

| 层级 | 职责 | 技术 |
|------|------|------|
| **Vue 前端** | 用户界面、EventSource 接收 SSE | Fetch API + ReadableStream |
| **SpringBoot** | 用户认证、权限控制、SSE 转发 | WebFlux + WebClient |
| **FastAPI** | LLM 对话、Agent 编排、工具调用 | LangChain + LangGraph |

### 数据流设计

#### 对话流程

```
Vue 前端
    │ POST /api/ai/chat/stream (带 JWT)
    ▼
ChatController (WebFlux)
    │ 验证 JWT → 提取 userId
    ▼
SSEForwardService
    │ POST /api/v1/chat/stream (带 API Key)
    ▼
FastAPI Agent
    │ SSE 流式返回
    │ - data: {"type":"start",...}
    │ - data: {"type":"token",...}
    │ - data: {"type":"tool_call",...}
    │ - data: {"type":"end"}
    ▼
ChatController
    │ Flux<String> 透传 SSE
    ▼
Vue 前端
```

#### 诊断流程

```
SpringBoot 预警模块
    │ 触发异常预警
    ▼
DiagnosisController
    │ POST /api/ai/diagnosis/analyze
    ▼
FastAPI DiagnosisService
    │ 1. 创建诊断任务
    │ 2. Agent 分析
    │ 3. RAG 检索
    │ 4. 流式返回
    ▼
SpringBoot (SSE 流)
    │ 实时展示分析进度
    ▼
Vue 前端

同时：
FastAPI → HTTP POST callback_url → SpringBoot (结果存储)
```

---

## 组件设计

### 包结构

```
com.kira.server
├── controller/              # 现有（不动）
│   ├── AlertsController.java
│   ├── DensityController.java
│   └── ...
├── controller/ai/          # 新增（AI 模块）
│   ├── ChatController.java         # 流式对话
│   ├── DiagnosisController.java    # 诊断分析
│   └── dto/                         # 请求/响应 DTO
│       ├── ChatRequest.java
│       ├── DiagnosisRequest.java
│       └── SSEEvent.java
├── config/                  # 扩展
│   ├── AgentWebClientConfig.java   # 新增
│   └── SecurityConfig.java         # 修改（排除 AI 路径）
├── service/ai/              # 新增（AI 服务）
│   ├── SSEForwardService.java      # SSE 转发服务
│   └── AgentCallbackService.java   # Agent 回调服务
└── filter/
    └── JwtAuthenticationFilter.java  # 修改
```

### 核心组件

#### ChatController（流式对话）

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.ChatRequest;
import com.kira.server.service.ai.SSEForwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SSEForwardService sseForwardService;

    /**
     * 流式对话 - SSE 转发
     * Vue 前端通过 Fetch API + ReadableStream 接收
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("Chat stream request: sessionId={}", request.getSessionId());

        // 转发到 FastAPI，返回 SSE 流
        return sseForwardService.forwardSSE(
            "/api/v1/chat/stream",
            request,
            Duration.ofMinutes(2)
        );
    }
}
```

#### DiagnosisController（诊断分析）

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.SSEForwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/api/ai/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {

    private final SSEForwardService sseForwardService;

    /**
     * 诊断分析 - SSE 转发
     */
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyze(@RequestBody DiagnosisRequest request) {
        log.info("Diagnosis request: wellId={}, alertType={}",
                request.getWellId(), request.getAlertType());

        return sseForwardService.forwardSSE(
            "/api/v1/diagnosis/analyze",
            request,
            Duration.ofMinutes(5)
        );
    }

    /**
     * 查询诊断结果
     */
    @GetMapping("/{taskId}")
    public Flux<String> getResult(@PathVariable String taskId) {
        return sseForwardService.forwardSSE(
            "/api/v1/diagnosis/" + taskId,
            null,
            Duration.ofSeconds(30)
        );
    }
}
```

#### SSEForwardService（SSE 转发核心）

```java
package com.kira.server.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SSEForwardService {

    private final WebClient agentWebClient;

    /**
     * SSE 流式转发
     * 将 FastAPI 的 SSE 流直接转发给前端
     *
     * @param uri     Agent 服务 URI
     * @param request 请求体
     * @param timeout 超时时间
     * @return SSE 事件流 (data: {json}\n\n 格式)
     */
    public Flux<String> forwardSSE(String uri, Object request, Duration timeout) {
        return agentWebClient.post()
                .uri(uri)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(this::dataBufferToString)
                .timeout(timeout)
                .doOnNext(line -> log.debug("Forwarded SSE: {}", line))
                .doOnError(error -> log.error("SSE forward error: {}", error.getMessage()))
                .onErrorResume(WebClientRequestException.class, e -> {
                    // Agent 服务不可用时返回友好错误
                    String errorEvent = String.format(
                            "data: {\"type\":\"error\",\"content\":\"Agent 服务暂时不可用，请稍后重试\"}\n\n"
                    );
                    return Flux.just(errorEvent);
                });
    }

    private String dataBufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
```

#### AgentCallbackService（Agent 回调服务）

```java
package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCallbackService {

    private final ObjectMapper objectMapper;

    /**
     * 接收 FastAPI 诊断结果回调
     */
    public void handleDiagnosisCallback(JsonNode payload) {
        String taskId = payload.get("task_id").asText();
        String status = payload.get("status").asText();

        log.info("Received diagnosis callback: taskId={}, status={}", taskId, status);

        // 存储到数据库
        // saveDiagnosisResult(taskId, payload);

        // 通知前端（WebSocket）
        // notifyFrontend(taskId, status);
    }
}
```

---

## 数据模型

### ChatRequest.java

```java
package com.kira.server.controller.ai.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class ChatRequest {
    private String message;

    @JsonProperty("session_id")
    private String sessionId;

    private Boolean stream = true;
}
```

### DiagnosisRequest.java

```java
package com.kira.server.controller.ai.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
public class DiagnosisRequest {
    @JsonProperty("well_id")
    private String wellId;

    @JsonProperty("alert_type")
    private String alertType;

    @JsonProperty("alert_triggered_at")
    private LocalDateTime alertTriggeredAt;

    @JsonProperty("alert_threshold")
    private AlertThreshold alertThreshold;

    private List<DrillingFluidSample> samples;

    private DiagnosisContext context;

    @JsonProperty("callback_url")
    private String callbackUrl;

    private Boolean stream = true;

    @Data
    public static class AlertThreshold {
        private String field;
        private String condition;
        private Double threshold;
        private Double currentValue;
    }

    @Data
    public static class DrillingFluidSample {
        private String id;
        private String wellId;
        private LocalDateTime sampleTime;
        private String formation;
        private Double outletTemp;
        private Double density;
        private Double gel10s;
        private Double gel10m;
        private Double rpm3;
        private Double rpm6;
        private Double rpm100;
        private Double rpm200;
        private Double rpm300;
        private Double rpm600;
        private Double plasticViscosity;
        private Double yieldPoint;
        private Double flowBehaviorIndex;
        private Double consistencyCoefficient;
        private Double apparentViscosity;
        private Double yieldPlasticRatio;
    }

    @Data
    public static class DiagnosisContext {
        private Double currentDepth;
        private String formationType;
        private String drillingPhase;
    }
}
```

### SSE 事件类型

#### 对话 SSE 事件

```typescript
interface ChatSSEEvent {
  type: 'start' | 'token' | 'tool_call' | 'tool_result' | 'end' | 'error'
  session_id?: string
  content?: string
  tool_data?: {
    call_id: string
    name: string
    arguments: Record<string, any>
    status: 'calling' | 'processing' | 'result'
    result?: string
    duration_ms?: number
  }
  error_code?: string
}
```

#### 诊断 SSE 事件

```typescript
interface DiagnosisSSEEvent {
  type: 'start' | 'thinking' | 'trend_analysis' | 'retrieval'
      | 'diagnosis' | 'prescription' | 'result' | 'done' | 'error'
  task_id?: string
  well_id?: string
  content?: string
  step?: string
  // ... 其他字段
}
```

---

## 配置

### 依赖配置 (pom.xml)

```xml
<dependencies>
    <!-- 新增：WebFlux 支持 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>

    <!-- 现有依赖保持不变 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- ... -->
</dependencies>
```

### application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: sky-server

# FastAPI Agent 服务配置
agent:
  base-url: ${AGENT_BASE_URL:http://localhost:8000}
  api-key: ${AGENT_API_KEY:test-key}
  timeout:
    connect: 5000
    read: 300000    # 5分钟，诊断分析可能较长

# JWT 配置
jwt:
  secret: ${JWT_SECRET:your-secret-key}
  expiration: 86400000  # 24小时
```

### AgentWebClientConfig.java

```java
package com.kira.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AgentWebClientConfig {

    @Value("${agent.base-url}")
    private String agentBaseUrl;

    @Value("${agent.api-key}")
    private String apiKey;

    @Bean
    public WebClient agentWebClient() {
        // 增大内存缓冲区限制（10MB）
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

---

## 认证与安全

### JWT 认证集成

#### 修改 JwtAuthenticationFilter

```java
@Component
public class JwtAuthenticationFilter implements WebFilter {

    private static final String[] EXCLUDE_PATHS = {
        "/api/auth/login",
        "/health",
        "/error"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 排除公开路径
        for (String exclude : EXCLUDE_PATHS) {
            if (path.contains(exclude)) {
                return chain.filter(exchange);
            }
        }

        // 验证 JWT（AI Controller 也需要验证）
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            Claims claims = validateToken(token);
            // 将用户信息存到请求属性
            exchange.getAttributes().put("userId", claims.getSubject());
            return chain.filter(exchange);
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }
}
```

### API Key 管理

FastAPI Agent 服务使用 API Key 认证，配置在 `AgentWebClientConfig` 中。

---

## 错误处理

### SSE 流内错误

FastAPI 返回的 SSE 错误事件直接透传：

```
data: {"type":"error","error_code":"LLM_ERROR","content":"..."}
```

### 连接级错误

```java
.onErrorResume(WebClientRequestException.class, e -> {
    String errorEvent = String.format(
        "data: {\"type\":\"error\",\"content\":\"Agent 服务暂时不可用\"}\n\n"
    );
    return Flux.just(errorEvent);
})
```

### 超时处理

```java
.timeout(Duration.ofMinutes(5))
.doOnError(TimeoutException.class, e -> {
    log.error("Agent request timeout");
})
```

---

## 前端对接

### API 定义 (api/ai.ts)

```typescript
export interface ChatSSEEvent {
  type: 'start' | 'token' | 'tool_call' | 'tool_result' | 'end' | 'error'
  session_id?: string
  content?: string
  tool_data?: {
    call_id: string
    name: string
    arguments: Record<string, any>
    status: 'calling' | 'processing' | 'result'
    result?: string
    duration_ms?: number
  }
  error_code?: string
}

export interface DiagnosisSSEEvent {
  type: 'start' | 'thinking' | 'trend_analysis' | 'retrieval'
      | 'diagnosis' | 'prescription' | 'result' | 'done' | 'error'
  task_id?: string
  well_id?: string
  content?: string
}

// 流式对话
export async function streamChat(
  message: string,
  sessionId?: string,
  onEvent: (event: ChatSSEEvent) => void,
  onError?: (error: string) => void
) {
  const response = await fetch('/api/ai/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('token')}`,
    },
    body: JSON.stringify({ message, session_id: sessionId, stream: true }),
  })

  if (!response.ok) throw new Error(`HTTP ${response.status}`)

  const reader = response.body?.getReader()
  const decoder = new TextDecoder()

  while (true) {
    const { done, value } = await reader!.read()
    if (done) break

    const chunk = decoder.decode(value)
    const lines = chunk.split('\n')

    for (const line of lines) {
      if (line.startsWith('data: ')) {
        try {
          const event: ChatSSEEvent = JSON.parse(line.slice(6))
          onEvent(event)
        } catch (e) {
          console.error('Failed to parse SSE:', line)
        }
      }
    }
  }
}

// 诊断分析
export async function analyzeDiagnosis(
  request: DiagnosisRequest,
  onEvent: (event: DiagnosisSSEEvent) => void
) {
  const response = await fetch('/api/ai/diagnosis/analyze', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('token')}`,
    },
    body: JSON.stringify({ ...request, stream: true }),
  })

  // 同上处理 SSE 流...
}
```

### Vue 组件示例

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { streamChat } from '@/api/ai'

const messages = ref<{ role: string; content: string }[]>([])
const currentContent = ref('')
const isLoading = ref(false)
const toolCalls = ref<any[]>([])
const sessionId = ref<string>()

async function sendMessage(userMessage: string) {
  isLoading.value = true
  currentContent.value = ''
  toolCalls.value = []

  await streamChat(
    userMessage,
    sessionId.value,
    (event) => {
      switch (event.type) {
        case 'start':
          sessionId.value = event.session_id!
          break
        case 'token':
          currentContent.value += event.content || ''
          break
        case 'tool_call':
          toolCalls.value.push({
            ...event.tool_data,
            status: 'calling',
          })
          break
        case 'tool_result':
          const idx = toolCalls.value.findIndex(t => t.call_id === event.tool_data?.call_id)
          if (idx >= 0) {
            toolCalls.value[idx] = event.tool_data
          }
          break
        case 'end':
          messages.value.push({
            role: 'assistant',
            content: currentContent.value,
          })
          currentContent.value = ''
          isLoading.value = false
          break
        case 'error':
          console.error('Chat error:', event.error_code)
          isLoading.value = false
          break
      }
    },
    (error) => {
      console.error('Stream error:', error)
      isLoading.value = false
    }
  )
}
</script>
```

---

## 测试

### 单元测试

```java
@SpringBootTest
@AutoConfigureWebTestClient
class ChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SSEForwardService sseForwardService;

    @Test
    void testChatStream() {
        given(sseForwardService.forwardSSE(any(), any(), any()))
            .willReturn(Flux.just(
                "data: {\"type\":\"start\"}\n\n",
                "data: {\"type\":\"token\",\"content\":\"你好\"}\n\n",
                "data: {\"type\":\"end\"}\n\n"
            ));

        webTestClient.post()
            .uri("/api/ai/chat/stream")
            .bodyValue(new ChatRequest("测试", null, true))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentType("text/event-stream")
            .expectBody(String.class)
            .consumeWith(response -> {
                String body = response.getResponseBody();
                assertThat(body).contains("data: {\"type\":\"start\"}");
            });
    }
}
```

### 集成测试

```bash
# 1. 启动 FastAPI 服务
cd yibccc-langchain && uv run uvicorn src.api.main:app --reload

# 2. 启动 SpringBoot
cd sky-chuanqin/sky-server && mvn spring-boot:run

# 3. 测试流式对话
curl -N -X POST http://localhost:8080/api/ai/chat/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d '{"message":"你好","stream":true}'

# 4. 测试诊断分析
curl -N -X POST http://localhost:8080/api/ai/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -d @test_diagnosis.json
```

---

## 部署

### Docker Compose

```yaml
version: '3.8'

services:
  sky-server:
    build: ./sky-chuanqin/sky-server
    ports:
      - "8080:8080"
    environment:
      - AGENT_BASE_URL=http://agent-service:8000
      - AGENT_API_KEY=${AGENT_API_KEY}
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      - agent-service
      - mysql
      - redis

  agent-service:
    build: ./yibccc-langchain
    ports:
      - "8000:8000"
    environment:
      - REDIS_URL=redis://redis:6379
      - PG_HOST=postgres
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
    depends_on:
      - redis
      - postgres

  mysql:
    image: mysql:8
    environment:
      - MYSQL_ROOT_PASSWORD=root
    volumes:
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    volumes:
      - redis_data:/data

  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=postgres
      - POSTGRES_DB=yibccc_chat
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  mysql_data:
  redis_data:
  postgres_data:
```

### 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| Agent 请求延迟 | p50/p95/p99 | p99 > 10s |
| Agent 错误率 | 失败请求比例 | > 5% |
| SSE 连接数 | 当前活跃连接 | 监控趋势 |
| SSE 超时率 | 超时连接比例 | > 2% |

---

## 实施计划

### 阶段一：基础架构（第1-2天）
1. 添加 WebFlux 依赖到 pom.xml
2. 创建 `com.kira.server.controller.ai` 包结构
3. 实现 `AgentWebClientConfig` 配置类
4. 实现 `SSEForwardService` 核心转发服务

### 阶段二：对话功能（第3-4天）
1. 实现 `ChatController`
2. 创建 `ChatRequest` DTO
3. 测试流式对话 SSE 转发
4. 前端对接 `streamChat` API

### 阶段三：诊断功能（第5-6天）
1. 实现 `DiagnosisController`
2. 创建 `DiagnosisRequest` DTO
3. 实现回调接收接口
4. 测试诊断分析 SSE 转发
5. 前端对接 `analyzeDiagnosis` API

### 阶段四：集成测试（第7天）
1. 端到端测试
2. 性能测试
3. 错误场景验证
4. 文档完善

---

## 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| WebFlux 与 MyBatis 不兼容 | 中 | AI 模块独立，不影响现有业务 |
| SSE 连接超时 | 中 | 设置合理 timeout，错误重试 |
| FastAPI 服务不可用 | 高 | 实现降级逻辑，返回友好错误 |
| 内存泄漏（长连接） | 中 | 监控连接数，设置超时断开 |

---

## 总结

本设计采用**混合模式**，在现有 Spring Web 架构中新增 WebFlux 模块，实现：

1. **最小改动**：现有 Controller 完全不动
2. **清晰隔离**：AI 模块独立包路径 `com.kira.server.controller.ai`
3. **SSE 透传**：使用 `Flux<String>` 直接透传 FastAPI 的 SSE 流
4. **统一认证**：复用现有 JWT 过滤器
5. **渐进式**：可独立部署和扩展 AI 模块

**预期效果**：
- Vue 前端通过 `/api/ai/chat/stream` 和 `/api/ai/diagnosis/analyze` 接入
- SpringBoot 作为中间层负责认证和转发
- FastAPI 专注 AI 能力，无需处理业务逻辑

---

**文档版本**: 1.0.0
**创建日期**: 2026-02-25
**维护者**: Backend Team
