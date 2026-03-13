# AI 诊断链路详细设计

## * 文档定位

* 本文是当前 AI 诊断链路的唯一详细设计文档。
* 与 AI 诊断相关的历史实现方案、面试话术、接口汇总、联调草稿，只作为归档材料，不再作为现行设计依据。
* 如果旧文档与当前代码或本文冲突，以当前代码和本文为准。
* 本文只覆盖正式链路，不覆盖联调阶段的临时测试页实现细节。

## * 文档目的

* 说明真实前端接入 AI 诊断能力时的正式链路。
* 说明 SpringBoot 与 FastAPI 在这条链路中的职责分工。
* 沉淀联调过程中已经暴露出的高频问题，降低后续排障成本。
* 明确“联调阶段临时测试页”只是辅助工具，不属于正式架构。

## * 正式链路范围

* 正式链路假设真实前端页面和业务交互已经完成。
* 本文描述的链路如下：

```text
前端页面/业务调用
  -> SpringBoot /api/ai/diagnosis/analyze
  -> SSEForwardService
  -> FastAPI /api/v1/diagnosis/analyze
  -> DiagnosisService
  -> DiagnosisAgent
  -> SSE 事件回传
  -> SpringBoot 转发给前端
```

* 联调阶段使用过的 `ai-diagnosis-test.html` 仅用于排查和演示，不作为正式前端架构的一部分。

## * 链路角色划分

### * 前端

* 负责收集诊断请求参数。
* 负责向 SpringBoot 发起 `POST /api/ai/diagnosis/analyze`。
* 负责以 SSE 方式接收诊断事件并实时展示。
* 负责将错误事件和正常事件分流展示，避免把所有失败都理解为“Spring 报错”。

### * SpringBoot

* 对前端暴露统一的 AI 诊断入口：`/api/ai/diagnosis/analyze`。
* 负责接收前端 JSON，并反序列化为 `DiagnosisRequest`。
* 负责将请求重新序列化后转发给 FastAPI。
* 负责将 FastAPI 返回的 SSE 原样或近原样透传给前端。
* 负责在上游失败时，将 `4xx/5xx` 响应体包装为前端可读的 SSE `error` 事件。

### * FastAPI

* 负责对请求体做最终模型校验。
* 负责创建诊断任务、保存任务事件、执行业务分析。
* 负责通过 `DiagnosisAgent` 组织 prompt、调用模型和工具、产出结构化结果。
* 负责将分析过程和结果按 SSE 事件输出。

## * SpringBoot 侧实现

### * Controller

* 入口控制器为 `DiagnosisController`。
* 入口路径是 `POST /api/ai/diagnosis/analyze`。
* 返回类型是 `Flux<String>`，内容类型是 `text/event-stream`。
* Controller 本身不做诊断分析，只负责接收请求和调用 `SSEForwardService`。

### * DTO

* SpringBoot 侧诊断请求对象为 `DiagnosisRequest`。
* 该 DTO 既承担“接收前端请求”的职责，也承担“转发给 FastAPI”的职责。
* 这意味着 DTO 的 JSON 字段命名、时间序列化格式，必须与 FastAPI 的 Pydantic 模型严格一致。

### * SSEForwardService

* `forwardSSE()` 负责把请求转发到 FastAPI，并将响应流转成 `Flux<String>`。
* `forwardSSEWithCache()` 在转发的同时，会收集完整结果用于缓存。
* 当上游返回业务错误时，`SSEForwardService` 现在会捕获 `WebClientResponseException`，将状态码和响应体包装成 SSE `error` 事件返回前端。
* 当上游不可达时，`SSEForwardService` 会捕获 `WebClientRequestException`，返回友好的错误提示。

## * FastAPI 侧实现

### * 路由入口

* FastAPI 侧入口为 `POST /api/v1/diagnosis/analyze`。
* 请求模型为 `DiagnosisRequest`。
* 路由层负责：
* 鉴权依赖校验。
* 请求体模型校验。
* 将请求交给 `DiagnosisService.analyze()`。
* 将异步事件流包装成 `StreamingResponse`。

