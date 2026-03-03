# Kafka 消息队列集成实战教程

> 本文档记录了 drilling-fluid 项目中 Kafka 消息队列的完整集成过程，包括架构设计、配置优化、问题排查等实战经验。

---

## 一、项目背景

### 1.1 业务场景

```
MQTT Broker → Kafka → 消费者服务 → WebSocket 客户端
     (采集)     (缓冲)     (处理)         (推送)
```

**原始架构问题**：
- MQTT 消息直接通过 Redis Pub/Sub 推送
- 高并发时 Redis 成为性能瓶颈
- 消息丢失风险高，无持久化机制
- 无法支持多消费者消费同一条消息

**引入 Kafka 后优势**：
- 高吞吐量：支持每秒百万级消息
- 持久化：消息持久化到磁盘，可回溯消费
- 分区并行：多个消费者并行处理
- DLT 机制：失败消息自动进入死信队列

---

## 二、Kafka 架构设计

### 2.1 整体架构

```
┌─────────────────┐    ┌──────────────┐    ┌─────────────────┐
│  mqtt-server    │───▶│              │───▶│  sky-server     │
│  (生产者)        │    │              │    │  (消费者)        │
│                 │    │   Kafka      │    │                 │
│  KafkaProducer  │    │              │    │  KafkaConsumer  │
│  Service        │    │              │    │  Service        │
└─────────────────┘    │              │    └─────────────────┘
                       │              │
                       │              │    ┌─────────────────┐
                       │              │───▶│  DLT Processor  │
                       │              │    │  (死信处理)       │
                       └──────────────┘    └─────────────────┘
```

### 2.2 Topic 设计

| Topic | 分区数 | 用途 |
|-------|--------|------|
| `mqtt.raw` | 6 | MQTT 原始数据 |
| `mqtt.raw-dlt` | 3 | 死信队列 |

### 2.3 消费者组设计

| 消费者组 | 消费者数量 | 监听 Topic |
|----------|-----------|-----------|
| `sky-server` | 2 | `mqtt.raw` |
| `sky-server-dlt` | 3 | `mqtt.raw-dlt` |

---

## 三、生产者实现

### 3.1 配置文件 (mqtt-server/application-kafka.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9094

    producer:
      # 可靠性配置
      acks: all                          # 等待所有 ISR 副本确认
      retries: 5                         # 发送失败重试次数
      batch-size: 16384                  # 批量发送 16KB
      buffer-memory: 33554432            # 缓冲区 32MB

      # 序列化器
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

      # Kafka 原生配置
      properties:
        enable.idempotence: true         # 幂等性（防止重复）
        linger.ms: 10                    # 等待 10ms 收集更多消息
        delivery.timeout.ms: 30000       # 请求超时 30s
```

### 3.2 配置参数详解

| 参数 | 作用 | 推荐值 |
|------|------|--------|
| `enable.idempotence` | 防止消息重复 | true（生产环境必须） |
| `linger.ms` | 批量发送等待时间 | 10ms（平衡延迟和吞吐） |
| `batch-size` | 批量缓冲区大小 | 16384（16KB） |
| `acks: all` | 持久化保证 | all（最高可靠性） |

### 3.3 生产者服务实现

```java
@Service
@Slf4j
public class KafkaProducerService {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    public void sendMessage(String topic, String key, Object message) {
        kafkaTemplate.send(topic, key, message).addCallback(
            success -> log.debug("消息发送成功: topic={}, offset={}",
                topic, result.getRecordMetadata().offset()),
            failure -> {
                log.error("消息发送失败: topic={}", topic, failure);
                handleSendFailure(topic, key, message, failure);
            }
        );
    }
}
```

---

## 四、消费者实现

### 4.1 配置文件 (sky-server/application-kafka.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9094

    consumer:
      group-id: sky-server
      enable-auto-commit: false           # 手动提交 offset
      auto-offset-reset: earliest         # 首次从最早消息开始

      # 反序列化器（改用 StringDeserializer）
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

      properties:
        max.poll.records: 500             # 单次 poll 最大记录数
        max.poll.interval.ms: 300000      # poll 间隔超时 5 分钟

    listener:
      ack-mode: manual                    # 手动确认
      concurrency: 2                      # 消费者线程数
      type: batch                         # 批量监听
```

### 4.2 消费者服务实现

```java
@Service
@Slf4j
public class KafkaConsumerService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ModbusDataWebSocketHandler modbusDataWebSocketHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(
        topics = "mqtt.raw",
        groupId = "sky-server",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeModbusData(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment) {

        String message = record.value();

        // 1. 消息格式验证
        if (!isValidJson(message)) {
            log.warn("收到非JSON格式的消息，跳过处理: message={}", message);
            acknowledgment.acknowledge();
            return;
        }

        // 2. JSON 解析
        ModbusData modbusData = objectMapper.readValue(message, ModbusData.class);

        // 3. 幂等性检查（Redis 去重）
        if (isDuplicateMessage(modbusData.getWellId(), modbusData.getSamplingTime())) {
            log.warn("重复消息，跳过处理: wellId={}, samplingTime={}",
                modbusData.getWellId(), modbusData.getSamplingTime());
            acknowledgment.acknowledge();
            return;
        }

        // 4. 业务处理
        modbusDataWebSocketHandler.pushModbusData(modbusData);

        // 5. 手动确认
        acknowledgment.acknowledge();
    }
}
```

---

