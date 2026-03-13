# 知识库导入消费者详细设计

> 版本: 2.1
> 更新日期: 2026-03-13 20:23:49 +08:00
> 对应实现:
> - `src/services/knowledge_import_consumer.py`
> - `src/services/vector_store_service.py`
> - `src/agents/diagnosis_middleware.py`
> - `src/api/main.py`

> 相关文档:
> - [AI诊断与RAG文档索引.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/AI诊断与RAG文档索引.md)
> - [处置Agent详细设计.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/处置Agent详细设计.md)
> - [接口文档-每阶段汇总.md](/Users/kirayang/IdeaProjects/drilling-fluid/yibccc-langchain/docs/detailed-design/接口文档-每阶段汇总.md)

## 1. 文档定位

本文描述 `yibccc-langchain` 当前仍在运行的知识导入链路，以及它如何服务后续 RAG 检索。

这份文档重点回答三件事：

- SpringBoot 发送导入任务后，FastAPI 侧是怎么消费的
- 文本是如何切分、写入 PGVector、再被诊断链路召回的
- 当前实现的边界在哪里，后续如果扩展成多路召回或更强的幻觉治理，应该从哪一层演进

以下旧口径已经移除，不再作为实现依据：

- 手工维护 `knowledge_documents` / `knowledge_chunks` 表
- 先父块再对子块二次切分的双层父块算法说明
- 自定义 SQL 向量检索语句
- 通过业务数据库表记录导入状态

当前实现已经统一为：

- SpringBoot 产生日志导入消息到 Redis Stream
- FastAPI 侧 `KnowledgeImportConsumer` 消费消息
- 使用 `VectorStoreService` 调用 LangChain PGVector 异步写入向量库
- 导入状态只写入 Redis 键，不写入业务表
- 后续 RAG 检索通过 `RetrievalMiddleware` 从 PGVector 读取知识上下文

## 2. 背景与设计目标

### 2.1 背景

知识导入链路的目标不是简单“把文件存起来”，而是把业务文档转成可检索、可注入、可在诊断过程中实时使用的知识单元。

在当前项目里，知识导入和知识使用是分开的：

- 导入阶段：把文档变成可检索向量数据
- 使用阶段：在诊断请求到来时进行语义检索和上下文注入

### 2.2 设计目标

当前实现主要围绕下面几个目标设计：

- 让导入和在线诊断解耦，避免上传时阻塞业务请求
- 让知识数据可以被 RAG 检索直接消费
- 让知识文档具备文档级和分块级两种访问视角
- 让导入状态可观测、可重试、可排障
- 避免维护一套自定义向量表结构，降低存储层复杂度

## 3. 当前整体链路

```text
SpringBoot
  -> Redis Stream: stream:knowledge_import
  -> KnowledgeImportConsumer.start()
  -> xreadgroup 消费消息
  -> _process_import()
     -> _update_import_status(CHUNKING)
     -> _create_chunks() 文本切分
     -> _update_import_status(EMBEDDING)
     -> _embed_and_store_chunks()
        -> 构造 1 个父文档 + N 个子分块
        -> VectorStoreService.add_parent_child_documents()
        -> LangChain PGVector 自动写入 langchain_pg_* 表
     -> _update_import_status(COMPLETED)
     -> xack
```

如果从 RAG 的视角继续往下看，它最终会接到诊断链路中：

```text
DiagnosisAgent
  -> RetrievalMiddleware
  -> similarity_search(query, filter={"chunk_type":"child", ...})
  -> 取回子分块
  -> 回查对应父文档
  -> 拼装上下文
  -> 注入系统消息给模型
```

这说明知识导入链路的输出不是终点，而是后续 RAG 检索的输入。

## 4. 运行入口

消费者在应用启动时由 `src/api/main.py` 的 `lifespan()` 自动启动：

```python
_knowledge_import_consumer = KnowledgeImportConsumer(pg_repo.pool)
asyncio.create_task(_knowledge_import_consumer.start())
```

说明：

- `pool` 参数目前仅为兼容启动签名保留，当前消费者本身不直接写 PostgreSQL 业务表
- `start()` 内部会自行初始化 Redis 客户端和 embeddings 客户端
- 如果 `running` 已经为 `True`，会直接返回，避免重复启动
- `stop()` 会关闭内部 Redis 连接

## 5. Redis Stream 协议与消费模型

### 5.1 Stream 与消费者组

| 项目 | 当前值 |
|------|--------|
| Stream 名称 | `stream:knowledge_import` |
| Consumer Group | `group:knowledge_workers` |
| Consumer Name | `worker-{随机8位}` |
| 读取方式 | `XREADGROUP` |
| 批量大小 | `count=1` |
| 阻塞时间 | `block=1000` 毫秒 |

这是一种典型的异步消费模型，优点是：

