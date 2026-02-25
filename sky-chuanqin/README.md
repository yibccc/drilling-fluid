# Sky-Chuanqin 钻井监控与预警系统

## 项目概述

Sky-Chuanqin是一个专为石油和天然气行业上游勘探与开采(E&P)领域设计的钻井监控与预警平台。系统通过实时采集、存储和分析钻井过程中的各项关键工程参数（如钻井液密度、压力、流量、扭矩等）与环境参数，进行深度数据挖掘和全性能评估，对可能出现的设备故障、地质异常及环境污染等风险进行精确、及时的预警。

本系统特别关注于陆上及海上钻井平台作业的数字化与智能化转型，旨在保障钻井作业的安全性、提升作业效率，并实现对环境影响的有效控制。

## 系统架构

系统采用Maven多模块架构，基于Spring Boot微服务框架。整体遵循业界成熟的分层设计思想，清晰地划分了各层职责，以实现高内聚、低耦合、高可维护性的设计目标。

### 模块划分

- **sky-common**: 公共组件模块，包含共享的DTO/VO实体类、结果封装类、工具类、异常处理、WebSocket配置、Redis配置、跨模块共享的服务接口等
- **sky-server**: 核心业务服务模块，提供REST API、业务逻辑、数据访问、任务调度、安全认证等功能
- **mqtt-server**: MQTT消息处理模块，负责MQTT消息的订阅、接收与数据处理桥接

### 架构分层

- **表示层 (Controller)**: 作为系统的HTTP API入口，负责接收来自前端应用或硬件设备的请求。包括DrillingDataController、FullPerformanceController、AlertsController、EmployeeController等
- **业务逻辑层 (Service)**: 实现所有核心业务逻辑，负责编排和组合多个数据访问层的操作，处理复杂的业务规则。包括钻井数据处理、全性能分析、预警管理、员工管理等
- **数据访问层 (Mapper/DAO)**: 负责与MySQL数据库进行持久化交互，使用MyBatis Plus作为ORM框架
- **实体/模型层 (Entity/DTO/VO)**: 包含系统中流转的各类Java对象，包括DrillingData、Density、ModbusData等实体类
- **任务调度层 (Task)**: 利用XXL-Job分布式任务调度框架和Spring内置的@Scheduled注解，执行各类定时或周期性任务
- **安全与认证 (Security)**: 基于Spring Security和JWT实现用户认证与授权
- **切面编程 (Aspect)**: 自定义注解@OperationLog和@AutoFill，实现操作日志记录和字段自动填充

## 核心功能

### 钻井数据管理
- 实时数据采集与存储（通过MQTT订阅和Modbus通信）
- 历史数据查询与分析
- 多维度数据筛选与可视化
- 钻井工程参数管理（EgineeringParameters）
- 井位信息管理（Well、Location）

### 钻井液参数分析
- 全性能参数实时监控（FullPerformance）
- 密度参数监控与分析（Density）
- 钙离子污染智能判定
- 二氧化碳侵入污染检测
- 钻井液长效稳定性评估

### 预警与日志
- 多级别预警管理（Alerts）
- 污染警报自动生成（PollutionAlarmLog）
- 预警状态跟踪与处理
- 历史警报日志查询
- 操作日志记录（OperationLog，基于AOP自动记录）

### 实时通信
- WebSocket实时消息推送
- Modbus数据实时推送（ModbusDataWebSocketHandler）
- 预警信息即时通知
- 系统状态心跳监测

### 员工与权限管理
- 员工信息管理（Employee）
- JWT令牌认证
- 基于Spring Security的访问控制

## 技术栈

- **核心框架**: Spring Boot 2.7.3
- **数据库**: MySQL 8.0
- **数据访问**: MyBatis Plus 3.5.3.1, Druid 1.2.1 (数据库连接池)
- **缓存**: Redis (用于缓存热点数据、存储临时状态和分布式锁)
- **实时通信**: Spring WebSocket, MQTT客户端
- **任务调度**: XXL-Job 2.3.0 (用于分布式定时任务调度)
- **安全认证**: Spring Security + JWT
- **API文档**: Knife4j 3.0.2 (基于OpenAPI/Swagger的自动化API文档生成工具)
- **工具库**: Hutool 5.8.26, Lombok 1.18.20
- **开发语言**: Java 11

## 项目结构

