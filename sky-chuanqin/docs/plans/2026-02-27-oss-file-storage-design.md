# 阿里云 OSS 文件存储设计文档

**创建日期**: 2026-02-27
**作者**: Kira
**状态**: 已批准

## 1. 概述

### 1.1 背景

当前知识库导入功能中，上传的文件仅在内存中用 Apache Tika 解析后即丢弃，原始文件未持久化存储。这导致：
- 无法重新解析文件（如 Tika 版本升级）
- 无法下载原始文件
- 同一文件可重复上传，造成资源浪费

### 1.2 目标

1. **文件持久化**：使用阿里云 OSS 存储原始文件
2. **文件去重**：按「文件名 + 分类」组合判断重复，拒绝重复上传
3. **最小改动**：复用项目已有的阿里云 OSS 依赖

### 1.3 技术选型

| 项目 | 选择 | 说明 |
|------|------|------|
| 存储服务 | 阿里云 OSS | 项目已有依赖，成熟稳定 |
| 去重依据 | 文件名 + 分类 | 数据库唯一约束实现 |
| 存储时机 | 上传时立即存储 | 去重检查通过后立即上传 |
| 配置管理 | application.yml | 使用现有配置文件 |

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端文件上传                              │
└────────────────────────┬────────────────────────────────────────┘
                         │ POST /api/knowledge/upload
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                  KnowledgeController                             │
│  - 验证文件                                                      │
│  - 生成 docId                                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                FileStorageService (新增)                         │
│  - 计算文件 SHA256 哈希                                          │
│  - 检查数据库是否已存在 (filename + category)                    │
│  - 如存在则抛出 DuplicateFileException                          │
│  - 上传文件到 OSS                                                │
│  - 保存文件记录到 file_records 表                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│             KnowledgeImportService (修改)                        │
│  - 解析文档内容                                                  │
│  - 发送 Redis Stream 消息（新增 oss_path 字段）                  │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              Python Consumer (修改)                              │
│  - 保存文档记录（引用 oss_path）                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 核心改动

| 改动类型 | 组件 | 说明 |
|---------|------|------|
| 新增 | `FileStorageService` | 文件上传和去重检查 |
| 新增 | `file_records` 表 | 存储文件元数据 |
| 新增 | `DuplicateFileException` | 文件重复异常 |
| 修改 | `KnowledgeImportService` | 调用存储服务后再解析 |
| 修改 | `knowledge_documents` 表 | 添加 oss_path 外键 |
| 修改 | Python Consumer | 处理 oss_path 字段 |

---

## 3. 数据库设计

### 3.1 新增表：file_records

```sql
-- 文件存储记录表
CREATE TABLE file_records (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL,           -- SHA256 哈希
    original_filename VARCHAR(500) NOT NULL,
    category VARCHAR(50) NOT NULL,
    subcategory VARCHAR(100),
    content_type VARCHAR(100),
    file_size BIGINT NOT NULL,
    oss_path VARCHAR(500) NOT NULL,           -- OSS 存储路径
    bucket_name VARCHAR(100) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- 唯一约束：同一文件名 + 分类只能有一条记录
    CONSTRAINT uq_filename_category UNIQUE (original_filename, category)
);

-- 索引：按哈希快速查找
CREATE INDEX idx_file_hash ON file_records(file_hash);

-- 索引：按分类查询
CREATE INDEX idx_category ON file_records(category);
```

### 3.2 修改表：knowledge_documents

```sql
-- 添加 OSS 路径字段（引用 file_records）
ALTER TABLE knowledge_documents
ADD COLUMN IF NOT EXISTS oss_path VARCHAR(500);

ALTER TABLE knowledge_documents
ADD COLUMN IF NOT EXISTS file_record_id BIGINT;

-- 外键关联
ALTER TABLE knowledge_documents
ADD CONSTRAINT fk_file_record
FOREIGN KEY (file_record_id) REFERENCES file_records(id);
```

---

## 4. 组件设计

### 4.1 FileStorageService

```java
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final OSS ossClient;
    private final FileRecordRepository fileRecordRepo;
    private final OssProperties ossProperties;

    /**
     * 上传文件并检查重复
     * @return 文件记录信息
     * @throws DuplicateFileException 文件已存在
     */
    public FileRecordDTO uploadAndCheckDuplicate(
            MultipartFile file,
            String category,
            String subcategory
    ) {
        // 1. 检查是否已存在（数据库唯一约束）
        Optional<FileRecord> existing = fileRecordRepo
            .findByFilenameAndCategory(file.getOriginalFilename(), category);

        if (existing.isPresent()) {
            throw new DuplicateFileException(
                "文件已存在: " + file.getOriginalFilename() +
                " (分类: " + category + ")"
            );
        }

        // 2. 计算文件哈希
        String hash = calculateSHA256(file);

        // 3. 生成 OSS 路径
        String ossPath = generateOssPath(category, file.getOriginalFilename(), hash);

        // 4. 上传到 OSS
        uploadToOSS(ossPath, file);

        // 5. 保存记录到数据库
        FileRecord record = FileRecord.builder()
            .fileHash(hash)
            .originalFilename(file.getOriginalFilename())
            .category(category)
            .subcategory(subcategory)
            .contentType(file.getContentType())
            .fileSize(file.getSize())
            .ossPath(ossPath)
            .bucketName(ossProperties.getBucketName())
            .build();

        record = fileRecordRepo.save(record);

        return toDTO(record);
    }

    private String calculateSHA256(MultipartFile file) {
        // 使用 DigestInputStream 计算 SHA256
    }

    private String generateOssPath(String category, String filename, String hash) {
        // 格式: knowledge/{category}/{hash前8位}/{filename}
        return String.format("knowledge/%s/%s/%s",
            category, hash.substring(0, 8), filename);
    }
}
```

