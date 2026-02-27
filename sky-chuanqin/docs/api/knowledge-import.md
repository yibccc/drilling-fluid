# 知识库导入 API 文档

## 概述

知识库导入 API 提供文档上传、解析、分块、向量化和存储的完整功能。支持单文件和批量上传，异步处理后通过状态查询接口获取进度。

---

## API 端点

### 1. 上传单个文档

**端点**: `POST /api/knowledge/upload`

**说明**: 上传单个文档文件，异步解析处理后返回文档 ID

**请求参数** (multipart/form-data):
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | File | 是 | 上传的文件 |
| category | String | 否 | 文档分类（默认: default） |
| subcategory | String | 否 | 文档子分类 |

**支持的文件类型**: `pdf`, `doc`, `docx`, `xls`, `xlsx`, `ppt`, `pptx`, `txt`

**文件大小限制**: 50MB

**响应**:
```json
{
  "code": 200,
  "msg": null,
  "data": {
    "doc_id": "DOC-1772187588393-B94A25FE",
    "title": "mountain.txt",
    "message": "文件正在处理中",
    "error": null,
    "import_status": "PARSING",
    "file_size": 1135,
    "content_type": "text/plain",
    "estimated_chunks": null,
    "current_chunks": null
  }
}
```

**字段说明**:
- `doc_id`: 文档唯一标识，用于后续状态查询
- `title`: 文档标题（文件名）
- `import_status`: 导入状态（见状态说明）
- `file_size`: 文件大小（字节）
- `content_type`: MIME 类型

---

### 2. 批量上传文档

**端点**: `POST /api/knowledge/upload/batch`

**说明**: 批量上传多个文档文件

**请求参数** (multipart/form-data):
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| files | File[] | 是 | 上传的文件数组（多个 files 字段） |
| category | String | 否 | 文档分类（默认: default） |

**响应**:
```json
{
  "code": 200,
  "msg": null,
  "data": {
    "total": 2,
    "success": 2,
    "failed": 0,
    "results": {
      "mountain.txt": "DOC-1772187591478-126CA792",
      "test.pdf": "DOC-1772187591480-ABC12345"
    }
  }
}
```

**字段说明**:
- `total`: 总文件数
- `success`: 成功上传数
- `failed`: 失败数
- `results`: 文件名到 docId 的映射

---

### 3. 查询文档状态

**端点**: `GET /api/knowledge/documents/{docId}/status`

**说明**: 查询文档的导入处理状态

**路径参数**:
- `docId`: 文档 ID（从上传响应中获取）

**响应**:
```json
{
  "code": 200,
  "msg": null,
  "data": {
    "docId": "DOC-1772187588393-B94A25FE",
    "importStatus": "COMPLETED"
  }
}
```

---

### 4. 获取文档详情

**端点**: `GET /api/knowledge/documents/{docId}`

**说明**: 获取文档的完整信息（含分块列表）

**路径参数**:
- `docId`: 文档 ID

**响应**:
```json
{
  "code": 200,
  "msg": null,
  "data": {
    "docId": "DOC-1772187588393-B94A25FE",
    "title": "mountain.txt",
    "category": "nature",
    "content": "文档完整内容...",
    "chunkCount": 5,
    "importStatus": "COMPLETED",
    "chunks": [
      {
        "chunkIndex": 0,
        "content": "分块内容...",
        "createdAt": "2026-02-27T10:19:48Z"
      }
    ]
  }
}
```

---

### 5. 获取文档分块列表

**端点**: `GET /api/knowledge/documents/{docId}/chunks`

**说明**: 获取文档的所有分块

**路径参数**:
- `docId`: 文档 ID

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | Integer | 否 | 页码（默认: 1） |
| size | Integer | 否 | 每页数量（默认: 20） |

**响应**:
```json
{
  "code": 200,
  "msg": null,
  "data": {
    "total": 5,
    "page": 1,
    "size": 20,
    "chunks": [...]
  }
}
```

---

### 6. 删除文档

**端点**: `DELETE /api/knowledge/documents/{docId}`

**说明**: 删除文档及其所有分块

**路径参数**:
- `docId`: 文档 ID

**响应**:
```json
{
  "code": 200,
  "msg": "删除成功",
  "data": null
}
```

---

## 导入状态说明

| 状态 | 说明 |
|------|------|
| `PENDING` | 待处理 |
| `PARSING` | Tika 正在解析文档 |
| `PARSED` | 解析完成 |
| `QUEUED` | 已进入 Redis 队列 |
| `CHUNKING` | 正在进行分块处理 |
| `EMBEDDING` | 正在生成向量 |
| `COMPLETED` | 导入完成 |
| `FAILED` | 导入失败 |
| `UNKNOWN` | 文档不存在或状态过期 |

---

## 完整流程示例

### 单文件上传流程

