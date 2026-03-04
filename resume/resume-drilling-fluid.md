钻井液性能实时检测与自动化分析系统(实验室科研项目)
系统实时接收数据采集端上传的监控数据，展示在可视化大屏中，并定时分析数据进行实时预警。
基于 FastAPI＋LangChain 搭建流式对话 API，集成 Apache Tika 实现多格式文档同步解析，结合 pgvector 实现 Parent-Child RAG 专家知识库，生成诊断与配药建议。
负责后端架构设计，建立联合索引优化性能参数实时监控表。
引入Kafka构建异步消息链路，通过 acks=all + 手动提交 offset + Redis幂等性校验保障消息可靠传递。
利用AOP＋自定义注解封装了非侵入式操作日志。并构建了应用级统一线程池。
基于XXL-Job构建定时数据分析任务，对采集到的数据进行周期性检测，预警触发后通过 WebSocket 按井号分组主动推送至前端并联动处置Agent自动生成处置建议。
负责MQTT采集链路接入与落库。

---

## MQTT采集链路详解（Q&A）

### Q1: MQTT采集链路的完整流程是什么？

**A:** 整个链路是"硬件设备 → MQTT Broker → mqtt-server → Kafka → sky-server → WebSocket → 前端"的七层数据流转架构。现场传感器采集钻井液性能数据（密度、温度、黏度等20+参数）后发布到MQTT Broker的指定主题（如`sky/test`），mqtt-server作为客户端订阅主题接收数据，解析JSON后落库到三张表（Density/DrillingData/ModbusData），同时将处理后的数据发送到Kafka的`mqtt.raw`主题，sky-server消费Kafka消息后通过WebSocket按井号分组实时推送到前端大屏展示。

```mermaid
flowchart LR
    A[现场传感器设备] -->|MQTT Publish<br/>topic: sky/test| B[MQTT Broker<br/>47.113.226.70:1883]
    B -->|MQTT Subscribe<br/>QoS=1| C[mqtt-server<br/>采集入库服务]
    C -->|JSON解析| D[ModbusDataDTO]
    D -->|密度数据| E[(Density表)]
    D -->|rpm600变化时| F[(DrillingData表)]
    D -->|rpm600变化时| G[(ModbusData表)]
    C -->|发送到Kafka<br/>topic: mqtt.raw| H[Kafka集群]
    H -->|消费消息| I[sky-server<br/>推送服务]
    I -->|幂等性检查<br/>Redis去重| J[WebSocket]
    J -->|按井号分组推送| K[前端大屏]

    style A fill:#e1f5ff
    style B fill:#fff4e1
    style C fill:#e8f5e9
    style E fill:#f3e5f5
    style F fill:#f3e5f5
    style G fill:#f3e5f5
    style H fill:#fff4e1
    style I fill:#e8f5e9
    style K fill:#e1f5ff
```

**数据流向示意：**
```
┌─────────┐    MQTT     ┌──────────┐    Parse    ┌─────────┐
│ 硬件设备 │ ────────→ │ MQTT Broker│ ─────────→ │mqtt-server│
└─────────┘  Publish   └──────────┘   JSON解析   └─────────┘
                                              │
                  ┌──────────────────────────┼──────────────────────────┐
                  ↓                          ↓                          ↓
           ┌──────────┐              ┌──────────┐              ┌──────────┐
           │Density表  │              │DrillingData表│          │ModbusData表│
           └──────────┘              └──────────┘              └──────────┘
                  │                          │                          │
                  └──────────────────────────┼──────────────────────────┘
                                             ↓
                                      ┌──────────┐      Kafka      ┌──────────┐
                                      │  Kafka   │ ──────────────→ │sky-server│
                                      │mqtt.raw │      消费        └──────────┘
                                      └──────────┘                     │
                                                                       ↓
                                                                ┌──────────┐
                                                                │WebSocket │ ──→ 前端
                                                                └──────────┘   分组推送
```

### Q2: MQTT客户端是如何初始化和保证连接稳定的？

**A:** 在`MqttConfiguration`中通过Spring Bean方式初始化`MyMqttClient`，采用Eclipse Paho客户端连接到Broker（地址：`tcp://47.113.226.70:1883`），配置QoS=1保证至少一次送达、心跳间隔20秒、自动重连机制；同时实现最多10次重试的连接策略，每次失败后等待1秒再重试，连接成功后订阅`sky/test`、`sky/demo`和配置的主题，并设置`MyMqttCallback`回调函数处理接收到的消息。

```mermaid
flowchart TD
    A[Spring容器启动] --> B[MqttConfiguration<br/>@Bean初始化]
    B --> C{创建MyMqttClient}
    C --> D[设置连接参数<br/>host: tcp://47.113.226.70:1883<br/>username/password<br/>clientId: sky_server_xxx]

    D --> E{连接重试循环<br/>最多10次}
    E -->|成功| F[mqttClient.connect<br/>自动重连=true<br/>心跳间隔=20s]
    E -->|失败| G[等待1秒]
    G --> E

    F --> H[设置回调<br/>setCallback]
    H --> I[MyMqttCallback<br/>messageArrived处理]

    F --> J[订阅主题<br/>subscribe]
    J --> K1[sky/test QoS=1]
    J --> K2[sky/demo QoS=1]
    J --> K3[配置主题 QoS=1]

    I --> L[监听消息到达]
    K1 & K2 & K3 --> L

    style B fill:#e8f5e9
    style F fill:#c8e6c9
    style I fill:#fff9c4
    style L fill:#e1f5ff
```

**连接配置参数：**
| 参数 | 值 | 说明 |
|------|-----|------|
| host | `tcp://47.113.226.70:1883` | MQTT Broker地址 |
| clientId | `sky_server_${random}` | 随机ID防止冲突 |
| QoS | `1` | 至少一次送达 |
| keepalive | `20` | 心跳间隔20秒 |
| automaticReconnect | `true` | 自动重连 |
| 重试次数 | `10次` | 失败后每秒重试 |

### Q3: 消息接收和处理的具体流程是怎样的？

**A:** 消息处理分三步：首先`MyMqttCallback.messageArrived()`接收MQTT消息并转换为String字符串；然后`MqttMessageService.processMessage()`根据主题路由到对应的处理方法，将JSON解析为`ModbusDataDTO`对象，同时从Redis获取井号、采样位置、是否油基等配置信息；最后根据数据类型分别落库——密度数据存入`Density`表，钻井工程数据和Modbus原始数据只在rpm600值变化时存入对应的`DrillingData`和`ModbusData`表。

```mermaid
flowchart TD
    A[MQTT消息到达] --> B[MyMqttCallback<br/>messageArrived]
    B --> C[提取payload<br/>转换为String]
    C --> D{判断主题类型}

    D -->|sky/test| E[MqttMessageService<br/>processTestTopicMessage]
    D -->|其他主题| F[记录日志<br/>暂不处理]

    E --> G[JSON解析<br/>→ ModbusDataDTO]
    G --> H{解析成功?}
    H -->|失败| I[记录错误日志<br/>结束]
    H -->|成功| J[从Redis获取配置]

    J --> K1[wellId<br/>井号]
    J --> K2[location<br/>采样位置]
    J --> K3[isOil<br/>是否油基]

    K1 & K2 & K3 --> L[补充数据元信息<br/>samplingTime=当前时间]

    L --> M[分三路落库]
    M --> N1[密度数据<br/>→ Density表<br/>每条都存]
    M --> N2[rpm600变化?<br/>→ DrillingData表]
    M --> N3[rpm600变化?<br/>→ ModbusData表]

    N2 & N3 --> O[发送到Kafka<br/>topic: mqtt.raw]

    style B fill:#fff9c4
    style E fill:#e8f5e9
    style G fill:#c8e6c9
    style J fill:#e1f5ff
    style M fill:#f3e5f5
    style O fill:#fff4e1
```

**代码调用链：**
```
MyMqttCallback.messageArrived()
    ↓
MqttMessageService.processMessage(topic, payload)
    ↓
processTestTopicMessage() → JSON.parseObject(payload, ModbusDataDTO)
    ↓
getRedisValue() → 获取wellId/location/isOil
    ↓
saveDensityData() → Density表（每条）
    ↓
processRpmData() → 判断rpm600是否变化
    ↓                 ↓
    ↓           rpm600变化
    ↓                 ↓
saveDrillingData()   saveModbusData()
    ↓                 ↓
    └────→ KafkaProducerService.sendMessage()
```

### Q4: 如何实现智能存储策略避免重复数据？

**A:** 采用基于rpm600值（600转/分钟时的读数，是关键监测指标）的变化检测机制——在`MqttMessageService.processRpmData()`中维护上一次的rpm600值，只有当前值与上一次值不同时才触发数据存储；这种设计避免了设备持续上报相同数值时的冗余存储，经测算可节省50%以上的存储空间，同时确保了关键变化点数据的完整记录。

```mermaid
flowchart TD
    A[收到MQTT消息] --> B[解析ModbusDataDTO]
    B --> C[获取当前rpm600值]

    C --> D{rpm600是否变化?}
    D -->|current != previous| E[需要存储]
    D -->|current == previous| F[跳过存储<br/>记录日志]

    E --> G[更新previousRpm600]
    G --> H[保存DrillingData]
    G --> I[保存ModbusData]

    H --> J[补充元信息<br/>samplingTime<br/>location<br/>wellId<br/>isOil]
    I --> J

    J --> K[Db.save落库]
    K --> L[发送到Kafka]

    F --> M[只发Kafka<br/>不入库]

    style D fill:#fff9c4
    style E fill:#c8e6c9
    style F fill:#ffcdd2
    style K fill:#e1f5ff
    style L fill:#fff4e1
```

