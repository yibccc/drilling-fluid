# 知识库文件导入功能设计文档

> **创建时间**: 2026-02-26
> **设计目标**: 实现完整的知识库文件导入链路，支持文档上传、解析、分块、向量化全流程

---

## 1. 概述

### 1.1 设计背景

当前知识库仅支持通过 JSON API 直接创建文档，缺少文件上传能力。本设计实现完整的文件导入链路：
- 前端支持上传 PDF、Word、Excel、PPT、TXT 等文档
- SpringBoot 使用 Apache Tika 解析文件内容
- rustfs 存储原始文件
- Redis Stream 异步队列处理
- Agent 服务完成分块和向量化

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 文件解析 | Apache Tika | 支持 1000+ 文件格式 |
| 文件存储 | rustfs | 保留原始文件，便于重新解析 |
| 消息队列 | Redis Stream | 异步处理，可靠投递 |
| 向量模型 | 通义千问 text-embedding-v3 | 1024 维向量 |
| 分块策略 | 父子分块 | 按章节分父块 + 600 字符子块 |
| 向量数据库 | PostgreSQL + pgvector | HNSW 索引加速 |

---

## 2. 架构设计

### 2.1 完整流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              文件导入流程                                     │
└─────────────────────────────────────────────────────────────────────────────┘

前端 ──[上传]──> SpringBoot
   │                    │
   │                    ├── 1. 文件验证（类型、大小）
   │                    ├── 2. Tika 解析提取文本
   │                    ├── 3. rustfs 存储原始文件
   │                    ├── 4. 提取元数据
   │                    ├── 5. 存入 knowledge_documents 表
   │                    └── 6. 发送 Redis Stream 消息
   │                              │
   │                              ▼
   │                         Redis Stream
   │                     (stream:knowledge_import)
   │                              │
   │                              ▼
   │                         Agent (消费组)
   │                    (group:knowledge_workers)
   │                              │
   │                    ├── 7. 父分块（章节级）
   │                    ├── 8. 子分块（600字符）
   │                    ├── 9. 向量化（DashScope）
   │                    └── 10. 存入 knowledge_chunks
   │                              │
   └──────────[WebSocket 推送]────┘
                     或轮询查询状态
```

### 2.2 导入状态机

```
PENDING      → 待处理
PARSING      → Tika 解析中
PARSED       → 解析完成，已入库
QUEUED       → 已进入 Redis 队列
CHUNKING     → Agent 分块处理中
EMBEDDING    → 向量化处理中
COMPLETED    → 导入完成
FAILED       → 导入失败
```

---

## 3. SpringBoot 实现

### 3.1 API 设计

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/knowledge/upload` | POST | 上传单个文件 |
| `/api/knowledge/upload/batch` | POST | 批量上传多个文件 |
| `/api/knowledge/documents/{docId}` | GET | 获取文档详情（含导入状态） |
| `/api/knowledge/documents/{docId}/chunks` | GET | 获取文档分块列表 |
| `/api/knowledge/documents/{docId}` | DELETE | 删除文档（含原始文件） |

### 3.2 核心组件

#### 3.2.1 KnowledgeController

```java
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @PostMapping("/upload")
    public ResponseEntity<KnowledgeDocumentVO> uploadFile(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "category", required = false) String category
    ) {
        // 返回 202 Accepted，异步处理
        return ResponseEntity.accepted()
            .body(fileProcessingService.processFile(file, category));
    }
}
```

#### 3.2.2 TikaDocumentParser

```java
@Component
public class TikaDocumentParser {
    private final Tika tika = new Tika();

    public DocumentContent parse(MultipartFile file) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.RESOURCE_NAME_KEY, file.getOriginalFilename());

        try (InputStream input = file.getInputStream()) {
            String text = tika.parseToString(input, metadata);

            return DocumentContent.builder()
                .title(metadata.get(TikaCoreProperties.TITLE))
                .author(metadata.get(TikaCoreProperties.CREATOR))
                .text(text)
                .build();
        }
    }
}
```

#### 3.2.3 Redis Stream 生产者

```java
@Component
public class KnowledgeImportProducer {
    public void sendImportMessage(KnowledgeDocument doc) {
        Map<String, String> message = Map.of(
            "doc_id", doc.getDocId(),
            "title", doc.getTitle(),
            "category", doc.getCategory(),
            "timestamp", String.valueOf(System.currentTimeMillis())
        );

        redisTemplate.opsForStream().add("stream:knowledge_import", message);
    }
}
```

---

## 4. Agent 实现

### 4.1 知识导入消费者

