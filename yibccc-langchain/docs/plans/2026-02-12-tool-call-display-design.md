# 工具调用流程展示设计文档

**日期**: 2026-02-12
**项目**: yibccc-langchain
**目标**: 在前端展示完整的工具调用流程

## 📋 需求汇总

| 项目 | 选择 |
|------|------|
| 架构模式 | B - 代理模式（SpringBoot Flux 转发） |
| 展示粒度 | B - 详细模式（工具名 + 参数 + 时长 + 结果） |
| 执行状态 | B - 三态（调用中 → 处理中 → 结果） |
| 多工具展示 | B - 折叠组（将相关工具调用折叠在一个"工具调用"块中，可展开查看） |
| 思考状态 | A - 固定显示（收到用户消息后立即显示，直到 AI 回复开始） |

## 🎯 展示效果示例

```
👤 用户: 今天几号？
🤖 思考中...（AI调用工具）
     工具调用: get_current_time
     工具结果: 2026-02-12
🤖 助手: 今天是2026年2月12日。
```

---

## 第一部分：整体架构与数据流

### 数据流向

```
┌─────────┐      SSE      ┌──────────────┐      SSE      ┌─────────┐
│  Vue3   │ ◄────────────► │ SpringBoot  │ ◄────────────► │  Agent  │
│ 前端     │   (带鉴权 Token)   │  WebFlux    │                 │ FastAPI │
└─────────┘                   └──────────────┘                 └─────────┘
```

### 各模块职责

#### Agent 端（当前项目）
1. 捕获工具调用事件：监听 LangChain Agent 的工具调用生命周期
2. 发送标准化 SSE 事件：将工具调用状态转换为 `ChatResponse` 事件
3. 执行时长统计：记录每个工具的执行时间

#### SpringBoot 端
1. JWT 鉴权：验证用户身份
2. SSE 代理转发：无修改地将 Agent 的 SSE 事件转发给 Vue3
3. 流式超时管理：防止长连接占用资源

#### Vue3 端
1. SSE 消费：监听 SpringBoot 转发的 SSE 事件
2. 状态管理：维护对话状态和工具调用状态机
3. UI 渲染：展示工具调用的折叠组和详细信息

---

## 第二部分：SSE 事件数据结构

### Agent 端事件定义（扩展 `ChatResponse`）

在 `src/models/schemas.py` 的 `ChatResponse` 基础上增强 `tool_data` 结构：

```python
# 扩展后的 tool_data 结构
{
    "call_id": "uuid",           # 工具调用唯一ID（用于关联状态）
    "name": "get_current_time",  # 工具名称
    "arguments": {"format": ...}, # 工具参数（详细模式）
    "status": "calling",         # calling | processing | result
    "result": "...",             # 工具执行结果
    "error": None,               # 错误信息（如有）
    "duration_ms": 125           # 执行时长（毫秒）
}
```

### 事件类型序列

一次完整工具调用的 SSE 事件序列：

| 事件顺序 | type | tool_data.status | 前端展示 |
|---------|------|-----------------|---------|
| 1 | `start` | - | 🤖 思考中... |
| 2 | `tool_call` | `calling` | 🔄 正在调用: get_current_time |
| 3 | `tool_call` | `processing` | ⚙️ 执行中: get_current_time |
| 4 | `tool_result` | `result` | ✅ 结果: 2026-02-12 (125ms) |
| 5 | `token` | - | 今天是... |
| 6 | `end` | - | (结束) |

### 关键设计点

1. **call_id**：关联同一工具调用的不同状态事件
2. **duration_ms**：在 `tool_result` 事件中附带总耗时
3. **status**：三态枚举，前端据此切换 UI 图标和动画

---

## 第三部分：Agent 端实现（FastAPI + LangChain）

### 核心修改：`src/services/chat_service.py`

需要在工具调用生命周期中注入事件发送逻辑。当前代码在 **126-127行** 跳过了工具消息，需要改为主动发送工具事件。

### 方案概述

使用 `CustomCallbackHandler` 或 `astream_events` 监听工具调用：

```python
# 伪代码示意
async def stream_chat_events(request, author_id):
    async for event in agent.astream(...):
        if event[1].get("name") == "Agent":  # AIMessage
            msg = event[1]["messages"][0]

            # 检测工具调用
            if hasattr(msg, "tool_calls") and msg.tool_calls:
                for tc in msg.tool_calls:
                    # 发送 calling 状态
                    yield ChatResponse.tool_call(tc.name, tc.args, status="calling")

                    # 发送 processing 状态
                    yield ChatResponse.tool_call(tc.name, tc.args, status="processing")

            # 流式返回 AI 回复
            async for token in msg.content:
                yield ChatResponse.token(token)

        elif event[1].get("name") == "tools":  # ToolMessage
            # 发送工具结果
            yield ChatResponse.tool_result(tool_name, result, duration_ms)
```

