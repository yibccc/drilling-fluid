# AI 功能前端对接文档

> **版本**: 2.0.0
> **更新日期**: 2026-02-26
> **后端服务**: sky-server (SpringBoot 2.7.3) + yibccc-langchain (FastAPI Agent)

---

## 目录

1. [概述](#概述)
2. [对话功能对接](#对话功能对接)
3. [诊断功能对接](#诊断功能对接)
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
| 诊断分析 | `/api/ai/diagnosis/analyze` | `/api/v1/diagnosis/analyze` | SpringBoot 转发 SSE |
| 查询诊断 | `/api/ai/diagnosis/{taskId}` | `/api/v1/diagnosis/{taskId}` | SpringBoot 转发 SSE |
| 查询缓存 | `/api/ai/diagnosis/stream?alertId=xxx` | - | SpringBoot 直接返回缓存 |
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

## 诊断功能对接

### 1. 发起诊断分析

**端点**: `POST /api/ai/diagnosis/analyze`

**请求**:

```typescript
interface DiagnosisRequest {
  well_id: string;                    // 井号
  alert_type: string;                 // 预警类型
  alert_triggered_at: string;         // 触发时间 (ISO 8601)
  alert_threshold?: AlertThreshold;   // 阈值信息
  samples?: DrillingFluidSample[];    // 采样数据
  context?: DiagnosisContext;         // 上下文
  callback_url?: string;              // 回调地址
  stream?: boolean;                   // 是否流式
}

interface AlertThreshold {
  field: string;
  condition: 'greater_than' | 'less_than' | 'equal';
  threshold: number;
  current_value: number;
}

interface DrillingFluidSample {
  id: string;
  well_id: string;
  sample_time: string;
  formation?: string;
  outlet_temp?: number;
  density?: number;
  gel_10s?: number;
  gel_10m?: number;
  rpm_3?: number;
  rpm_6?: number;
  rpm_100?: number;
  rpm_200?: number;
  rpm_300?: number;
  rpm_600?: number;
  plastic_viscosity?: number;
  yield_point?: number;
  // ... 更多字段
}

interface DiagnosisContext {
  current_depth?: number;
  formation_type?: string;
  drilling_phase?: string;
}
```

**SSE 响应事件**:

```typescript
type DiagnosisEventType = 'start' | 'thinking' | 'result' | 'done' | 'error';

type ThinkingStep =
  | 'data_analysis'    // 数据分析准备
  | 'analyzing'        // AI 分析中
  | 'tool_call'        // 调用工具
  | 'tool_result'      // 工具返回结果
  | 'reasoning'        // AI 思考内容
  | 'structuring';     // 生成结构化结果

interface DiagnosisSSEEvent {
  type: DiagnosisEventType;
  task_id?: string;
  well_id?: string;
  content?: string;
  step?: ThinkingStep;
  result?: DiagnosisResult;
  error_code?: string;
}

interface DiagnosisResult {
  diagnosis: {
    summary: string;
    cause: string;
    risk_level: 'LOW' | 'MEDIUM' | 'HIGH';
  };
  trend_analysis: TrendAnalysis[];
  measures: Measure[];
  prescription: {
    dilution_water?: string;
    mixing_time?: string;
  };
}
```

**前端实现**:

```typescript
class DiagnosisService {
  private baseUrl = '/api/ai/diagnosis';

  async analyze(request: DiagnosisRequest, callbacks: DiagnosisCallbacks): Promise<string> {
    const response = await fetch(`${this.baseUrl}/analyze`, {
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
    let taskId = '';

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
            const event: DiagnosisSSEEvent = JSON.parse(data);

            if (event.type === 'start' && event.task_id) {
              taskId = event.task_id;
            }

            await this.handleEvent(event, callbacks);
          }
        }
      }
    }

    return taskId;
  }

  private async handleEvent(event: DiagnosisSSEEvent, callbacks: DiagnosisCallbacks): Promise<void> {
    switch (event.type) {
      case 'start':
        callbacks.onStart?.(event.task_id!, event.well_id!);
        break;
      case 'thinking':
        callbacks.onThinking?.(event.content!, event.step!);
        break;
      case 'result':
        await callbacks.onResult?.(event.result!);
        break;
      case 'done':
        callbacks.onDone?.(event.task_id!);
        break;
      case 'error':
        callbacks.onError?.(event.error_code!, event.content!);
        break;
    }
  }

  // 查询缓存的诊断结果（WebSocket 预警后使用）
  async getCachedDiagnosis(alertId: string, callbacks: DiagnosisCallbacks): Promise<void> {
    const response = await fetch(`${this.baseUrl}/stream?alertId=${alertId}`);

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
            const event: DiagnosisSSEEvent = JSON.parse(data);
            await this.handleEvent(event, callbacks);
          }
        }
      }
    }
  }
}

interface DiagnosisCallbacks {
  onStart?: (taskId: string, wellId: string) => void;
  onThinking?: (content: string, step: ThinkingStep) => void;
  onResult?: (result: DiagnosisResult) => void | Promise<void>;
  onDone?: (taskId: string) => void;
  onError?: (code: string, message: string) => void;
}
```

### 2. 查询诊断结果

**端点**: `GET /api/ai/diagnosis/{taskId}`

用于查询已提交的诊断任务结果：

```typescript
async getDiagnosisResult(taskId: string): Promise<DiagnosisResult> {
  const response = await fetch(`/api/ai/diagnosis/${taskId}`);

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }

  const data = await response.json();
  return data.result;
}
```

### 3. 查询缓存诊断结果

**端点**: `GET /api/ai/diagnosis/stream?alertId={alertId}`

当收到 WebSocket 预警后，使用此接口获取缓存的诊断结果：

```typescript
const diagnosisService = new DiagnosisService();

// alertId 从 WebSocket 消息中获取
await diagnosisService.getCachedDiagnosis('ALERT-1234567890', {
  onResult: (result) => {
    displayDiagnosisResult(result);
  },
  onError: (code, message) => {
    console.error('诊断错误:', code, message);
  }
});
```

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

```typescript
// ==================== 类型定义 ====================
interface DiagnosisRequest {
  well_id: string;
  alert_type: string;
  alert_triggered_at: string;
  stream: boolean;
}

interface AiDiagnosisAlertMessage {
  type: 'AI_DIAGNOSIS_ALERT';
  alertId: string;
  wellId: string;
  wellLocation: string;
  alertType: string;
  severity: string;
  triggeredAt: number;
  status: string;
  diagnosisUrl: string;
}

// ==================== 诊断服务 ====================
class DiagnosisService {
  private baseUrl = '/api/ai/diagnosis';

  // 发起诊断
  async analyze(request: DiagnosisRequest): Promise<string> {
    const response = await fetch(`${this.baseUrl}/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let taskId = '';

    while (true) {
      const { done, value } = await reader!.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const event = JSON.parse(line.slice(6));

          if (event.type === 'start') {
            taskId = event.task_id;
            console.log('诊断开始:', taskId);
          } else if (event.type === 'thinking') {
            console.log('思考:', event.content);
          } else if (event.type === 'result') {
            console.log('结果:', event.result);
            // 显示结果
          } else if (event.type === 'done') {
            console.log('诊断完成');
          }
        }
      }
    }

    return taskId;
  }

  // 查询缓存结果
  async getCachedResult(alertId: string): Promise<any> {
    const response = await fetch(`${this.baseUrl}/stream?alertId=${alertId}`);
    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let result: any = null;

    while (true) {
      const { done, value } = await reader!.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const event = JSON.parse(line.slice(6));
          if (event.type === 'result') {
            result = event.result;
          }
        }
      }
    }

    return result;
  }
}