```javascript
// 1. 上传文件
const formData = new FormData();
formData.append('file', fileInput.files[0]);
formData.append('category', 'nature');
formData.append('subcategory', 'landscape');

const response = await fetch('/api/knowledge/upload', {
  method: 'POST',
  body: formData
});

const { data } = await response.json();
const docId = data.doc_id;  // 注意: 使用 doc_id 不是 docId
console.log('文档 ID:', docId);

// 2. 轮询状态
const checkStatus = setInterval(async () => {
  const statusResp = await fetch(`/api/knowledge/documents/${docId}/status`);
  const { data: statusData } = await statusResp.json();

  console.log('当前状态:', statusData.importStatus);

  if (statusData.importStatus === 'COMPLETED') {
    clearInterval(checkStatus);
    console.log('导入完成！');
    // 获取文档详情
    const detailResp = await fetch(`/api/knowledge/documents/${docId}`);
    const detail = await detailResp.json();
    console.log('分块数量:', detail.data.chunkCount);
  } else if (statusData.importStatus === 'FAILED') {
    clearInterval(checkStatus);
    console.error('导入失败');
  }
}, 3000);  // 每 3 秒查询一次
```

### 批量上传流程

```javascript
// 1. 批量上传
const formData = new FormData();
files.forEach(file => {
  formData.append('files', file);  // 注意: 使用 files 复数
});
formData.append('category', 'documents');

const response = await fetch('/api/knowledge/upload/batch', {
  method: 'POST',
  body: formData
});

const { data } = await response.json();
console.log(`成功: ${data.success}, 失败: ${data.failed}`);

// 2. 获取所有 docId
const docIds = Object.values(data.results);
console.log('文档 IDs:', docIds);

// 3. 批量查询状态
const statuses = await Promise.all(
  docIds.map(docId =>
    fetch(`/api/knowledge/documents/${docId}/status`)
      .then(resp => resp.json())
  )
);
```

---

## 错误处理

### 文件类型不支持

```json
{
  "code": 0,
  "msg": "不支持的文件类型: xxx。支持的类型：pdf, doc, docx, xls, xlsx, ppt, pptx, txt"
}
```

### 文件过大

```json
{
  "code": 0,
  "msg": "文件大小不能超过 50MB"
}
```

### 文档不存在

```json
{
  "code": 200,
  "msg": null,
  "data": {
    "docId": "DOC-xxx",
    "importStatus": "UNKNOWN"
  }
}
```

---

## 配置参数

在 `application.yml` 中配置：

```yaml
# 线程池配置
thread-pool:
  core-pool-size: 4
  max-pool-size: 10
  queue-capacity: 100

# Redis 配置（确保与 Python Agent 一致）
spring:
  redis:
    host: localhost
    port: 6379
    password: root
    database: 0
```

---

## 前端对接注意事项

### 1. 字段命名

**重要**: API 响应使用 **snake_case**，不是 camelCase

```javascript
// ✅ 正确
const docId = response.data.doc_id;
const status = response.data.import_status;

// ❌ 错误
const docId = response.data.docId;  // undefined
```

### 2. 状态轮询建议

```javascript
// 推荐的轮询实现
async function pollDocumentStatus(docId, options = {}) {
  const {
    interval = 3000,    // 查询间隔（毫秒）
    maxAttempts = 40,   // 最大尝试次数（2分钟）
    onProgress = (status) => {},
    onComplete = (doc) => {},
    onError = (error) => {}
  } = options;

  for (let i = 0; i < maxAttempts; i++) {
    try {
      const resp = await fetch(`/api/knowledge/documents/${docId}/status`);
      const { data } = await resp.json();

      onProgress(data.importStatus);

      if (data.importStatus === 'COMPLETED') {
        const docResp = await fetch(`/api/knowledge/documents/${docId}`);
        const doc = await docResp.json();
        onComplete(doc.data);
        return;
      }

      if (data.importStatus === 'FAILED') {
        onError(new Error('导入失败'));
        return;
      }

    } catch (error) {
      onError(error);
      return;
    }

    await new Promise(resolve => setTimeout(resolve, interval));
  }

  onError(new Error('查询超时'));
}
```

### 3. 文件上传进度

如需上传进度，使用 XMLHttpRequest：

```javascript
function uploadFile(file, category, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const formData = new FormData();
    formData.append('file', file);
    formData.append('category', category);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const percent = (e.loaded / e.total) * 100;
        onProgress(percent);
      }
    };

    xhr.onload = () => {
      if (xhr.status === 200) {
        resolve(JSON.parse(xhr.responseText));
      } else {
        reject(new Error(xhr.responseText));
      }
    };

    xhr.onerror = () => reject(new Error('上传失败'));

    xhr.open('POST', '/api/knowledge/upload');
    xhr.send(formData);
  });
}
```

---

## 测试

### cURL 测试示例

```bash
# 单文件上传
curl -X POST http://localhost:18080/api/knowledge/upload \
  -F "file=@/path/to/document.pdf" \
  -F "category=technical"

# 批量上传
curl -X POST http://localhost:18080/api/knowledge/upload/batch \
  -F "files=@/path/to/doc1.pdf" \
  -F "files=@/path/to/doc2.txt" \
  -F "category=test"

# 查询状态
curl http://localhost:18080/api/knowledge/documents/DOC-xxx/status

# 获取文档详情
curl http://localhost:18080/api/knowledge/documents/DOC-xxx

# 删除文档
curl -X DELETE http://localhost:18080/api/knowledge/documents/DOC-xxx
```

---

## 相关文档

- 设计文档: `docs/detailed-design/KNOWLEDGE_IMPORT_DESIGN.md`
- Python 消费者: `yibccc-langchain/docs/detailed-design/knowledge-import-consumer.md`
- 数据库脚本: `yibccc-langchain/docs/sql/knowledge_import_schema.sql`