### 4.2 DuplicateFileException

```java
public class DuplicateFileException extends RuntimeException {
    private final String filename;
    private final String category;

    public DuplicateFileException(String message, String filename, String category) {
        super(message);
        this.filename = filename;
        this.category = category;
    }

    public String getFilename() {
        return filename;
    }

    public String getCategory() {
        return category;
    }
}
```

### 4.3 OssProperties 配置类

```java
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
@Data
public class OssProperties {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
}
```

### 4.4 FileRecordRepository

```java
@Repository
public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {
    Optional<FileRecord> findByFilenameAndCategory(String filename, String category);
    Optional<FileRecord> findByFileHash(String hash);
}
```

### 4.5 修改 KnowledgeImportService

```java
// 新增依赖
private final FileStorageService fileStorageService;

@Async("taskExecutor")
public void processFileAsync(String docId, byte[] fileBytes, String filename,
                              String contentType, long fileSize, String category, String subcategory) {
    try {
        // 1. 上传文件到 OSS（包含去重检查）
        // 注意：需要调整接口，将 MultipartFile 传递进来
        FileRecordDTO fileRecord = fileStorageService.uploadAndCheckDuplicate(
            multipartFile, category, subcategory
        );

        // 2. 更新状态：PARSING
        updateStatus(docId, ImportStatus.PARSING);

        // 3. 解析文档内容
        DocumentContent content = parseContent(new ByteArrayInputStream(fileBytes), filename);

        // 4. 构建元数据（新增 oss_path）
        DocumentMetadata metadata = DocumentMetadata.builder()
            .originalFilename(filename)
            .contentType(contentType)
            .fileSize(fileSize)
            .createdAt(LocalDateTime.now())
            .category(category)
            .subcategory(subcategory)
            .ossPath(fileRecord.getOssPath())
            .fileRecordId(fileRecord.getId())
            .build();

        // 5. 发送 Redis Stream 消息
        sendImportMessage(docId, content, metadata);

        // 6. 更新状态：QUEUED
        updateStatus(docId, ImportStatus.QUEUED);

    } catch (DuplicateFileException e) {
        log.warn("文件重复: {}", e.getMessage());
        updateStatus(docId, ImportStatus.DUPLICATE);
    } catch (Exception e) {
        log.error("文件处理失败: docId={}, error={}", docId, e.getMessage(), e);
        updateStatus(docId, ImportStatus.FAILED);
    }
}
```

---

## 5. 数据流程

```
┌──────────────────────────────────────────────────────────────────────┐
│ Step 1: 前端上传文件                                                  │
├──────────────────────────────────────────────────────────────────────┤
│ POST /api/knowledge/upload                                          │
│   - file: document.pdf                                               │
│   - category: "技术文档"                                             │
│   - subcategory: "API文档"                                           │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Step 2: KnowledgeController.generateDocId()                          │
├──────────────────────────────────────────────────────────────────────┤
│ 返回: DOC-1740628800000-A1B2C3D4                                     │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Step 3: FileStorageService.uploadAndCheckDuplicate()                 │
├──────────────────────────────────────────────────────────────────────┤
│ 3.1 查询 file_records 表                                             │
│     SELECT * FROM file_records                                       │
│     WHERE original_filename = 'document.pdf'                         │
│     AND category = '技术文档'                                        │
│                                                                      │
│ 3.2 如果存在 → 抛出 DuplicateFileException → 返回 409                │
│                                                                      │
│ 3.3 如果不存在 → 继续                                                │
│     - 计算 SHA256: a1b2c3d4...                                       │
│     - 生成 OSS 路径: knowledge/技术文档/a1b2c3d4/document.pdf        │
│     - 上传到 OSS                                                     │
│     - 插入 file_records 表                                          │
│     - 返回 FileRecordDTO                                             │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Step 4: KnowledgeImportService.processFileAsync()                    │
├──────────────────────────────────────────────────────────────────────┤
│ 4.1 Tika 解析文件内容                                                │
│ 4.2 构建 DocumentMetadata（包含 oss_path）                            │
│ 4.3 发送 Redis Stream 消息                                           │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│ Step 5: Python Consumer 处理                                         │
├──────────────────────────────────────────────────────────────────────┤
│ 5.1 接收消息，提取 oss_path                                          │
│ 5.2 保存到 knowledge_documents 表（关联 file_record_id）              │
│ 5.3 文档分块、向量化                                                 │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 6. 错误处理

| 场景 | 异常 | HTTP 状态码 | 响应消息 |
|------|------|-------------|----------|
| 文件已存在 | `DuplicateFileException` | 409 Conflict | `{"error": "文件已存在: document.pdf (分类: 技术文档)"}` |
| OSS 上传失败 | `OSSException` | 500 | `{"error": "文件上传失败: ..."}` |
| 数据库异常 | `DataIntegrityViolationException` | 500 | `{"error": "保存文件记录失败"}` |
| 文件为空 | `IllegalArgumentException` | 400 | `{"error": "文件不能为空"}` |
| 文件过大 | `IllegalArgumentException` | 400 | `{"error": "文件大小不能超过 50MB"}` |

### 全局异常处理器

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateFileException.class)
    public ResponseEntity<Result<?>> handleDuplicateFile(DuplicateFileException e) {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Result.error(e.getMessage()));
    }

    @ExceptionHandler(OSSException.class)
    public ResponseEntity<Result<?>> handleOssError(OSSException e) {
        log.error("OSS 上传失败", e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Result.error("文件上传失败: " + e.getMessage()));
    }
}
```