```
sky-chuanqin/
├── sky-common/                    # 公共组件模块
│   └── src/main/java/com/kira/common/
│       ├── config/                # 配置类（MybatisPlus、Redis、WebSocket）
│       ├── constant/              # 常量定义
│       ├── context/               # 上下文（BaseContext）
│       ├── enumeration/           # 枚举类
│       ├── exception/             # 自定义异常
│       ├── handler/               # 处理器（WebSocketHandler）
│       ├── pojo/                  # 实体类（DrillingData、Density、ModbusData等）
│       ├── result/                # 结果封装（Result、PageResult）
│       ├── service/               # 共享服务接口
│       └── websocket/             # WebSocket服务端
├── sky-server/                    # 核心业务服务模块
│   └── src/main/java/com/kira/server/
│       ├── annotation/            # 自定义注解（@AutoFill、@OperationLog）
│       ├── aspect/                # AOP切面
│       ├── config/                # 配置类（Jwt、Security、ThreadPool、XxlJob、Knife4j）
│       ├── controller/            # 控制器层
│       ├── domain/                # 领域模型
│       ├── handler/               # 处理器
│       ├── interceptor/           # 拦截器
│       ├── mapper/                # 数据访问层
│       ├── properties/            # 配置属性类
│       ├── security/              # 安全相关
│       ├── service/               # 业务逻辑层
│       ├── task/                  # 定时任务
│       └── SkyApplication.java    # 启动类
├── mqtt-server/                   # MQTT消息处理模块
│   └── src/main/java/com/kira/mqtt/
│       ├── config/                # MQTT配置
│       ├── domain/dto/            # 数据传输对象
│       ├── mapper/                # 数据访问层
│       ├── mqtt/                  # MQTT客户端实现
│       ├── service/               # 消息处理服务
│       └── MqttApplication.java   # 启动类
└── docs/interview/                # 项目文档
    ├── AGENTS.md
    ├── KAFKA_MIGRATION_PLAN.md
    └── OPERATION_LOG_IMPLEMENTATION_SUMMARY.md
```

## 代码统计

- **Java源文件总数**: 153个文件
- **Java代码总行数**: 约11,797行
- **平均每个文件行数**: 约77行

## 服务端口

- **sky-server**: 18080
- **mqtt-server**: 18081

## 环境要求

- JDK 11+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+
- MQTT Broker (如EMQX、Mosquitto)

## 环境要求

- JDK 11+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+

## 快速开始

### 1. 克隆仓库

```bash
git clone https://github.com/your-username/sky-chuanqin.git
cd sky-chuanqin
```

### 2. 配置数据库

在`sky-server/src/main/resources/application-dev.yml`文件中配置数据库连接信息（通过环境变量或直接配置）：

```yaml
sky:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    host: localhost
    port: 3306
    database: sky_chuanqin
    username: your_username
    password: your_password
```

### 3. 配置Redis

```yaml
sky:
  redis:
    host: 127.0.0.1
    port: 6379
    password: # 如果有密码，请填写
    database: 0
```

### 4. 配置MQTT

在`mqtt-server/src/main/resources/application-dev.yml`中配置MQTT连接信息：

```yaml
mqtt:
  host: tcp://your-mqtt-broker:1883
  username: your_username
  password: your_password
  topic: sky/test1/#
```

### 5. 编译与运行

```bash
# 构建所有模块
mvn clean package -DskipTests

# 运行主服务
java -jar sky-server/target/sky-server.jar

# 运行MQTT服务
java -jar mqtt-server/target/mqtt-server.jar
```

或使用Maven直接运行：

```bash
# 运行主服务
mvn spring-boot:run -pl sky-server

# 运行MQTT服务
mvn spring-boot:run -pl mqtt-server
```

### 6. 运行测试

```bash
# 运行所有模块测试
mvn clean verify

# 运行单个模块测试
mvn test -pl sky-server
mvn test -pl mqtt-server
```

## API文档

启动应用后，访问以下URL查看API文档：

- **主服务API文档**: http://localhost:18080/doc.html
- **MQTT服务API文档**: http://localhost:18081/doc.html

## 开发规范

### 编码风格
- Java: 4空格缩进，大括号同行，避免通配符导入
- 使用Lombok减少样板代码
- Controller响应统一使用`Result<>`包装
- 接口命名以`I`开头，实现类以`ServiceImpl`结尾
- REST路径使用小写kebab-case（如`/alerts`、`/location`）

### 提交规范
- Git提交信息使用简洁的现在时态摘要
- 每次提交保持一个聚焦的变更
- 避免提交敏感信息，使用环境变量或profile-specific配置

### 文档说明
- 项目详细说明见 `docs/interview/AGENTS.md`
- Kafka迁移计划见 `docs/interview/KAFKA_MIGRATION_PLAN.md`
- 操作日志实现总结见 `docs/interview/OPERATION_LOG_IMPLEMENTATION_SUMMARY.md`

## 许可证

[MIT License](LICENSE)

## 联系方式

如有问题或建议，请通过以下方式联系：
- 提交Issue
- 发送Pull Request
- 项目文档: 见 `docs/interview/` 目录