### 执行时长统计

使用 `time.perf_counter()` 在工具执行前后记录时间：

```python
start = time.perf_counter()
result = await tool.ainvoke(**args)
duration_ms = int((time.perf_counter() - start) * 1000)
```

### 需要修改的文件

1. **`src/models/schemas.py`** - 扩展 `ChatResponse` 的 `tool_data` 结构
2. **`src/services/chat_service.py`** - 实现工具事件监听和发送

---

## 第四部分：SpringBoot 端实现（Java 17 + WebFlux）

### 核心职责：SSE 透传 + JWT 鉴权

```java
@RestController
@RequestMapping("/api/agent")
public class AgentProxyController {

    private final WebClient webClient;
    private final JwtValidator jwtValidator;

    @Value("${agent.base-url:http://localhost:8000}")
    private String agentBaseUrl;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam(required = false) String sessionId,
            @RequestParam String message,
            @RequestParam(defaultValue = "true") boolean stream,
            @RequestHeader("Authorization") String authHeader) {

        // 1. JWT 鉴权
        String userId = jwtValidator.validate(authHeader);
        if (userId == null) {
            return Flux.error(new UnauthorizedException());
        }

        // 2. 构建目标 URL
        String agentUrl = UriComponentsBuilder.fromHttpUrl(agentBaseUrl + "/chat")
                .queryParam("session_id", sessionId)
                .queryParam("message", message)
                .queryParam("stream", stream)
                .build()
                .toUriString();

        // 3. 代理 SSE 流
        return webClient.get()
                .uri(agentUrl)
                .header("X-User-Id", userId)
                .retrieve()
                .bodyToFlux(String.class)
                .map(data -> ServerSentEvent.<String>builder().data(data).build())
                .doOnError(e -> log.error("Agent stream error for user {}", userId, e));
    }
}
```

### 配置要点

1. **WebClient 超时**：设置较长超时（如 5 分钟）支持长连接
2. **错误处理**：Agent 宕机时返回友好的 `error` 事件
3. **跨域配置**：允许 Vue3 前端域名

### 需要新增的文件

1. **`AgentProxyController.java`** - SSE 代理控制器
2. **`WebClientConfig.java`** - WebClient Bean 配置
3. **`JwtValidator.java`** - JWT 验证器（可能已存在）

---

## 第五部分：Vue3 前端实现

### 组件设计

```
ChatMessage.vue
├── UserMessage.vue      # 用户消息
├── AIMessage.vue        # AI 回复（含思考状态 + 工具调用折叠组）
│   ├── ThinkingIndicator.vue    # "🤖 思考中..."
│   └── ToolCallGroup.vue         # 工具调用折叠组
│       └── ToolCallItem.vue     # 单个工具调用详情
```

### 状态管理（Pinia）

```typescript
// stores/chatStore.ts
interface ToolCallState {
  callId: string
  name: string
  status: 'calling' | 'processing' | 'result'
  arguments: Record<string, any>
  result?: string
  durationMs?: number
  error?: string
}

interface ChatState {
  messages: Message[]
  currentToolCalls: ToolCallState[]  // 当前消息关联的工具调用
  isThinking: boolean
}
```

### SSE 监听逻辑

```typescript
// composables/useAgentStream.ts
export function useAgentStream() {
  const chatStore = useChatStore()

  async function sendMessage(message: string, sessionId?: string) {
    // 设置思考状态
    chatStore.setThinking(true)

    const eventSource = new EventSource(
      `/api/agent/chat?message=${encodeURIComponent(message)}&session_id=${sessionId || ''}`,
      { headers: { Authorization: `Bearer ${getToken()}` } }
    )

    eventSource.onmessage = (event) => {
      const data = JSON.parse(event.data)

      switch (data.type) {
        case 'start':
          sessionId = data.session_id
          break

        case 'tool_call':
          // 更新工具调用状态
          chatStore.updateToolCall({
            callId: data.tool_data.call_id,
            name: data.tool_data.name,
            status: data.tool_data.status,
            arguments: data.tool_data.arguments
          })
          break

        case 'tool_result':
          // 更新工具结果
          chatStore.updateToolCall({
            callId: data.tool_data.call_id,
            status: 'result',
            result: data.tool_data.result,
            durationMs: data.tool_data.duration_ms
          })
          break

        case 'token':
          // 流式追加 AI 回复
          chatStore.appendToken(data.content)
          chatStore.setThinking(false)
          break

        case 'end':
          eventSource.close()
          break
      }
    }
  }

  return { sendMessage }
}
```