### * DiagnosisService

* 负责创建任务记录。
* 负责消费 `DiagnosisAgent.analyze()` 输出的事件流。
* 负责保存事件、保存结果、更新任务状态。
* 负责在分析完成后处理回调逻辑。

### * DiagnosisAgent

* 负责构建分析 prompt。
* 负责调用大模型和工具链。
* 负责把中间过程包装成 `thinking`、`tool_call`、`tool_result`、`result`、`done` 等事件。
* 负责在异常时输出 `error` 事件，而不是让异常直接中断调用方。

## * 请求与响应约定

### * 前端到 SpringBoot

* 前端发送 JSON 请求体。
* 推荐最小字段包括：
* `alert_id`
* `well_id`
* `alert_type`
* `alert_triggered_at`
* `alert_threshold`
* `samples`
* `stream`

### * SpringBoot 到 FastAPI

* SpringBoot 不是直接透传原始 JSON，而是：
* 先把 JSON 反序列化成 Java DTO。
* 再把 Java DTO 序列化成新的 JSON 请求体。
* 因此，哪怕前端传的是正确 JSON，只要 Java DTO 的字段映射或时间格式定义不对，转发后的 JSON 仍然可能与 FastAPI 模型不兼容。

### * FastAPI 到 SpringBoot 到前端

* 正常情况下，返回的是标准 SSE 文本块：

```text
data: {"type":"start", ...}

data: {"type":"thinking", ...}

data: {"type":"result", ...}

data: {"type":"done", ...}
```

* 异常情况下，也应尽量返回 SSE `error` 事件，而不是直接让中间层抛成 HTML 500。

## * 已确认的高风险坑

### * 坑 1：字段名在 Java 和 Python 之间不一致

* 症状：
* 前端传参看起来没问题，但 FastAPI 返回 `422 Unprocessable Entity`。
* 原因：
* Java DTO 使用驼峰字段，Python 模型使用蛇形字段。
* 如果 Java 字段缺少 `@JsonProperty`，Spring 转发给 FastAPI 时字段名会错。
* 本次已经遇到过：
* `rpm_3`、`rpm_6`、`rpm_100`、`rpm_200`、`rpm_300`、`rpm_600` 未正确映射。
* 建议：
* 所有跨语言 DTO 字段都显式写 `@JsonProperty`。
* 不要依赖“看起来会自动转换”。

### * 坑 2：前端传的是时间字符串，但 Spring 转发时可能变成时间数组

* 症状：
* FastAPI 返回 `422`，并提示：
* `Input should be a valid datetime`
* `input: [2024,1,1,10,0]`
* 原因：
* `LocalDateTime` 在 WebClient 侧默认序列化行为下，可能输出成数组，而不是 ISO 字符串。
* 本次已经遇到过：
* `alert_triggered_at`
* `samples[0].sample_time`
* 建议：
* 对跨服务的 `LocalDateTime` 字段显式加 `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")`。
* 不要只验证 Controller 入参能反序列化，还要验证“转发出去的 JSON 长什么样”。

### * 坑 3：前端看到 500，不代表根因在 SpringBoot

* 症状：
* 页面展示：
* `请求失败 500`
* 但日志中真正错误是 FastAPI `422`。
* 原因：
* 如果 SpringBoot 直接使用 `WebClient.retrieve()`，又不处理 `WebClientResponseException`，上游 `4xx/5xx` 很容易在中间层表现成笼统 500。
* 建议：
* 中间层必须捕获上游响应异常，并把状态码和响应体透传给前端。
* 联调时要优先看“上游返回体”，不要只看浏览器状态码。

### * 坑 4：可选上下文字段在业务代码里被当成必填

