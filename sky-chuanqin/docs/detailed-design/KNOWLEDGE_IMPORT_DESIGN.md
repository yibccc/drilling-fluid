# 知识库导入与诊断检索链路设计

> **实现基线**: 以当前代码实现为准  
> **最后更新**: 2026-03-13  
> **版本**: v2.1

## 1. 文档目的

本文只描述与“知识库导入到诊断 Agent 检索消费”直接相关的正式链路，目标是回答两个问题：

1. 前端上传知识文档后，如何经过 Spring Boot 进入 Agent 并最终写入知识库。
2. 诊断 Agent 在分析时，如何检索并消费这些知识内容。

本文刻意移除了与该正式链路无关的内容，包括：

- 与主链路无关的历史实现说明
- 不参与正式链路的同步直调入库路径
- 与知识导入无直接关系的通用 AI 集成细节

---

## 2. 正式链路范围

当前正式链路定义如下：

```text
前端文件上传
  -> SpringBoot /api/knowledge/upload
  -> KnowledgeController
  -> KnowledgeImportService
  -> OSS 文件存储 + Tika 文本解析
  -> Redis Stream: stream:knowledge_import
  -> FastAPI KnowledgeImportConsumer
  -> 文档切块 + 向量化
  -> PGVector(collection=knowledge_docs)
  -> DiagnosisAgent 检索中间件 RetrievalMiddleware
  -> 诊断分析结果返回前端
```

这条链路强调两点：

- 知识导入的正式入口在 Spring Boot。
- 知识消费发生在 FastAPI 的诊断 Agent 侧，通过同一个向量库完成检索。

---

## 3. 角色与职责

### 3.1 前端

前端负责：

- 选择并上传知识文档
- 调用 Spring Boot 上传接口
- 根据 `docId` 轮询导入状态
- 在诊断页面调用 AI 诊断接口，并展示最终诊断结果

前端不直接调用 Agent 服务，也不直接写知识库。

### 3.2 Spring Boot

Spring Boot 负责：

- 对外暴露统一的知识上传入口
- 校验上传文件
- 将原始文件上传至 OSS 并做去重
- 使用 Tika 提取纯文本内容
- 将导入任务写入 Redis Stream
- 维护导入状态
- 对外暴露诊断入口并转发诊断请求到 Agent

Spring Boot 是“业务接入层”和“链路编排层”，但不是最终的知识库存储层。

### 3.3 FastAPI Agent 服务

FastAPI 负责：

- 消费 Redis Stream 中的知识导入任务
- 对文档内容进行切块
- 生成向量并写入 PGVector
- 在诊断时从 PGVector 检索相关知识
- 将知识内容注入大模型上下文
- 输出结构化诊断结果

FastAPI 是“知识处理层”和“诊断推理层”。

### 3.4 PGVector 知识库

PGVector 负责：

- 保存父文档与子分块
- 为诊断 Agent 提供语义检索能力
- 作为知识导入和知识消费的汇合点

当前集合名为 `knowledge_docs`。

---

## 4. 端到端时序

### 4.1 知识导入时序

```text
1. 前端 -> POST /api/knowledge/upload
2. KnowledgeController 校验文件、生成 docId
3. KnowledgeImportService 上传 OSS、解析文本
4. KnowledgeImportService 写入 Redis:
   - knowledge:status:{docId}
   - stream:knowledge_import
5. KnowledgeImportConsumer 消费消息
6. Consumer 更新状态: CHUNKING -> EMBEDDING
7. 如写库失败，Consumer 进入有限次重试并更新状态为 RETRYING
8. Consumer 切块并写入 PGVector
9. 成功则更新状态为 COMPLETED；达到重试上限则更新为 FAILED
10. 前端通过 /api/knowledge/documents/{docId}/status 查询结果
```

### 4.2 诊断调用时序

```text
1. 前端 -> POST /api/ai/diagnosis/analyze
2. DiagnosisController 接收请求
3. SSEForwardService 携带内部鉴权头转发到 FastAPI /api/v1/diagnosis/analyze
4. DiagnosisService 创建任务并调用 DiagnosisAgent
5. RetrievalMiddleware 从 PGVector 检索知识
6. 检索结果注入模型上下文
7. DiagnosisAgent 输出 result/done 等 SSE 事件
8. Spring Boot 原样转发 SSE 给前端
```

这两条时序在 PGVector 汇合：前一条负责写知识，后一条负责读知识。

---