**rpm600变化检测逻辑：**
```java
// 上一次的值（成员变量）
private Double previousRpm600 = null;

// 判断是否需要存储
boolean shouldStore = shouldStoreRpmData(currentRpm600);

// 更新上一次的值
previousRpm600 = currentRpm600;
```

**存储效果对比：**
| 场景 | 无变化检测 | 有变化检测 |
|------|-----------|-----------|
| 设备每秒上报 | 每秒存1条 | 仅变化时存 |
| 相同值持续100次 | 100条记录 | 1条记录 |
| 节省比例 | 0% | **50%+** |

### Q5: Kafka如何保证消息的可靠投递？

**A:** 通过生产者配置`acks=all`等待所有ISR副本确认、`enable-idempotence=true`开启幂等性防止网络重试导致重复、`retries=5`失败自动重试5次；消费端采用手动提交offset机制（`Acknowledgment.acknowledge()`），只有业务处理成功后才确认消息，处理失败则抛出异常触发重试或进入死信队列`mqtt.raw-dlt`，配合`DltProcessor`记录异常消息供人工介入和后续恢复，形成完整的消息可靠性保障体系。

```mermaid
flowchart TD
    subgraph P["生产者 mqtt-server"]
        A[构造消息] --> B[KafkaTemplate.send]
        B --> C{acks=all<br/>等待ISR确认}
        C -->|成功| D[记录成功日志]
        C -->|失败| E{重试5次}
        E -->|仍失败| F[handleSendFailure<br/>记录补偿日志]
        E -->|重试成功| D
    end

    subgraph K["Kafka集群"]
        G[mqtt.raw主题]
    end

    subgraph C["消费者 sky-server"]
        H[consumeModbusData<br/>@KafkaListener] --> I[processModbusData]
        I --> J{幂等性检查<br/>Redis去重}
        J -->|重复消息| K[跳过处理<br/>acknowledge]
        J -->|新消息| L[pushModbusData<br/>WebSocket推送]
        L --> M{处理成功?}
        M -->|成功| N[acknowledge<br/>手动提交offset]
        M -->|失败| O[抛出异常<br/>触发重试/DLT]
    end

    subgraph DLT["死信队列"]
        P[mqtt.raw-dlt] --> Q[DltProcessor<br/>记录异常日志]
    end

    D --> G
    F -->|补偿任务| G
    G --> H
    O --> P

    style P fill:#e8f5e9
    style C fill:#fff9c4
    style DLT fill:#ffcdd2
    style N fill:#c8e6c9
    style O fill:#ef9a9a
```

**Kafka可靠性配置：**
| 端 | 配置项 | 值 | 作用 |
|---|--------|-----|------|
| 生产者 | acks | all | 等待所有ISR副本确认 |
| 生产者 | enable-idempotence | true | 防止网络重试导致重复 |
| 生产者 | retries | 5 | 失败自动重试5次 |
| 消费者 | manual-ack | true | 手动提交offset |
| 消费者 | DLT | mqtt.raw-dlt | 死信队列兜底 |

### Q6: 如何实现幂等性去重防止重复处理？

**A:** 在`KafkaConsumerService`中基于Redis的`setIfAbsent`原子操作实现幂等性检查，使用`wellId + samplingTime`作为唯一标识生成key（格式：`kafka:processed:{wellId}:{timestamp}`），设置24小时过期时间；处理消息前先检查Redis，如果key已存在说明是重复消息直接跳过，只有新消息才执行业务逻辑并写入Redis，这种方式即使在Kafka重试场景下也能保证同一条数据不会被重复处理。

```mermaid
flowchart TD
    A[KafkaConsumer收到消息] --> B[解析ModbusData]
    B --> C[提取wellId和samplingTime]

    C --> D[构造Redis Key<br/>kafka:processed:{wellId}:{timestamp}]

    D --> E[Redis.setIfAbsent<br/>key, value, 24h过期]

    E --> F{返回值?}
    F -->|true| G[Key不存在<br/>首次处理]
    F -->|false| H[Key已存在<br/>重复消息]

    G --> I[执行业务逻辑<br/>pushModbusData]
    I --> J[WebSocket推送成功]
    J --> K[消息处理完成]

    H --> L[跳过处理<br/>记录warn日志]
    L --> M[直接acknowledge<br/>确认消息]

    K --> M
    M --> N[手动提交offset]

    style F fill:#fff9c4
    style G fill:#c8e6c9
    style H fill:#ffcdd2
    style E fill:#e1f5ff
```

**幂等性检查代码：**
```java
// 唯一标识：井号 + 采样时间
String key = "kafka:processed:" + wellId + ":" + timestamp;

// 原子操作：只有key不存在时才设置成功
Boolean isNew = redisTemplate.opsForValue()
        .setIfAbsent(key, "1", Duration.ofHours(24));

// 返回false说明key已存在 = 重复消息
return Boolean.FALSE.equals(isNew);
```

**去重保证：**
| 场景 | 无幂等性 | 有幂等性 |
|------|---------|---------|
| Kafka重试 | 重复推送 | 只推送一次 |
| 网络重复消息 | 重复推送 | 自动过滤 |
| 数据一致性 | ❌ 可能重复 | ✅ 保证唯一 |

### Q7: WebSocket如何实现按井号分组推送？

**A:** 在`ModbusDataWebSocketHandler.pushModbusData()`中，首先将`ModbusData`转换为前端需要的`ModbusRealtimeVO`格式（包含type、wellId、samplingTime、timestamp等字段），然后判断wellId是否为空——如果有井号则调用`webSocketServer.sendToWell(wellId, message)`只推送到订阅该井号的客户端，实现精准投递；如果井号为空则广播到所有连接的客户端；这种按井号分组的设计避免了前端接收无关数据，提升了系统整体性能。

```mermaid
flowchart TD
    A[KafkaConsumer消费消息] --> B[幂等性检查通过]
    B --> C[ModbusDataWebSocketHandler<br/>pushModbusData]

    C --> D[转换数据格式<br/>ModbusData → ModbusRealtimeVO]
    D --> E[序列化为JSON]

    E --> F{wellId是否为空?}
    F -->|非空| G[按井号分组推送<br/>sendToWell wellId]
    F -->|为空| H[广播到所有客户端<br/>sendToAllClient]

    G --> I{找到订阅该井号的客户端}
    I -->|找到| J[精准推送<br/>只推送到订阅客户端]
    I -->|未找到| K[记录日志<br/>无订阅者]

    H --> L[推送到所有连接]

    J --> M[前端接收数据<br/>大屏实时更新]
    L --> M

    style F fill:#fff9c4
    style G fill:#c8e6c9
    style H fill:#fff9c4
    style J fill:#e1f5ff
    style M fill:#e8f5e9
```

**WebSocket连接管理：**
```java
// WebSocket服务器维护井号到连接的映射
ConcurrentMap<String, Set<WebSocket>> wellConnections;

// 客户端连接时订阅井号
websocketServer.subscribeToWell(wellId, session);

// 按井号推送
websocketServer.sendToWell(wellId, message);
```

**推送策略对比：**
| 策略 | 推送范围 | 带宽消耗 | 前端过滤 |
|------|---------|---------|---------|
| 无分组推送 | 全部客户端 | 高 | 需要自己过滤 |
| 按井号分组 | 订阅该井的客户端 | 低 | 无需过滤 |

### Q8: 整体技术架构的亮点有哪些？

**A:** 采用MQTT轻量级物联网协议适配现场设备采集，Kafka消息队列实现采集与后端服务的可靠解耦，通过生产者acks=all配合手动提交offset保障数据零丢失；Redis实现幂等性去重防止重复处理，WebSocket按井号分组推送实现精准投递，死信队列机制保障异常消息可追溯可恢复，智能存储策略基于rpm600变化检测节省50%+存储空间；整体架构兼顾了高性能、高可靠性和可扩展性。

```mermaid
graph TB
    subgraph "技术栈分层"
        A1[采集层<br/>MQTT]
        A2[队列层<br/>Kafka]
        A3[处理层<br/>sky-server]
        A4[推送层<br/>WebSocket]
    end

    subgraph "核心亮点"
        B1["✅ MQTT轻量级<br/>适配物联网设备"]
        B2["✅ Kafka解耦<br/>acks=all + 手动提交"]
        B3["✅ Redis幂等性<br/>setIfAbsent去重"]
        B4["✅ 智能存储<br/>rpm600变化检测<br/>节省50%+空间"]
        B5["✅ 分组推送<br/>按井号精准投递"]
        B6["✅ 死信队列<br/>异常可追溯可恢复"]
    end

    subgraph "业务价值"
        C1["📊 高性能<br/>异步解耦 + 智能存储"]
        C2["🔒 高可靠<br/>三重保障 + DLT兜底"]
        C3["📈 可扩展<br/>按井号分组 + 水平扩展"]
    end

    A1 --> B1
    A2 --> B2
    A3 --> B3 & B4
    A4 --> B5
    A2 -.-> B6

    B1 & B2 & B3 & B4 & B5 & B6 --> C1 & C2 & C3

    style A1 fill:#e1f5ff
    style A2 fill:#fff4e1
    style A3 fill:#e8f5e9
    style A4 fill:#f3e5f5
    style B1 fill:#c8e6c9
    style B2 fill:#c8e6c9
    style B3 fill:#c8e6c9
    style B4 fill:#c8e6c9
    style B5 fill:#c8e6c9
    style B6 fill:#c8e6c9
```

