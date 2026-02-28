# 数据库迁移验证记录

**日期**: 2026-02-27
**执行人**: [待填写]
**脚本**: oss_file_storage_schema.sql
**状态**: 待执行

## 验证项

- [ ] file_records 表已创建
- [ ] 唯一索引 uq_filename_category 已创建
- [ ] 索引 idx_file_hash 已创建
- [ ] 索引 idx_file_category 已创建
- [ ] knowledge_documents.oss_path 字段已添加
- [ ] knowledge_documents.file_record_id 字段已添加
- [ ] 索引 idx_knowledge_doc_oss_path 已创建

## 验证 SQL

```sql
-- 验证表结构
DESC file_records;

-- 验证 knowledge_documents 表
DESC knowledge_documents;

-- 验证索引
SHOW INDEX FROM file_records;
SHOW INDEX FROM knowledge_documents;
```

## 执行命令

```bash
# 替换以下参数为实际数据库连接信息
DB_HOST="47.113.226.70"
DB_PORT="3306"
DB_NAME="sky"
DB_USER="root"
DB_PASS="9988741"

# 执行 SQL 脚本
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME < yibccc-langchain/docs/sql/oss_file_storage_schema.sql
```