## 5. 核心实现映射

### 5.1 Spring Boot 入口

知识导入入口：

- `POST /api/knowledge/upload`
- 文件位置：`sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/knowledge/KnowledgeController.java`

诊断入口：

- `POST /api/ai/diagnosis/analyze`
- 文件位置：`sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java`

### 5.2 知识导入编排

核心服务：

- `KnowledgeImportService`
- 文件位置：`sky-chuanqin/sky-server/src/main/java/com/kira/server/service/knowledge/KnowledgeImportService.java`

关键职责：

- `generateDocId()` 生成文档 ID
- `processFileAsync()` 执行正式异步导入
- `parseContent()` 使用 Tika 提取文本
- `sendImportMessage()` 写入 Redis Stream
- `updateStatus()` 更新 Redis 中的导入状态

### 5.3 Agent 侧导入消费

核心消费者：

- `KnowledgeImportConsumer`
- 文件位置：`yibccc-langchain/src/services/knowledge_import_consumer.py`

关键职责：

- 监听 `stream:knowledge_import`
- `_process_import()` 执行单条任务处理
- `_create_chunks()` 文本切块
- `_embed_and_store_chunks()` 写入向量库
- `_update_import_status()` 回写状态

### 5.4 向量库存储

核心服务：

- `VectorStoreService`
- 文件位置：`yibccc-langchain/src/services/vector_store_service.py`

关键职责：

- 初始化 DashScope Embeddings
- 初始化 `PGVector(collection_name="knowledge_docs")`
- `add_parent_child_documents()` 存储父文档 + 子分块
- `similarity_search()` 提供语义检索
- `get_document_by_chunk_id()` 根据父子关系回捞文档

### 5.5 诊断侧知识消费

核心 Agent：

- `DiagnosisAgent`
- 文件位置：`yibccc-langchain/src/agents/diagnosis_agent.py`

核心中间件：

- `RetrievalMiddleware`
- 文件位置：`yibccc-langchain/src/agents/diagnosis_middleware.py`

关键职责：

- `DiagnosisAgent.analyze()` 负责诊断过程输出
- `RetrievalMiddleware.abefore_model()` 在模型调用前检索知识库
- 检索子分块后回捞父文档
- 将检索结果注入 `SystemMessage`

---

## 6. 知识导入数据流

### 6.1 上传请求

正式上传接口：

- `POST /api/knowledge/upload`

请求格式：

- `multipart/form-data`

关键字段：

- `file`
- `category`
- `subcategory`

### 6.2 Redis Stream 消息

Stream 名称：

- `stream:knowledge_import`

关键字段：

- `doc_id`
- `title`
- `content`
- `category`
- `subcategory`
- `original_filename`
- `content_type`
- `file_size`
- `oss_path`
- `file_record_id`
- `metadata`
- `timestamp`

这里的 `content` 是 Tika 解析后的纯文本，是后续切块和向量化的直接输入。

### 6.3 向量库存储模型

当前采用 Parent-Child 存储模型：

- 1 个父文档：保存全文
- N 个子分块：保存切块后的片段

关键元数据字段包括：

- `chunk_id`
- `parent_chunk_id`
- `doc_id`
- `title`
- `category`
- `chunk_type`
- `created_at`
- `oss_path`
- `file_record_id`

这样设计的目的有两个：

- 检索时使用子分块提升召回精度
- 返回给模型时优先使用父文档提升上下文完整性

---

## 7. 导入状态流转

当前正式状态流转如下：

```text
PARSING
  -> QUEUED
  -> CHUNKING
  -> EMBEDDING
  -> RETRYING
  -> COMPLETED
  -> FAILED
```

说明：

- `PARSING`：Spring Boot 正在解析文件
- `QUEUED`：已写入 Redis Stream，等待 Agent 消费
- `CHUNKING`：Agent 正在切块
- `EMBEDDING`：Agent 正在向量化并写库
- `RETRYING`：写库或向量化失败后，Consumer 正在进行有限次重试
- `COMPLETED`：知识已可被诊断链路检索
- `FAILED`：达到重试上限或不可恢复异常后失败

`COMPLETED` 的业务含义不是“文件上传完成”，而是“已成功进入诊断可检索知识库”。

---

## 8. 诊断 Agent 如何调用知识库

诊断 Agent 不直接通过一个显式的“知识搜索 HTTP 接口”去查知识库，而是通过中间件自动完成检索注入。

执行方式如下：