// ==================== WebSocket 客户端 ====================
class WebSocketClient {
  private ws: WebSocket | null = null;
  private handlers: Map<string, (data: any) => void> = new Map();

  connect(url: string) {
    this.ws = new WebSocket(url);

    this.ws.onopen = () => console.log('WebSocket 连接成功');

    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      const handler = this.handlers.get(message.type);
      if (handler) {
        handler(message);
      }
    };

    this.ws.onclose = () => {
      console.log('WebSocket 连接关闭，5秒后重连...');
      setTimeout(() => this.connect(url), 5000);
    };
  }

  on<T>(type: string, handler: (data: T) => void) {
    this.handlers.set(type, handler);
  }
}

// ==================== React 组件 ====================
function DiagnosisPage() {
  const [alerts, setAlerts] = useState<AiDiagnosisAlertMessage[]>([]);
  const [currentResult, setCurrentResult] = useState<any>(null);

  // 初始化 WebSocket
  useEffect(() => {
    const wsClient = new WebSocketClient();
    wsClient.connect('ws://localhost:18080/websocket');

    wsClient.on<AiDiagnosisAlertMessage>('AI_DIAGNOSIS_ALERT', (alert) => {
      setAlerts(prev => [alert, ...prev]);

      // 显示通知
      new Notification(`${alert.alertType}预警`, {
        body: `井号: ${alert.wellId}`,
      });

      // 自动加载结果
      if (alert.status === 'COMPLETED') {
        loadResult(alert.alertId);
      }
    });

    return () => {
      wsClient.disconnect();
    };
  }, []);

  // 加载诊断结果
  const loadResult = async (alertId: string) => {
    const service = new DiagnosisService();
    const result = await service.getCachedResult(alertId);
    setCurrentResult(result);
  };

  // 手动发起诊断
  const startDiagnosis = async () => {
    const service = new DiagnosisService();
    const request: DiagnosisRequest = {
      well_id: 'WELL-001',
      alert_type: '钙污染',
      alert_triggered_at: new Date().toISOString(),
      stream: true
    };

    await service.analyze(request);
  };

  return (
    <div>
      <button onClick={startDiagnosis}>发起诊断</button>

      <h2>预警列表</h2>
      {alerts.map(alert => (
        <div key={alert.alertId}>
          <span>{alert.alertType}</span>
          <span>{alert.wellId}</span>
          <span>{alert.severity}</span>
          <button onClick={() => loadResult(alert.alertId)}>
            查看结果
          </button>
        </div>
      ))}

      {currentResult && (
        <div>
          <h3>诊断结果</h3>
          <p>结论: {currentResult.diagnosis.summary}</p>
          <p>原因: {currentResult.diagnosis.cause}</p>
          <p>风险: {currentResult.diagnosis.risk_level}</p>
        </div>
      )}
    </div>
  );
}
```

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