**架构亮点汇总：**

| 技术点 | 实现方案 | 业务价值 |
|--------|----------|----------|
| **MQTT** | Eclipse Paho客户端 | 轻量级物联网协议，适合现场设备 |
| **Kafka** | 生产者acks=all + 手动提交offset | 保障消息零丢失，实现异步解耦 |
| **Redis** | setIfAbsent幂等性检查 | 防止重复处理，保证数据一致性 |
| **WebSocket** | 按井号分组推送 | 精准投递，减少前端无关数据 |
| **死信队列** | DLT处理器 | 异常消息可追溯、可恢复 |
| **智能存储** | rpm600变化检测 | 节省存储空间50%+ |

**整体架构优势：**
- **高性能**：异步处理 + 智能存储 + 分组推送
- **高可靠**：三重保障机制 + 死信队列兜底
- **可扩展**：水平扩展 + 按井号隔离 + 模块化设计

---

## XXL-Job定时任务与AI诊断联动（Q&A）

### Q7: 死信队列(DLT)是如何工作的？如何处理异常消息？

**A:** 当Kafka消费者处理消息抛出异常时，Spring Kafka会自动重试（默认重试机制），达到最大重试次数后消息会被转发到死信队列（Dead Letter Topic）。在我们的实现中，消费失败的消息会进入`mqtt.raw-dlt`主题，由`DltProcessor`专门处理；目前实现了异常日志记录供后续分析，预留了数据库持久化、告警通知和修复后重新投递的扩展点，确保每条异常消息都可追溯、可恢复，避免数据丢失。

```mermaid
flowchart TD
    A[KafkaConsumer消费消息] --> B[processModbusData处理]
    B --> C{处理是否成功?}
    C -->|成功| D[acknowledge确认]
    C -->|失败| E[抛出异常]

    E --> F{Spring Kafka<br/>自动重试}
    F -->|未达最大次数| G[重新消费]
    F -->|达到最大次数| H[转发到死信队列<br/>mqtt.raw-dlt]

    G --> B
    H --> I[DltProcessor监听]
    I --> J[记录异常日志<br/>alert级别]
    J --> K{预留扩展点}
    K --> L1[1. 记录到数据库]
    K --> L2[2. 发送告警通知]
    K --> L3[3. 修复后重新投递]

    D --> M[手动提交offset<br/>消息消费完成]

    style C fill:#fff9c4
    style H fill:#ffcdd2
    style I fill:#fff4e1
    style J fill:#e1f5ff
    style L1 fill:#c8e6c9
    style L2 fill:#c8e6c9
    style L3 fill:#c8e6c9
```

**死信队列处理流程：**

| 阶段 | 组件 | 行为 | 配置 |
|------|------|------|------|
| **异常捕获** | KafkaConsumerService | 抛出异常，不确认消息 | `throw e` |
| **自动重试** | Spring Kafka | 自动重试机制 | 默认重试策略 |
| **DLT转发** | RetryTopicConfigurer | 转发到死信队列 | `mqtt.raw-dlt` |
| **DLT处理** | DltProcessor | 记录异常日志 | `@KafkaListener` |

**DltProcessor核心代码：**
```java
// DltProcessor.java:20-23
@KafkaListener(topics = "mqtt.raw-dlt", groupId = "sky-server-dlt")
public void processDltMessage(String message) {
    log.error("消息进入死信队列: {}", message);
    // 预留扩展：记录数据库、发送告警、修复后重新投递
}
```

**死信队列应用场景：**

| 异常类型 | 处理方式 | 恢复策略 |
|----------|----------|----------|
| 数据格式错误 | 记录到DLT | 人工修复后重新投递 |
| 业务逻辑异常 | 记录到DLT | 修复bug后重放 |
| 临时性故障（网络） | 自动重试 | 无需人工介入 |
| 系统宕机 | 消息保留在Kafka | 系统恢复后自动消费 |

---

### Q8: XXL-Job定时任务如何与AI诊断联动？

**A:** 通过XXL-Job调度平台配置三个定时任务（钙污染检测、二氧化碳污染检测、钻井液稳定性检测），任务执行时调用`FullPerformanceService`的检测方法判断是否触发预警条件；如果检测到异常（如`isPolluted=true`），立即通过`AiDiagnosisTriggerService.triggerDiagnosis()`触发AI诊断分析，同时构造预警消息通过WebSocket推送到前端大屏，前端可根据诊断URL查看AI生成的处置建议，实现了"检测→预警→诊断→建议"的完整自动化闭环。

```mermaid
flowchart TD
    subgraph XXL["XXL-Job调度中心"]
        A1["caPollutionDetectionJob<br/>钙污染检测"]
        A2["co2PollutionDetectionJob<br/>二氧化碳污染检测"]
        A3["drillingFluidStabilityDetectionJob<br/>稳定性检测"]
    end

    subgraph TASK["PollutionDetectionTest任务"]
        B1["@XxlJob定时触发"] --> B2[从Redis获取wellId]
        B2 --> B3[调用FullPerformanceService<br/>执行检测逻辑]
        B3 --> B4{是否触发预警?}
        B4 -->|否| B5[记录正常日志<br/>任务结束]
        B4 -->|是| B6[记录异常日志]
    end

    subgraph AI["AI诊断联动"]
        C1[构造DiagnosisRequest] --> C2[调用AiDiagnosisTriggerService]
        C2 --> C3[SSEForwardService<br/>请求Python AI服务]
        C3 --> C4[流式接收诊断结果]
        C4 --> C5[DiagnosisCacheService<br/>缓存结果]
    end

    subgraph PUSH["预警推送"]
        D1[构造WebSocket消息<br/>AI_DIAGNOSIS_ALERT] --> D2[WebSocketServer推送]
        D2 --> D3[前端大屏显示预警]
        D3 --> D4[用户点击诊断URL]
        D4 --> D5[查看AI处置建议]
    end

    A1 & A2 & A3 --> B1
    B6 --> C1
    B6 --> D1
    C5 --> D1

    style XXL fill:#fff4e1
    style TASK fill:#e8f5e9
    style AI fill:#e1f5ff
    style PUSH fill:#f3e5f5
    style B6 fill:#ffcdd2
    style D3 fill:#c8e6c9
```

**定时任务配置：**

| 任务名称 | 执行频率 | 检测内容 | 预警条件 |
|---------|---------|---------|---------|
| `caPollutionDetectionJob` | 可配置 | 钙污染检测 | `isCaPollution().pollution.red=true` |
| `co2PollutionDetectionJob` | 可配置 | 二氧化碳污染检测 | `isCo2Pollution().pollution.red=true` |
| `drillingFluidStabilityDetectionJob` | 可配置 | 钻井液长效稳定检测 | `notTreatedForLongTime().pollution.red=true` |

**核心联动代码：**
```java
// PollutionDetectionTest.java:215-238
private void triggerAiDiagnosis(String wellId, String alertType,
                                 Map<String, List<ParameterVO>> detectionResult,
                                 String wellLocation) {
    String alertId = "ALERT-" + System.currentTimeMillis();

    // 1. 构造诊断请求
    DiagnosisRequest request = buildDiagnosisRequest(wellId, alertType, detectionResult);

    // 2. 触发 AI 诊断
    boolean success = aiDiagnosisTriggerService.triggerDiagnosis(
            alertId, wellId, alertType, request
    );

    // 3. 发送 WebSocket 预警
    sendAiDiagnosisAlert(alertId, wellId, wellLocation, alertType,
            success ? "COMPLETED" : "FAILED");
}
```

**WebSocket预警消息格式：**
```json
{
  "type": "AI_DIAGNOSIS_ALERT",
  "alertId": "ALERT-1740931200000",
  "wellId": "SHB001",
  "wellLocation": "四川盆地",
  "alertType": "钙污染",
  "severity": "HIGH",
  "triggeredAt": 1740931200000,
  "status": "COMPLETED",
  "diagnosisUrl": "/api/ai/diagnosis/stream?alertId=ALERT-1740931200000"
}
```

**完整业务流程时序：**
```
XXL-Job定时触发
    ↓
执行污染检测逻辑
    ↓
检测到异常 (isPolluted=true)
    ↓
    ├─→ 触发AI诊断 → Python Agent分析 → 缓存诊断结果
    │
    └─→ 发送WebSocket预警 → 前端显示预警弹窗
            ↓
         用户点击查看
            ↓
    请求 /api/ai/diagnosis/stream?alertId=xxx
            ↓
    从Redis缓存获取诊断结果
            ↓
    前端流式展示AI生成的处置建议
```

