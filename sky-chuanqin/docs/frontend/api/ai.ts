/**
 * AI API 模块
 * 用于 Vue 前端调用 SpringBoot AI 接口
 */

// API 基础 URL
const API_BASE = '/api/ai';

// SSE 事件类型定义
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
  step?: string
}

export interface DiagnosisRequest {
  well_id: string
  alert_type: string
  alert_triggered_at?: string
  alert_threshold?: {
    field: string
    condition: string
    threshold: number
    current_value: number
  }
  samples?: any[]
  context?: {
    current_depth?: number
    formation_type?: string
    drilling_phase?: string
  }
  callback_url?: string
  stream?: boolean
}

/**
 * 流式对话 API
 *
 * @param message 用户消息
 * @param sessionId 会话ID（可选）
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function streamChat(
  message: string,
  sessionId: string | undefined,
  onEvent: (event: ChatSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
    body: JSON.stringify({ message, session_id: sessionId, stream: true }),
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: ChatSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}

/**
 * 诊断分析 API
 *
 * @param request 诊断请求
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function analyzeDiagnosis(
  request: DiagnosisRequest,
  onEvent: (event: DiagnosisSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/diagnosis/analyze`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
    body: JSON.stringify({ ...request, stream: true }),
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: DiagnosisSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}

/**
 * 查询诊断结果 API
 *
 * @param taskId 任务ID
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function getDiagnosisResult(
  taskId: string,
  onEvent: (event: DiagnosisSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/diagnosis/${taskId}`, {
    method: 'GET',
    headers: {
      'Authorization': token ? `Bearer ${token}` : '',
    },
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: DiagnosisSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}
