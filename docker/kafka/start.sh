#!/bin/bash
# Kafka 启动和主题创建脚本

set -e

echo "=== 启动 Kafka ==="
docker compose up -d

echo "=== 等待 Kafka 启动 (30秒) ==="
sleep 30

echo "=== 检查 Kafka 状态 ==="
docker compose ps

echo "=== 创建 Kafka 主题 ==="
# MQTT 原始数据主题（6 分区）
docker compose exec kafka kafka-topics.sh --create \
  --topic mqtt.raw \
  --partitions 6 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092 || echo "Topic mqtt.raw 可能已存在"

# 报警触发主题（3 分区）
docker compose exec kafka kafka-topics.sh --create \
  --topic alerts.triggered \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092 || echo "Topic alerts.triggered 可能已存在"

# 死信队列主题（3 分区）
docker compose exec kafka kafka-topics.sh --create \
  --topic mqtt.raw-dlt \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092 || echo "Topic mqtt.raw-dlt 可能已存在"

echo "=== 列出所有主题 ==="
docker compose exec kafka kafka-topics.sh --list --bootstrap-server localhost:9092

echo "=== Kafka 已就绪 ==="
echo "Bootstrap server: localhost:9092"
echo "Topics: mqtt.raw, alerts.triggered, mqtt.raw-dlt"