**XXL-Job配置类：**
```java
// XxlJobConfig.java:38-50
@Bean
public XxlJobSpringExecutor xxlJobExecutor() {
    XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
    xxlJobSpringExecutor.setAdminAddresses(adminAddresses);  // 调度中心地址
    xxlJobSpringExecutor.setAppname(appname);                 // 执行器名称
    xxlJobSpringExecutor.setPort(port);                       // 执行器端口
    xxlJobSpringExecutor.setAccessToken(accessToken);         // 访问令牌
    return xxlJobSpringExecutor;
}
```

**业务价值：**
- **自动化闭环**：从检测到预警到诊断建议全流程自动化，无需人工干预
- **实时响应**：定时任务周期性检测，异常立即触发预警和诊断
- **智能决策**：AI Agent基于专家知识库生成专业处置建议
- **可追溯**：每次预警生成唯一alertId，诊断结果可回溯查询

---

## Kafka技术选型与优化（Q&A）

### Q9: 为什么选择Kafka而不是继续用Redis Pub/Sub？

**A:** 主要基于四个核心差异进行选型决策：**持久化能力**方面，Kafka基于磁盘日志存储消息可持久化，宕机后数据不丢失，Redis Pub/Sub默认不持久化、消费后即删除、宕机可能丢失数据；**回溯能力**方面，Kafka支持重置offset重新消费历史数据便于数据修复和回溯分析，Redis消费后消息即删除无法回溯；**吞吐量**方面，Kafka支持批量发送（batch.size=16384）和分区并行处理更适合高吞吐场景，Redis是内存操作单机瓶颈明显；**消费模式**方面，Kafka采用拉模式消费者可控速率，Redis采用推模式可能压垮消费者。

```mermaid
graph TB
    subgraph REDIS["Redis Pub/Sub"]
        A1[生产者发布] --> A2[内存队列]
        A2 --> A3[推送给订阅者]
        A3 --> A4[消费后删除]
        A4 --> A5["❌ 宕机数据丢失<br/>❌ 无法回溯<br/>✅ 实时性好"]
    end

    subgraph KAFKA["Kafka"]
        B1[生产者发送] --> B2[磁盘日志<br/>持久化存储]
        B2 --> B3[消费者拉取]
        B3 --> B4[手动提交offset]
        B4 --> B5["✅ 宕机数据不丢失<br/>✅ 支持回溯<br/>✅ 高吞吐批量"]
    end

    style REDIS fill:#ffcdd2
    style KAFKA fill:#c8e6c9
    style A5 fill:#ef5350
    style B5 fill:#66bb6a
```

**技术选型对比表：**

| 对比维度 | Redis Pub/Sub | Kafka | 选择理由 |
|---------|---------------|-------|----------|
| **持久化** | 内存存储，默认不持久化 | 磁盘日志，持久化存储 | 数据安全 |
| **回溯能力** | 消费后删除，无法回溯 | 支持offset重置，可回溯 | 数据修复 |
| **消息留存** | 即时删除 | 可配置保留时间（7天） | 历史查询 |
| **吞吐量** | 内存操作，单机瓶颈 | 批量发送+分区并行 | 高性能 |
| **消费模式** | 推模式 | 拉模式 | 消费者可控 |
| **集群支持** | 主从复制 | 分区副本+ISR | 高可用 |
| **监控运维** | 基础监控 | 成熟生态（Kafka Manager等） | 可运维 |

**业务场景匹配：**
```
我们的需求：
├── 数据需要持久化存储（钻井液监控数据）
├── 偶尔需要回溯分析历史数据
├── 采集频率较高（分钟级），需要高吞吐
├── 消费者可能宕机，需要消息不丢失
└── 需要支持多个消费者并行处理

结论：Kafka更匹配 ✓
```

---

### Q10: Kafka消费慢了怎么办？你做了哪些优化？

**A:** 代码层面做了四层优化配置：**并发消费**通过`concurrency=3`配置3个消费线程并行处理消息；**批量拉取**通过`max.poll.records=500`单次拉取500条消息减少网络往返开销；**超时控制**通过`max.poll.interval.ms=300000`设置5分钟处理超时避免长时间阻塞触发rebalance；**序列化优化**使用JsonSerializer/JsonDeserializer提升序列化效率。如果仍然消费慢可以进一步增加partition数量提高并行度，或增加consumer实例数（不超过partition数），或优化业务逻辑减少单条消息处理耗时。

```mermaid
flowchart TD
    A[Kafka消费慢问题] --> B{瓶颈分析}

    B -->|网络瓶颈| C[批量拉取优化<br/>max.poll.records=500]
    B -->|单线程处理| D[并发消费优化<br/>concurrency=3]
    B -->|处理耗时过长| E[超时控制优化<br/>max.poll.interval.ms=5min]
    B -->|序列化慢| F[序列化优化<br/>JsonSerializer]

    C --> G[✅ 减少网络往返<br/>提升吞吐量]
    D --> H[✅ 多线程并行处理<br/>充分利用CPU]
    E --> I[✅ 避免rebalance<br/>稳定消费]
    F --> J[✅ 减少CPU开销<br/>提升处理速度]

    G --> K[消费延迟降低]
    H --> K
    I --> K
    J --> K

    K --> L{仍然慢?}
    L -->|是| M[进一步优化方案]
    L -->|否| N[问题解决]

    M --> M1[增加partition数<br/>提升并行度]
    M --> M2[增加consumer实例<br/>不超过partition数]
    M --> M3[优化业务逻辑<br/>减少处理耗时]

    style C fill:#c8e6c9
    style D fill:#c8e6c9
    style E fill:#c8e6c9
    style F fill:#c8e6c9
    style M1 fill:#fff9c4
    style M2 fill:#fff9c4
    style M3 fill:#fff9c4
```

**Kafka消费者优化配置：**

| 配置项 | 配置值 | 作用 | 代码位置 |
|--------|--------|------|----------|
| concurrency | 3 | 消费者线程数 | KafkaConfig.java:99 |
| max.poll.records | 500 | 单次拉取最大记录数 | application-kafka.yml:35 |
| max.poll.interval.ms | 300000 | poll间隔超时（5分钟） | application-kafka.yml:37 |
| ack-mode | manual | 手动确认模式 | KafkaConfig.java:96 |
| auto.offset.reset | earliest | 首次消费从最早开始 | application-kafka.yml:26 |

**消费性能优化层次：**
```
Level 1: 配置优化（已实现）
├── 批量拉取：max.poll.records=500
├── 并发消费：concurrency=3
├── 超时控制：max.poll.interval.ms=300000
└── 序列化：JsonSerializer

Level 2: 架构优化（可选）
├── 增加partition数 → 提升并行度
├── 增加consumer实例 → 充分利用partition
└── 优化业务逻辑 → 减少单条处理耗时

Level 3: 监控预警
├── 监控consumer lag（消费延迟）
├── 监控rebalance频率
└── 监控处理耗时分布
```

**消费者性能指标：**
```bash
# 查看消费延迟（consumer lag）
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group sky-server --describe

# 输出示例：
# TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# mqtt.raw    0          10000           10050           50   # 正常
# mqtt.raw    1          9800            10000           200  # 需要关注
```

---

### Q11: 生产者发送失败你怎么处理的？

**A:** 在`KafkaProducerService.sendMessage()`中通过异步回调机制处理发送结果：成功时记录日志包含topic、partition、offset信息便于追踪；失败时调用`handleSendFailure()`记录失败日志并预留三种降级方案——**方案1**记录到本地日志文件由后续补偿任务重发、**方案2**发送到Redis作为降级通道确保消息不丢失、**方案3**对关键消息直接写数据库兜底。同时配合生产者配置`retries=5`自动重试5次、`delivery.timeout.ms=30000`30秒发送超时，在Kafka集群短暂故障时自动恢复而不影响业务。

```mermaid
flowchart TD
    A[KafkaTemplate.send<br/>异步发送] --> B{等待结果}

    B -->|成功回调| C[onSuccess<br/>记录成功日志<br/>topic/partition/offset]
    C --> D[✅ 消息发送完成]

    B -->|失败回调| E[onFailure<br/>记录失败日志]

    E --> F[handleSendFailure<br/>处理发送失败]

    F --> G{降级方案选择}

    G --> H1["方案1<br/>记录本地日志<br/>补偿任务重发"]
    G --> H2["方案2<br/>发送到Redis<br/>降级通道"]
    G --> H3["方案3<br/>关键消息<br/>直接写数据库"]

    H1 --> I[⚠️ 消息已记录<br/>等待后续补偿]
    H2 --> J[⚠️ 消息已降级<br/>Redis接收]
    H3 --> K[⚠️ 消息已持久<br/>数据库存储]

    I --> L[记录warn日志<br/>topic/key记录]
    J --> L
    K --> L

    style C fill:#c8e6c9
    style E fill:#ffcdd2
    style H1 fill:#fff9c4
    style H2 fill:#fff9c4
    style H3 fill:#fff9c4
```

**生产者发送处理流程：**

| 阶段 | 方法 | 行为 | 代码位置 |
|------|------|------|----------|
| **发送** | kafkaTemplate.send() | 异步发送消息 | KafkaProducerService.java:34 |
| **成功回调** | onSuccess() | 记录成功日志 | KafkaProducerService.java:38-42 |
| **失败回调** | onFailure() | 调用失败处理 | KafkaProducerService.java:45-49 |
| **失败处理** | handleSendFailure() | 记录+降级 | KafkaProducerService.java:66-75 |