- 上传链路和向量化链路隔离
- 导入任务天然具备队列缓冲能力
- 未来可以水平扩容多个 worker

### 5.2 消息字段

当前实现会从 Stream 消息中读取以下字段：

| 字段 | 必填 | 说明 |
|------|------|------|
| `doc_id` | 是 | 文档唯一标识 |
| `title` | 是 | 文档标题 |
| `content` | 是 | 文档全文 |
| `category` | 否 | 文档分类，默认 `default` |
| `oss_path` | 否 | OSS 路径 |
| `file_record_id` | 否 | SpringBoot 文件记录 ID |
| `metadata` | 否 | JSON 字符串，解析失败时回退为空对象 |

这里有一个很重要的设计点：`oss_path`、`file_record_id` 和外部 `metadata` 最终都会进入向量文档的 metadata，而不是另存一张业务关系表。

### 5.3 消费循环

`_worker()` 的逻辑可以概括为：

1. 阻塞读取消息
2. 遍历 Stream 返回结果
3. 对每条消息调用 `_process_import()`
4. 如果处理过程中抛异常，则记录错误并 ACK，避免消息永久卡死

这个设计比较偏工程稳定性：

- 不追求严格幂等重放
- 优先保证队列不会因为坏消息被拖死
- 把复杂重试收敛在单条任务内部处理

## 6. 单条导入任务处理流程

### 6.1 参数解码

`_process_import()` 首先会从 Redis 原始字节字段中解码出：

- `doc_id`
- `title`
- `content`
- `category`
- `oss_path`
- `file_record_id`
- `metadata`

其中 `metadata` 会尝试按 JSON 解析，如果解析失败则回退为空对象。

### 6.2 状态推进

当前单条任务状态流转如下：

```text
CHUNKING -> EMBEDDING -> COMPLETED
                    -> RETRYING
                    -> FAILED
```

对应 Redis 键如下：

| 键模式 | 说明 |
|--------|------|
| `knowledge:status:{doc_id}` | 当前状态 |
| `knowledge:chunks:{doc_id}` | 最终分块数 |
| `knowledge:error:{doc_id}` | 最后一次错误信息 |

### 6.3 重试策略

当前内置重试参数：

- 最大重试次数：`MAX_RETRIES = 3`
- 重试退避：`RETRY_DELAY_SECONDS = 0.1`，按次数递增

重试仅包裹“向量化并存储”阶段，而不是整个消费循环。

这么做的好处是：

- 文本切分错误一般是确定性错误，不必无限重试
- 网络抖动、向量服务抖动更适合做快速重试
- 整体模型更容易排障

### 6.4 ACK 策略

- 成功完成导入后 ACK
- 单条消息处理异常时，记录日志并 ACK，避免重复处理
- `_process_import()` 内部的最后一次失败也会 ACK

这意味着当前设计更偏“至少尝试 + 最终释放队列”，而不是“绝不丢消息”的严格事务队列模型。

## 7. 分块策略

### 7.1 当前分块实现

`_create_chunks()` 不再维护“父块 3000 字符 + 子块 600 字符”的双层规则，而是直接调用 `create_text_splitter()` 生成基础分块：

```python
splitter = create_text_splitter()
texts = splitter.split_text(text)
```

每个基础分块会生成如下元信息：

| 字段 | 说明 |
|------|------|
| `text` | 分块正文 |
| `page` | 当前固定为 `1` |
| `position` | 分块顺序，从 `0` 开始 |

### 7.2 为什么这样切

当前分块策略的核心目标不是“最大化还原原文结构”，而是：

- 让检索粒度足够细
- 让 embedding 文本长度受控
- 让后续父文档回查仍然保留全局语义

这是一个“导入阶段切细，检索阶段回大”的思路。

## 8. Parent-Child 存储模型

虽然切分时不再单独构造“多个父块”，但写向量库时仍会组织成：

- 1 个父文档：全文拼接结果，`chunk_type=parent`
- N 个子分块：基础分块列表，`chunk_type=child`

### 8.1 父文档 metadata

| 字段 | 示例 |
|------|------|
| `chunk_id` | `DOC001_parent` |
| `doc_id` | `DOC001` |
| `title` | 文档标题 |
| `category` | 文档分类 |
| `chunk_type` | `parent` |
| `created_at` | UTC ISO 时间 |

### 8.2 子分块 metadata

| 字段 | 示例 |
|------|------|
| `chunk_id` | `DOC001_parent_child_0` |
| `parent_chunk_id` | `DOC001_parent` |
| `doc_id` | `DOC001` |
| `title` | 文档标题 |
| `category` | 文档分类 |
| `position` | 0, 1, 2... |
| `chunk_type` | `child` |
| `created_at` | UTC ISO 时间 |

