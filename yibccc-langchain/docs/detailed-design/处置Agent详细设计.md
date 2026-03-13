# 处置 Agent 详细设计

> 版本: 1.0
> 更新日期: 2026-03-13 20:23:49 +08:00
> 对应实现:
> - `src/agents/diagnosis_agent.py`
> - `src/tools/diagnosis_tools.py`
> - `src/models/diagnosis_schemas.py`
> - `src/services/diagnosis_service.py`
> - `src/repositories/diagnosis_repo.py`

> 相关文档:
> - [AI诊断与RAG文档索引.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/AI诊断与RAG文档索引.md)
> - [knowledge-import-consumer.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md)
> - [接口文档-每阶段汇总.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/接口文档-每阶段汇总.md)

## 1. 文档定位

本文描述 `yibccc-langchain` 当前“处置 Agent”能力的真实实现形态。

需要先明确一个边界：

- 当前代码中没有独立的 `TreatmentAgent`、`DisposalAgent` 或单独的处置微服务
- “处置 Agent”是 `DiagnosisAgent` 内部的一段逻辑能力
- 它和趋势分析、原因诊断、风险评估一起，组成一次完整的 AI 诊断输出
- 处置能力的最终产物是两部分：
  - `measures`：处置措施列表
  - `prescription`：配药方案

因此，本文中的“处置 Agent”是一个逻辑分层概念，不是一个单独部署的进程。

## 2. 业务目标

处置 Agent 的目标不是简单“给建议”，而是把诊断结论落到可执行动作上，解决下面几个问题：

- 当前异常应该先做什么，后做什么
- 每一步处置的优先级是什么
- 是否需要稀释、降黏、加重或补充其他药剂
- 处置动作应该持续多久、用量大概是多少
- 最终输出是否可以被前端实时展示、被数据库持久化、被 SpringBoot 回放

换句话说，趋势分析和原因判断解决“为什么出问题”，处置 Agent 解决“接下来怎么做”。

## 3. 在整体链路中的位置

当前完整链路可以抽象为：

```text
预警请求
  -> DiagnosisService
  -> DiagnosisAgent
     -> RetrievalMiddleware 注入知识上下文
     -> analyze_trend 工具做趋势分析
     -> LLM 汇总原因与风险
     -> format_prescription 工具生成配药建议
     -> 结构化输出 LLMDiagnosisOutput
  -> DiagnosisService 持久化 result / event
  -> SSE 持续返回前端
```

处置 Agent 位于 `DiagnosisAgent` 内部的后半段，依赖前半段已经拿到的三类信息：

- 采样数据与阈值信息
- 趋势分析结果
- 检索到的知识库上下文

## 4. 架构角色划分

### 4.1 DiagnosisAgent

`DiagnosisAgent` 是总控 Agent，负责：

- 构造统一系统提示词
- 绑定工具
- 接收中间件注入的知识上下文
- 流式消费模型输出
- 将最终结构化结果转换成 `DiagnosisResult`

在当前实现里，处置能力不是一个单独的 Agent 节点，而是 `DiagnosisAgent` 输出结构中的一部分。

### 4.2 RetrievalMiddleware

`src/agents/diagnosis_middleware.py` 负责在模型调用前完成知识增强：

- 从消息中提取用户查询
- 使用 `chunk_type=child` 过滤只召回知识子分块
- 将检索结果拼接成上下文注入模型

它不直接生成处置方案，但会显著影响处置建议的专业性和可解释性。

### 4.3 diagnose tools

`src/tools/diagnosis_tools.py` 当前只挂了两个工具：

- `analyze_trend`
- `format_prescription`

其中与处置 Agent 最直接相关的是 `format_prescription`。

### 4.4 DiagnosisService

`DiagnosisService` 不负责生成处置内容，但负责：

- 创建任务记录
- 转发 `DiagnosisAgent` 产出的 SSE 事件
- 在 `result` 事件到达时持久化完整结果
- 在 `done` 事件到达时更新任务状态

这保证了处置结果不是一次性文本，而是可以回查、缓存、回放的结构化业务数据。

## 5. 核心数据模型

### 5.1 结构化输出 Schema

`DiagnosisAgent` 使用 `ToolStrategy(LLMDiagnosisOutput)` 约束最终输出。与处置 Agent 直接相关的字段是：

```python
class LLMDiagnosisOutput(BaseModel):
    summary: str
    cause: str
    risk_level: str
    trend_outlook: Optional[str]
    trend_analysis: List[LLMTrendAnalysis]
    measures: List[LLMTreatmentMeasure]
    prescription: LLMPrescription
```

这里可以看出，处置能力并不是额外返回一个自由文本，而是直接成为结构化诊断结果的一部分。

### 5.2 领域模型映射