### UI 渲染效果

```vue
<!-- AIMessage.vue -->
<template>
  <div class="ai-message">
    <!-- 思考状态 -->
    <ThinkingIndicator v-if="isThinking" />

    <!-- 工具调用折叠组 -->
    <ToolCallGroup v-if="toolCalls.length > 0" :calls="toolCalls" />

    <!-- AI 回复内容 -->
    <div class="ai-content">{{ content }}</div>
  </div>
</template>
```

```vue
<!-- ToolCallItem.vue -->
<template>
  <div class="tool-call-item">
    <!-- 状态图标 -->
    <span class="status-icon">
      {{ statusIcon }}
    </span>

    <!-- 工具信息 -->
    <div class="tool-info">
      <span class="tool-name">{{ name }}</span>

      <!-- 详细模式：显示参数 -->
      <code v-if="status === 'result'" class="tool-args">
        {{ formatArgs(arguments) }}
      </code>

      <!-- 结果 -->
      <div v-if="status === 'result'" class="tool-result">
        {{ result }}
        <span class="duration">({{ durationMs }}ms)</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const statusIcon = computed(() => ({
  'calling': '🔄',
  'processing': '⚙️',
  'result': '✅'
}))
</script>
```

### 需要新增的文件

1. **`stores/chatStore.ts`** - 对话状态管理
2. **`composables/useAgentStream.ts`** - SSE 流处理
3. **`components/chat/AIMessage.vue`** - AI 消息组件
4. **`components/chat/ToolCallGroup.vue`** - 工具调用折叠组
5. **`components/chat/ToolCallItem.vue`** - 单个工具调用项
6. **`components/chat/ThinkingIndicator.vue`** - 思考状态指示器

---

## 第六部分：错误处理与边界情况

### Agent 端错误处理

| 场景 | 处理方式 | SSE 事件 |
|------|---------|---------|
| 工具执行失败 | 捕获异常，返回错误信息 | `tool_result` + `error` 字段 |
| 超时 | 设置工具执行超时（如 30s） | `error` + `timeout` 代码 |
| LLM 服务异常 | 降级处理或友好提示 | `error` + `llm_error` |

```python
# schemas.py 扩展错误类型
class ToolCallResult(BaseModel):
    name: str
    status: Literal["pending", "success", "failed"]
    result: Optional[str] = None
    error: Optional[str] = None  # 错误堆栈或用户友好消息
```

### SpringBoot 端错误处理

| 场景 | 处理方式 |
|------|---------|
| Agent 服务不可用 | 返回 `503 Service Unavailable`，前端显示重试按钮 |
| 鉴权失败 | 返回 `401 Unauthorized`，前端跳转登录 |
| 连接超时 | 配置 `timeout`，断线后前端自动重连 |

```java
@ControllerAdvice
public class AgentErrorHandler {

    @ExceptionHandler(WebClientRequestException.class)
    public ResponseEntity<ErrorResponse> handleAgentUnavailable() {
        return ResponseEntity.status(503)
            .body(new ErrorResponse("AGENT_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试"));
    }
}
```

### Vue3 端错误处理

```typescript
// useAgentStream.ts
eventSource.onerror = (error) => {
  chatStore.setError({
    code: 'CONNECTION_ERROR',
    message: '连接中断，请检查网络后重试'
  })
  eventSource.close()
}

// AIMessage.vue - 显示错误状态
<div v-if="tool.error" class="tool-error">
  ❌ 工具执行失败: {{ tool.error }}
</div>
```

### 测试用例清单

| 模块 | 测试场景 |
|------|---------|
| **Agent** | 工具成功调用、工具超时、工具异常、并发工具调用 |
| **SpringBoot** | JWT 鉴权、SSE 转发、Agent 宕机降级 |
| **Vue3** | 工具状态切换、折叠组展开/收起、错误提示渲染 |

---

## 📝 实施任务清单

### Agent 端（当前项目）

- [ ] 扩展 `ChatResponse.tool_data` 结构（schemas.py）
- [ ] 修改 `chat_service.py` 实现工具事件监听
- [ ] 实现工具执行时长统计
- [ ] 编写单元测试

### SpringBoot 端

- [ ] 创建 `AgentProxyController.java`
- [ ] 配置 WebClient Bean
- [ ] 实现错误处理器
- [ ] 集成测试

### Vue3 端

- [ ] 创建 `chatStore.ts` 状态管理
- [ ] 创建 `useAgentStream.ts` SSE 处理
- [ ] 创建相关组件（AIMessage, ToolCallGroup 等）
- [ ] E2E 测试