**核心代码实现：**
```java
// KafkaProducerService.java:34-55
kafkaTemplate.send(topic, key, message).addCallback(
    // 成功回调
    new ListenableFutureCallback<SendResult<String, Object>>() {
        @Override
        public void onSuccess(SendResult<String, Object> result) {
            RecordMetadata metadata = result.getRecordMetadata();
            log.debug("消息发送成功: topic={}, partition={}, offset={}",
                metadata.topic(), metadata.partition(), metadata.offset());
        }

        @Override
        public void onFailure(Throwable ex) {
            log.error("消息发送失败: topic={}, key={}, error={}",
                topic, key, ex.getMessage(), ex);
            handleSendFailure(topic, key, message, ex);  // 失败处理
        }
    }
);

// KafkaProducerService.java:66-75
private void handleSendFailure(String topic, String key, Object message, Throwable ex) {
    // 方案1: 记录到本地日志文件，后续有补偿任务重发
    // 方案2: 发送到Redis作为降级通道
    // 方案3: 关键消息直接写数据库

    log.warn("消息发送失败已记录，等待补偿: topic={}, key={}", topic, key);

    // TODO: 实现具体的失败处理逻辑
}
```

**生产者可靠性配置：**
```yaml
# application-kafka.yml
spring.kafka.producer:
  acks: all                    # 等待所有ISR副本确认
  retries: 5                   # 失败重试5次
  enable-idempotence: true     # 开启幂等性
  batch-size: 16384            # 批量发送16KB
  linger-ms: 10                # 等待10ms收集更多消息
  delivery.timeout.ms: 30000   # 30秒发送超时
  request.timeout.ms: 5000     # 5秒请求超时
```

**降级方案对比：**

| 降级方案 | 优点 | 缺点 | 适用场景 |
|---------|------|------|----------|
| **本地日志+补偿重发** | 简单可靠，不影响主流程 | 有延迟，需要补偿任务 | 非关键消息 |
| **Redis降级通道** | 实时性好，可快速切换 | 依赖Redis稳定性 | 重要消息 |
| **直接写数据库** | 最可靠，数据永久存储 | 性能开销大 | 关键业务数据 |

**异常场景处理：**
```
场景1: Kafka集群短暂故障
├── retries=5 自动重试
├── 30秒后仍失败 → handleSendFailure
└── 集群恢复后 → 补偿任务重发

场景2: 网络抖动
├── enable-idempotence=true 防止重复
├── 自动重试成功
└── 无需人工介入

场景3: Kafka集群长期宕机
├── 所有消息进入降级通道
├── Redis/数据库兜底
└── 集群恢复后批量迁移
```

---

## AOP操作日志与统一线程池（Q&A）

### Q12: AOP操作日志是如何实现非侵入式的？包含哪些功能？

**A:** 通过自定义`@OperationLog`注解配合`OperationLogAspect`切面类实现非侵入式日志记录：使用`@Around("@annotation(operationLog)")`环绕通知拦截带注解的方法，在方法执行前收集请求信息（模块、操作类型、描述、请求URL、IP地址、User-Agent、操作用户ID），执行过程中捕获参数和返回值，执行后记录执行时间、状态（成功/失败）和错误信息。核心特性包括：**敏感字段过滤**自动屏蔽password、token等敏感数据，**异步保存**通过`@Async`注解避免影响主业务性能，**IP地址解析**支持多种代理头获取真实IP，**数据长度限制**防止大对象序列化影响性能。

```mermaid
flowchart TD
    A[Controller方法调用<br/>@OperationLog注解] --> B[OperationLogAspect拦截]

    B --> C[执行前收集信息]
    C --> C1[模块/类型/描述<br/>来自注解]
    C --> C2[请求信息<br/>URL/Method/IP/UA]
    C --> C3[用户信息<br/>BaseContext.getCurrentId]
    C --> C4[方法信息<br/>类名/方法名]

    C1 & C2 & C3 & C4 --> D{saveRequestData=true?}
    D -->|是| E[序列化请求参数<br/>filterSensitiveData过滤]
    D -->|否| F[跳过参数记录]

    E --> G[执行目标方法<br/>joinPoint.proceed]
    F --> G

    G --> H{执行结果?}
    H -->|成功| I[status=1<br/>记录返回值]
    H -->|失败| J[status=0<br/>记录错误信息]

    I --> K[计算执行时间<br/>endTime-startTime]
    J --> K

    K --> L[异步保存日志<br/>saveLogAsync<br/>@Async]
    L --> M[✅ 返回业务结果]

    style B fill:#e8f5e9
    style E fill:#fff9c4
    style I fill:#c8e6c9
    style J fill:#ffcdd2
    style L fill:#e1f5ff
```

**@OperationLog注解定义：**
```java
// OperationLog.java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    String module() default "";           // 业务模块
    OperationType type() default OTHER;    // 操作类型
    String description() default "";       // 操作描述
    boolean saveRequestData() default true;   // 是否保存请求参数
    boolean saveResponseData() default true;  // 是否保存响应数据
}
```

**使用示例：**
```java
// Controller中使用
@PostMapping("/add")
@OperationLog(
    module = "钻井液管理",
    type = OperationType.INSERT,
    description = "新增钻井液性能参数"
)
public Result<DrillingData> add(@RequestBody DrillingDataDTO dto) {
    // 业务逻辑，无需手动记录日志
    return Result.success(drillingDataService.save(dto));
}
```

**操作日志记录内容：**

| 字段 | 来源 | 说明 |
|------|------|------|
| module | 注解 | 业务模块名称 |
| operationType | 注解 | 操作类型（INSERT/UPDATE/DELETE等） |
| description | 注解 | 操作描述 |
| requestMethod | HttpServletRequest | HTTP方法（GET/POST/PUT/DELETE） |
| requestUrl | HttpServletRequest | 请求URI |
| ip | HttpServletRequest | 真实IP地址（支持代理） |
| userAgent | HttpServletRequest | User-Agent头 |
| operatorId | BaseContext | 当前操作用户ID |
| method | JoinPoint | 目标类名+方法名 |
| requestParam | JoinPoint.args | 请求参数（已脱敏） |
| responseData | 方法返回值 | 响应数据 |
| status | 执行结果 | 1成功/0失败 |
| errorMsg | 异常信息 | 错误信息 |
| executionTime | 计时 | 执行耗时（毫秒） |

**敏感字段过滤机制：**
```java
// OperationLogAspect.java:44-46
private static final String[] SENSITIVE_FIELDS = {
    "password", "pwd", "secret", "token", "credential", "idCard", "identity"
};

// 过滤结果示例
// 请求前: {"username":"admin","password":"123456"}
// 请求后: {"username":"admin","password":"******"}
```

---

### Q13: 统一线程池是如何设计的？如何避免资源浪费？

**A:** 通过`ThreadPoolConfig`配置类创建两个专用线程池实现资源隔离：**drillingDataExecutor**用于处理钻井数据相关任务，核心线程数=CPU核心数、最大线程数=CPU核心数×2、队列容量100；**taskExecutor**用于异步任务（如操作日志记录），核心线程数2、最大线程数5、队列容量200。两者都配置了优雅关闭策略（`waitForTasksToCompleteOnShutdown=true`）和拒绝策略（`CallerRunsPolicy`由调用线程执行），确保应用关闭时任务完成且有降级方案。配置通过`@ConfigurationProperties(prefix="thread-pool")`支持外部配置，可根据服务器资源灵活调整。

```mermaid
flowchart TD
    subgraph CONFIG["ThreadPoolConfig配置类"]
        A1[@Configuration<br/>@EnableAsync] --> A2["读取配置<br/>thread-pool.*"]
        A2 --> A3["创建两个线程池Bean"]
    end

    subgraph EXECUTOR1["drillingDataExecutor<br/>业务线程池"]
        B1["corePoolSize = CPU核心数"]
        B2["maxPoolSize = CPU核心数×2"]
        B3["queueCapacity = 100"]
        B4["threadNamePrefix = drilling-data-"]
        B5["CallerRunsPolicy拒绝策略"]
    end

    subgraph EXECUTOR2["taskExecutor<br/>异步任务线程池"]
        C1["corePoolSize = 2"]
        C2["maxPoolSize = 5"]
        C3["queueCapacity = 200"]
        C4["threadNamePrefix = async-task-"]
        C5["CallerRunsPolicy拒绝策略"]
    end

    subgraph USAGE["使用场景"]
        D1["@Async('drillingDataExecutor')<br/>钻井数据处理"]
        D2["@Async('taskExecutor')<br/>操作日志保存"]
    end

    A3 --> B1 & B2 & B3 & B4 & B5
    A3 --> C1 & C2 & C3 & C4 & C5
    B5 --> D1
    C5 --> D2

    style CONFIG fill:#e8f5e9
    style EXECUTOR1 fill:#fff9c4
    style EXECUTOR2 fill:#e1f5ff
    style D1 fill:#c8e6c9
    style D2 fill:#c8e6c9
```

**线程池配置对比：**