```python
class KnowledgeImportConsumer:
    """知识库导入消费者"""

    async def start(self):
        """启动消费者"""
        while self.running:
            messages = await self.redis.xreadgroup(
                "group:knowledge_workers",
                f"worker-{id(self)}",
                {"stream:knowledge_import": '>'},
                count=1,
                block=1000
            )

            for message_id, data in messages:
                await self._process_import(message_id, data)

    def _create_parent_chunks(self, text: str) -> list[str]:
        """创建父分块（按章节/段落）"""
        paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]

        parent_chunks = []
        current_chunk = ""

        for para in paragraphs:
            if len(current_chunk) + len(para) > 3000:
                parent_chunks.append(current_chunk)
                current_chunk = para
            else:
                current_chunk += "\n\n" + para if current_chunk else para

        return parent_chunks

    def _create_child_chunks(self, parent_text: str) -> list[str]:
        """创建子分块（600字符，100重叠）"""
        splitter = RecursiveCharacterTextSplitter(
            chunk_size=600,
            chunk_overlap=100,
        )
        return splitter.split_text(parent_text)
```

### 4.2 向量存储修复

**问题**: 当前代码使用 `str(embedding.tolist())` 存储向量，效率低。

**修复方案**:

```python
async def create_chunks(self, doc_id: str, chunks: List[Dict]) -> int:
    """创建文档分块 - 修复版"""

    async with self.pool.acquire() as conn:
        # 注册 pgvector 类型编解码器
        await conn.set_type_codec(
            'vector',
            encoder=lambda v: str(v),  # list -> vector string
            decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
            schema='pg_catalog',
            format='text'
        )

        # 批量 embedding
        texts = [c["content"] for c in chunks]
        embeddings = await self._embed_batch(texts)

        # 直接存储向量数组
        for chunk, embedding in zip(chunks, embeddings):
            await conn.execute(
                "INSERT INTO knowledge_chunks ... VALUES ($1, $2, $3, $4)",
                doc_id, chunk["chunk_index"], chunk["content"], embedding
            )
```

---

## 5. 数据库变更

### 5.1 knowledge_documents 表新增字段

```sql
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS original_file_path TEXT;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS file_type VARCHAR(100);
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_started_at TIMESTAMPTZ;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_completed_at TIMESTAMPTZ;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_error TEXT;

CREATE INDEX idx_knowledge_documents_import_status
ON knowledge_documents(import_status);
```

### 5.2 Redis Stream 配置

```bash
# Stream 名称
stream:knowledge_import

# 消费者组
group:knowledge_workers

# 消息格式
{
  "doc_id": "DOC-xxx",
  "title": "文档标题",
  "category": "分类",
  "timestamp": "1234567890"
}
```

---

## 6. 前端集成

### 6.1 文件上传组件

```vue
<el-upload
  :action="uploadUrl"
  :multiple="true"
  :accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt"
  :limit="10"
  drag
>
  <el-icon class="el-icon--upload"><upload-filled /></el-icon>
  <div class="el-upload__text">
    拖拽文件到此处或 <em>点击上传</em>
  </div>
</el-upload>
```

### 6.2 WebSocket 状态监听

```javascript
// 监听导入进度
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'KNOWLEDGE_IMPORT_PROGRESS') {
    updateProgress(data.docId, data.progress);
  } else if (data.type === 'KNOWLEDGE_IMPORT_COMPLETED') {
    showCompleted(data.docId);
  }
};
```

---

## 7. 支持的文件类型

| 类型 | MIME Type | 扩展名 |
|------|-----------|--------|
| PDF | application/pdf | .pdf |
| Word | application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document | .doc, .docx |
| Excel | application/vnd.ms-excel, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet | .xls, .xlsx |
| PPT | application/vnd.ms-powerpoint, application/vnd.openxmlformats-officedocument.presentationml.presentation | .ppt, .pptx |
| 文本 | text/plain | .txt |

---

## 8. 实施任务清单

| 任务 | 优先级 | 说明 |
|------|--------|------|
| 1. 数据库变更 | P0 | 添加新字段和索引 |
| 2. SpringBoot 文件上传 | P0 | 实现上传和解析 |
| 3. Tika 解析器 | P0 | 集成 Apache Tika |
| 4. rustfs 存储集成 | P1 | 存储原始文件 |
| 5. Redis Stream 生产者 | P0 | 发送导入消息 |
| 6. Agent 消费者 | P0 | 处理导入任务 |
| 7. 父子分块实现 | P0 | 实现分块逻辑 |
| 8. 向量存储修复 | P0 | 修复 embedding 存储 |
| 9. 前端上传组件 | P1 | 文件上传 UI |
| 10. WebSocket 推送 | P1 | 实时状态推送 |
| 11. 文档管理页面 | P2 | 列表和详情页 |
| 12. 错误处理和重试 | P1 | 异常处理机制 |

---

## 9. 后续优化方向

1. **支持更多格式**: 图片 OCR、表格解析
2. **智能分块**: 基于语义的动态分块
3. **增量更新**: 检测文档变化，只更新变更部分
4. **多租户隔离**: 按组织隔离知识库
5. **搜索增强**: 混合检索（向量 + 关键词）