1. 前端发起诊断请求到 Spring Boot。
2. Spring Boot 将请求转发到 FastAPI。
3. `DiagnosisService` 调用 `DiagnosisAgent.analyze()`。
4. `RetrievalMiddleware.abefore_model()` 从当前请求 prompt 中提取查询语义。
5. 中间件调用 `VectorStoreService.similarity_search()` 检索 `chunk_type=child` 的子分块。
6. 根据子分块的 `parent_chunk_id` 回捞父文档。
7. 将检索结果格式化为知识库上下文，插入新的 `SystemMessage`。
8. 大模型在“采样数据 + 业务上下文 + 检索知识”的共同输入下生成诊断结果。

这意味着知识导入链路的成败，会直接影响诊断 Agent 的知识增强效果。

---

## 9. 链路评估

### 9.1 优点

这条链路的优点比较明确：

- 职责清晰：Spring Boot 负责接入与编排，FastAPI 负责知识处理与推理。
- 解耦较好：知识导入通过 Redis Stream 异步削峰，避免上传请求长时间阻塞。
- 存储模型合理：Parent-Child 结构兼顾召回精度和上下文完整性。
- 复用性强：同一套 PGVector 同时服务于知识导入和诊断检索。
- 可观察性基础具备：至少已经有 `docId` 状态查询、Redis 状态键和日志链路。

### 9.2 当前风险

当前链路也存在比较明显的风险点：

- 状态分散：导入状态保存在 Redis，知识内容保存在 PGVector，文件元信息在 OSS/文件记录表，排障需要跨多处查看。
- 最终一致性依赖异步：前端看到上传成功，不代表知识已可用于诊断，必须以 `COMPLETED` 为准。
- Stream 消费恢复能力仍有限：当前已经补了进程内有限次重试，但还没有死信、消息重分配或跨进程补偿机制。
- 检索条件较弱：当前检索主要依赖 query 语义，业务过滤维度较少，容易召回到类别相近但场景不完全匹配的文档。
- 诊断知识检索仍然隐式：当前依赖中间件自动注入知识上下文，入口不够显式，新同学阅读代码时需要结合 Agent 与 Middleware 一起理解。
- Agent 鉴权更严格：FastAPI 已删除 `dev-user` 回退，Spring Boot 或其他调用方必须显式携带合法内部鉴权头或用户鉴权头。

### 9.3 是否适合作为当前正式方案

结论是：**适合作为当前阶段的正式主链路，但需要尽快做实现收敛和治理补强。**

原因如下：

- 主链路已经跑通，结构上没有明显方向性错误。
- 知识导入和诊断消费已经在同一知识库汇合，设计闭环成立。
- 目前最大问题不是“架构方向错误”，而是“可靠性、补偿能力和可运维性还不够完整”，这些短板会直接影响线上稳定性和排障效率。

---

## 10. 实现收敛与后续优化

### 10.1 已完成的主链路收敛

以下同步旁路已从正式实现中移除：

- Spring Boot `POST /api/knowledge/upload/sync`
- `KnowledgeImportService.processFileSync()`
- FastAPI `POST /api/v1/diagnosis/knowledge/documents`

这样做的效果是：

- 知识导入只保留一个正式入口，避免双通道并存。
- 状态流转统一回到 `PARSING -> QUEUED -> CHUNKING -> EMBEDDING -> RETRYING -> COMPLETED/FAILED`。
- 文档、代码、测试可以围绕同一条链路维护。

### 10.2 建议持续对齐的描述

建议持续关注以下内容：

- 与正式链路不一致的历史文档内容
- 任何仍把“上传成功”等同于“知识可检索”的接口说明
- 对“知识检索由中间件自动注入”这一实现方式的补充说明

### 10.3 建议补强可运维性

建议后续补上：

- 死信或补偿处理机制
- 统一的导入链路观测视图
- 更细粒度的检索过滤条件
- 更明确的内部调用鉴权约束和告警

---

## 11. 推荐排障顺序

当知识导入后诊断效果异常时，建议按以下顺序排查：

1. 前端确认上传接口是否成功返回 `docId`。
2. 调用 `/api/knowledge/documents/{docId}/status` 查看当前处于 `RETRYING`、`COMPLETED` 还是 `FAILED`。
3. 检查 Spring Boot 是否成功写入 `stream:knowledge_import`。
4. 检查 `KnowledgeImportConsumer` 是否正在运行且成功消费。
5. 如果状态停在 `RETRYING` 或 `FAILED`，优先看 Consumer 日志中的失败原因和重试次数。
6. 检查 PGVector 中是否已写入对应 `doc_id` 的父子文档。
7. 检查 `RetrievalMiddleware` 是否检索到相关文档。
8. 如果检索为空，再分析是导入失败、切块质量问题，还是查询召回不准。