---

## 7. 配置文件

### application-dev.yml

```yaml
aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
    bucket-name: kira-knowledge-files
```

### OSS 配置 Bean

```java
@Configuration
@EnableConfigurationProperties(OssProperties.class)
public class OssConfig {

    @Bean
    public OSS ossClient(OssProperties properties) {
        return new OSSClientBuilder().build(
            properties.getEndpoint(),
            properties.getAccessKeyId(),
            properties.getAccessKeySecret()
        );
    }
}
```

---

## 8. 测试策略

### 8.1 单元测试

**FileStorageServiceTest:**
- `testUploadNewFile_Success()` - 测试新文件上传成功
- `testUploadDuplicateFile_ThrowsException()` - 测试重复文件抛出异常
- `testUploadSameFilenameDifferentCategory_Success()` - 测试同名不同分类可上传
- `testSHA256Calculation_Correct()` - 测试哈希计算正确性

### 8.2 集成测试

**KnowledgeControllerIntegrationTest:**
- `testUploadDuplicateFile_Returns409()` - 测试重复文件返回 409

### 8.3 测试数据

| 测试场景 | 文件名 | 分类 | 预期结果 |
|---------|--------|------|----------|
| 新文件 | test.pdf | 技术 | 成功 |
| 重复文件 | test.pdf | 技术 | 409 Conflict |
| 同名不同分类 | test.pdf | 手册 | 成功 |
| 空文件 | empty.pdf | 技术 | 400 Bad Request |
| 超大文件 | large.pdf | 技术 | 400 Bad Request |

---

## 9. 枚举修改

### ImportStatus 新增状态

```java
public enum ImportStatus {
    PARSING,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    DUPLICATE  // 新增：文件重复
}
```

---

## 10. 实施清单

| 任务 | 优先级 | 说明 |
|------|--------|------|
| 创建 file_records 表 | P0 | 数据库迁移脚本 |
| 修改 knowledge_documents 表 | P0 | 添加 oss_path 外键 |
| 创建 OssProperties 配置类 | P0 | 配置 OSS 参数 |
| 创建 OssConfig 配置 Bean | P0 | 注入 OSS 客户端 |
| 创建 FileRecord 实体类 | P0 | JPA 实体 |
| 创建 FileRecordRepository | P0 | 数据访问层 |
| 创建 FileStorageService | P0 | 核心存储服务 |
| 创建 DuplicateFileException | P0 | 自定义异常 |
| 修改 KnowledgeImportService | P0 | 调用存储服务 |
| 添加全局异常处理 | P1 | 统一错误响应 |
| 修改 Python Consumer | P1 | 处理 oss_path |
| 编写单元测试 | P1 | 测试覆盖 |
| 编写集成测试 | P1 | 端到端测试 |

---

## 11. 附录

### 11.1 OSS 路径规范

```
knowledge/{category}/{hash前8位}/{filename}

示例:
knowledge/技术文档/a1b2c3d4/API文档.pdf
knowledge/用户手册/e5f6g7h8/用户指南.pdf
```

### 11.2 相关文件

| 文件路径 | 说明 |
|---------|------|
| `sky-chuanqin/sky-server/src/main/java/com/kira/server/service/knowledge/FileStorageService.java` | 新增：文件存储服务 |
| `sky-chuanqin/sky-server/src/main/java/com/kira/server/exception/DuplicateFileException.java` | 新增：重复文件异常 |
| `sky-chuanqin/sky-server/src/main/java/com/kira/server/config/OssProperties.java` | 新增：OSS 配置 |
| `sky-chuanqin/sky-server/src/main/java/com/kira/server/repository/FileRecordRepository.java` | 新增：文件记录仓储 |
| `sky-chuanqin/sky-server/src/main/resources/application-dev.yml` | 修改：添加 OSS 配置 |
| `yibccc-langchain/docs/sql/oss_file_storage_schema.sql` | 新增：数据库迁移脚本 |