`oss_path`、`file_record_id` 和外部 `metadata` 会被合并进父子文档的 metadata。

### 8.3 为什么要有父子关系

这是当前实现和“只存碎片”方案最大的区别。

子分块的价值：

- 适合语义检索
- 相似度更精细
- 更容易命中局部知识点

父文档的价值：

- 提供更完整上下文
- 避免模型只拿到一句零散结论
- 减少因碎片化导致的理解偏差

后续 `RetrievalMiddleware` 的做法正是：

- 先检索子分块
- 再回查父文档
- 最终把父文档注入模型

这是一种比较常见、也比较稳妥的 Parent-Child RAG 模式。

## 9. 向量库实现

`VectorStoreService` 使用 LangChain 官方 PGVector 集成：

```python
self.vector_store = PGVector(
    embeddings=self.embeddings,
    collection_name="knowledge_docs",
    connection=connection_string,
    use_jsonb=True,
    async_mode=True,
)
```

### 9.1 当前提供的能力

`VectorStoreService` 当前暴露的核心方法包括：

- `add_documents()`
- `similarity_search()`
- `add_parent_child_documents()`
- `query_by_parent_chunk()`
- `query_by_doc_id()`
- `get_document_by_chunk_id()`
- `delete_by_doc_id()`

### 9.2 当前结论

- 业务代码不再手写 `knowledge_documents` / `knowledge_chunks` 建表 SQL
- 实际持久化表由 LangChain PGVector 自动维护
- 当前数据库口径应以 `langchain_pg_collection`、`langchain_pg_embedding` 为准
- 检索依赖 metadata 过滤，例如 `doc_id`、`chunk_type`、`parent_chunk_id`

## 10. 与诊断 RAG 的关系

这部分是最容易在项目里被忽略、但面试时最值得讲清楚的地方。

### 10.1 当前 RAG 检索路径

当前诊断链路中的 RAG 是这样工作的：

1. `DiagnosisAgent` 收到请求
2. `RetrievalMiddleware.abefore_model()` 在模型调用前拦截
3. 从状态中提取过滤条件，例如 `category`
4. 强制补充 `chunk_type=child`，只检索子分块
5. 用 `similarity_search(query, k=5, filter=child_filter)` 做向量召回
6. 根据 `parent_chunk_id` 回查父文档
7. 把父文档内容拼成 `SystemMessage` 注入模型

### 10.2 这套设计的好处

- 召回阶段足够细：用子分块做匹配
- 注入阶段足够全：用父文档补上下文
- metadata 过滤简单直观：按 `category`、`doc_id`、`chunk_type` 控制范围
- 与导入链路天然衔接：导入阶段已经把父子关系准备好了

### 10.3 当前不是多路召回

这一点要特别明确：

- 当前实现是单路向量召回
- 还没有 BM25、关键词召回、规则召回、图谱召回等并行通道
- 也还没有 rerank 层

所以如果文档或面试里提到“多路召回”，应该说成“可演进方向”，不能说成当前已实现能力。

## 11. 当前实现的优点

### 11.1 导入与查询解耦

上传和 embedding 不在一个同步请求里完成，系统更稳，也更容易扩容。

### 11.2 父子分块兼顾粒度与上下文

检索时命中更准，注入时上下文更完整，比单纯存全文或单纯存碎片都更平衡。

### 11.3 存储层简单

借助 LangChain PGVector 自动管理表结构，减少了自研表结构和迁移脚本负担。

### 11.4 metadata 设计实用

`doc_id`、`title`、`category`、`parent_chunk_id`、`oss_path`、`file_record_id` 这些字段，让后续删除、过滤、排障都更方便。

## 12. 当前实现的局限

### 12.1 还没有多路召回

当前完全依赖单一向量召回，在这些场景下可能不够强：

- 关键词非常明确但语义不明显
- 业务术语缩写较多
- 用户问题与文档写法存在明显表达偏差

### 12.2 还没有 rerank

当前直接拿相似度 TopK，再回查父文档，没有独立的精排阶段。

### 12.3 空检索会走降级模式

`RetrievalMiddleware` 在查不到结果时，会退化成“请基于你的专业知识进行分析”。

这保证了服务可用，但也意味着：

- 模型可能脱离知识库直接回答
- 幻觉风险会显著上升
- 对强事实性问题不够稳

### 12.4 ACK 策略更偏工程吞吐

当前消息最终都会 ACK，适合保持队列流动性，但不适合对“绝不丢任务”有极强要求的场景。

## 13. 后续演进建议

### 13.1 多路召回

如果后续要增强 RAG，推荐从单路向量召回扩展到多路召回：

- 向量召回：解决语义相似
- BM25 / 关键词召回：解决术语精确匹配
- 分类过滤召回：解决范围收缩
- 规则召回：针对高优先级业务场景强制注入关键文档