## 五、死信队列 (DLT) 实现

### 5.1 DLT 配置

```java
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            dltListenerContainerFactory() {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dltConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3);
        return factory;
    }
}
```

### 5.2 DLT 消费者

```java
@KafkaListener(
    topics = "mqtt.raw-dlt",
    groupId = "sky-server-dlt",
    containerFactory = "dltListenerContainerFactory"
)
public void consumeDltMessage(ConsumerRecord<String, String> record) {
    // 记录失败消息
    log.error("DLT消息: topic={}, partition={}, offset={}, value={}",
        record.topic(), record.partition(), record.offset(), record.value());

    // 持久化到数据库，后续人工处理或补偿
    saveFailedMessage(record);
}
```

---

## 六、常见问题与解决方案

### 6.1 UnknownHostException: kafka

**问题**:
```
java.net.UnknownHostException: kafka
```

**原因**: Kafka 的 `advertised.listeners` 配置了 Docker 内部主机名

**解决方案**: bootstrap-servers 改为 `localhost:9094`（EXTERNAL 监听器）

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9094  # 使用 EXTERNAL 端口
```

### 6.2 JsonDeserializer 类型错误

**问题**:
```
IllegalStateException: No type information in headers
```

**原因**: `JsonDeserializer` 需要类型信息，但生产者未在消息头中添加

**解决方案**: 改用 `StringDeserializer` + Jackson 手动解析

```yaml
value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

```java
// 手动解析
ModbusData modbusData = objectMapper.readValue(message, ModbusData.class);
```

### 6.3 非JSON消息导致解析失败

**问题**: 测试消息 `"test-message-xxx"` 不是 JSON 格式

**解决方案**: 添加消息格式验证

```java
private boolean isValidJson(String message) {
    if (message == null || message.trim().isEmpty()) return false;
    String trimmed = message.trim();
    return trimmed.startsWith("{") || trimmed.startsWith("[");
}
```

### 6.4 消息重复消费

**问题**: 网络重试导致消息重复

**解决方案**: Redis 幂等性去重

```java
private boolean isDuplicateMessage(String wellId, LocalDateTime timestamp) {
    String key = "kafka:processed:" + wellId + ":" + timestamp;
    return Boolean.FALSE.equals(
        redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(24))
    );
}
```

---

## 七、性能优化

### 7.1 生产者优化

| 场景 | batch-size | linger.ms |
|------|------------|-----------|
| 低延迟优先 | 8192 | 0 |
| 默认均衡 | 16384 | 10 |
| 高吞吐优先 | 32768 | 20 |

### 7.2 消费者优化

```yaml
# 增加吞吐量
max.poll.records: 1000        # 增加单次 poll 数量
concurrency: 4                 # 增加消费者线程

# 避免重平衡
max.poll.interval.ms: 600000   # 延长 poll 间隔
```

### 7.3 日志优化

```yaml
logging:
  level:
    org.apache.kafka: warn      # 减少 Kafka 冗余日志
    org.springframework.kafka: warn
```

---

## 八、监控指标

### 8.1 关键指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| `consumer-lag` | 消费延迟 | > 1000 |
| `consumer-records-per-sec` | 消费速率 | < 预期值 |
| `consumer-failed-per-sec` | 消费失败率 | > 1% |
| `producer-request-rate` | 生产速率 | - |

### 8.2 监控实现

```java
@KafkaListener(topics = "mqtt.raw")
public void consume(ConsumerRecord<String, String> record) {
    long start = System.currentTimeMillis();

    try {
        processMessage(record.value());
        long duration = System.currentTimeMillis() - start;

        // 上报监控指标
        metricsService.record("kafka.consume.duration", duration);
        metricsService.record("kafka.consume.success", 1);

    } catch (Exception e) {
        metricsService.record("kafka.consume.failed", 1);
        throw e;
    }
}
```

---

## 九、最佳实践总结

### 9.1 生产环境配置清单

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS}
    producer:
      acks: all
      retries: 5
      enable.idempotence: true
      properties:
        linger.ms: 10
        max.in.flight.requests.per.connection: 5
    consumer:
      enable-auto-commit: false
      auto-offset-reset: earliest
      properties:
        max.poll.records: 500
        max.poll.interval.ms: 300000
    listener:
      ack-mode: manual
      concurrency: 3
```

### 9.2 代码规范

1. **幂等性处理**: 所有消费逻辑必须实现幂等性
2. **异常捕获**: 捕获异常后确认消息，避免阻塞队列
3. **日志记录**: 记录消息 key、partition、offset 便于追踪
4. **监控上报**: 关键指标实时上报
5. **配置外部化**: 使用环境变量配置 Kafka 地址

### 9.3 运维经验

| 问题 | 解决方案 |
|------|----------|
| 消息积压 | 增加消费者线程数 |
| 消费延迟 | 优化消费逻辑，异步处理 |
| 重复消费 | 幂等性去重 |
| 消息丢失 | 检查 offset 提交时机 |

---

## 十、延伸阅读

- [Kafka 官方文档](https://kafka.apache.org/documentation/)
- [Spring Kafka 参考文档](https://docs.spring.io/spring-kafka/reference/)
- [Kafka 监控最佳实践](https://www.confluent.io/blog/kafka-monitoring/)

---

> **作者备注**: 本文档基于 drilling-fluid 项目的实际 Kafka 集成经验总结，涵盖了从架构设计、配置优化到问题排查的完整流程。