在 `src/models/diagnosis_schemas.py` 中，处置结果被定义成两个核心模型：

```python
class TreatmentMeasure(BaseModel):
    step: int
    action: str
    duration: Optional[str] = None
    amount: Optional[str] = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    notes: Optional[str] = None


class Prescription(BaseModel):
    dilution_water: Optional[str] = None
    viscosity_reducer: Optional[str] = None
    mixing_time: Optional[str] = None
    other_agents: Optional[Dict[str, str]] = None
```

这两个模型的职责不同：

- `TreatmentMeasure` 偏执行动作视角，强调步骤、动作、优先级
- `Prescription` 偏工艺配方视角，强调药剂与配比

这是一种很适合前端展示和数据库落表的拆分方式。

## 6. 处置 Agent 的执行过程

### 6.1 输入阶段

输入来自 `DiagnosisRequest`，关键字段包括：

- `alert_type`
- `alert_threshold`
- `samples`
- `context`

其中 `samples` 会先按时间倒序整理，保证最近样本优先参与分析。

### 6.2 提示词阶段

`DiagnosisAgent._build_analysis_prompt()` 会把诊断任务组织成一个明确指令：

- 先做趋势分析
- 再结合自动注入的知识上下文分析原因与措施
- 最后生成配药方案

当前提示词里已经把处置任务显式写出来：

```text
1. 趋势分析：使用 analyze_trend 工具分析主要参数趋势
2. 知识增强：结合系统自动注入的知识库上下文分析原因和处置措施
3. 生成配药方案：使用 format_prescription 工具生成具体方案
```

这意味着处置 Agent 的生成不是随意发挥，而是受流程约束的。

### 6.3 工具调用阶段

处置能力主要通过 `format_prescription()` 完成规则化补强。

当前工具输入：

- `measures`：处置措施描述
- `density`：当前密度
- `plastic_viscosity`：当前塑性黏度

当前工具内部采用轻量规则引擎：

- 当措施文本包含“密度高/密度偏高”时，给出稀释水 `8%`、搅拌 `45分钟`
- 当措施文本包含“黏度高/塑性黏度高”时，给出降黏剂 `0.3%`、稀释水 `5%`
- 当措施文本包含“密度低”时，给出加重剂 `重晶石 2%`

输出是一个格式化字符串，再由模型吸收后写入最终结构化输出。

### 6.4 结构化收敛阶段

模型最终产出 `LLMDiagnosisOutput` 后，`DiagnosisAgent._convert_to_diagnosis_result()` 会做一次领域收敛：

- 把 `LLMTreatmentMeasure` 转成 `TreatmentMeasure`
- 把 `LLMPrescription` 转成 `Prescription`
- 对优先级、风险等级等枚举值做兜底校正

这一步的意义是：

- 避免模型直接输出不受控的字段结构
- 给前端和持久化层提供稳定协议
- 让后续缓存、回放、重放都依赖统一格式

## 7. SSE 输出与可观测性

处置 Agent 本身没有独立 SSE 事件类型，它依附于诊断链路事件流。

当前与处置阶段最相关的 SSE 事件有：

- `thinking(step="tool_call")`
- `thinking(step="tool_result")`
- `result`
- `done`
- `error`

一个典型过程是：

```text
start
  -> thinking(data_analysis)
  -> thinking(analyzing)
  -> thinking(tool_call)      调用 format_prescription
  -> thinking(tool_result)    获取配药结果
  -> result                   输出 measures + prescription
  -> done
```

这样设计的好处是，前端不用专门识别“处置 Agent 开始了”，只要在 `result` 里读取结构化结果即可。

## 8. 持久化设计

处置结果最终存储在 `diagnosis_results` 表中，对应字段：

- `measures`
- `prescription`

持久化发生在 `DiagnosisService.analyze()` 中：

- 当收到 `event.type == "result"` 且 `event.result` 存在时
- 调用 `DiagnosisRepository.save_result(task_id, result)`

同时，所有中间事件会写入 `diagnosis_events` 表，用于：

- 问题排查
- 回放链路
- 联调分析
- 线上故障定位

这使得处置 Agent 的输出既可实时展示，也可事后追踪。

## 9. 当前实现的优点

### 9.1 不拆独立服务，链路简单

把处置能力放进 `DiagnosisAgent` 内部，而不是单独拆一个 Agent 服务，有几个直接收益：

- 少一次网络调用
- 少一层协议设计
- 结果天然和诊断结论保持一致
- 前端只消费一个 SSE 流

### 9.2 结构化输出稳定

通过 `measures + prescription` 双模型输出，处置建议比单段自然语言更适合：

- 前端展示
- 数据库存档
- SpringBoot 缓存
- 后续做处置效果闭环

### 9.3 工具能力可替换