---

## 12. 一句话结论

这条正式链路可以概括为：

```text
知识文档先由 Spring Boot 接入并异步投递到 Redis Stream，
再由 FastAPI Agent 消费、在失败时进行有限次重试、切块、向量化并写入 PGVector；
诊断 Agent 在分析前通过 RetrievalMiddleware 从同一知识库检索内容，
将检索结果注入模型上下文后完成诊断；所有 Agent 调用都必须显式通过鉴权，不再回退到 `dev-user`。
```

正式方案建议只保留这一条主链路，其余同步旁路应尽快收敛或移除。

---

## 13. 面试问答参考

下面这部分用于回答面试官围绕“向量数据库、知识库架构、检索策略”的常见问题。回答口径以当前真实实现为准。

### 13.1 如果面试官问：你们的向量数据库用的是什么？

可以回答：

```text
我们现在用的是 PostgreSQL + pgvector，代码层通过 LangChain 的 PGVector 集成来访问。
向量库集合名是 knowledge_docs，Embedding 用的是 DashScope 的向量模型。
知识文档导入后会在 Agent 端切块、向量化，再写入 pgvector，诊断 Agent 检索时也是直接从这套向量库里召回。
```

如果想再展开一点，可以补一句：

```text
我们当前没有单独引入 Milvus 这一类专用向量数据库，因为现阶段数据规模还在中小量级，
pgvector 足够支撑，而且和现有 PostgreSQL 体系集成成本更低。
```

### 13.2 如果面试官问：为什么选 pgvector，而不是 Milvus、ES 或 Faiss？

推荐回答：

```text
我们的场景不是一个超大规模通用向量检索平台，而是钻井液诊断场景下的知识增强能力。
当前数据规模是几百到几万级别的文档或分块，查询也会结合 doc_id、category、chunk_type 这类业务元数据过滤。
在这个阶段，pgvector 的优势是：
第一，和 PostgreSQL 体系集成简单，运维成本低；
第二，metadata 过滤和业务调试更方便；
第三，对我们这种中小规模 RAG 场景已经够用。
所以我们优先选择了更轻、更稳、更容易落地的方案。
```

### 13.3 如果面试官追问：那你们现在的知识库设计是什么样的？

可以回答：

```text
我们采用的是 Parent-Child 分块策略。
每个知识文档会拆成 1 个父文档和 N 个子分块，父文档保存全文，子分块保存切块后的片段。
向量召回时先查 child chunk，这样召回精度更高；
查到 child 以后再根据 parent_chunk_id 回捞父文档，把更完整的上下文注入给诊断 Agent。
所以它兼顾了检索精度和上下文完整性。
```

再口语一点也可以这样说：

```text
我们不是把一整篇大文档直接塞给模型，也不是只拿一个很碎的片段给模型，
而是先用小块做召回，再回到大块上下文，这样更适合诊断场景。
```

### 13.4 如果面试官问：你们有哪些检索策略？

可以回答：

```text
目前主要有三层策略。
第一层是语义检索，也就是根据用户问题或诊断 prompt 做向量相似度搜索。
第二层是 metadata 过滤，比如 category、doc_id、chunk_type，避免无关知识混入。
第三层是父子块策略，先召回 child，再回捞 parent，把完整知识上下文注入模型。
```

如果想更贴近代码实现，可以补一句：

```text
具体是在 RetrievalMiddleware 里先按 chunk_type=child 检索，再根据 parent_chunk_id 找父文档，
最后把父文档内容拼成 SystemMessage 注入给 Agent。
```

### 13.5 如果面试官问：知识库是怎么被 Agent 用起来的？

可以回答：

```text
我们的 Agent 不是在业务代码里显式调用一个 search_knowledge 工具，
而是在模型调用前通过 RetrievalMiddleware 自动做检索。
中间件会从当前用户问题里提取查询语义，去向量库检索相关知识，
把检索结果格式化后作为系统上下文注入给大模型。
这样模型最终拿到的是“实时诊断数据 + 检索到的知识内容”这两部分信息。
```

### 13.6 如果面试官问：知识导入链路是什么？

可以回答：

