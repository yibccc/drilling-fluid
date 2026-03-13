# AI 诊断与 RAG 文档索引

> 更新日期: 2026-03-13 20:23:49 +08:00
> 适用范围: `yibccc-langchain/docs/detailed-design`

## 1. 文档定位

本文作为 `yibccc-langchain` 当前 AI 诊断、知识导入与 RAG 相关详细设计的阅读入口。

如果你是第一次接触这块代码，建议按下面顺序阅读。

## 2. 推荐阅读顺序

### 2.1 先看接口与整体入口

1. [接口文档-每阶段汇总.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/接口文档-每阶段汇总.md)

适合了解：

- 当前对外暴露了哪些接口
- 诊断请求和 SSE 返回长什么样
- 聊天、诊断、知识检索接口分别在哪里

### 2.2 再看知识导入与 RAG 底座

2. [knowledge-import-consumer.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md)

适合了解：

- 知识文档如何通过 Redis Stream 异步导入
- 为什么采用 Parent-Child 存储
- 当前 RAG 为什么是“检索子分块、回查父文档”
- 如果面试被问到多路召回、幻觉治理，该怎么回答

### 2.3 最后看诊断结果如何落到处置方案

3. [处置Agent详细设计.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/处置Agent详细设计.md)

适合了解：

- 当前“处置 Agent”为什么不是独立服务
- 处置措施和配药方案在 `DiagnosisAgent` 中如何生成
- `measures` 和 `prescription` 为什么拆成两个结构化字段

### 2.4 聊天能力单独阅读

4. [streaming-chat-api.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/streaming-chat-api.md)

适合了解：

- 聊天流式接口
- LangGraph 对话状态与归档表设计
- 工具调用事件流

## 3. 主题与文档映射

| 主题 | 推荐文档 |
|------|----------|
| 对外接口 | `接口文档-每阶段汇总.md` |
| 知识导入 | `knowledge-import-consumer.md` |
| RAG 检索 | `knowledge-import-consumer.md` |
| 处置与配药 | `处置Agent详细设计.md` |
| 流式聊天 | `streaming-chat-api.md` |

## 4. 常见查阅路径

### 如果你想搞清楚“诊断为什么能用到知识库”

先看：

- [knowledge-import-consumer.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md)

再看：

- [处置Agent详细设计.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/处置Agent详细设计.md)

### 如果你想搞清楚“前端该怎么调诊断接口”

先看：

- [接口文档-每阶段汇总.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/接口文档-每阶段汇总.md)

再看：

- [处置Agent详细设计.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/处置Agent详细设计.md)

### 如果你想准备 AI 诊断 / RAG 面试

优先看：

- [knowledge-import-consumer.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md)
- [处置Agent详细设计.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/处置Agent详细设计.md)

因为这两份文档底部都补了面试官常问问题。
