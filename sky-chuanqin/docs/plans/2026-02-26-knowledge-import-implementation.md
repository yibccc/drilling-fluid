# 知识库文件导入功能实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现完整的知识库文件导入链路，支持前端上传文档 → SpringBoot Tika 解析 → Redis Stream → Agent 分块向量化 → PostgreSQL pgvector 存储

**Architecture:** 异步队列架构，SpringBoot 负责文件解析和存储，Agent 服务负责分块和向量化，通过 Redis Stream 解耦

**Tech Stack:** SpringBoot 2.7.3, Apache Tika, FastAPI, LangChain, PostgreSQL 17 + pgvector, Redis Stream, Vue 3 + Element Plus

---

## 前置准备

### Task 0: 环境验证和依赖检查

**Files:**
- Check: `sky-chuanqin/pom.xml`
- Check: `yibccc-langchain/pyproject.toml`
- Check: `sky-chuanqin/sky-server/src/main/resources/application.yml`

**Step 1: 验证 SpringBoot Tika 依赖**

打开 `sky-chuanqin/pom.xml`，确认或添加：

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.1</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.1</version>
</dependency>
```

**Step 2: 验证 Redis 配置**

确认 `application.yml` 中有 Redis 配置：

```yaml
spring:
  redis:
    host: ${sky.redis.host}
    port: ${sky.redis.port}
    password: ${sky.redis.password}
```

**Step 3: 验证 Agent 项目依赖**

确认 `yibccc-langchain/pyproject.toml` 包含：

```toml
dependencies = [
    "langchain-text-splitters>=0.2.0",
    "langchain-community>=0.2.0",
]
```

**Step 4: Commit (如需添加依赖)**

```bash
git add pom.xml pyproject.toml
git commit -m "deps: 添加 Tika 和文本分块依赖"
```

---

## 第一阶段：数据库变更

### Task 1: 创建数据库迁移脚本

**Files:**
- Create: `sky-chuanqin/src/main/resources/db/migration/V2026.02.26__add_knowledge_import_fields.sql`

**Step 1: 创建迁移文件**

```sql
-- src/main/resources/db/migration/V2026.02.26__add_knowledge_import_fields.sql

-- 为 knowledge_documents 表添加文件导入相关字段
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS original_file_path TEXT;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS file_type VARCHAR(100);
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_started_at TIMESTAMPTZ;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_completed_at TIMESTAMPTZ;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS import_error TEXT;

-- 添加索引
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_import_status
ON knowledge_documents(import_status);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_category_status
ON knowledge_documents(category, import_status);

-- 添加导入状态枚举注释
COMMENT ON COLUMN knowledge_documents.import_status IS '
PENDING - 待处理
PARSING - Tika 解析中
PARSED - 解析完成
QUEUED - 已入队
CHUNKING - 分块处理中
EMBEDDING - 向量化中
COMPLETED - 完成
FAILED - 失败
';
```

**Step 2: 本地测试迁移**

```bash
cd sky-chuanqin
mvn flyway:migrate
```

预期输出: `Successfully applied 1 migration to schema`

**Step 3: 验证字段已添加**

```sql
\d knowledge_documents
```

预期: 看到新增的 8 个字段

**Step 4: Commit**

```bash
git add src/main/resources/db/migration/V2026.02.26__add_knowledge_import_fields.sql
git commit -m "db: 添加知识库导入相关字段和索引"
```

---

## 第二阶段：SpringBoot 文件上传和解析

### Task 2: 创建导入状态枚举

**Files:**
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/enums/ImportStatus.java`

**Step 1: 创建枚举类**

```java
package com.kira.server.enums;

/**
 * 知识库导入状态
 */
public enum ImportStatus {
    PENDING("待处理"),
    PARSING("解析中"),
    PARSED("解析完成"),
    QUEUED("已入队"),
    CHUNKING("分块中"),
    EMBEDDING("向量化中"),
    COMPLETED("完成"),
    FAILED("失败");

    private final String description;

    ImportStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
```

**Step 2: 编写测试**

```java
// Test: 测试枚举值
@Test
public void testImportStatusEnum() {
    assertEquals("待处理", ImportStatus.PENDING.getDescription());
    assertEquals(8, ImportStatus.values().length);
}
```

**Step 3: 运行测试**

```bash
cd sky-chuanqin
mvn test -Dtest=ImportStatusTest
```

**Step 4: Commit**

```bash
git add sky-server/src/main/java/com/kira/server/enums/ImportStatus.java
git commit -m "feat: 添加知识库导入状态枚举"
```

---

### Task 3: 创建文档内容 DTO

**Files:**
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/knowledge/dto/DocumentContent.java`
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/knowledge/dto/DocumentMetadata.java`

**Step 1: 创建 DocumentContent 类**

```java
package com.kira.server.controller.knowledge.dto;

import lombok.Builder;
import lombok.Data;
import org.apache.tika.metadata.Metadata;

/**
 * 文档解析内容
 */
@Data
@Builder
public class DocumentContent {
    private String title;
    private String author;
    private String creationDate;
    private String text;
    private Metadata metadata;
}
```

**Step 2: 创建 DocumentMetadata 类**