一个典型的多路召回链路可以是：

```text
query
  -> 向量召回 topK1
  -> BM25 召回 topK2
  -> 规则召回 topK3
  -> 合并去重
  -> rerank
  -> 回查父文档
  -> 注入模型
```

### 13.2 幻觉治理

如果后续要重点治理 RAG 幻觉，可以从这些方面增强：

- 没检索到知识时，限制模型自由发挥而不是完全放开
- 在提示词里要求“结论必须引用知识上下文”
- 在结果里显式返回引用来源
- 对高风险问题增加规则校验或二次审核
- 给前端区分“知识库支持回答”和“降级回答”

### 13.3 观测性增强

后续可以补的可观测能力包括：

- 导入耗时指标
- 每个文档分块数统计
- embedding 失败率
- 检索命中率
- 空检索率
- 父文档回查成功率

## 14. 对外接口影响

当前知识库相关 HTTP 路由位于 `src/api/routes/diagnosis.py`：

- `POST /api/v1/diagnosis/knowledge/search`
- `DELETE /api/v1/diagnosis/knowledge/documents/{doc_id}`

注意：

- 历史上的同步文档写入接口 `POST /api/v1/diagnosis/knowledge/documents` 已移除
- 路由测试也明确校验该接口返回 `404`

## 15. 与旧文档的差异

本次统一后的口径如下：

- 不再维护 `knowledge_documents` / `knowledge_chunks` 表结构说明
- 不再维护自定义 SQL 检索语句
- 不再声称消费者直接写 PostgreSQL 业务文档表
- 不再使用“父块 3000 / 子块 600 / overlap 100”作为当前实现描述
- 把知识导入和后续诊断 RAG 的关系明确写进文档

如果后续知识导入链路再次重构，请直接对照以下代码更新本文：

- `src/services/knowledge_import_consumer.py`
- `src/services/vector_store_service.py`
- `src/agents/diagnosis_middleware.py`
- `src/api/routes/diagnosis.py`

## 16. 面试官可能会问的问题

### 16.1 你们为什么要单独做一个知识导入消费者

可以这样回答：

```text
因为上传文件和向量化处理都比较重，如果放在同步请求里会拖慢接口响应。
我们把导入任务写到 Redis Stream，再由 FastAPI 的 KnowledgeImportConsumer 异步消费，
这样上传链路和 embedding 链路就解耦了，系统吞吐和稳定性都会更好。
```

### 16.2 你们为什么不自己维护 knowledge_documents 和 knowledge_chunks 表

可以这样回答：

```text
因为当前已经切到 LangChain PGVector 方案，向量表和集合表都由框架自动管理。
这样做的好处是减少自定义表结构和迁移成本，把精力集中在分块策略、metadata 设计和检索链路上。
```

### 16.3 你们当前 RAG 的召回方式是什么

可以这样回答：

```text
当前是单路向量召回，不是多路召回。
具体做法是先检索 child chunk，再根据 parent_chunk_id 回查父文档，
最后把父文档作为上下文注入模型。
也就是说，召回粒度是子块，注入粒度是父文档。
```

### 16.4 如果面试官追问 RAG 多路召回，你怎么回答

可以这样回答：

```text
当前线上实现还是单路向量召回，但从架构上已经为多路召回留出了空间。
如果要继续增强，我会把召回拆成向量召回、BM25 召回、规则召回三路，
先各自拿 topK，再做 merge + rerank，最后仍然回查父文档注入模型。
这样既能保留语义匹配能力，也能补齐术语精确匹配能力。
```

### 16.5 你们怎么降低 RAG 幻觉

可以这样回答：

```text
我们现在已经做了两层约束。
第一层是必须先走知识检索，再把检索结果注入模型，而不是直接裸问模型；
第二层是用 parent-child 结构避免只给模型一小段碎片文本。
但要坦诚说，当前空检索时仍然会降级到基于模型自身知识回答，所以幻觉并没有被彻底消灭。
如果继续治理，我会加引用约束、结果来源返回、空检索限制回答和 rerank。
```

### 16.6 为什么要先检索子分块，再回查父文档

可以这样回答：

```text
因为子分块更适合做相似度匹配，粒度更细，命中率更高；
但真正喂给模型时，只给子分块容易上下文不足，所以我们会根据 parent_chunk_id 把对应父文档找回来。
这是一种兼顾检索精度和上下文完整性的做法。
```

### 16.7 这个知识导入设计的最大工程价值是什么

可以这样回答：

```text
最大的价值是把文档上传、向量化存储和后续 RAG 检索串成了一条稳定闭环。
导入阶段就把父子关系、metadata、分类信息准备好，
后面的诊断链路才能低成本地做知识增强，而不是每次请求时临时拼凑上下文。
```