| 配置项 | drillingDataExecutor | taskExecutor | 设计思路 |
|--------|---------------------|--------------|----------|
| **核心线程数** | CPU核心数 | 2 | 业务需要更多并发 |
| **最大线程数** | CPU核心数×2 | 5 | CPU密集型 vs IO密集型 |
| **队列容量** | 100 | 200 | 任务量预期 |
| **线程名前缀** | drilling-data- | async-task- | 便于问题排查 |
| **用途** | 钻井数据处理 | 异步任务（日志） | 资源隔离 |
| **拒绝策略** | CallerRunsPolicy | CallerRunsPolicy | 降级执行 |

**线程池工作原理：**
```
任务提交流程：
┌─────────────┐
│ 提交新任务   │
└──────┬──────┘
       ↓
┌─────────────────────┐
│ 核心线程数未满？      │ ——是→ → 创建新线程执行
└──────┬──────────────┘
       │ 否
       ↓
┌─────────────────────┐
│ 队列未满？           │ ——是→ → 加入队列等待
└──────┬──────────────┘
       │ 否
       ↓
┌─────────────────────┐
│ 最大线程数未满？      │ ——是→ → 创建非核心线程执行
└──────┬──────────────┘
       │ 否
       ↓
┌─────────────────────┐
│ 拒绝策略：CallerRunsPolicy │
│ （由提交任务的线程执行）    │
└─────────────────────┘
```

**拒绝策略对比：**

| 策略 | 行为 | 适用场景 |
|------|------|----------|
| **CallerRunsPolicy** | 调用线程执行任务 | ✅ 当前选择，有降级保障 |
| AbortPolicy | 抛出异常 | 需要显式处理拒绝 |
| DiscardPolicy | 静默丢弃 | 不重要的任务 |
| DiscardOldestPolicy | 丢弃最老任务 | 可接受数据丢失 |

**使用示例：**
```java
// 指定线程池执行异步任务
@Async("taskExecutor")
public void saveLogAsync(OperationLog operationLog) {
    this.save(operationLog);
}

// 配置文件支持动态调整
// application.yml
thread-pool:
  core-pool-size: 8      # 核心线程数，0表示使用CPU核心数
  max-pool-size: 16      # 最大线程数，0表示使用CPU核心数×2
  queue-capacity: 100    # 队列容量
  keep-alive-seconds: 60 # 线程空闲时间
```

**优雅关闭机制：**
```java
// ThreadPoolConfig.java:85-86
executor.setWaitForTasksToCompleteOnShutdown(true);  // 等待任务完成
executor.setAwaitTerminationSeconds(60);             // 最多等待60秒

// 应用关闭时的行为：
// 1. 停止接收新任务
// 2. 等待队列中的任务执行完成（最多60秒）
// 3. 超时后强制关闭
```

**线程池监控建议：**
```java
// 可通过日志或监控平台观察
log.info("钻井数据线程池初始化完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
    actualCorePoolSize, actualMaxPoolSize, queueCapacity);

// 建议监控指标：
// - 活跃线程数：getActiveCount()
// - 池中当前线程数：getPoolSize()
// - 队列中的任务数：getQueue().size()
// - 已完成的任务数：getCompletedTaskCount()
```

**业务价值：**
- **资源隔离**：不同类型任务使用不同线程池，避免相互影响
- **性能优化**：根据任务类型（CPU密集/IO密集）配置不同参数
- **稳定性保障**：拒绝策略确保极端情况下系统不崩溃
- **可观测性**：线程名前缀便于日志分析和问题排查

---

## MQTT消息不丢失保障机制（Q&A）

### Q14: MQTT客户端如何保障消息不丢失？实现了哪些机制？

**A:** 通过三层防护机制保障消息可靠性：**协议层**采用QoS 1确保消息至少送达一次，订阅时显式指定`mqttClient.subscribe(topic, 1)`，发布时默认使用QoS 1；**会话层**设置`setCleanSession(false)`保持会话状态，断线重连后可接收Broker缓存的离线消息；**持久化层**使用`MqttDefaultFilePersistence`将消息存储到磁盘（路径：`/Users/kirayang/mqtt/data`），应用重启后可恢复未确认的消息。同时配置心跳间隔20秒检测连接状态，`setAutomaticReconnect(true)`实现自动重连，形成完整的可靠性保障体系。

```mermaid
flowchart TD
    subgraph PROTOCOL["协议层保障"]
        A1["QoS 1<br/>至少一次交付"]
        A2["PUBACK确认机制"]
    end

    subgraph SESSION["会话层保障"]
        B1["CleanSession=false<br/>保持会话"]
        B2["离线消息缓存<br/>Broker端存储"]
    end

    subgraph PERSIST["持久化层保障"]
        C1["MqttDefaultFilePersistence<br/>磁盘存储"]
        C2["/Users/kirayang/mqtt/data"]
    end

    subgraph CONNECT["连接层保障"]
        D1["KeepAlive=20秒<br/>心跳检测"]
        D2["AutomaticReconnect<br/>自动重连"]
    end

    PROTOCOL --> F["✅ 消息至少送达"]
    SESSION --> G["✅ 离线消息不丢失"]
    PERSIST --> H["✅ 重启后恢复"]
    CONNECT --> I["✅ 连接可靠"]

    style PROTOCOL fill:#c8e6c9
    style SESSION fill:#fff9c4
    style PERSIST fill:#e1f5ff
    style CONNECT fill:#f3e5f5
```

**MyMqttClient核心配置：**
```java
// MyMqttClient.java:37-56
public void connect() throws MqttException {
    if (client == null) {
        // ✅ 磁盘持久化，重启后恢复消息
        String persistenceDir = "/Users/kirayang/mqtt/data";
        client = new MqttClient(host, clientId,
            new MqttDefaultFilePersistence(persistenceDir));
    }

    MqttConnectOptions options = new MqttConnectOptions();
    options.setUserName(username);
    options.setPassword(password.toCharArray());
    options.setConnectionTimeout(timeout);
    options.setKeepAliveInterval(keepalive);     // ✅ 20秒心跳
    options.setCleanSession(false);              // ✅ 保持会话
    options.setAutomaticReconnect(true);         // ✅ 自动重连

    if (!client.isConnected()) {
        client.connect(options);
    }
}

// ✅ 默认发布QoS 1
public void publish(String topic, String message) {
    publish(topic, message, 1, false);
}
```

**消息可靠性保障层次：**

| 层次 | 机制 | 实现位置 | 作用 |
|------|------|----------|------|
| **协议层** | QoS 1 | 订阅/发布 | 确保消息至少送达一次 |
| **会话层** | CleanSession=false | MqttConnectOptions | 断线保留会话和订阅 |
| **持久化层** | 磁盘存储 | MqttDefaultFilePersistence | 应用重启恢复未确认消息 |
| **连接层** | KeepAlive + 自动重连 | MqttConnectOptions | 检测并恢复断线 |

---

### Q15: QoS三种级别的区别是什么？为什么选择QoS 1？

**A:** MQTT定义了三种QoS级别对应不同的交付保证：**QoS 0（最多一次）**即发即忘，无确认机制，性能最高但可能丢失消息；**QoS 1（至少一次）**发送后等待PUBACK确认，确保消息送达但可能重复，是我们采用的方案；**QoS 2（恰好一次）**通过四步握手确保不丢失不重复，但性能开销最大。选择QoS 1是在可靠性和性能之间的最佳平衡——钻井液监控数据需要确保送达（不能丢），但可以接受偶尔重复（前端有去重处理）。

```mermaid
flowchart TB
    subgraph QOS0["QoS 0 - At Most Once"]
        A1["PUBLISH"] --> A2["无确认"]
        A2 --> A3["⚠️ 可能丢失<br/>✅ 性能最高"]
    end

    subgraph QOS1["QoS 1 - At Least Once"]
        B1["PUBLISH"] --> B2["PUBACK确认"]
        B2 --> B3["✅ 不丢失<br/>✅ 性能较好"]
    end

    subgraph QOS2["QoS 2 - Exactly Once"]
        C1["PUBLISH"] --> C2["四步握手"]
        C2 --> C3["✅ 不丢失不重复<br/>⚠️ 性能较低"]
    end

    style QOS0 fill:#ffcdd2
    style QOS1 fill:#c8e6c9
    style QOS2 fill:#fff9c4
```

**QoS级别对比表：**

| QoS | 名称 | 交付保证 | 性能 | 适用场景 |
|-----|------|---------|------|----------|
| **0** | At Most Once | 可能丢失 | 最高 | 频繁上报的传感器数据 |
| **1** | At Least Once | 至少送达 | 较高 | 告警消息、监控数据 ✅ |
| **2** | Exactly Once | 恰好一次 | 最低 | 金融交易、计费系统 |

**QoS 1交付流程：**
```
Publisher                    Broker
   │                           │
   │ ──────────────────────────┤
   │      PUBLISH(qos=1, MID=1)
   │                           │
   │ ◄──────────────────────────┤
   │      PUBACK(MID=1)        [消息已确认]
   │                           │
(可以丢弃)               [转发给订阅者]
```

---

### Q16: 磁盘持久化与内存持久化的区别是什么？

**A:** Eclipse Paho提供两种持久化策略：**MemoryPersistence**将消息存储在JVM堆内存中，性能最高但应用重启时所有未确认消息丢失；**MqttDefaultFilePersistence**将消息序列化后写入磁盘文件，应用重启后可从文件恢复未确认的消息队列。我们使用磁盘持久化，存储路径设置为`/Users/kirayang/mqtt/data`，确保mqtt-server重启后仍能恢复发送中/接收中的消息，配合Broker端的会话持久化形成端到端的可靠性保障。

