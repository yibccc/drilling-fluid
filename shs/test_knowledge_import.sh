#!/bin/bash
# 知识库文件导入测试脚本

BASE_URL="http://localhost:18080"
TEST_FILE="/Users/kirayang/Desktop/mountain.txt"

echo "=== 知识库文件导入测试 ==="
echo ""

# 1. 测试单文件上传
echo "1. 测试单文件上传..."
RESPONSE=$(curl -X POST "${BASE_URL}/api/knowledge/upload" \
  -F "file=@${TEST_FILE}" \
  -F "category=nature" \
  -F "subcategory=landscape")

echo "响应: ${RESPONSE}"

# 提取 docId (API returns snake_case doc_id)
DOC_ID=$(echo "${RESPONSE}" | jq -r '.data.doc_id')
echo "文档 ID: ${DOC_ID}"
echo ""

# 2. 等待并查询状态
echo "2. 查询文档状态（轮询）..."
for i in {1..10}; do
  echo "第 ${i} 次查询..."
  STATUS=$(curl -s "${BASE_URL}/api/knowledge/documents/${DOC_ID}/status")
  echo "状态: ${STATUS}"

  IMPORT_STATUS=$(echo "${STATUS}" | jq -r '.data.importStatus')
  if [ "${IMPORT_STATUS}" = "COMPLETED" ] || [ "${IMPORT_STATUS}" = "FAILED" ]; then
    echo "最终状态: ${IMPORT_STATUS}"
    break
  fi

  sleep 3
done
echo ""

# 3. 测试批量上传
echo "3. 测试批量上传..."
RESPONSE=$(curl -X POST "${BASE_URL}/api/knowledge/upload/batch" \
  -F "files=@${TEST_FILE}" \
  -F "files=@${TEST_FILE}" \
  -F "category=test")

echo "响应: ${RESPONSE}"
echo ""

echo "=== 测试完成 ==="
