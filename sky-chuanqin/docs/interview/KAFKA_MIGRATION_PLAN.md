# Kafka 迁移详细计划

## 范围与目标
- 将 mqtt-server -> Redis Pub/Sub -> sky-server 改为 mqtt-server -> Kafka -> sky-server，获得持久化、可重放、消费位点和死信保障。
- 交付物：可用的 Kafka 集群（Docker 部署）、代码与配置切换、双发布/影子消费验证、可回滚方案。

## 1. 基础设施（Ubuntu 上用 Docker 安装 Kafka）
### 1.1 前置
- 已安装 Docker & Docker Compose；放行服务器 9092 端口（外部访问）/ 29092（内部访问）。

### 1.2 使用 KRaft 模式部署（免 ZK）
创建 `docker-compose.yml`（示例：bitnami/kafka 3.6）：
```yaml
version: '3.8'
services:
  kafka:
    image: bitnami/kafka:3.6
    container_name: kafka
    ports:
      - "9092:9092"   # 对外
      - "29092:29092" # 内部
    environment:
      - KAFKA_CFG_NODE_ID=1
      - KAFKA_CFG_PROCESS_ROLES=broker,controller
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,INTERNAL://:29092,CONTROLLER://:9093
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://${HOST_IP}:9092,INTERNAL://kafka:29092
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=1@kafka:9093
      - KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR=1
      - KAFKA_CFG_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1
      - KAFKA_CFG_TRANSACTION_STATE_LOG_MIN_ISR=1
      - ALLOW_PLAINTEXT_LISTENER=yes
    volumes:
      - ./data:/bitnami/kafka
```
启动与健康检查：
```bash
docker compose up -d
docker compose exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```
创建主题：
```bash
docker compose exec kafka kafka-topics.sh --create --topic mqtt.raw --partitions 6 --replication-factor 1 --bootstrap-server localhost:9092
docker compose exec kafka kafka-topics.sh --create --topic alerts.triggered --partitions 3 --replication-factor 1 --bootstrap-server localhost:9092
```
（如需多副本，将容器扩容并调高 replication-factor）。

## 2. 仓库改动
- 根 pom：如需，引入 Spring Kafka BOM；可不改，直接在模块依赖。
- `mqtt-server/pom.xml`、`sky-server/pom.xml`：添加 `spring-kafka`、`jackson-databind`（已有）或保持 JsonSerializer。
- `application.yml`（按 profile）新增：
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      acks: all
      retries: 5
      enable-idempotence: true
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: sky-server
      enable-auto-commit: false
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.kira.server.domain.*
    listener:
      ack-mode: manual
messaging:
  provider: kafka # redis/kafka 切换开关
```

## 3. 代码改动
- **mqtt-server**
  - 新建 KafkaProducerService（封装 KafkaTemplate<String, PayloadDto>）。
  - MQTT 消费回调中改为发送到 Kafka；message key 设为 deviceId/wellId，保证同设备局部有序。
  - 保留 Redis 发布作为可选分支（由 `messaging.provider` 控制），便于回滚。
- **sky-server**
  - 添加 @KafkaListener 消费 `mqtt.raw`；使用 MANUAL ack，处理成功后提交偏移。
  - 失败重试 + DLT：可用 `@RetryableTopic` 生成 `mqtt.raw-dlt`，或自定义错误处理器。
  - 业务幂等：按 deviceId+timestamp/messageId 去重，避免重复消费副作用。
- 可选：在 sky-server 中将原始消息转存 `mqtt.cleaned` 供后续链路使用。

## 4. 迁移步骤
1) 部署 Kafka（上文 Docker），确认主题存在。
2) 加依赖与配置，默认仍走 Redis；添加 `messaging.provider` 开关。
3) mqtt-server 启用“双写”：同时发 Redis 与 Kafka；用 `kafka-console-consumer` 验证数据入 `mqtt.raw`。
4) sky-server 启用“影子消费”：消费 Kafka 但不落库/不改状态，仅日志校验解析与吞吐。
5) 切换为 Kafka 主通道：开启业务处理与手动提交 offset，观察日志与 lag。
6) 观察 24-48 小时：
   - lag 监控：`kafka-consumer-groups.sh --describe --group sky-server --bootstrap-server <ip>:9092`
   - 错误/重试/DLT 命中情况
7) 稳定后移除 Redis 发布订阅路径与相关配置。

## 5. 验证与监控
- 冒烟：发送样例 MQTT 消息，确认 sky-server 正常入库/触发业务。
- 压测：短时间突发流量，确保无丢失且延迟可接受。
- 观测：记录 topic/partition/offset/key 于日志；跟踪 DLT 以便补偿。

## 6. 回滚
- 保留 Redis 分支和开关；如 Kafka 异常，切回 Redis。
- 回滚时停止 Kafka 消费者避免重复处理；问题解决后再切回 Kafka，并从停留 offset 继续。