* 症状：
* FastAPI 已经进入分析流程，先返回 `start`，随后返回：
* `'NoneType' object has no attribute 'current_depth'`
* 原因：
* 模型层允许 `context` 为可选，但 `DiagnosisAgent._build_analysis_prompt()` 里直接读取 `request.context.current_depth`。
* 建议：
* 对 `context` 这类可选字段，业务层必须兜底。
* 不要把“模型允许为空”和“代码默认不为空”混在一起。

### * 坑 5：前端只看解析后的 SSE 事件，容易遗漏原始流问题

* 症状：
* 页面里看到的是 `raw` 事件，而不是预期的结构化事件。
* 原因：
* SSE 原始文本可能有格式异常、重复前缀、转义问题，导致前端 JSON 解析失败。
* 本次已经见到的现象：
* `data:data: {...}`
* 建议：
* 联调工具和真实前端都应该同时保留：
* 原始 SSE 文本视图
* 解析后的事件视图
* 这样才能区分“后端没返回对”还是“前端解析有问题”。

### * 坑 6：Spring MVC 返回 Flux 时会有异步执行器告警

* 症状：
* 日志出现：
* `Streaming through a reactive type requires an Executor to write to the response`
* 原因：
* 当前项目是 Spring MVC + WebFlux 混合使用，Controller 返回 `Flux<String>` 时，MVC 需要异步执行器写响应。
* 影响：
* 这不是当前 422/500 的根因，但在高并发或长连接场景下不是健康状态。
* 建议：
* 后续补一个合适的 MVC async `TaskExecutor`，不要长期依赖 `SimpleAsyncTaskExecutor`。

## * 联调建议

* 先验证前端请求 JSON 是否满足接口契约。
* 再验证 SpringBoot 接收后的 DTO 是否映射正确。
* 再验证 SpringBoot 转发给 FastAPI 的最终 JSON 长什么样。
* 如果前端拿到 500，第一时间看 SpringBoot 是否已经把上游 `detail` 透出来。
* 如果 FastAPI 已经返回 `start` 再返回 `error`，说明问题已经进入业务逻辑层，而不是请求模型校验层。

## * 推荐排障顺序

* 第一步：看前端拿到的是 HTTP 错误，还是 SSE `error` 事件。
* 第二步：如果是 SSE `error`，优先读 `content` 里的上游明细。
* 第三步：如果是 FastAPI `422`，先检查字段名和时间格式。
* 第四步：如果是 FastAPI 业务异常，检查 `DiagnosisService` 和 `DiagnosisAgent` 是否错误假设某些字段一定存在。
* 第五步：如果事件流解析异常，同时检查原始 SSE 文本和前端解析逻辑。

## * 补充说明

* 当前已经存在联调用静态测试页，可用于快速验证请求体和 SSE 展示。
* 但正式接入应以真实前端页面为准，测试页只作为辅助排障工具。
* 后续如果前端协议稳定，建议再补一份“前端接入规范”，明确：
* 请求字段最小集
* SSE 事件类型约定
* 错误展示策略
* 重试与超时处理方式

## * 面试回答模板

### * 如果面试官问：AI 诊断链路的完整流程是什么？

* 可以简短回答：

```text
完整链路是：前端先调用 SpringBoot 的 /api/ai/diagnosis/analyze，
SpringBoot 用 DiagnosisController 接收请求后，交给 SSEForwardService 转发到 FastAPI 的 /api/v1/diagnosis/analyze。
FastAPI 先做请求校验，然后由 DiagnosisService 创建任务并调用 DiagnosisAgent 做分析，
分析过程会持续产出 start、thinking、result、done 这类 SSE 事件，
再通过 SpringBoot 实时转发给前端展示。
如果中间有校验错误或业务异常，SpringBoot 也会把上游错误包装成 SSE error 事件返回前端。
```

### * 如果想再更口语一点

* 可以这样答：

```text
这条链路本质上是一个“前端发起，SpringBoot中转，FastAPI分析，再把流式结果回传前端”的过程。
SpringBoot负责统一入口和协议转换，FastAPI负责真正的诊断分析，前端负责消费 SSE 实时展示结果。
```
