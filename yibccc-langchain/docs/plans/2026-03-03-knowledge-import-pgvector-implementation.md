# 知识导入 PGVector 迁移实施计划

**日期**: 2026-03-03
**状态**: 待实施
**依赖**: [设计文档](./2026-03-03-knowledge-import-pgvector-design.md)

---

## 1. 实施概览

### 1.1 目标

将知识导入消费者从旧的 `KnowledgeRepository` 迁移到新的 `VectorStoreService`，实现完全基于 PGVector 的知识存储。

### 1.2 关键变更

1. **存储层替换**: `KnowledgeRepository` → `VectorStoreService`
2. **元数据统一**: 所有元数据存储在 PGVector 的 `cmetadata` JSONB 字段
3. **父子分块**: 通过 `parent_chunk_id` 在 metadata 中维护层级关系

### 1.3 实施阶段

```
阶段1: 核心修改 (P0)
├── 1.1 扩展 VectorStoreService
├── 1.2 修改 KnowledgeImportConsumer
└── 1.3 更新单元测试

阶段2: 验证与迁移 (P1)
├── 2.1 集成测试
├── 2.2 数据迁移脚本
└── 2.3 性能基准

阶段3: 清理 (P2)
├── 3.1 废弃 KnowledgeRepository
└── 3.2 文档更新
```

---

## 2. 详细实施步骤

### 阶段1: 核心修改 (P0)

#### 步骤 1.1: 扩展 VectorStoreService

**目标文件**: `src/services/vector_store_service.py`

**新增方法**:

1. `add_parent_child_documents()` - 批量插入父子分块
2. `query_by_parent_chunk()` - 查询父分块的子分块
3. `query_by_doc_id()` - 查询文档的所有分块
4. `delete_by_doc_id()` - 删除文档的所有分块

**参考实现**: 见设计文档第2章

**验证步骤**:
```bash
# 1. 运行单元测试
pytest tests/services/test_vector_store_service.py -v

# 2. 检查类型检查
mypy src/services/vector_store_service.py
```

---

#### 步骤 1.2: 修改 KnowledgeImportConsumer

**目标文件**: `src/services/knowledge_import_consumer.py`

**修改内容**:

1. **移除导入**:
   ```python
   # 删除这一行
   # from src.repositories.knowledge_repo import KnowledgeRepository
   ```

2. **新增导入**:
   ```python
   from src.services.vector_store_service import VectorStoreService
   from langchain_core.documents import Document
   from datetime import datetime
   ```

3. **重写 `_embed_and_store_chunks` 方法**:

   完整实现见设计文档第4.2.1节。

4. **修改 `_process_import` 中的调用**:

   ```python
   # 原调用
   # await self._embed_and_store_chunks(doc_id, all_chunks)

   # 新调用
   await self._embed_and_store_chunks(
       doc_id=doc_id,
       title=title,
       category=category,
       chunks=all_chunks,
       base_metadata={
           "oss_path": oss_path,
           "file_record_id": file_record_id,
           **metadata
       }
   )
   ```

**验证步骤**:
```bash
# 1. 运行消费者单元测试
pytest tests/services/test_knowledge_import_consumer.py -v

# 2. 类型检查
mypy src/services/knowledge_import_consumer.py
```

---

#### 步骤 1.3: 更新单元测试

**目标文件**:
- `tests/services/test_vector_store_service.py`
- `tests/services/test_knowledge_import_consumer.py`

**测试更新内容**:

1. **VectorStoreService 测试新增**:
   - `test_add_parent_child_documents()`
   - `test_query_by_parent_chunk()`
   - `test_query_by_doc_id()`
   - `test_delete_by_doc_id()`

2. **KnowledgeImportConsumer 测试修改**:
   - Mock `VectorStoreService` 替代 `KnowledgeRepository`
   - 验证 metadata 结构正确
   - 验证父子关系正确

**验证步骤**:
```bash
pytest tests/services/ -v --tb=short
```

---

### 阶段2: 验证与迁移 (P1)

#### 步骤 2.1: 集成测试

**目标**: 验证端到端导入流程

**测试场景**:
1. 单文档导入 (无父子分块)
2. 多文档批量导入
3. 父子分块层级验证
4. 大文件导入性能
5. 错误处理和重试