```java
package com.kira.server.controller.knowledge.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档元数据
 */
@Data
@Builder
public class DocumentMetadata {
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private LocalDateTime createdAt;
    private Map<String, String> properties;
}
```

**Step 3: Commit**

```bash
git add sky-server/src/main/java/com/kira/server/controller/knowledge/dto/
git commit -m "feat: 添加文档解析相关 DTO"
```

---

### Task 4: 创建 Tika 文档解析器

**Files:**
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/service/knowledge/TikaDocumentParser.java`
- Test: `sky-chuanqin/sky-server/src/test/java/com/kira/server/service/knowledge/TikaDocumentParserTest.java`

**Step 1: 编写测试**

```java
package com.kira.server.service.knowledge;

import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TikaDocumentParserTest {

    private TikaDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new TikaDocumentParser();
    }

    @Test
    void testParsePlainText() throws IOException {
        String content = "这是一个测试文档\n\n包含两段文字";
        MockMultipartFile file = new MockMultipartFile(
            "test.txt",
            "test.txt",
            "text/plain",
            content.getBytes()
        );

        DocumentContent result = parser.parse(file);

        assertNotNull(result);
        assertTrue(result.getText().contains("测试文档"));
        assertEquals(2, result.getText().split("\n\n").length);
    }

    @Test
    void testParseExtractsMetadata() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
            "test.txt",
            "document.txt",
            "text/plain",
            "content".getBytes()
        );

        DocumentContent result = parser.parse(file);

        assertEquals("document.txt", result.getMetadata().get("resourceName"));
    }
}
```

**Step 2: 运行测试（预期失败）**

```bash
mvn test -Dtest=TikaDocumentParserTest
```

预期: `Class not found: TikaDocumentParser`

**Step 3: 实现解析器**

```java
package com.kira.server.service.knowledge;

import com.kira.server.controller.knowledge.dto.DocumentContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Tika 文档解析器
 * 支持 PDF、Word、Excel、PPT、TXT 等多种格式
 */
@Slf4j
@Component
public class TikaDocumentParser {

    private final Tika tika = new Tika();

    /**
     * 解析文档内容
     */
    public DocumentContent parse(MultipartFile file) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(Metadata.RESOURCE_NAME_KEY, file.getOriginalFilename());