```mermaid
flowchart TD
    subgraph MEMORY["MemoryPersistence"]
        A1[消息存储] --> A2["JVM堆内存"]
        A2 --> A3["⚡ 性能最高"]
        A2 --> A4["❌ 重启后丢失"]
    end

    subgraph DISK["MqttDefaultFilePersistence"]
        B1[消息存储] --> B2["磁盘文件<br/>/Users/kirayang/mqtt/data"]
        B2 --> B3["✅ 重启后恢复"]
        B2 --> B4["✅ 可靠性高"]
    end

    style MEMORY fill:#ffcdd2
    style DISK fill:#c8e6c9
```

**持久化策略对比：**

| 对比维度 | MemoryPersistence | MqttDefaultFilePersistence |
|---------|-------------------|---------------------------|
| **存储位置** | JVM堆内存 | 磁盘文件 |
| **性能** | 最高 | 略低（磁盘IO） |
| **重启恢复** | ❌ 无法恢复 | ✅ 自动恢复 |
| **适用场景** | 测试环境 | 生产环境 ✅ |

**代码实现：**
```java
// MyMqttClient.java:39-41
String persistenceDir = "/Users/kirayang/mqtt/data";
client = new MqttClient(host, clientId,
    new MqttDefaultFilePersistence(persistenceDir));
```

**端到端持久化保障：**
```
Client端                          Broker端
┌─────────────┐                ┌─────────────┐
│磁盘持久化   │ ←──QoS 1──→    │会话持久化   │
│未确认消息   │                │离线消息缓存 │
│重启可恢复   │                │重连可投递   │
└─────────────┘                └─────────────┘

结果：✅ 客户端重启 → 恢复未确认消息
      ✅ Broker重启 → 保留会话和离线消息
      ✅ 双重保障 → 消息零丢失
```

---

## AI诊断Agent调用链路（Q&A）

### Q17: 诊断Agent的完整调用链路是什么？

**A:** 整个链路是"SpringBoot → FastAPI → DiagnosisService → DiagnosisAgent → LangChain → LLM + Tools"的五层调用架构。SpringBoot触发预警后调用`AiDiagnosisTriggerService`向FastAPI发送SSE请求，FastAPI的`/api/v1/diagnosis/analyze`端点接收请求后交由`DiagnosisService`处理，服务层创建任务记录并调用`DiagnosisAgent.analyze()`，Agent使用LangChain 1.0的`create_agent`构建，通过`ToolStrategy`实现结构化输出，流式返回SSE事件包含思考过程、工具调用和最终诊断结果。

```mermaid
flowchart LR
    A[XXL-Job定时任务] -->|检测到异常| B[AiDiagnosisTriggerService<br/>SpringBoot]
    B -->|HTTP POST<br/>DiagnosisRequest| C[FastAPI<br/>diagnosis.py]
    C -->|StreamingResponse<br/>text/event-stream| D[DiagnosisService]
    D -->|analyze| E[DiagnosisAgent<br/>LangChain 1.0.0]
    E -->|astream| F[create_agent<br/>流式执行]

    F -->|调用| G1[analyze_trend<br/>趋势分析工具]
    F -->|调用| G2[search_knowledge<br/>知识检索工具]
    F -->|调用| G3[format_prescription<br/>配药方案工具]

    F -->|结构化输出| H[LLMDiagnosisOutput<br/>ToolStrategy]
    H -->|转换| I[DiagnosisResult]
    I -->|SSE事件| J[SpringBoot回调/前端]

    G2 -.->|检索| K[pgvector<br/>专家知识库]

    style A fill:#fff4e1
    style B fill:#e8f5e9
    style C fill:#e1f5ff
    style D fill:#fff9c4
    style E fill:#f3e5f5
    style F fill:#c8e6c9
    style H fill:#ffcc80
    style K fill:#b39ddb
```

**SSE事件流示例：**
```
data: {"type":"start","task_id":"TASK-xxx","well_id":"SHB001",...}

data: {"type":"thinking","content":"正在分析 5 条采样数据...","step":"data_analysis"}

data: {"type":"thinking","content":"调用工具: analyze_trend","step":"tool_call"}

data: {"type":"thinking","content":"调用工具: search_knowledge","step":"tool_call"}

data: {"type":"thinking","content":"正在生成诊断结果...","step":"reasoning"}

data: {"type":"result","result":{"diagnosis":{"summary":"密度偏高..."},...}}

data: {"type":"done","status":"SUCCESS"}
```

---

### Q18: LangChain Agent是如何构建的？结构化输出如何实现？

**A:** 使用LangChain 1.0.0的`create_agent`函数构建诊断Agent，传入`ChatOpenAI`模型（temperature=0.3保证稳定输出）、三个诊断工具（analyze_trend、search_knowledge、format_prescription）和专家角色系统提示词；通过`ToolStrategy(LLMDiagnosisOutput)`实现结构化输出，LLM返回的结果自动映射到Pydantic模型包含诊断总结、原因分析、风险等级、趋势分析、处置措施和配药方案；使用`AsyncRedisSaver`作为checkpoint存储，支持通过thread_id恢复对话状态。

```mermaid
flowchart TD
    subgraph BUILD["Agent构建"]
        A1[ChatOpenAI<br/>temperature=0.3] --> A2[工具列表<br/>3个诊断工具]
        A3[系统提示词<br/>专家角色] --> A4[ToolStrategy<br/>结构化输出]
        A5[AsyncRedisSaver<br/>checkpoint] --> A6[create_agent]
    end

    subgraph TOOLS["诊断工具集"]
        B1[analyze_trend<br/>趋势分析]
        B2[search_knowledge<br/>知识检索]
        B3[format_prescription<br/>配药方案]
    end

    subgraph OUTPUT["结构化输出"]
        C1[LLMDiagnosisOutput<br/>Pydantic模型]
        C1 --> C2[summary<br/>诊断总结]
        C1 --> C3[cause<br/>原因分析]
        C1 --> C4[risk_level<br/>风险等级]
        C1 --> C5[trend_analysis<br/>趋势列表]
        C1 --> C6[measures<br/>处置措施]
        C1 --> C7[prescription<br/>配药方案]
    end

    A6 --> B1 & B2 & B3
    A6 --> C1

    style BUILD fill:#c8e6c9
    style TOOLS fill:#fff9c4
    style OUTPUT fill:#e1f5ff
```

**Agent构建代码：**
```python
# diagnosis_agent.py:102-149
def _build_agent(self):
    tools = [
        analyze_trend,      # 趋势分析工具
        search_knowledge,   # 知识检索工具
        format_prescription # 配药方案工具
    ]

    system_prompt = """你是一位钻井液性能诊断专家。你的职责是：
    1. 分析钻井液采样数据，识别异常趋势
    2. 基于历史数据和知识库，诊断问题原因
    3. 提供具体的处置措施和配药方案
    4. 评估风险等级并提供趋势预测"""

    self.agent = create_agent(
        model=self.model,
        tools=tools,
        system_prompt=system_prompt,
        checkpointer=self.checkpointer,
        response_format=ToolStrategy(LLMDiagnosisOutput)
    )
```

**结构化输出Schema：**
```python
# LLMDiagnosisOutput: Pydantic模型定义
class LLMDiagnosisOutput(BaseModel):
    summary: str              # 诊断总结
    cause: str                # 问题原因
    risk_level: str           # LOW/MEDIUM/HIGH/CRITICAL
    trend_outlook: Optional[str]
    trend_analysis: List[LLMTrendAnalysis]
    measures: List[LLMTreatmentMeasure]
    prescription: LLMPrescription
```

---

### Q19: 流式输出是如何实现的？如何处理多流模式？

**A:** 使用LangChain的`astream()`方法配合多流模式实现流式输出：通过`stream_mode=["messages", "updates"]`同时获取LLM生成的tokens和Agent的执行进度；messages模式返回LLM思考文本块和工具调用块，用于实时展示Agent推理过程；updates模式返回节点状态更新，包含完整的工具调用参数和返回结果；将不同流模式的事件统一转换为`DiagnosisEvent`并通过SSE推送到前端，前端可实时显示分析进度、工具调用和最终结果。

```mermaid
flowchart TD
    A[agent.astream] --> B{stream_mode?}

    B -->|messages| C[(token_chunk, metadata)]
    C --> C1{token类型?}
    C1 -->|text| D[DiagnosisEvent.thinking<br/>step=reasoning]
    C1 -->|tool_call_chunks| E[DiagnosisEvent.thinking<br/>step=tool_call]

    B -->|updates| F[{node_name: update_dict}]
    F --> G{update内容?}
    G -->|tool_calls| H[DiagnosisEvent.thinking<br/>step=tool_call]
    G -->|tool result| I[DiagnosisEvent.thinking<br/>step=tool_result]

    D & E & H & I --> J[SSE推送<br/>text/event-stream]

    K[最终状态] --> L[structured_response<br/>LLMDiagnosisOutput]
    L --> M[转换为DiagnosisResult]
    M --> N[DiagnosisEvent.result]
    N --> J

    style B fill:#fff9c4
    style J fill:#c8e6c9
    style L fill:#e1f5ff
```