现在 `format_prescription` 只是一个轻规则工具，后续可以平滑升级成：

- 更复杂的规则引擎
- 知识库约束生成
- 人审规则校验器
- 独立处置子 Agent

因为上层 `DiagnosisResult` 协议已经稳定，升级成本相对可控。

## 10. 当前实现的局限

### 10.1 还不是独立 Agent 编排

当前处置能力更准确地说是“诊断 Agent 的处置子阶段”，还没有：

- 独立状态机
- 独立记忆
- 独立任务重试策略
- 独立处置审计日志

如果后续希望把处置链路做得更强，就可以考虑单独拆分。

### 10.2 配药工具仍偏规则化

`format_prescription()` 目前是关键词驱动的轻量规则实现，适合作为首版能力，但也存在限制：

- 规则覆盖面有限
- 缺少井况、地层、历史处置反馈闭环
- 不支持更复杂的药剂组合推理

### 10.3 SSE 粒度还不够细

当前前端只能从通用 `thinking` 事件里推断处置阶段，没有独立的：

- `treatment_plan`
- `prescription_generation`
- `measure_validation`

事件类型。

如果后续想增强可观测性，可以把处置阶段拆成更细的事件流。

## 11. 后续演进建议

可以按下面三步渐进增强：

### 11.1 先增强工具

优先升级 `format_prescription`：

- 引入更多参数字段
- 加入单位校验
- 根据井况动态调整建议
- 输出更强约束的结构化对象

### 11.2 再增强事件

在不拆服务的前提下，为处置阶段新增更细粒度 SSE 事件：

- 处置策略生成开始
- 配药计算完成
- 风险复核完成

### 11.3 最后再考虑独立子 Agent

当以下需求同时出现时，再考虑把处置 Agent 独立出去：

- 处置逻辑明显复杂于诊断逻辑
- 需要单独重试和审计
- 需要引入处置反馈闭环学习
- 需要把处置建议下发给不同业务系统

## 12. 面试官可能会问的问题

### 12.1 你们的处置 Agent 是独立服务吗

可以这样回答：

```text
当前不是独立服务，而是 DiagnosisAgent 内部的一段处置决策能力。
我们这样做是为了先把链路收敛成一个统一的 SSE 输出和统一的结构化结果，
避免趋势分析、原因诊断、处置措施、配药方案分散在多个服务里导致协议复杂化。
如果后续处置逻辑继续膨胀，再考虑拆成独立子 Agent。
```

### 12.2 为什么不把处置措施和配药方案都写成一段大文本

可以这样回答：

```text
因为大文本不利于前端展示、数据库持久化和后续效果评估。
我们把它拆成 measures 和 prescription，
前者解决“步骤和动作”，后者解决“药剂和配比”，
这样结构更稳定，也方便后续做闭环分析。
```

### 12.3 处置 Agent 依赖哪些输入

可以这样回答：

```text
核心输入有三类：预警和样本数据、趋势分析结果、知识库召回上下文。
DiagnosisAgent 先做趋势分析，再结合 RetrievalMiddleware 注入的知识上下文生成原因与措施，
最后通过 format_prescription 工具把处置方案落到配药建议上。
```

### 12.4 你们怎么保证处置建议不是纯幻觉

可以这样回答：

```text
我们做了三层约束。
第一层是知识检索中间件，先把相关知识片段注入上下文；
第二层是 analyze_trend 和 format_prescription 这些工具，把部分关键步骤工具化；
第三层是结构化输出和领域模型转换，避免模型随意改变返回结构。
所以它不是完全自由生成，而是“检索增强 + 工具约束 + 结构化收敛”的方式。
```

### 12.5 为什么 format_prescription 现在还是规则引擎

可以这样回答：

```text
这是有意为之。我们先用规则引擎把配药建议稳定下来，
因为配药方案比普通文本建议更需要可控和可解释。
等数据和反馈积累起来之后，再逐步引入更强的模型推理或者独立处置子 Agent。
```

### 12.6 如果以后要把处置 Agent 独立出去，你会怎么拆

可以这样回答：

```text
我会先保持 DiagnosisResult 协议不变，
把处置阶段从 DiagnosisAgent 中抽成独立子链路，
输入仍然是趋势分析结果、诊断结论和知识上下文，
输出仍然是 measures 和 prescription。
这样前端、SpringBoot、数据库都不用大改，只是内部编排从单 Agent 变成多阶段 Agent。
```

### 12.7 这个设计最大的工程价值是什么

可以这样回答：

```text
最大的价值是把“诊断结论”真正落成了“可执行的处置方案”，
而且这个方案不是一次性文本，而是可流式展示、可持久化、可回放、可扩展的结构化结果。
这让 AI 能力更容易进入真实业务闭环，而不是停留在演示层。
```