```text
前端先把知识文件上传到 Spring Boot，
Spring Boot 做文件校验、上传 OSS、用 Tika 解析正文，
然后把导入任务写入 Redis Stream。
FastAPI 侧的 KnowledgeImportConsumer 会消费这条消息，
做文本切块、向量化并写入 pgvector。
写入成功后状态会变成 COMPLETED，这时候诊断 Agent 就可以检索到这份知识了。
```

### 13.7 如果面试官问：你们为什么用 Redis Stream 做导入，而不是同步写库？

推荐回答：

```text
主要是为了把“上传文件”和“向量化入库”解耦。
文件上传接口应该尽快返回，不能把切块、Embedding、写向量库这些耗时操作都压在一次 HTTP 请求里。
所以我们用 Redis Stream 做异步导入，Spring Boot 负责接入和投递任务，Agent 侧负责实际消费和入库。
这样可扩展性和系统稳定性都会更好。
```

### 13.8 如果面试官问：你们现在这套设计的优点是什么？

可以回答：

```text
优点主要有四个。
第一，Spring Boot 和 Agent 职责分工清晰，接入层和推理层解耦。
第二，异步导入避免上传接口长时间阻塞。
第三，Parent-Child 分块让检索精度和上下文完整性更平衡。
第四，向量库和诊断链路打通后，知识可以直接增强诊断结果，而不是停留在静态资料库。
```

### 13.9 如果面试官问：这套设计的短板或风险是什么？

可以回答：

```text
当前短板主要在工程治理层面，不是架构方向问题。
比如导入链路目前虽然已经有有限次重试，但还没有死信和更完整的补偿机制；
检索过滤维度还可以继续加强；
另外召回质量评估和索引优化也还有提升空间。
所以我会把它定义为“架构合理，但运维与治理仍需继续完善”。
```

### 13.10 如果面试官问：那你们未来会换 Milvus 吗？

建议回答：

```text
我们现在阶段不会为了“主流”去切 Milvus。
因为当前规模还是中小量级，pgvector 足够，而且和现有 PostgreSQL 架构集成最好。
如果未来文档分块量明显上升到几十万甚至百万级，
或者需要把向量检索做成多业务共用的平台，我们才会认真评估迁移到 Milvus 这一类专用向量数据库。
也就是说，我们现在的策略不是盲目追新，而是按业务规模和成本收益来选型。
```

### 13.11 如果面试官问：你们有没有做召回验证？

可以回答：

```text
有。
我们现在专门提供了联调页和召回测试接口，
上传文档成功后可以直接输入查询语句，验证向量库里是否能召回到刚导入的知识内容。
这类能力对联调特别重要，因为它能把“文件上传成功”和“知识真正可检索”区分开。
```

### 13.12 一段适合面试收尾的总结话术

如果面试官问完一轮后，你想做一个总括，可以这样回答：

```text
我们当前的知识库方案，本质上是一个面向诊断场景的中小规模 RAG 架构。
底层用 PostgreSQL + pgvector，知识导入通过 Redis Stream 异步解耦，
文档采用 Parent-Child 分块策略，检索通过 RetrievalMiddleware 自动注入给 Agent。
它的优点是实现成本低、链路清晰、和现有系统集成度高；
短板主要在补偿机制、过滤增强和运维治理。
所以我会把它定义为“现阶段合理、可落地，而且有明确演进路径”的方案。
```

---

## 14. 面试速记版

这一节用于面试时快速组织语言，尽量做到“短、准、像真实项目”。

### 14.1 1 分钟快答版

如果面试官问：“你们知识库和向量数据库怎么做的？”  
可以直接答：

```text
我们现在做的是一个面向钻井液诊断场景的中小规模 RAG 架构。
前端上传知识文档后，Spring Boot 先做文件接入、Tika 解析和 OSS 存储，然后通过 Redis Stream 把导入任务异步投递给 FastAPI Agent。
Agent 端会做 Parent-Child 分块、向量化，并把结果写入 PostgreSQL + pgvector。
诊断时不是手工调搜索接口，而是通过 RetrievalMiddleware 在模型调用前自动检索相关知识，再把结果注入到大模型上下文。
我们现在选 pgvector，主要是因为当前规模还是中小量级，和现有 PostgreSQL 体系集成更简单、成本更低，也足够支撑当前诊断业务。
```

### 14.2 30 秒极简版

如果时间很短，可以再压缩成：