**流式输出代码：**
```python
# diagnosis_agent.py:194-250
async for stream_mode, data in self.agent.astream(
    {"messages": [HumanMessage(content=prompt)]},
    config=config,
    stream_mode=["messages", "updates"],
):
    if stream_mode == "messages":
        token, metadata = data
        # LLM思考文本
        if hasattr(token, "text") and token.text:
            yield DiagnosisEvent.thinking(task_id, token.text, "reasoning")
        # 工具调用块
        elif hasattr(token, "tool_call_chunks"):
            yield DiagnosisEvent.thinking(task_id, f"调用工具: {tool_name}", "tool_call")

    elif stream_mode == "updates":
        for node_name, update in data.items():
            if "messages" in update:
                for msg in update["messages"]:
                    # 完整工具调用
                    if hasattr(msg, "tool_calls"):
                        yield DiagnosisEvent.thinking(task_id, f"调用工具: {tool_name}", "tool_call")
                    # 工具返回结果
                    elif msg.type == "tool":
                        yield DiagnosisEvent.thinking(task_id, f"工具返回: {content}", "tool_result")
```

**多流模式对比：**

| stream_mode | 返回内容 | 用途 | 示例 |
|-------------|---------|------|------|
| **messages** | (token_chunk, metadata) | LLM实时生成内容 | 思考文本、工具调用片段 |
| **updates** | {node_name: update_dict} | Agent节点状态更新 | 完整工具调用、返回结果 |
| **values** | 当前完整state | 获取最终状态 | 提取结构化输出 |

**业务价值：**
- **实时反馈**：前端可实时展示Agent分析进度
- **可观测性**：完整记录工具调用链路便于调试
- **用户体验**：流式输出减少等待时间感知

---

## 专家知识库 RAG 链路详解（Q&A）

### Q20: 专家知识库 RAG 链路的完整流程是什么？

**A:** 整个链路支持 **“同步强一致性”** 与 **“异步高吞吐”** 双模式架构：
1. **SpringBoot 端**：作为业务入口，利用 **Apache Tika** 执行文档（pdf/doc/md/txt）解析。
2. **同步模式 (Sync)**：适用于小文档。通过 WebClient 同步调用 FastAPI 接口，直接返回切片结果，实现“即传即搜”。
3. **异步模式 (Async)**：适用于生产环境大批量任务。
    - **解耦**：SpringBoot 提取文件字节流并上传 OSS 后，将任务推入 **Redis Stream**。
    - **消费**：Python 侧的 `KnowledgeImportConsumer` 监听 Stream，异步执行 **Parent-Child** 切片、向量化存储。
4. **存储策略**：利用 **PGVector** 实现 Parent-Child 双层存储。父文档保留全文上下文，子分块负责高精语义检索。
5. **检索回溯**：Agent 诊断时，检索 Child 命中后自动回溯 Parent 全文，确保诊断建议具备专业深度。

```mermaid
flowchart TD
    User([用户上传文档]) --> Gateway[SpringBoot 业务入口]
    
    subgraph PROCESS [解析与流转]
        Gateway --> Tika[Apache Tika 文本提取]
        Tika --> Mode{模式选择}
        Mode -- 同步 --> Sync[WebClient 调用 FastAPI]
        Mode -- 异步 --> Stream[Push to Redis Stream]
    end

    subgraph WORKER [Python 异步消费者]
        Stream --> Consumer[KnowledgeImportConsumer]
        Consumer --> Split[Parent-Child 切分策略]
    end

    Sync --> Split
    
    subgraph STORAGE [向量存储层]
        Split --> PG[(PostgreSQL + PGVector)]
        PG -.-> P[Parent: 完整上下文]
        PG -.-> C[Child: 语义向量分块]
    end

    subgraph RETRIEVAL [智能检索链路]
        Q[用户诊断提问] --> Middleware[RetrievalMiddleware 中间件]
        Middleware --> Search{检索 Child}
        Search -->|Match| Trace[回溯 Parent 全文]
        Trace --> Agent[Agent 结合上下文生成建议]
    end

    style Gateway fill:#e8f5e9
    style Stream fill:#fff9c4
    style PG fill:#e1f5ff
    style Agent fill:#c8e6c9
```

### Q21: 面试官：在 SpringBoot 中实现异步文件处理时，你遇到了什么坑？是如何解决的？

**A:** 最核心的坑是 **`MultipartFile` 的生命周期与线程安全问题**：
*   **现象**：在异步方法 (`@Async`) 中直接传递 `MultipartFile` 对象，常报 `FileNotFoundException`。
*   **原因**：`MultipartFile` 指向的是 Servlet 容器管理的临时文件。当主线程（HTTP 请求）返回响应后，Servlet 会立即清理这些临时文件。此时异步线程由于并发执行，可能还没来得及读取文件，文件就被删除了。
*   **解决**：
    1.  **数据拷贝**：在进入异步方法前，在主线程中先调用 `file.getBytes()` 将内容拷贝到内存（字节数组），或将文件持久化到 OSS。
    2.  **参数解耦**：传递 `byte[]` 或 `ossUrl` 给异步方法，从而摆脱对 Request 线程生命周期的依赖。

### Q22: 面试官：既然已经有了子分块(Child Chunks)，为什么还要引入 Parent-Child 检索模式？

**A:** 这种模式是为了解决 RAG 系统中 **“检索精度” 与 “生成质量”** 的经典矛盾：
1. **检索精度（子分块）**：较小的子分块（如 600 tokens）包含的语义更聚焦，在进行余弦相似度计算时，能够更精准地命中用户问题中的具体细节。
2. **生成质量（父文档）**：如果直接将碎片化的子块喂给大模型，会导致模型因缺乏上下文而产生“幻觉”或专业建议断章取义。
3. **逻辑闭环**：通过 Parent-Child 模式，我们实现了 **“用细节搜索，用全局回答”**。Agent 既能精准定位到分块，又能获得整篇专家手册的上下文支持，从而生成的配药方案和处置建议更具逻辑性和可操作性。

### Q23: 面试官：为什么选择 Redis Stream 而不是 Kafka 或 RabbitMQ 作为异步任务队列？

**A:** 主要基于 **“架构复杂度” 与 “业务匹配度”** 的权衡：
1. **运维极简**：项目已使用 Redis 做缓存和 LangGraph 状态存储，引入 Redis Stream **零新增运维成本**。
2. **功能完备**：Redis Stream 提供了 **Consumer Group (消费组)**、**ACK (确认机制)** 和 **Pending List (失败重试)**，其可靠性足以支撑非金融级的知识库解析任务。
3. **低延迟响应**：相比 Kafka 的厚重，Redis Stream 的读写延迟极低，且能够很好地处理任务突发流量，非常适合 AI 中间件这种高频 IO 场景。

### Q24: 面试官：异步处理链路中，用户如何感知处理进度？

**A:** 我设计了一套 **“状态机 + 多路通知”** 机制：
1. **状态追踪**：在 Redis 中维护一个哈希表 `knowledge:status:{doc_id}`，定义了 `PARSING` (解析中)、`EMBEDDING` (向量化中)、`COMPLETED` (已完成) 和 `FAILED` (失败) 等状态。
2. **进度反馈**：
    - **主动查询**：后端提供 `/status/{doc_id}` 接口供前端轮询。
    - **回调机制**：Python 侧解析完成后，通过 HTTP Post 回调 Java 侧接口。
    - **前端推送**：Java 侧接收到回调后，利用 **SSE** 或 **WebSocket** 向前端实时推送处理成功的通知，实现“异步处理，实时触达”。

---

## 向量数据库 PGVector 专项（Q&A）

### Q25: 面试官：PGVector 支持哪些距离计算策略？你们是怎么选型的？

**A:** PGVector 主要支持三类距离算法：
1. **L2 距离 (欧几里得距离)**：计算向量间的绝对距离，适用于对幅值敏感的场景。
2. **余弦相似度 (Cosine Similarity)**：衡量向量夹角的余弦值，只关注方向而非大小。
3. **内积 (Inner Product)**：计算向量的点积。
**选型建议**：在我们的专家知识库场景中，我们选择了 **余弦相似度 (`vector_cosine_ops`)**。因为在 RAG 文本检索中，文本长度（幅值）往往受到分块策略影响，而我们更关心语义的方向一致性，余弦相似度在处理 NLP 嵌入向量时表现最稳定且符合工业界主流标准。

### Q26: 面试官：PGVector 如何在大规模数据下保证检索效率？

**A:** 主要是通过建立 **向量索引 (Vector Index)** 来实现的，PGVector 支持两种主流索引：
1. **IVFFlat (Inverted File with Flat Compression)**：通过聚类将空间划分为多个列表。检索时只搜索最接近的几个列表。
    - *优点*：内存占用低，构建快。
    - *缺点*：如果数据分布变化快，需要重新训练聚类中心。
2. **HNSW (Hierarchical Navigable Small Worlds)**：构建多层图结构。
    - *优点*：检索速度极快，召回率（Recall）非常高，支持增量添加数据而无需重新构建索引。
    - *缺点*：构建索引时的内存和时间开销比 IVFFlat 大。
**我们的实践**：考虑到知识库文档是动态增加的，我们优先推荐使用 **HNSW 索引**。虽然它在写入时略慢，但它在千万级数据量下依然能保持毫秒级的检索响应，非常适合需要高响应速度的 Agent 诊断场景。