        try (InputStream input = file.getInputStream()) {
            // 提取文本内容
            String text = tika.parseToString(input, metadata);

            return DocumentContent.builder()
                .title(metadata.get(TikaCoreProperties.TITLE))
                .author(metadata.get(TikaCoreProperties.CREATOR))
                .creationDate(metadata.get(TikaCoreProperties.CREATED))
                .text(text)
                .metadata(metadata)
                .build();
        } catch (Exception e) {
            log.error("文档解析失败: {}", file.getOriginalFilename(), e);
            throw new IOException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查是否支持该文件类型
     */
    public boolean isSupported(String contentType) {
        return contentType != null && (
            contentType.equals("application/pdf") ||
            contentType.equals("application/msword") ||
            contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
            contentType.equals("application/vnd.ms-excel") ||
            contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
            contentType.equals("application/vnd.ms-powerpoint") ||
            contentType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation") ||
            contentType.equals("text/plain")
        );
    }
}
```

**Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=TikaDocumentParserTest
```

预期: `Tests run: 2, Failures: 0, Errors: 0`

**Step 5: Commit**

```bash
git add sky-server/src/main/java/com/kira/server/service/knowledge/TikaDocumentParser.java
git add sky-server/src/test/java/com/kira/server/service/knowledge/TikaDocumentParserTest.java
git commit -m "feat: 实现 Tika 文档解析器"
```

---

### Task 5: 创建文件上传控制器

**Files:**
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/knowledge/KnowledgeController.java`
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/knowledge/dto/KnowledgeUploadResponse.java`

**Step 1: 创建响应 DTO**

```java
package com.kira.server.controller.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库上传响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse {
    private String docId;
    private String title;
    private String status;
    private String message;
}
```

**Step 2: 编写控制器测试**

```java
package com.kira.server.controller.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testUploadFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.txt",
            "text/plain",
            "测试内容".getBytes()
        );

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/knowledge/upload")
                .file(file)
                .param("category", "test"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.docId").exists())
            .andExpect(jsonPath("$.status").value("PARSING"));
    }
}
```

**Step 3: 运行测试（预期失败）**

```bash
mvn test -Dtest=KnowledgeControllerTest
```

**Step 4: 实现控制器**

```java
package com.kira.server.controller.knowledge;

import com.kira.server.controller.knowledge.dto.KnowledgeUploadResponse;
import com.kira.server.enums.ImportStatus;
import com.kira.server.service.knowledge.KnowledgeImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeImportService importService;

    /**
     * 上传单个文件
     */
    @PostMapping("/upload")
    public ResponseEntity<KnowledgeUploadResponse> uploadFile(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "category", required = false, defaultValue = "default") String category,
        @RequestParam(value = "subcategory", required = false) String subcategory
    ) {
        log.info("收到文件上传请求: filename={}, category={}", file.getOriginalFilename(), category);

        // 验证文件
        validateFile(file);

        // 异步处理
        String docId = importService.processFileAsync(file, category, subcategory);

        KnowledgeUploadResponse response = KnowledgeUploadResponse.builder()
            .docId(docId)
            .title(file.getOriginalFilename())
            .status(ImportStatus.PARSING.name())
            .message("文件正在处理中")
            .build();

        return ResponseEntity.accepted().body(response);
    }

    /**
     * 批量上传文件
     */
    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadBatch(
        @RequestParam("files") MultipartFile[] files,
        @RequestParam(value = "category", required = false, defaultValue = "default") String category
    ) {
        log.info("收到批量上传请求: count={}", files.length);

        Map<String, String> results = new HashMap<>();
        for (MultipartFile file : files) {
            try {
                String docId = importService.processFileAsync(file, category, null);
                results.put(file.getOriginalFilename(), docId);
            } catch (Exception e) {
                results.put(file.getOriginalFilename(), "ERROR: " + e.getMessage());
            }
        }

        return ResponseEntity.accepted().body(Map.of(
            "count", files.length,
            "results", results
        ));
    }

    /**
     * 获取文档状态
     */
    @GetMapping("/documents/{docId}/status")
    public ResponseEntity<Map<String, Object>> getDocumentStatus(@PathVariable String docId) {
        return ResponseEntity.ok(importService.getDocumentStatus(docId));
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // 文件大小限制 50MB
        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 50MB");
        }
    }
}
```

**Step 5: 运行测试验证**

```bash
mvn test -Dtest=KnowledgeControllerTest
```

**Step 6: Commit**

```bash
git add sky-server/src/main/java/com/kira/server/controller/knowledge/
git commit -m "feat: 实现知识库文件上传控制器"
```

---

### Task 6: 创建文件处理服务

**Files:**
- Create: `sky-chuanqin/sky-server/src/main/java/com/kira/server/service/knowledge/KnowledgeImportService.java`

**Step 1: 编写测试**

```java
package com.kira.server.service.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.yml")
class KnowledgeImportServiceTest {

    @Autowired
    private KnowledgeImportService importService;

    @Autowired
    private TikaDocumentParser parser;

    @Test
    void testProcessFileAsync() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "test.txt",
            "test.txt",
            "text/plain",
            "测试文档内容\n\n第二段内容".getBytes()
        );

        String docId = importService.processFileAsync(file, "test", null);

        assertNotNull(docId);
        assertTrue(docId.startsWith("DOC-"));

        // 等待异步处理完成
        Thread.sleep(2000);

        var status = importService.getDocumentStatus(docId);
        assertEquals("PARSED", status.get("importStatus"));
    }
}
```

**Step 2: 运行测试（预期失败）**

```bash
mvn test -Dtest=KnowledgeImportServiceTest
```

**Step 3: 实现服务**

```java
package com.kira.server.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.knowledge.dto.DocumentContent;
import com.kira.server.controller.knowledge.dto.DocumentMetadata;
import com.kira.server.enums.ImportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库导入服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeImportService {

    private final TikaDocumentParser tikaParser;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STREAM_NAME = "stream:knowledge_import";

    /**
     * 异步处理文件
     */
    @Async("taskExecutor")
    public String processFileAsync(MultipartFile file, String category, String subcategory) {
        String docId = generateDocId();

        try {
            log.info("开始处理文件: docId={}, filename={}", docId, file.getOriginalFilename());

            // 1. 更新状态：PARSING
            updateStatus(docId, ImportStatus.PARSING);

            // 2. Tika 解析
            DocumentContent content = tikaParser.parse(file);

            // 3. 构建元数据
            DocumentMetadata metadata = DocumentMetadata.builder()
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .createdAt(LocalDateTime.now())
                .build();

            // 4. 存入数据库（这里简化，实际需要 repository）
            saveToDatabase(docId, content, metadata, category, subcategory);

            // 5. 发送 Redis Stream 消息
            sendImportMessage(docId, content.getTitle(), category);

            // 6. 更新状态：QUEUED
            updateStatus(docId, ImportStatus.QUEUED);

            log.info("文件处理完成，已入队: docId={}", docId);
            return docId;

        } catch (Exception e) {
            log.error("文件处理失败: docId={}", docId, e);
            updateStatus(docId, ImportStatus.FAILED);
            throw new RuntimeException("文件处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取文档状态
     */
    public Map<String, Object> getDocumentStatus(String docId) {
        // 从 Redis 获取状态
        String statusKey = "knowledge:status:" + docId;
        String status = redisTemplate.opsForValue().get(statusKey);

        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("importStatus", status != null ? status : "UNKNOWN");
        return result;
    }

    private String generateDocId() {
        return "DOC-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void updateStatus(String docId, ImportStatus status) {
        String statusKey = "knowledge:status:" + docId;
        redisTemplate.opsForValue().set(statusKey, status.name());
    }

    private void saveToDatabase(String docId, DocumentContent content, DocumentMetadata metadata,
                               String category, String subcategory) {
        // TODO: 实现数据库存储
        // 这里需要创建 repository 并保存到 knowledge_documents 表
        log.info("保存到数据库: docId={}, title={}", docId, content.getTitle());
    }

    private void sendImportMessage(String docId, String title, String category) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("doc_id", docId);
            message.put("title", title != null ? title : "Untitled");
            message.put("category", category != null ? category : "default");
            message.put("timestamp", String.valueOf(System.currentTimeMillis()));

            redisTemplate.opsForStream().add(STREAM_NAME, message);
            log.info("已发送知识库导入消息: docId={}", docId);
        } catch (Exception e) {
            log.error("发送 Redis 消息失败: docId={}", docId, e);
        }
    }
}
```

**Step 4: 配置异步任务**

在 `sky-chuanqin/sky-server/src/main/java/com/kira/server/config/AsyncConfig.java`:

```java
package com.kira.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("knowledge-import-");
        executor.initialize();
        return executor;
    }
}
```

**Step 5: 运行测试验证**

```bash
mvn test -Dtest=KnowledgeImportServiceTest
```

**Step 6: Commit**

```bash
git add sky-server/src/main/java/com/kira/server/service/knowledge/KnowledgeImportService.java
git add sky-server/src/main/java/com/kira/server/config/AsyncConfig.java
git commit -m "feat: 实现知识库导入服务"
```

---

## 第三阶段：Agent 消费者实现

### Task 7: 修复向量存储问题

**Files:**
- Modify: `yibccc-langchain/src/repositories/knowledge_repo.py`

**Step 1: 编写测试**

```python
# tests/repositories/test_knowledge_repo_vector_fix.py
import pytest
import asyncio
from src.repositories.knowledge_repo import KnowledgeRepository


@pytest.mark.asyncio
async def test_vector_storage_with_native_format(db_pool):
    """测试使用 pgvector 原生格式存储向量"""
    repo = KnowledgeRepository(db_pool)

    # 模拟向量
    test_vector = [0.1] * 1024

    async with db_pool.acquire() as conn:
        # 注册 pgvector 类型
        await conn.set_type_codec(
            'vector',
            encoder=lambda v: str(v),
            decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
            schema='pg_catalog',
            format='text'
        )

        # 测试插入
        await conn.execute(
            "INSERT INTO knowledge_chunks (parent_doc_id, chunk_index, content, embedding) "
            "VALUES ($1, $2, $3, $4)",
            "TEST-DOC", 0, "test content", test_vector
        )

        # 测试查询
        row = await conn.fetchrow(
            "SELECT embedding FROM knowledge_chunks WHERE parent_doc_id = $1",
            "TEST-DOC"
        )

        assert row is not None
        # 验证向量可以正常比较
        result = await conn.fetchval(
            "SELECT embedding <-> $1 FROM knowledge_chunks WHERE parent_doc_id = $2",
            str(test_vector), "TEST-DOC"
        )
        assert result is not None
```

**Step 2: 运行测试（观察当前行为）**

```bash
cd yibccc-langchain
pytest tests/repositories/test_knowledge_repo_vector_fix.py -v
```

**Step 3: 修复向量存储**

修改 `src/repositories/knowledge_repo.py`:

```python
async def create_chunks(
    self,
    doc_id: str,
    chunks: List[Dict[str, Any]]
) -> int:
    """创建文档分块（含向量）- 使用 pgvector 原生格式"""
    if not self.embedding_client:
        raise KnowledgeBaseError("Embedding 客户端未配置")

    async with self.pool.acquire() as conn:
        # 注册 pgvector 类型编解码器
        await conn.set_type_codec(
            'vector',
            encoder=lambda v: str(v),  # list[float] -> vector string
            decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
            schema='pg_catalog',
            format='text'
        )

        async with conn.transaction():
            # 删除旧分块
            await conn.execute(
                "DELETE FROM knowledge_chunks WHERE parent_doc_id = $1",
                doc_id
            )

            # 批量生成 embedding（优化性能）
            texts = [chunk["content"] for chunk in chunks]
            embeddings = await self._embed_batch(texts)

            # 批量插入
            for idx, (chunk, embedding) in enumerate(zip(chunks, embeddings)):
                content = chunk["content"]
                # 直接传递向量列表，让编解码器处理
                await conn.execute(
                    """
                    INSERT INTO knowledge_chunks
                    (parent_doc_id, chunk_index, content, embedding)
                    VALUES ($1, $2, $3, $4)
                    """,
                    doc_id,
                    idx,
                    content,
                    embedding  # list[float]
                )

            # 更新文档的分块计数
            await conn.execute(
                "UPDATE knowledge_documents SET chunk_count = $1 WHERE doc_id = $2",
                len(chunks),
                doc_id
            )

    return len(chunks)


async def _embed_batch(self, texts: List[str]) -> List[List[float]]:
    """批量生成 embedding（性能优化）"""
    import asyncio

    def sync_embed_batch():
        # 使用 LangChain 的批量 embedding
        return self.embedding_client.embed_documents(texts)

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, sync_embed_batch)
```

**Step 4: 更新向量搜索也使用相同格式**

```python
async def vector_search(
    self,
    query: str,
    top_k: int = 5,
    category: Optional[str] = None
) -> List[Dict[str, Any]]:
    """向量搜索 - 使用原生格式"""
    if not self.embedding_client:
        raise KnowledgeBaseError("Embedding 客户端未配置")

    # 生成查询向量
    query_embedding = await self._embed_text(query)

    async with self.pool.acquire() as conn:
        # 确保类型编解码器已注册
        await conn.set_type_codec(
            'vector',
            encoder=lambda v: str(v),
            decoder=lambda v: [float(x) for x in v.strip('[]').split(',')],
            schema='pg_catalog',
            format='text'
        )

        if category:
            rows = await conn.fetch(
                """
                SELECT DISTINCT ON (kd.doc_id)
                    kd.doc_id, kd.title, kd.category, kd.content,
                    MIN(kc.embedding <-> $1) as distance
                FROM knowledge_documents kd
                JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                WHERE kd.category = $2
                GROUP BY kd.doc_id, kd.title, kd.category, kd.content
                ORDER BY distance
                LIMIT $3
                """,
                query_embedding,  # 直接传递 list
                category,
                top_k
            )
        else:
            rows = await conn.fetch(
                """
                SELECT DISTINCT ON (kd.doc_id)
                    kd.doc_id, kd.title, kd.category, kd.content,
                    MIN(kc.embedding <-> $1) as distance
                FROM knowledge_documents kd
                JOIN knowledge_chunks kc ON kd.doc_id = kc.parent_doc_id
                GROUP BY kd.doc_id, kd.title, kd.category, kd.content
                ORDER BY distance
                LIMIT $2
                """,
                query_embedding,
                top_k
            )

        return [dict(row) for row in rows]
```

**Step 5: 运行测试验证**

```bash
pytest tests/repositories/test_knowledge_repo_vector_fix.py -v
```

**Step 6: Commit**

```bash
cd yibccc-langchain
git add src/repositories/knowledge_repo.py
git add tests/repositories/test_knowledge_repo_vector_fix.py
git commit -m "fix: 修复 pgvector 向量存储，使用原生格式而非字符串"
```

---

### Task 8: 实现知识导入消费者

**Files:**
- Create: `yibccc-langchain/src/services/knowledge_import_consumer.py`
- Create: `yibccc-langchain/tests/services/test_knowledge_import_consumer.py`

**Step 1: 编写测试**

```python
# tests/services/test_knowledge_import_consumer.py
import pytest
import asyncio
from unittest.mock import AsyncMock, Mock, patch
from src.services.knowledge_import_consumer import KnowledgeImportConsumer


@pytest.mark.asyncio
async def test_consumer_processes_message():
    """测试消费者处理消息"""
    mock_pool = AsyncMock()
    mock_redis = AsyncMock()

    consumer = KnowledgeImportConsumer(mock_pool, mock_redis)

    # 模拟消息
    message_data = {
        b'doc_id': b'TEST-DOC-123',
        b'title': b'Test Document',
        b'category': b'test',
        b'timestamp': b'1234567890'
    }

    # 模拟数据库返回
    mock_pool.acquire.return_value.__aenter__.return_value.fetchrow.return_value = {
        'doc_id': 'TEST-DOC-123',
        'title': 'Test Document',
        'content': 'This is a test document.\n\nWith two paragraphs.'
    }

    with patch.object(consumer, '_update_import_status', AsyncMock()):
        with patch.object(consumer, '_embed_and_store_chunks', AsyncMock(return_value=2)):
            await consumer._process_import('test-message-id', message_data)

            # 验证状态更新
            assert consumer._update_import_status.call_count >= 2


@pytest.mark.asyncio
async def test_parent_chunk_creation():
    """测试父分块创建"""
    mock_pool = AsyncMock()
    mock_redis = AsyncMock()

    consumer = KnowledgeImportConsumer(mock_pool, mock_redis)

    text = "Paragraph 1\n\nParagraph 2\n\nParagraph 3"
    chunks = consumer._create_parent_chunks(text)

    assert len(chunks) > 0
    assert all(isinstance(c, str) for c in chunks)
```

**Step 2: 运行测试（预期失败）**

```bash
pytest tests/services/test_knowledge_import_consumer.py -v
```

**Step 3: 实现消费者**

```python
# src/services/knowledge_import_consumer.py
"""
知识库导入消费者
从 Redis Stream 消费导入任务，处理分块和向量化
"""

import logging
import asyncio
from typing import List, Dict, Any, Optional
import asyncpg

from src.config import settings
from src.models.exceptions import KnowledgeBaseError

logger = logging.getLogger(__name__)


class KnowledgeImportConsumer:
    """知识库导入消费者"""

    def __init__(self, pool: asyncpg.Pool, redis_client):
        self.pool = pool
        self.redis = redis_client
        self.stream_name = "stream:knowledge_import"
        self.consumer_group = "group:knowledge_workers"
        self.consumer_name = f"worker-{id(self)}"
        self.running = False
        self.embeddings = None

    async def start(self):
        """启动消费者"""
        # 创建消费组（如果不存在）
        try:
            await self.redis.xgroup_create(
                self.stream_name,
                self.consumer_group,
                id='0',
                mkstream=True
            )
            logger.info(f"创建消费组: {self.consumer_group}")
        except Exception as e:
            logger.info(f"消费组已存在: {self.consumer_group}")

        self.running = True
        logger.info(f"知识导入消费者启动: {self.consumer_name}")

        while self.running:
            await self._process_messages()
            await asyncio.sleep(0.1)

    async def stop(self):
        """停止消费者"""
        self.running = False
        logger.info("知识导入消费者停止")

    async def _process_messages(self):
        """处理消息"""
        try:
            # 读取消息（阻塞1秒）
            messages = await self.redis.xreadgroup(
                self.consumer_group,
                self.consumer_name,
                {self.stream_name: '>'},
                count=1,
                block=1000
            )

            if not messages:
                return

            for stream, stream_messages in messages:
                for message_id, data in stream_messages:
                    try:
                        await self._process_import(message_id, data)
                    except Exception as e:
                        logger.error(f"处理消息失败: {e}", exc_info=True)
                        # ACK 消息避免重复处理
                        await self.redis.xack(self.stream_name, self.consumer_group, message_id)

        except Exception as e:
            logger.error(f"读取消息失败: {e}", exc_info=True)
            await asyncio.sleep(1)

    async def _process_import(self, message_id: str, data: dict):
        """处理单个导入任务"""
        doc_id = data.get(b'doc_id', b'').decode()
        title = data.get(b'title', b'').decode()
        category = data.get(b'category', b'').decode()

        try:
            logger.info(f"开始处理导入: docId={doc_id}")

            # 1. 从数据库获取文档内容
            doc = await self._get_document(doc_id)
            if not doc:
                raise KnowledgeBaseError(f"文档不存在: {doc_id}")

            # 2. 更新状态：CHUNKING
            await self._update_import_status(doc_id, "CHUNKING")

            # 3. 父子分块
            all_chunks = await self._create_chunks(doc['content'])

            logger.info(f"文档分块完成: docId={doc_id}, chunks={len(all_chunks)}")

            # 4. 更新状态：EMBEDDING
            await self._update_import_status(doc_id, "EMBEDDING")

            # 5. 向量化并存储
            await self._embed_and_store_chunks(doc_id, all_chunks)

            # 6. 更新状态：COMPLETED
            await self._update_import_status(doc_id, "COMPLETED",
                                           chunk_count=len(all_chunks))

            # 7. ACK 消息
            await self.redis.xack(self.stream_name, self.consumer_group, message_id)

            logger.info(f"导入完成: docId={doc_id}, chunks={len(all_chunks)}")

        except Exception as e:
            logger.error(f"导入失败: docId={doc_id}, error={e}", exc_info=True)
            await self._update_import_status(doc_id, "FAILED", error=str(e))
            await self.redis.xack(self.stream_name, self.consumer_group, message_id)

    async def _get_document(self, doc_id: str) -> Optional[Dict]:
        """从数据库获取文档"""
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT doc_id, title, content FROM knowledge_documents WHERE doc_id = $1",
                doc_id
            )
            return dict(row) if row else None

    async def _create_chunks(self, text: str) -> List[Dict[str, Any]]:
        """创建父子分块"""
        from langchain_text_splitters import RecursiveCharacterTextSplitter

        chunks = []

        # 先创建父分块（按段落）
        parent_chunks = self._create_parent_chunks(text)

        for parent_idx, parent_chunk in enumerate(parent_chunks):
            # 子分块器
            child_splitter = RecursiveCharacterTextSplitter(
                chunk_size=600,
                chunk_overlap=100,
                length_function=len,
            )

            child_chunks = child_splitter.split_text(parent_chunk)

            for child_idx, child_content in enumerate(child_chunks):
                chunks.append({
                    'content': child_content,
                    'parent_index': parent_idx,
                    'chunk_index': len(chunks)
                })

        return chunks

    def _create_parent_chunks(self, text: str) -> List[str]:
        """创建父分块（按章节/段落）"""
        # 按双换行分段（段落级）
        paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]

        # 合并小段落，确保父块约 2000-3000 字符
        parent_chunks = []
        current_chunk = ""

        for para in paragraphs:
            if len(current_chunk) + len(para) > 3000:
                if current_chunk:
                    parent_chunks.append(current_chunk)
                current_chunk = para
            else:
                current_chunk += "\n\n" + para if current_chunk else para

        if current_chunk:
            parent_chunks.append(current_chunk)

        return parent_chunks

    async def _embed_and_store_chunks(self, doc_id: str, chunks: List[Dict]):
        """向量化并存储分块"""
        # 复用现有的 knowledge_repo
        from src.repositories.knowledge_repo import KnowledgeRepository
        from langchain_community.embeddings import DashScopeEmbeddings

        # 初始化 embeddings
        if not self.embeddings:
            self.embeddings = DashScopeEmbeddings(
                model=settings.embedding_model,
                dashscope_api_key=settings.dashscope_api_key,
            )

        repo = KnowledgeRepository(self.pool, self.embeddings)
        await repo.create_chunks(doc_id, chunks)

    async def _update_import_status(self, doc_id: str, status: str,
                                   chunk_count: int = None, error: str = None):
        """更新导入状态到 Redis"""
        status_key = f"knowledge:status:{doc_id}"
        await self.redis.set(status_key, status)

        if chunk_count is not None:
            count_key = f"knowledge:chunks:{doc_id}"
            await self.redis.set(count_key, str(chunk_count))

        if error:
            error_key = f"knowledge:error:{doc_id}"
            await self.redis.set(error_key, error)
```

**Step 4: 运行测试验证**

```bash
pytest tests/services/test_knowledge_import_consumer.py -v
```

**Step 5: 添加启动入口**

在 `src/api/main.py` 中添加消费者启动逻辑：

```python
# 在启动时启动消费者
@app.on_event("startup")
async def startup_event():
    # ... 现有代码 ...

    # 启动知识导入消费者
    from src.services.knowledge_import_consumer import KnowledgeImportConsumer

    consumer = KnowledgeImportConsumer(pool, redis_client)
    asyncio.create_task(consumer.start())

    # 保存引用以便关闭
    app.state.knowledge_consumer = consumer


@app.on_event("shutdown")
async def shutdown_event():
    # 停止消费者
    if hasattr(app.state, 'knowledge_consumer'):
        await app.state.knowledge_consumer.stop()
```

**Step 6: Commit**

```bash
cd yibccc-langchain
git add src/services/knowledge_import_consumer.py
git add tests/services/test_knowledge_import_consumer.py
git add src/api/main.py
git commit -m "feat: 实现知识库导入消费者"
```

---

## 第四阶段：前端集成

### Task 9: 创建文件上传组件

**Files:**
- Create: `sky-chuanqin/sky-admin/src/views/knowledge/KnowledgeUpload.vue`

**Step 1: 创建上传组件**

```vue
<template>
  <div class="knowledge-upload">
    <el-card header="知识库文档上传">
      <el-upload
        ref="uploadRef"
        :action="uploadUrl"
        :headers="uploadHeaders"
        :on-success="handleSuccess"
        :on-error="handleError"
        :on-progress="handleProgress"
        :before-upload="beforeUpload"
        :file-list="fileList"
        :multiple="true"
        :accept="acceptTypes"
        :limit="10"
        :auto-upload="true"
        drag
        class="upload-area"
      >
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">
          拖拽文件到此处或 <em>点击上传</em>
        </div>
        <template #tip>
          <div class="el-upload__tip">
            支持 PDF、Word、Excel、PPT、TXT 格式，单个文件不超过 50MB
          </div>
        </template>
      </el-upload>

      <!-- 上传状态列表 -->
      <div v-if="uploadingFiles.length > 0" class="upload-status">
        <h4>上传进度</h4>
        <div v-for="file in uploadingFiles" :key="file.uid" class="file-item">
          <div class="file-info">
            <el-icon><document /></el-icon>
            <span>{{ file.name }}</span>
          </div>
          <el-progress
            :percentage="file.percentage"
            :status="file.status === 'success' ? 'success' :
                     file.status === 'fail' ? 'exception' : undefined"
          />
          <div class="file-meta">
            <el-tag v-if="file.docId" type="info" size="small">
              {{ file.docId }}
            </el-tag>
            <el-tag v-if="file.importStatus" :type="getStatusType(file.importStatus)" size="small">
              {{ getStatusText(file.importStatus) }}
            </el-tag>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document } from '@element-plus/icons-vue'
import { getToken } from '@/utils/auth'

const uploadUrl = computed(() => `${process.env.VUE_APP_BASE_API}/api/knowledge/upload`)
const acceptTypes = '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt'
const uploadHeaders = computed(() => ({
  'Authorization': `Bearer ${getToken()}`
}))

const fileList = ref([])
const uploadingFiles = ref([])

const beforeUpload = (file) => {
  const validTypes = [
    'application/pdf',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.ms-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-powerpoint',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'text/plain'
  ]

  if (!validTypes.includes(file.type)) {
    ElMessage.error('不支持的文件类型')
    return false
  }

  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 50MB')
    return false
  }

  uploadingFiles.value.push({
    uid: file.uid,
    name: file.name,
    percentage: 0,
    status: 'uploading'
  })

  return true
}

const handleProgress = (event, file) => {
  const uploadingFile = uploadingFiles.value.find(f => f.uid === file.uid)
  if (uploadingFile) {
    uploadingFile.percentage = Math.floor(event.percent)
  }
}

const handleSuccess = (response, file) => {
  const uploadingFile = uploadingFiles.value.find(f => f.uid === file.uid)
  if (uploadingFile) {
    uploadingFile.status = 'success'
    uploadingFile.docId = response.docId
    uploadingFile.importStatus = response.status || 'PARSING'

    ElMessage.success(`${file.name} 上传成功`)

    // 开始轮询导入状态
    pollImportStatus(response.docId, file.uid)
  }
}

const handleError = (error, file) => {
  const uploadingFile = uploadingFiles.value.find(f => f.uid === file.uid)
  if (uploadingFile) {
    uploadingFile.status = 'fail'
    ElMessage.error(`${file.name} 上传失败`)
  }
}

const pollImportStatus = async (docId, fileUid) => {
  const interval = setInterval(async () => {
    try {
      const response = await fetch(`/api/knowledge/documents/${docId}/status`, {
        headers: {
          'Authorization': `Bearer ${getToken()}`
        }
      })
      const data = await response.json()

      const uploadingFile = uploadingFiles.value.find(f => f.uid === fileUid)
      if (uploadingFile) {
        uploadingFile.importStatus = data.importStatus
      }

      if (data.importStatus === 'COMPLETED' || data.importStatus === 'FAILED') {
        clearInterval(interval)
        if (data.importStatus === 'COMPLETED') {
          ElMessage.success(`文档 ${docId} 导入完成`)
        }
      }
    } catch (error) {
      console.error('获取状态失败', error)
    }
  }, 3000)

  // 5分钟后停止轮询
  setTimeout(() => clearInterval(interval), 5 * 60 * 1000)
}

const getStatusType = (status) => {
  const typeMap = {
    'PENDING': 'info',
    'PARSING': 'warning',
    'PARSED': 'primary',
    'QUEUED': 'primary',
    'CHUNKING': 'warning',
    'EMBEDDING': 'warning',
    'COMPLETED': 'success',
    'FAILED': 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    'PENDING': '待处理',
    'PARSING': '解析中',
    'PARSED': '解析完成',
    'QUEUED': '已入队',
    'CHUNKING': '分块中',
    'EMBEDDING': '向量化中',
    'COMPLETED': '完成',
    'FAILED': '失败'
  }
  return textMap[status] || status
}
</script>

<style scoped lang="scss">
.knowledge-upload {
  padding: 20px;

  .upload-area {
    margin-bottom: 20px;
  }

  .upload-status {
    margin-top: 30px;

    .file-item {
      margin-bottom: 15px;
      padding: 15px;
      background: #f5f7fa;
      border-radius: 4px;

      .file-info {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
        font-weight: 500;
      }

      .file-meta {
        display: flex;
        gap: 8px;
        margin-top: 8px;
      }
    }
  }
}
</style>
```

**Step 2: 添加路由**

在 `src/router/index.js` 中添加：

```javascript
{
  path: '/knowledge',
  component: Layout,
  children: [
    {
      path: 'upload',
      name: 'KnowledgeUpload',
      component: () => import('@/views/knowledge/KnowledgeUpload'),
      meta: { title: '知识库上传', icon: 'upload' }
    }
  ]
}
```

**Step 3: Commit**

```bash
cd sky-admin
git add src/views/knowledge/KnowledgeUpload.vue
git add src/router/index.js
git commit -m "feat: 添加知识库文件上传页面"
```

---

## 第五阶段：集成测试

### Task 10: 端到端集成测试

**Files:**
- Create: `sky-chuanqin/sky-server/src/test/java/com/kira/server/integration/KnowledgeImportE2ETest.java`

**Step 1: 编写集成测试**

```java
package com.kira.server.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeImportE2ETest {

    @Autowired
    private MockMvc mockMvc;

    private static String testDocId;

    @Test
    @Order(1)
    void testCompleteImportFlow() throws Exception {
        // 1. 上传文件
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test-document.txt",
            "text/plain",
            """
            第一段内容：钙污染是钻井液中常见的问题。

            第二段内容：当钙离子浓度过高时，会影响钻井液的性能。

            第三段内容：需要通过添加处理剂来解决钙污染问题。
            """.getBytes()
        );

        String response = mockMvc.perform(
                MockMvcRequestBuilders.multipart("/api/knowledge/upload")
                    .file(file)
                    .param("category", "pollution")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.docId").exists())
            .andExpect(jsonPath("$.status").value("PARSING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // 提取 docId
        testDocId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response)
            .get("docId")
            .asText();

        Assertions.assertNotNull(testDocId);

        // 2. 等待状态变化
        await().atMost(30, SECONDS).untilAsserted(() -> {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/knowledge/documents/" + testDocId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importStatus").exists());
        });

        // 3. 验证最终状态
        // 注意：需要 Agent 服务运行才能完成整个流程
    }

    @Test
    @Order(2)
    void testBatchUpload() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile(
            "files", "test1.txt", "text/plain", "Content 1".getBytes()
        );
        MockMultipartFile file2 = new MockMultipartFile(
            "files", "test2.txt", "text/plain", "Content 2".getBytes()
        );

        mockMvc.perform(
                MockMvcRequestBuilders.multipart("/api/knowledge/upload/batch")
                    .file(file1)
                    .file(file2)
                    .param("category", "test")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.results").exists());
    }
}
```

**Step 2: 运行集成测试**

```bash
cd sky-chuanqin
mvn test -Dtest=KnowledgeImportE2ETest
```

**Step 3: Commit**

```bash
git add sky-server/src/test/java/com/kira/server/integration/KnowledgeImportE2ETest.java
git commit -m "test: 添加知识库导入集成测试"
```

---

## 验收清单

完成以上所有任务后，验证以下功能：

- [ ] 可以通过前端上传 PDF、Word、Excel、PPT、TXT 文件
- [ ] SpringBoot 使用 Tika 成功解析文件内容
- [ ] 原始文件存储到 rustfs
- [ ] Redis Stream 正确发送导入消息
- [ ] Agent 消费者成功处理消息
- [ ] 文档正确分块（父子分块策略）
- [ ] 向量使用 pgvector 原生格式存储
- [ ] 前端可以实时查看导入进度
- [ ] 导入完成后可以检索到文档内容

---

## 后续优化方向

1. **rustfs 集成**: 实际对接 rustfs 文件存储服务
2. **WebSocket 推送**: 实时推送导入状态变化
3. **文档管理页面**: 查看所有文档、删除文档、查看分块详情
4. **批量导入优化**: 支持上传 ZIP 压缩包自动解压
5. **错误重试**: 失败文档自动重新处理
6. **文档版本管理**: 支持同一文档的多个版本
7. **权限控制**: 按用户/组织隔离知识库