**验证步骤**:
```bash
pytest tests/integration/test_upload_knowledge.py -v
```

---

#### 步骤 2.2: 数据迁移脚本

**目标文件**: `scripts/migrate_knowledge_to_pgvector.py`

**脚本功能**:
1. 从旧表读取所有文档
2. 重组父子分块关系
3. 写入 PGVector
4. 记录迁移状态
5. 支持断点续传

**执行步骤**:
```bash
# 1. 备份旧数据
pg_dump --table=knowledge_documents --table=knowledge_chunks > backup.sql

# 2. 执行迁移
python scripts/migrate_knowledge_to_pgvector.py --batch-size=100

# 3. 验证数据完整性
python scripts/verify_migration.py
```

---

#### 步骤 2.3: 性能基准

**测试指标**:

| 指标 | 旧架构 | 新架构 | 目标 |
|------|--------|--------|------|
| 单文档导入 (100 chunks) | X ms | Y ms | <= X ms |
| 批量导入 (1000 docs) | X ms | Y ms | <= X ms |
| 语义检索 latency | X ms | Y ms | <= X ms |

**测试脚本**:
```bash
python benchmarks/knowledge_import_benchmark.py
```

---

### 阶段3: 清理 (P2)

#### 步骤 3.1: 废弃 KnowledgeRepository

**操作**:
1. 在 `knowledge_repo.py` 添加废弃警告:
   ```python
   import warnings
   warnings.warn(
       "KnowledgeRepository is deprecated. Use VectorStoreService instead.",
       DeprecationWarning,
       stacklevel=2
   )
   ```

2. 更新导入消费者的 fallback 逻辑（如果需要兼容期）

3. 更新文档，标记为废弃

---

#### 步骤 3.2: 文档更新

**更新文件**:
- `README.md`: 更新架构说明
- `docs/architecture.md`: 更新存储层架构图
- `docs/deployment.md`: 更新部署步骤

---

## 3. 风险评估与缓解

| 风险 | 影响 | 可能性 | 缓解措施 |
|------|------|--------|----------|
| 迁移过程中数据丢失 | 高 | 低 | 完整备份，双写阶段，验证脚本 |
| 父子关系丢失 | 高 | 中 | 严格的 ID 生成规则，验证查询 |
| 性能下降 | 中 | 中 | 性能基准测试，索引优化 |
| 元数据不兼容 | 中 | 中 | 详细的映射表，测试覆盖 |
| 并发导入冲突 | 中 | 低 | 文档级锁，幂等设计 |

---

## 4. 回滚方案

如果发生严重问题，按以下步骤回滚：

1. **立即停止新导入**
   ```bash
   # 停止消费者
   systemctl stop knowledge-consumer
   ```

2. **切换回旧存储**
   - 恢复使用 `KnowledgeRepository`
   - 更新配置指向旧表

3. **数据修复**
   - 如有新数据只写入 PGVector，需要导出并重新导入旧表
   - 使用备份恢复

4. **验证后恢复服务**
   ```bash
   systemctl start knowledge-consumer
   ```

---

## 5. 成功标准

实施完成的定义：

- [ ] 所有 P0 任务完成
- [ ] 单元测试通过率 100%
- [ ] 集成测试通过
- [ ] 性能基准达到目标
- [ ] 数据迁移验证通过
- [ ] 文档已更新
- [ ] 生产环境稳定运行 24 小时无异常

---

## 附录

### A. 相关文档

- [设计文档](./2026-03-03-knowledge-import-pgvector-design.md)
- [PGVector 架构设计](./2026-03-03-knowledge-rag-refactor-design.md)
- [LangChain PGVector 文档](https://python.langchain.com/docs/integrations/vectorstores/pgvector)

### B. 命令速查

```bash
# 运行测试
pytest tests/services/test_vector_store_service.py -v
pytest tests/services/test_knowledge_import_consumer.py -v
pytest tests/integration/test_upload_knowledge.py -v

# 类型检查
mypy src/services/vector_store_service.py
mypy src/services/knowledge_import_consumer.py

# 执行迁移
python scripts/migrate_knowledge_to_pgvector.py --batch-size=100

# 验证迁移
python scripts/verify_migration.py
```
