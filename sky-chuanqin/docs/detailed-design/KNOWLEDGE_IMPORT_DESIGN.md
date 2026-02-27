# 知识库文件导入模块 - 详细设计文档

> **实现状态**: 已完成
> **最后更新**: 2026-02-26
> **版本**: v1.0

## 实现状态概览

| 模块 | 状态 | 说明 |
|------|------|------|
| Tika 文档解析 | ✅ 完成 | TikaDocumentParser |
| 文件上传 API | ✅ 完成 | KnowledgeController |
| 异步处理服务 | ✅ 完成 | KnowledgeImportService |
| Redis Stream 消息 | ✅ 完成 | stream:knowledge_import |
| 导入状态管理 | ✅ 完成 | ImportStatus 枚举 |
| 单元测试 | ✅ 完成 | 12 个测试用例 |

---

## 1. 功能定位

本模块是钻井液知识库系统的文件导入功能，负责：

- 接收用户上传的文档（PDF、Word、Excel、PPT、TXT）
- 使用 Apache Tika 解析文档内容
- 发送 Redis Stream 消息到 Agent 服务
- 实时跟踪文档导入状态
- 提供单文件和批量上传接口

## 2. 技术架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         前端 (Vue 3)                             │
│                      文件上传组件                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP POST /api/knowledge/upload
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SpringBoot 后端                             │
│                                                                     │
│  ┌──────────────┐    ┌─────────────┐    ┌──────────────┐       │
│  │ Knowledge    │───▶│ Knowledge   │───▶│   Tika       │       │
│  │ Controller   │    │ Import      │    │ Document     │       │
│  │              │    │ Service     │    │ Parser       │       │
│  └──────────────┘    └─────────────┘    └──────────────┘       │
│                                                      │            │
│                                                  ┌─────────▼─────┐ │
│                                                  │ StringRedis  │ │
│                                                  │ Template     │ │
│                                                  └─────────┬─────┘ │
│                                                            │        │
│                                                            ▼        │
│                                                   ┌──────────────────┐   │
│                                                   │ Redis Stream     │   │
│                                                   │ stream:knowledge  │   │
│                                                   │ _import          │   │
│                                                   └──────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## 3. API 接口规范

### 3.1 单文件上传

**接口**: `POST /api/knowledge/upload`

**请求参数**:
| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| file | MultipartFile | 是 | 上传的文件 |
| category | String | 否 | 文档分类，默认 "default" |
| subcategory | String | 否 | 文档子分类 |

**响应示例**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "docId": "DOC-1762140000000-ABC12345",
    "title": "钻井液钙污染处理.pdf",
    "status": "PARSING",
    "message": "文件正在处理中",
    "fileSize": 1234567,
    "contentType": "application/pdf"
  }
}
```

**支持格式**:
- PDF: `.pdf`
- Word: `.doc`, `.docx`
- Excel: `.xls`, `.xlsx`
- PowerPoint: `.ppt`, `.pptx`
- 文本: `.txt`

**文件限制**: 最大 50MB

### 3.2 批量上传

**接口**: `POST /api/knowledge/upload/batch`

**请求参数**:
| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| files | MultipartFile[] | 是 | 文件数组 |
| category | String | 否 | 文档分类 |

**响应示例**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "total": 3,
    "success": 2,
    "failed": 1,
    "results": {
      "doc1.pdf": "DOC-xxx",
      "doc2.txt": "DOC-yyy",
      "large.pdf": "ERROR: 文件大小超过 50MB"
    }
  }
}
```

### 3.3 查询文档状态

**接口**: `GET /api/knowledge/documents/{docId}/status`

**响应示例**:
```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "docId": "DOC-123",
    "importStatus": "COMPLETED"
  }
}
```

## 4. 导入状态流转

```
PENDING → PARSING → PARSED → QUEUED → [Agent 处理] → COMPLETED
                                      └──────→ FAILED
```

| 状态 | 说明 |
|------|------|
| PENDING | 待处理 |
| PARSING | Tika 正在解析文档 |
| PARSED | 解析完成 |
| QUEUED | 已入队到 Redis Stream |
| CHUNKING | Agent 正在分块 |
| EMBEDDING | Agent 正在向量化 |
| COMPLETED | 完成 |
| FAILED | 失败 |

## 5. 核心类设计

### 5.1 TikaDocumentParser

```java
@Component
public class TikaDocumentParser {
    public DocumentContent parse(MultipartFile file);
    public String detectMimeType(MultipartFile file);
    public String parseToString(MultipartFile file);
}
```

**职责**: 使用 Apache Tika 解析多种文档格式

### 5.2 KnowledgeImportService

```java
@Service
@RequiredArgsConstructor
public class KnowledgeImportService {
    @Async("taskExecutor")
    public String processFileAsync(MultipartFile file,
                                     String category,
                                     String subcategory);

    public Map<String, Object> getDocumentStatus(String docId);
}
```

**职责**:
- 异步处理文件
- 协调 Tika 解析和 Redis 消息发送
- 管理导入状态

### 5.3 ImportStatus 枚举

```java
public enum ImportStatus {
    PENDING, PARSING, PARSED, QUEUED,
    CHUNKING, EMBEDDING, COMPLETED, FAILED
}
```

## 6. Redis Stream 消息格式

**Stream 名称**: `stream:knowledge_import`

**消息字段**:
| 字段 | 类型 | 说明 |
|------|------|------|
| doc_id | String | 文档唯一标识 |
| title | String | 文档标题 |
| content | String | 文档内容（纯文本） |
| category | String | 文档分类 |
| subcategory | String | 子分类 |
| original_filename | String | 原始文件名 |
| content_type | String | MIME 类型 |
| file_size | String | 文件大小（字节） |
| metadata | String | 元数据 JSON |
| timestamp | String | 时间戳 |

## 7. 配置说明

### application.yml 配置

```yaml
spring:
  redis:
    host: ${sky.redis.host}
    port: ${sky.redis.port}
    password: ${sky.redis.password}
    database: 10
```

### 线程池配置

```java
@Bean(name = "taskExecutor")
public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(200);
    return executor;
}
```

## 8. 测试

### 单元测试

- `TikaDocumentParserTest`: 5 个测试用例
- `KnowledgeImportServiceTest`: 5 个测试用例

### 运行测试

```bash
cd sky-chuanqin/sky-server
mvn test -Dtest=*Knowledge*
```

## 9. 文件清单

| 文件路径 | 说明 |
|---------|------|
| `controller/knowledge/KnowledgeController.java` | 上传控制器 |
| `controller/knowledge/dto/*.java` | DTO 类 |
| `service/knowledge/KnowledgeImportService.java` | 导入服务 |
| `service/knowledge/TikaDocumentParser.java` | Tika 解析器 |
| `enums/ImportStatus.java` | 状态枚举 |
| `tests/service/knowledge/*Test.java` | 单元测试 |
