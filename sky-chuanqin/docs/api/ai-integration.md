# AI 功能前端对接文档

> **版本**: 2.0.0
> **更新日期**: 2026-02-26
> **后端服务**: sky-server (SpringBoot 2.7.3) + yibccc-langchain (FastAPI Agent)

---

## 目录

1. [概述](#概述)
2. [对话功能对接](#对话功能对接)
3. [诊断功能说明](#诊断功能说明)
4. [WebSocket 预警对接](#websocket-预警对接)
5. [SSE 事件处理](#sse-事件处理)
6. [完整示例](#完整示例)

---

## 概述

### 架构说明

```
┌─────────────┐      HTTP/SSE      ┌──────────────┐      HTTP      ┌─────────────────┐
│             │ ──────────────────> │              │ ─────────────> │                 │
│   前端应用   │                     │ sky-server   │               │ yibccc-langchain │
│             │ <────────────────── │  (SpringBoot)│ <──────────── │   (FastAPI)     │
└─────────────┘      WebSocket      │   端口:18080  │    SSE        │    端口:8000     │
                                     └──────────────┘               └─────────────────┘
                                               │                                │
                                               │                                │
                                     ┌─────────▼────────┐           ┌──────────▼──────────┐
                                     │      Redis       │           │    PostgreSQL       │
                                     │   (缓存/队列)    │           │   (数据持久化)      │
                                     └──────────────────┘           └─────────────────────┘
```

### 服务端点

| 功能 | SpringBoot 端点 | Agent 服务端点 | 说明 |
|------|----------------|---------------|------|
| 流式对话 | `/api/ai/chat/stream` | `/api/v1/chat/stream` | SpringBoot 转发 SSE |
| 诊断链路 | 见详细设计文档 | 见详细设计文档 | AI 诊断口径已统一收口 |
| 健康检查 | - | `/health` | Agent 服务健康状态 |

### SSE 事件类型

| 事件类型 | 用途 | 来源 |
|---------|------|------|
| `start` | 会话/任务开始 | 对话、诊断 |
| `token` | 文本片段流式输出 | 对话 |
| `tool_call` | 工具调用状态 | 对话 |
| `tool_result` | 工具执行结果 | 对话 |
| `thinking` | AI 思考过程 | 诊断 |
| `result` | 诊断结果 | 诊断 |
| `done` / `end` | 完成 | 对话、诊断 |
| `error` | 错误信息 | 对话、诊断 |

---

## 对话功能对接

### 1. 流式对话

**端点**: `POST /api/ai/chat/stream`

**请求**:

```typescript
interface ChatRequest {
  message: string;        // 用户消息
  session_id?: string;    // 会话 ID（首次为空）
  stream?: boolean;       // 是否流式，默认 true
}

// 示例
const request: ChatRequest = {
  message: "你好，请介绍一下钻井液的基本性能",
  stream: true
};
```

**SSE 响应事件**:

```typescript
type ChatEventType = 'start' | 'token' | 'tool_call' | 'tool_result' | 'end' | 'error';

interface ChatSSEEvent {
  type: ChatEventType;
  session_id?: string;
  content?: string;
  tool_data?: ToolCallData;
  error_code?: string;
}

interface ToolCallData {
  call_id: string;
  name: string;
  arguments: Record<string, any>;
  status: 'calling' | 'processing' | 'result';
  result?: string;
  duration_ms?: number;
}
```

**前端实现**:

```typescript
class ChatService {
  private baseUrl = '/api/ai';

  async streamChat(request: ChatRequest, callbacks: ChatCallbacks): Promise<void> {
    const response = await fetch(`${this.baseUrl}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader!.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const data = line.slice(6);
          if (data.trim()) {
            const event: ChatSSEEvent = JSON.parse(data);
            await this.handleEvent(event, callbacks);
          }
        }
      }
    }
  }

  private async handleEvent(event: ChatSSEEvent, callbacks: ChatCallbacks): Promise<void> {
    switch (event.type) {
      case 'start':
        callbacks.onStart?.(event.session_id!);
        break;
      case 'token':
        callbacks.onToken?.(event.content!);
        break;
      case 'tool_call':
        await callbacks.onToolCall?.(event.tool_data!);
        break;
      case 'tool_result':
        await callbacks.onToolResult?.(event.tool_data!);
        break;
      case 'end':
        callbacks.onEnd?.();
        break;
      case 'error':
        callbacks.onError?.(event.error_code!, event.content!);
        break;
    }
  }
}

interface ChatCallbacks {
  onStart?: (sessionId: string) => void;
  onToken?: (token: string) => void;
  onToolCall?: (toolData: ToolCallData) => void | Promise<void>;
  onToolResult?: (toolData: ToolCallData) => void | Promise<void>;
  onEnd?: () => void;
  onError?: (code: string, message: string) => void;
}
```

**使用示例**:

```typescript
const chatService = new ChatService();

await chatService.streamChat(
  { message: "现在几点了？", stream: true },
  {
    onStart: (sessionId) => {
      console.log('会话开始:', sessionId);
      // 保存 sessionId 用于后续对话
    },
    onToken: (token) => {
      // 追加到显示区域
      appendMessage(token);
    },
    onToolCall: (toolData) => {
      // 显示工具调用状态
      if (toolData.status === 'calling') {
        showToolStatus(`🔄 调用工具: ${toolData.name}`);
      } else if (toolData.status === 'processing') {
        showToolStatus(`⚙️ 执行中: ${toolData.name}`);
      }
    },
    onToolResult: (toolData) => {
      // 显示工具结果
      showToolResult(`✅ ${toolData.name}: ${toolData.result} (${toolData.duration_ms}ms)`);
    },
    onEnd: () => {
      console.log('对话结束');
      hideLoadingIndicator();
    },
    onError: (code, message) => {
      console.error('错误:', code, message);
      showErrorMessage(message);
    }
  }
);
```

---

## 诊断功能说明

> 本文不再维护 AI 诊断的正文说明。
>
> AI 诊断链路的唯一详细设计文档已经收口到：
> `/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/docs/detailed-design/AI_DIAGNOSIS_CHAIN_DESIGN.md`
>
> 本文中历史上保留过诊断请求模型、SSE 事件、查询方式与缓存说明，
> 这些内容已经出现过口径漂移，不再作为接入依据。

### 当前使用方式

* 前端对接 AI 诊断时，统一参考详细设计文档中的正式链路说明
* 诊断请求字段、返回事件、错误处理、缓存回放策略，全部以详细设计文档为准
* 如果需要前端代码示例，建议从当前页面实现或联调测试页出发，不再从本文复制旧示例

---

## WebSocket 预警对接

### AI_DIAGNOSIS_ALERT 消息格式

当 XXL-Job 检测到污染并触发 AI 诊断后，SpringBoot 会推送 WebSocket 预警消息：

```typescript
interface AiDiagnosisAlertMessage {
  type: 'AI_DIAGNOSIS_ALERT';
  alertId: string;              // 预警 ID，用于查询诊断结果
  wellId: string;               // 井 ID
  wellLocation: string;         // 井位置
  alertType: string;            // 预警类型（钙污染、二氧化碳污染、钻井液稳定性问题）
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  triggeredAt: number;          // 触发时间戳
  status: 'COMPLETED' | 'FAILED' | 'ERROR';
  diagnosisUrl: string;         // 诊断结果查询 URL
}
```

### WebSocket 连接实现

```typescript
class AiWebSocketClient {
  private ws: WebSocket | null = null;
  private url: string;
  private reconnectTimer: number | null = null;
  private messageHandlers: Map<string, (data: any) => void> = new Map();

  constructor(url: string) {
    this.url = url;
  }

  connect(): void {
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log('WebSocket 连接成功');
      this.clearReconnectTimer();
    };

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        const handler = this.messageHandlers.get(message.type);
        if (handler) {
          handler(message);
        }
      } catch (error) {
        console.error('解析 WebSocket 消息失败:', error);
      }
    };

    this.ws.onclose = () => {
      console.log('WebSocket 连接关闭');
      this.scheduleReconnect();
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket 错误:', error);
    };
  }

  onMessageType<T>(type: string, handler: (data: T) => void): void {
    this.messageHandlers.set(type, handler);
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;

    this.reconnectTimer = window.setTimeout(() => {
      console.log('尝试重连 WebSocket...');
      this.connect();
    }, 5000);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  disconnect(): void {
    this.clearReconnectTimer();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}
```

### 使用示例

```typescript
// 创建 WebSocket 客户端
const wsClient = new AiWebSocketClient('ws://localhost:18080/websocket');

// 监听 AI 诊断预警
wsClient.onMessageType<AiDiagnosisAlertMessage>('AI_DIAGNOSIS_ALERT', (alert) => {
  console.log('收到 AI 诊断预警:', alert);

  // 显示预警通知
  showNotification({
    title: `${alert.alertType}预警`,
    message: `井号: ${alert.wellId}, 位置: ${alert.wellLocation}`,
    severity: alert.severity,
    onClick: () => {
      // 点击后查询诊断结果
      loadDiagnosisResult(alert.alertId);
    }
  });

  // 如果状态是 COMPLETED，自动加载诊断结果
  if (alert.status === 'COMPLETED') {
    loadDiagnosisResult(alert.alertId);
  }
});

// 连接 WebSocket
wsClient.connect();

// 加载诊断结果
async function loadDiagnosisResult(alertId: string) {
  const diagnosisService = new DiagnosisService();

  await diagnosisService.getCachedDiagnosis(alertId, {
    onResult: (result) => {
      displayDiagnosisResult(result);
    },
    onError: (code, message) => {
      showErrorMessage(`诊断失败: ${message}`);
    }
  });
}
```

---

## SSE 事件处理

### 通用 SSE 处理器

```typescript
class SSEHandler {
  async processStream<T extends { type: string }>(
    response: Response,
    eventHandlers: Record<string, (event: T) => void | Promise<void>>
  ): Promise<void> {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader!.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const data = line.slice(6).trim();
          if (data) {
            const event: T = JSON.parse(data);
            const handler = eventHandlers[event.type];
            if (handler) {
              await handler(event);
            }
          }
        }
      }
    }
  }
}
```

### React Hook 封装

```typescript
import { useState, useCallback, useRef } from 'react';