```text
我们现在用的是 PostgreSQL + pgvector。
知识导入走 Spring Boot 接入、Redis Stream 异步投递、FastAPI Agent 消费入库。
文档采用 Parent-Child 分块，诊断时通过 RetrievalMiddleware 自动做知识检索和上下文注入。
当前规模下 pgvector 足够，后续只有在数据量和并发显著上来时才会评估迁 Milvus。
```

### 14.3 如果面试官问：你具体做了什么？

推荐回答：

```text
我主要参与的是知识导入链路和诊断知识检索链路的打通与收敛。
包括把知识上传统一收敛到 Spring Boot 入口，去掉同步旁路；
把导入流程规范成 Redis Stream 异步消费；
补了导入状态管理、有限次重试和联调测试页；
同时把诊断 Agent 的知识使用方式统一成 RetrievalMiddleware 自动检索注入。
所以我负责的不只是“把文档存进去”，而是把“导入、可观测、可检索、可验证”这一整条链路跑通。
```

### 14.4 如果面试官追问：为什么你们不用最主流的 Milvus？

你可以回答得更成熟一些：

```text
我们不是不知道 Milvus，而是当前阶段没有为了“主流”做过度设计。
Milvus 更适合更大规模、更高并发、或者多业务共用的专用向量检索平台。
但我们当前数据规模还是几百到几万级分块，诊断场景又很依赖 metadata 过滤和业务联调，
所以 pgvector 在这个阶段更轻、更稳、集成成本更低。
我们的思路是先把当前架构跑稳，再根据数据规模和性能瓶颈决定是否迁移，而不是一开始就引入更重的基础设施。
```

### 14.5 如果面试官问：你们怎么保证召回不是“查得到但查不对”？

可以回答：

```text
我们主要从三层控制。
第一层是分块策略，先查 child 再回捞 parent，减少只召回到零碎片段的问题。
第二层是 metadata 过滤，比如 category、doc_id、chunk_type，避免无关知识混入。
第三层是联调验证，我们专门做了上传测试页和召回测试接口，上传成功后可以直接验证某个 query 是否真的能命中刚导入的知识内容。
当然这部分现在还属于工程治理持续增强阶段，后续还会继续补评估和观测能力。
```

### 14.6 如果面试官问：你们这套最难的点是什么？

推荐回答：

```text
最难的不是“把向量存进去”，而是把整条链路做成真实可用的业务能力。
因为这里面涉及 Spring Boot 接入、异步投递、Agent 消费、分块策略、向量入库、诊断调用、状态流转和联调验证。
如果只是做一个 demo，很多问题看不出来；
但一旦进入真实业务，就会发现上传成功不等于知识可检索，检索成功也不等于模型一定用对了。
所以真正难的是把这些环节串成一条稳定、可观测、可排障的链路。
```

### 14.7 如果面试官问：你们这套现在还有哪些不足？

可以答得坦诚一点：

```text
现在最大的不足不在架构方向，而在治理深度。
比如导入链路目前已经有有限次重试，但还没有死信和完整补偿机制；
检索虽然支持 metadata 过滤，但过滤策略还可以继续加强；
另外召回评估、索引优化和更系统的观测能力也还在持续完善。
所以我会把它定义为：方向对了，主链路也跑通了，但工程化成熟度还在继续补强。
```

### 14.8 如果面试官问：这个项目你学到的最重要的一点是什么？

可以这样回答：

```text
我最大的收获是，知识库项目真正的核心不是“接了一个向量库”，
而是怎么把知识导入、检索策略、模型消费和业务链路结合起来。
如果没有完整链路，向量库只是一个技术点；
但当它能真正参与诊断决策，而且还能被验证、被观测、被排障，它才是业务能力。
```

### 14.9 一段更自然的口语版

如果你想说得更像真实交流，而不是背稿，可以用这版：

```text
我们现在这套知识库，说白了就是给诊断 Agent 提供专业知识增强。
底层不是用特别重的专用向量平台，而是先用 PostgreSQL + pgvector。
原因很简单：当前量级不大，而且我们很依赖业务字段过滤和现有系统集成，pgvector 更合适。
文档上传以后不是同步处理，而是先经过 Spring Boot，再通过 Redis Stream 异步交给 Agent 消费。
Agent 端会做父子分块、向量化，最后诊断时通过中间件自动检索知识并注入模型。
所以我觉得我们这套方案的特点不是“炫技”，而是链路清晰、成本可控，而且确实能支撑当前业务。
```