interface UseSSEOptions<T> {
  onEvent?: (event: T) => void;
  onError?: (error: Error) => void;
  onComplete?: () => void;
}

function useSSEStream<T extends { type: string }>() {
  const [isConnected, setIsConnected] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const connect = useCallback(async (
    url: string,
    body: any,
    options: UseSSEOptions<T>
  ) => {
    setIsConnected(true);
    setError(null);

    abortControllerRef.current = new AbortController();

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: abortControllerRef.current.signal
      });

      const handler = new SSEHandler();
      await handler.processStream(response, {
        '*': (event: T) => {
          options.onEvent?.(event);
        }
      });

      options.onComplete?.();
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      setError(error);
      options.onError?.(error);
    } finally {
      setIsConnected(false);
    }
  }, []);

  const abort = useCallback(() => {
    abortControllerRef.current?.abort();
    setIsConnected(false);
  }, []);

  return { connect, abort, isConnected, error };
}
```

### React 使用示例

```typescript
function ChatComponent() {
  const [messages, setMessages] = useState<string[]>([]);
  const [toolCalls, setToolCalls] = useState<ToolCallData[]>([]);

  const { connect, abort, isConnected, error } = useSSEStream<ChatSSEEvent>();

  const sendMessage = async (message: string) => {
    setMessages(prev => [...prev, `用户: ${message}`]);

    let aiResponse = '';

    await connect('/api/ai/chat/stream', { message, stream: true }, {
      onEvent: (event) => {
        switch (event.type) {
          case 'start':
            console.log('会话开始:', event.session_id);
            break;
          case 'token':
            aiResponse += event.content!;
            setMessages(prev => {
              const updated = [...prev];
              updated[updated.length - 1] = `AI: ${aiResponse}`;
              return updated;
            });
            break;
          case 'tool_call':
            setToolCalls(prev => [...prev, event.tool_data!]);
            break;
          case 'tool_result':
            setToolCalls(prev =>
              prev.map(tc =>
                tc.call_id === event.tool_data!.call_id
                  ? event.tool_data!
                  : tc
              )
            );
            break;
          case 'end':
            console.log('对话结束');
            break;
        }
      },
      onError: (error) => {
        console.error('SSE 错误:', error);
      },
      onComplete: () => {
        console.log('流式传输完成');
      }
    });
  };

  return (
    <div>
      <div className="messages">
        {messages.map((msg, i) => (
          <div key={i}>{msg}</div>
        ))}
      </div>
      <div className="tool-calls">
        {toolCalls.map(tc => (
          <div key={tc.call_id}>
            <span>{tc.name}</span>
            <span>{tc.status}</span>
            {tc.result && <span>{tc.result}</span>}
          </div>
        ))}
      </div>
      <button
        onClick={() => sendMessage("你好")}
        disabled={isConnected}
      >
        发送
      </button>
      {error && <div className="error">{error.message}</div>}
    </div>
  );
}
```

---

## 完整示例

### 诊断功能完整流程

AI 诊断完整示例不再在本文维护。

请统一参考：
`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/docs/detailed-design/AI_DIAGNOSIS_CHAIN_DESIGN.md`

---

## 附录

### A. 错误代码

| 错误代码 | HTTP 状态 | 说明 |
|---------|---------|------|
| `AUTH_FAILED` | 401 | API Key 无效 |
| `LLM_ERROR` | 500 | LLM 调用失败 |
| `TOOL_ERROR` | 500 | 工具执行失败 |
| `DIAGNOSIS_ERROR` | 500 | 诊断分析失败 |

### B. 工具调用图标建议

| 状态 | 图标 | 颜色 |
|------|------|------|
| calling | 🔄 | 蓝色 |
| processing | ⚙️ | 橙色 |
| result | ✅ | 绿色 |

### C. 浏览器兼容性

| 功能 | Chrome | Firefox | Safari | Edge |
|------|--------|---------|--------|------|
| SSE | ✅ | ✅ | ✅ | ✅ |
| WebSocket | ✅ | ✅ | ✅ | ✅ |
| Fetch API | ✅ | ✅ | ✅ | ✅ |

---

**文档维护者**: Frontend Team
**最后更新**: 2026-02-26
