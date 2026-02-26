# SpringBoot AI 集成实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标：** 集成 SpringBoot 后端与 FastAPI Agent 服务，实现流式对话和诊断功能（使用 SSE）。

**架构：** 混合 Spring Web + WebFlux 架构。SpringBoot 作为中间层处理 JWT 认证和 SSE 转发，FastAPI 处理 LLM/Agent/RAG 能力。

**技术栈：** Spring Boot 2.x, WebFlux, WebClient, SSE, JWT, MySQL, Redis

---

## 前置条件

- 已安装 Java 11
- 已安装 Maven 3.6+
- FastAPI Agent 服务运行在 `http://localhost:8000`（或配置了 AGENT_BASE_URL）
- MySQL 数据库运行中

---

## 第一阶段：基础架构（依赖与配置）

### 任务 1：添加 WebFlux 依赖

**文件：**
- 修改：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/pom.xml`

**步骤 1：在 pom.xml 中添加 WebFlux 依赖**

在 `<dependencies>` 部分内添加以下依赖：

```xml
<!-- WebFlux for SSE streaming support -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

将其添加在现有的 `spring-boot-starter-web` 依赖之后（约第 57 行）。

**步骤 2：验证 Maven 依赖解析**

运行：`mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-starter-webflux`
预期：输出显示 `spring-boot-starter-webflux` 依赖树

**步骤 3：提交**

```bash
git add sky-server/pom.xml
git commit -m "feat: 添加 WebFlux 依赖以支持 SSE 流式传输"
```

---

### 任务 2：添加 Agent 服务配置

**文件：**
- 修改：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/resources/application.yml`

**步骤 1：在 application.yml 末尾添加 agent 配置**

在文件末尾（`thread-pool` 部分之后）添加以下配置：

```yaml
# FastAPI Agent 服务配置
agent:
  base-url: ${AGENT_BASE_URL:http://localhost:8000}
  api-key: ${AGENT_API_KEY:test-key}
  timeout:
    connect: 5000
    read: 300000    # 5分钟，诊断分析可能较长
```

**步骤 2：验证应用能够加载配置**

运行：`mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev --logging.level.root=INFO"`
预期：应用启动无配置错误
停止：`Ctrl+C`

**步骤 3：提交**

```bash
git add sky-server/src/main/resources/application.yml
git commit -m "feat: 添加 Agent 服务配置"
```

---

### 任务 3：创建 AgentWebClientConfig 配置类

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/config/AgentWebClientConfig.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/config/AgentWebClientConfigTest.java`

```java
package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "agent.base-url=http://test-agent:8000",
    "agent.api-key=test-api-key"
})
class AgentWebClientConfigTest {

    @Autowired
    private WebClient agentWebClient;

    @Test
    void testAgentWebClientBeanExists() {
        assertThat(agentWebClient).isNotNull();
    }

    @Test
    void testAgentWebClientHasCorrectConfiguration() {
        // WebClient 不直接暴露 base URL，但可以验证 bean 存在
        assertThat(agentWebClient).isNotNull();
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=AgentWebClientConfigTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.web.reactive.function.client.WebClient' available"

**步骤 3：创建 AgentWebClientConfig 配置类**

```java
package com.kira.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * FastAPI Agent 服务 WebClient 配置
 * 用于 SSE 流式转发
 */
@Configuration
public class AgentWebClientConfig {

    @Value("${agent.base-url}")
    private String agentBaseUrl;

    @Value("${agent.api-key}")
    private String apiKey;

    /**
     * 配置 Agent 服务 WebClient
     * - 增大内存缓冲区限制（10MB）用于 SSE 流
     * - 设置默认 API Key 认证头
     * - 设置默认 Content-Type
     */
    @Bean
    public WebClient agentWebClient() {
        // 增大内存缓冲区限制（10MB）用于 SSE 流式数据
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(agentBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-API-Key", apiKey)
                .exchangeStrategies(strategies)
                .build();
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=AgentWebClientConfigTest`
预期：通过

**步骤 5：验证应用能启动**

运行：`mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.root=ERROR"`
预期：应用启动无错误
停止：`Ctrl+C`

**步骤 6：提交**

```bash
git add sky-server/src/main/java/com/kira/server/config/AgentWebClientConfig.java
git add sky-server/src/test/java/com/kira/server/config/AgentWebClientConfigTest.java
git commit -m "feat: 创建 AgentWebClientConfig 用于 SSE 转发"
```

---

## 第二阶段：核心 AI 包结构（DTOs）

### 任务 4：创建 ChatRequest DTO

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/dto/ChatRequest.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/dto/ChatRequestTest.java`

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ChatRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatRequestSerialization() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");
        request.setSessionId("session-123");
        request.setStream(true);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"message\":\"你好\"");
        assertThat(json).contains("\"session_id\":\"session-123\"");
        assertThat(json).contains("\"stream\":true");
    }

    @Test
    void testChatRequestDeserialization() throws Exception {
        String json = "{\"message\":\"test\",\"session_id\":\"abc\",\"stream\":false}";

        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        assertThat(request.getMessage()).isEqualTo("test");
        assertThat(request.getSessionId()).isEqualTo("abc");
        assertThat(request.getStream()).isFalse();
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=ChatRequestTest`
预期：失败，显示 "package com.kira.server.controller.ai.dto does not exist"

**步骤 3：创建 ChatRequest DTO**

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * AI 对话请求 DTO
 * 用于转发到 FastAPI Agent 服务
 */
@Data
public class ChatRequest {

    /**
     * 用户消息内容
     */
    private String message;

    /**
     * 会话ID，用于保持上下文
     */
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 是否流式返回
     */
    private Boolean stream = true;
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=ChatRequestTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/dto/ChatRequest.java
git add sky-server/src/test/java/com/kira/server/controller/ai/dto/ChatRequestTest.java
git commit -m "feat: 添加 ChatRequest DTO 用于 AI 对话"
```

---

### 任务 5：创建 DiagnosisRequest DTO

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/dto/DiagnosisRequest.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/dto/DiagnosisRequestTest.java`

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DiagnosisRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDiagnosisRequestSerialization() throws Exception {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId("well-001");
        request.setAlertType("HIGH_DENSITY");
        request.setStream(true);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"well_id\":\"well-001\"");
        assertThat(json).contains("\"alert_type\":\"HIGH_DENSITY\"");
    }

    @Test
    void testDiagnosisRequestWithAlertThreshold() throws Exception {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId("well-001");

        DiagnosisRequest.AlertThreshold threshold = new DiagnosisRequest.AlertThreshold();
        threshold.setField("density");
        threshold.setCondition(">");
        threshold.setThreshold(1.5);
        threshold.setCurrentValue(1.8);

        request.setAlertThreshold(threshold);

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"field\":\"density\"");
        assertThat(json).contains("\"currentValue\":1.8");
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=DiagnosisRequestTest`
预期：失败，显示 "package com.kira.server.controller.ai.dto does not exist"

**步骤 3：创建 DiagnosisRequest DTO**

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 诊断分析请求 DTO
 * 用于触发钻井液异常智能诊断分析
 */
@Data
public class DiagnosisRequest {

    /**
     * 井号ID
     */
    @JsonProperty("well_id")
    private String wellId;

    /**
     * 预警类型
     */
    @JsonProperty("alert_type")
    private String alertType;

    /**
     * 预警触发时间
     */
    @JsonProperty("alert_triggered_at")
    private LocalDateTime alertTriggeredAt;

    /**
     * 预警阈值信息
     */
    @JsonProperty("alert_threshold")
    private AlertThreshold alertThreshold;

    /**
     * 钻井液样本数据列表
     */
    private List<DrillingFluidSample> samples;

    /**
     * 诊断上下文信息
     */
    private DiagnosisContext context;

    /**
     * 回调URL（用于异步结果通知）
     */
    @JsonProperty("callback_url")
    private String callbackUrl;

    /**
     * 是否流式返回
     */
    private Boolean stream = true;

    /**
     * 预警阈值信息
     */
    @Data
    public static class AlertThreshold {
        private String field;
        private String condition;
        private Double threshold;
        private Double currentValue;
    }

    /**
     * 钻井液样本数据
     */
    @Data
    public static class DrillingFluidSample {
        private String id;
        private String wellId;
        private LocalDateTime sampleTime;
        private String formation;
        private Double outletTemp;
        private Double density;
        private Double gel10s;
        private Double gel10m;
        private Double rpm3;
        private Double rpm6;
        private Double rpm100;
        private Double rpm200;
        private Double rpm300;
        private Double rpm600;
        private Double plasticViscosity;
        private Double yieldPoint;
        private Double flowBehaviorIndex;
        private Double consistencyCoefficient;
        private Double apparentViscosity;
        private Double yieldPlasticRatio;
    }

    /**
     * 诊断上下文
     */
    @Data
    public static class DiagnosisContext {
        private Double currentDepth;
        private String formationType;
        private String drillingPhase;
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=DiagnosisRequestTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/dto/DiagnosisRequest.java
git add sky-server/src/test/java/com/kira/server/controller/ai/dto/DiagnosisRequestTest.java
git commit -m "feat: 添加 DiagnosisRequest DTO 用于 AI 诊断"
```

---

### 任务 6：创建 SSEEvent DTO

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/dto/SSEEvent.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/dto/SSEEventTest.java`

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SSEEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatSSEEventStart() throws Exception {
        SSEEvent.ChatSSEEvent event = new SSEEvent.ChatSSEEvent();
        event.setType(SSEEventType.START);
        event.setSessionId("session-123");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"start\"");
        assertThat(json).contains("\"session_id\":\"session-123\"");
    }

    @Test
    void testChatSSEEventToken() throws Exception {
        SSEEvent.ChatSSEEvent event = new SSEEvent.ChatSSEEvent();
        event.setType(SSEEventType.TOKEN);
        event.setContent("你好");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"token\"");
        assertThat(json).contains("\"content\":\"你好\"");
    }

    @Test
    void testDiagnosisSSEEvent() throws Exception {
        SSEEvent.DiagnosisSSEEvent event = new SSEEvent.DiagnosisSSEEvent();
        event.setType(SSEEventType.DIAGNOSIS);
        event.setTaskId("task-001");
        event.setContent("分析完成");

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"diagnosis\"");
        assertThat(json).contains("\"task_id\":\"task-001\"");
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=SSEEventTest`
预期：失败，显示 "package com.kira.server.controller.ai.dto does not exist"

**步骤 3：创建 SSEEventType 枚举**

```java
package com.kira.server.controller.ai.dto;

/**
 * SSE 事件类型枚举
 */
public enum SSEEventType {
    // 通用事件
    START,
    END,
    ERROR,

    // 对话事件
    TOKEN,
    TOOL_CALL,
    TOOL_RESULT,

    // 诊断事件
    THINKING,
    TREND_ANALYSIS,
    RETRIEVAL,
    DIAGNOSIS,
    PRESCRIPTION,
    RESULT,
    DONE
}
```

**步骤 4：创建 SSEEvent DTO 及内部类**

```java
package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * SSE 事件 DTO
 * 包含对话和诊断两种类型的 SSE 事件
 */
@Data
public class SSEEvent {

    /**
     * 对话 SSE 事件
     */
    @Data
    public static class ChatSSEEvent {
        private SSEEventType type;
        @JsonProperty("session_id")
        private String sessionId;
        private String content;
        @JsonProperty("tool_data")
        private ToolData toolData;
        @JsonProperty("error_code")
        private String errorCode;
    }

    /**
     * 诊断 SSE 事件
     */
    @Data
    public static class DiagnosisSSEEvent {
        private SSEEventType type;
        @JsonProperty("task_id")
        private String taskId;
        @JsonProperty("well_id")
        private String wellId;
        private String content;
        private String step;
    }

    /**
     * 工具调用数据
     */
    @Data
    public static class ToolData {
        @JsonProperty("call_id")
        private String callId;
        private String name;
        private Map<String, Object> arguments;
        private String status; // calling, processing, result
        private String result;
        @JsonProperty("duration_ms")
        private Long durationMs;
    }
}
```

**步骤 5：运行测试验证通过**

运行：`mvn test -Dtest=SSEEventTest`
预期：通过

**步骤 6：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/dto/SSEEventType.java
git add sky-server/src/main/java/com/kira/server/controller/ai/dto/SSEEvent.java
git add sky-server/src/test/java/com/kira/server/controller/ai/dto/SSEEventTest.java
git commit -m "feat: 添加 SSEEvent DTOs 用于流式事件"
```

---

## 第三阶段：核心 AI 服务

### 任务 7：创建 SSEForwardService

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/SSEForwardService.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/service/ai/SSEForwardServiceTest.java`

```java
package com.kira.server.service.ai;

import com.kira.server.config.AgentWebClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "agent.base-url=http://test-agent:8000",
    "agent.api-key=test-key"
})
class SSEForwardServiceTest {

    @Autowired
    private SSEForwardService sseForwardService;

    @Test
    void testServiceBeanExists() {
        assertThat(sseForwardService).isNotNull();
    }

    @Test
    void testForwardSSEMethodExists() {
        // 验证方法存在且签名正确
        // 实际功能将在集成测试中验证
        Flux<String> result = sseForwardService.forwardSSE("/test", null, Duration.ofSeconds(5));
        assertThat(result).isNotNull();
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=SSEForwardServiceTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'com.kira.server.service.ai.SSEForwardService' available"

**步骤 3：创建 SSEForwardService**

```java
package com.kira.server.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SSE 流式转发服务
 * 将 FastAPI 的 SSE 流直接透传给前端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SSEForwardService {

    private final WebClient agentWebClient;

    /**
     * SSE 流式转发
     * 将 FastAPI 的 SSE 流直接转发给前端
     *
     * @param uri     Agent 服务 URI (例如 "/api/v1/chat/stream")
     * @param request 请求体 (可以为 null，用于 GET 请求)
     * @param timeout 超时时间
     * @return SSE 事件流 (data: {json}\n\n 格式)
     */
    public Flux<String> forwardSSE(String uri, Object request, Duration timeout) {
        log.info("转发 SSE 请求到: {}", uri);

        WebClient.RequestHeadersSpec<?> requestSpec = agentWebClient.post()
                .uri(uri);

        if (request != null) {
            requestSpec.bodyValue(request);
        }

        return requestSpec
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(this::dataBufferToString)
                .timeout(timeout)
                .doOnNext(line -> log.debug("转发的 SSE 行: {}", line))
                .doOnError(error -> log.error("SSE 转发错误: {}", error.getMessage(), error))
                .onErrorResume(WebClientRequestException.class, e -> {
                    // Agent 服务不可用时返回友好错误
                    log.error("Agent 服务不可用: {}", e.getMessage());
                    String errorEvent = String.format(
                            "data: {\"type\":\"error\",\"content\":\"Agent 服务暂时不可用，请稍后重试\"}\n\n"
                    );
                    return Flux.just(errorEvent);
                })
                .doOnComplete(() -> log.info("SSE 流完成，URI: {}", uri));
    }

    /**
     * 将 DataBuffer 转换为 String
     */
    private String dataBufferToString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=SSEForwardServiceTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/service/ai/SSEForwardService.java
git add sky-server/src/test/java/com/kira/server/service/ai/SSEForwardServiceTest.java
git commit -m "feat: 创建 SSEForwardService 用于 Agent SSE 流式传输"
```

---

### 任务 8：创建 AgentCallbackService

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/service/ai/AgentCallbackService.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/service/ai/AgentCallbackServiceTest.java`

```java
package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentCallbackServiceTest {

    @Autowired
    private AgentCallbackService agentCallbackService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testServiceBeanExists() {
        assertThat(agentCallbackService).isNotNull();
    }

    @Test
    void testHandleDiagnosisCallback() {
        String json = "{\"task_id\":\"task-001\",\"status\":\"completed\",\"result\":\"test result\"}";
        JsonNode payload = objectMapper.readTree(json);

        // 不应抛出异常
        agentCallbackService.handleDiagnosisCallback(payload);
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=AgentCallbackServiceTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'com.kira.server.service.ai.AgentCallbackService' available"

**步骤 3：创建 AgentCallbackService**

```java
package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 回调服务
 * 接收 FastAPI Agent 的异步回调通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCallbackService {

    private final ObjectMapper objectMapper;

    /**
     * 接收 FastAPI 诊断结果回调
     *
     * @param payload 回调数据
     */
    public void handleDiagnosisCallback(JsonNode payload) {
        String taskId = payload.get("task_id").asText();
        String status = payload.get("status").asText();

        log.info("收到诊断回调: taskId={}, status={}", taskId, status);

        // TODO: 存储到数据库
        // saveDiagnosisResult(taskId, payload);

        // TODO: 通知前端（WebSocket）
        // notifyFrontend(taskId, status);
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=AgentCallbackServiceTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/service/ai/AgentCallbackService.java
git add sky-server/src/test/java/com/kira/server/service/ai/AgentCallbackServiceTest.java
git commit -m "feat: 创建 AgentCallbackService 用于异步回调"
```

---

## 第四阶段：对话控制器（流式对话）

### 任务 9：创建 ChatController

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/ChatController.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/ChatControllerTest.java`

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.ChatRequest;
import com.kira.server.service.ai.SSEForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
class ChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SSEForwardService sseForwardService;

    @Test
    void testChatStreamEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        webTestClient.post()
            .uri("/api/ai/chat/stream")
            .bodyValue(new ChatRequest())
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testChatStreamReturnsSSEContentType() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        webTestClient.post()
            .uri("/api/ai/chat/stream")
            .bodyValue(new ChatRequest())
            .exchange()
            .expectHeader().contentType("text/event-stream");
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=ChatControllerTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'com.kira.server.controller.ai.ChatController' available"

**步骤 3：创建 ChatController**

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.ChatRequest;
import com.kira.server.service.ai.SSEForwardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 对话控制器
 * 处理流式对话请求，SSE 转发到 FastAPI Agent 服务
 */
@Api(tags = "AI对话接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SSEForwardService sseForwardService;

    /**
     * 流式对话 - SSE 转发
     * Vue 前端通过 Fetch API + ReadableStream 接收
     *
     * @param request 对话请求
     * @return SSE 事件流
     */
    @ApiOperation("流式对话")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("对话流式请求: sessionId={}, message={}",
                request.getSessionId(), request.getMessage());

        // 转发到 FastAPI，返回 SSE 流
        return sseForwardService.forwardSSE(
                "/api/v1/chat/stream",
                request,
                Duration.ofMinutes(2)
        );
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=ChatControllerTest`
预期：通过

**步骤 5：验证应用启动**

运行：`mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.root=ERROR"`
预期：应用启动无错误
停止：`Ctrl+C`

**步骤 6：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/ChatController.java
git add sky-server/src/test/java/com/kira/server/controller/ai/ChatControllerTest.java
git commit -m "feat: 添加 ChatController 用于流式对话"
```

---

## 第五阶段：诊断控制器（诊断分析）

### 任务 10：创建 DiagnosisController

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/DiagnosisControllerTest.java`

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.SSEForwardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
class DiagnosisControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private SSEForwardService sseForwardService;

    @Test
    void testDiagnosisAnalyzeEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"start\"}\n\n"));

        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId("well-001");

        webTestClient.post()
            .uri("/api/ai/diagnosis/analyze")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void testDiagnosisGetResultEndpointExists() {
        when(sseForwardService.forwardSSE(any(), any(), any()))
            .thenReturn(Flux.just("data: {\"type\":\"result\"}\n\n"));

        webTestClient.get()
            .uri("/api/ai/diagnosis/task-001")
            .exchange()
            .expectStatus().isOk();
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=DiagnosisControllerTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'com.kira.server.controller.ai.DiagnosisController' available"

**步骤 3：创建 DiagnosisController**

```java
package com.kira.server.controller.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.SSEForwardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 诊断分析控制器
 * 处理钻井液异常智能诊断分析请求
 */
@Api(tags = "AI诊断接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/diagnosis")
@RequiredArgsConstructor
public class DiagnosisController {

    private final SSEForwardService sseForwardService;

    /**
     * 诊断分析 - SSE 转发
     *
     * @param request 诊断请求
     * @return SSE 事件流
     */
    @ApiOperation("诊断分析")
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyze(@RequestBody DiagnosisRequest request) {
        log.info("诊断请求: wellId={}, alertType={}",
                request.getWellId(), request.getAlertType());

        return sseForwardService.forwardSSE(
                "/api/v1/diagnosis/analyze",
                request,
                Duration.ofMinutes(5)
        );
    }

    /**
     * 查询诊断结果
     *
     * @param taskId 任务ID
     * @return SSE 事件流
     */
    @ApiOperation("查询诊断结果")
    @GetMapping(value = "/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> getResult(@PathVariable String taskId) {
        log.info("获取诊断结果: taskId={}", taskId);

        return sseForwardService.forwardSSE(
                "/api/v1/diagnosis/" + taskId,
                null,
                Duration.ofSeconds(30)
        );
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=DiagnosisControllerTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java
git add sky-server/src/test/java/com/kira/server/controller/ai/DiagnosisControllerTest.java
git commit -m "feat: 添加 DiagnosisController 用于 AI 诊断"
```

---

## 第六阶段：回调控制器（异步回调）

### 任务 11：创建 AgentCallbackController

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/controller/ai/AgentCallbackController.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/controller/ai/AgentCallbackControllerTest.java`

```java
package com.kira.server.controller.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.service.ai.AgentCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureWebMvc
class AgentCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentCallbackService agentCallbackService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDiagnosisCallbackEndpoint() throws Exception {
        String json = "{\"task_id\":\"task-001\",\"status\":\"completed\"}";
        JsonNode payload = objectMapper.readTree(json);

        doNothing().when(agentCallbackService).handleDiagnosisCallback(any());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/ai/callback/diagnosis")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk());

        verify(agentCallbackService).handleDiagnosisCallback(any());
    }
}
```

**步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=AgentCallbackControllerTest`
预期：失败，显示 "NoSuchBeanDefinitionException: No qualifying bean of type 'com.kira.server.controller.ai.AgentCallbackController' available"

**步骤 3：创建 AgentCallbackController**

```java
package com.kira.server.controller.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.kira.server.service.ai.AgentCallbackService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 回调控制器
 * 接收 FastAPI Agent 的异步回调通知
 */
@Api(tags = "Agent回调接口")
@Slf4j
@RestController
@RequestMapping("/api/ai/callback")
@RequiredArgsConstructor
public class AgentCallbackController {

    private final AgentCallbackService agentCallbackService;

    /**
     * 接收诊断结果回调
     *
     * @param payload 回调数据
     * @return 确认响应
     */
    @ApiOperation("诊断结果回调")
    @PostMapping("/diagnosis")
    public ResponseEntity<String> handleDiagnosisCallback(@RequestBody JsonNode payload) {
        log.info("收到诊断回调: {}", payload);

        try {
            agentCallbackService.handleDiagnosisCallback(payload);
            return ResponseEntity.ok("Callback received");
        } catch (Exception e) {
            log.error("处理诊断回调错误: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Callback processing failed");
        }
    }
}
```

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=AgentCallbackControllerTest`
预期：通过

**步骤 5：提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/AgentCallbackController.java
git add sky-server/src/test/java/com/kira/server/controller/ai/AgentCallbackControllerTest.java
git commit -m "feat: 添加 AgentCallbackController 用于异步回调"
```

---

## 第七阶段：安全配置更新

### 任务 12：更新 SecurityConfig 放行 AI 路径

**文件：**
- 修改：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/main/java/com/kira/server/config/SecurityConfig.java`

**步骤 1：编写测试**

创建测试文件：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/config/SecurityConfigTest.java`

```java
package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureWebMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testAIChatEndpointAccessibleWithoutAuth() throws Exception {
        // 开发期间，AI 端点应该可访问
        mockMvc.perform(MockMvcRequestBuilders.post("/api/ai/chat/stream")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound()); // 404 因为服务未运行，而非 401
    }

    @Test
    void testAIDiagnosisEndpointAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/ai/diagnosis/analyze")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound()); // 404 因为服务未运行，而非 401
    }

    @Test
    void testAICallbackEndpointAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/ai/callback/diagnosis")
                .contentType("application/json")
                .content("{\"task_id\":\"test\"}"))
                .andExpect(status().isNotFound()); // 404 因为服务未运行，而非 401
    }
}
```

**步骤 2：运行测试验证当前状态**

运行：`mvn test -Dtest=SecurityConfigTest`
预期：通过（因为 SecurityConfig 目前使用 `.antMatchers("/**").permitAll()`）

**步骤 3：更新 SecurityConfig 明确放行 AI 端点**

在 `authorizeHttpRequests` 部分添加以下内容，放在 Swagger 端点配置之后：

```java
// AI 端点 (调试期间放行，生产环境需要认证)
.antMatchers(
        "/api/ai/**"
).permitAll()
```

将其添加在第 130 行之后（`/error` permitAll 之后），`.anyRequest().authenticated()` 之前。

**步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=SecurityConfigTest`
预期：通过

**步骤 5：验证应用启动**

运行：`mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.root=ERROR"`
预期：应用启动无安全配置错误
停止：`Ctrl+C`

**步骤 6：提交**

```bash
git add sky-server/src/main/java/com/kira/server/config/SecurityConfig.java
git add sky-server/src/test/java/com/kira/server/config/SecurityConfigTest.java
git commit -m "feat: 更新 SecurityConfig 放行 AI 端点"
```

---

## 第八阶段：集成测试

### 任务 13：创建使用 Mock Agent 的集成测试

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/sky-server/src/test/java/com/kira/server/integration/AgentIntegrationTest.java`

**步骤 1：创建集成测试文件**

```java
package com.kira.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.ai.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Agent 服务集成测试
 *
 * 注意：这些测试需要 FastAPI Agent 服务正在运行
 * 如果服务未运行，测试将跳过或失败
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
class AgentIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testChatStreamIntegration() {
        ChatRequest request = new ChatRequest();
        request.setMessage("你好，请介绍一下钻井液的基本性能");
        request.setStream(true);

        webTestClient.post()
                .uri("/api/ai/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }

    @Test
    void testDiagnosisAnalyzeIntegration() {
        String requestJson = """
            {
                "well_id": "well-001",
                "alert_type": "HIGH_DENSITY",
                "alert_triggered_at": "2026-02-26T10:00:00",
                "stream": true
            }
            """;

        webTestClient.post()
                .uri("/api/ai/diagnosis/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream;charset=UTF-8");
    }
}
```

**步骤 2：运行集成测试（可选 - 需要 Agent 服务运行）**

运行：`mvn test -Dtest=AgentIntegrationTest`
预期：
- 如果 Agent 服务运行中：通过
- 如果 Agent 服务未运行：失败并显示连接拒绝（这是预期的）

**步骤 3：提交**

```bash
git add sky-server/src/test/java/com/kira/server/integration/AgentIntegrationTest.java
git commit -m "test: 添加 Agent 集成测试"
```

---

### 任务 14：创建手动测试指南

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/docs/testing/ai-integration-testing-guide.md`

**步骤 1：创建测试指南文档**

```markdown
# AI 集成测试指南

## 前置条件

1. FastAPI Agent 服务运行在 `http://localhost:8000`
2. SpringBoot 应用运行在端口 `18080`
3. 有效的 JWT 令牌（或使用开发模式的 permitAll）

## 启动服务

### 1. 启动 FastAPI Agent 服务

```bash
cd yibccc-langchain
uv run uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000
```

### 2. 启动 SpringBoot 服务

```bash
cd sky-chuanqin/sky-server
mvn spring-boot:run
```

## 测试端点

### 对话流式测试

```bash
curl -N -X POST http://localhost:18080/api/ai/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好，请介绍一下钻井液的基本性能",
    "stream": true
  }'
```

预期响应：
```
data: {"type":"start","session_id":"..."}
data: {"type":"token","content":"你"}
data: {"type":"token","content":"好"}
...
data: {"type":"end"}
```

### 诊断分析测试

```bash
curl -N -X POST http://localhost:18080/api/ai/diagnosis/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "well_id": "well-001",
    "alert_type": "HIGH_DENSITY",
    "alert_triggered_at": "2026-02-26T10:00:00",
    "stream": true
  }'
```

预期响应：
```
data: {"type":"start","task_id":"..."}
data: {"type":"thinking","content":"分析中..."}
...
data: {"type":"done"}
```

### 获取诊断结果测试

```bash
curl -N -X GET http://localhost:18080/api/ai/diagnosis/{taskId}
```

### 回调测试

```bash
curl -X POST http://localhost:18080/api/ai/callback/diagnosis \
  -H "Content-Type: application/json" \
  -d '{
    "task_id": "task-001",
    "status": "completed",
    "result": "test result"
  }'
```

预期：`Callback received`

## 故障排除

### Agent 服务不可用

错误：`Agent 服务暂时不可用，请稍后重试`

解决方案：
1. 检查 FastAPI Agent 服务是否运行：`curl http://localhost:8000/health`
2. 检查 application.yml 中的 agent.base-url 配置
3. 检查网络连接

### 认证错误

错误：`401 Unauthorized`

解决方案：
1. 检查 JWT 令牌有效性
2. 验证 SecurityConfig 在开发模式下放行 `/api/ai/**`
3. 检查 Authorization 头格式：`Bearer <token>`

### SSE 流超时

错误：流在 2 分钟（对话）或 5 分钟（诊断）后断开

解决方案：
1. 调整 ChatController 和 DiagnosisController 中的超时值
2. 检查 Agent 服务的处理时间
3. 考虑使用回调 URL 进行长时间运行的分析
```

**步骤 2：提交**

```bash
git add docs/testing/ai-integration-testing-guide.md
git commit -m "docs: 添加 AI 集成测试指南"
```

---

## 第九阶段：前端 API 模块

### 任务 15：创建前端 API 模块参考

**文件：**
- 创建：`/Users/kirayang/IdeaProjects/drilling-fluid/sky-chuanqin/docs/frontend/api/ai.ts`（参考）

**步骤 1：创建前端 API 参考文档**

```typescript
/**
 * AI API 模块
 * 用于 Vue 前端调用 SpringBoot AI 接口
 */

// API 基础 URL
const API_BASE = '/api/ai';

// SSE 事件类型定义
export interface ChatSSEEvent {
  type: 'start' | 'token' | 'tool_call' | 'tool_result' | 'end' | 'error'
  session_id?: string
  content?: string
  tool_data?: {
    call_id: string
    name: string
    arguments: Record<string, any>
    status: 'calling' | 'processing' | 'result'
    result?: string
    duration_ms?: number
  }
  error_code?: string
}

export interface DiagnosisSSEEvent {
  type: 'start' | 'thinking' | 'trend_analysis' | 'retrieval'
      | 'diagnosis' | 'prescription' | 'result' | 'done' | 'error'
  task_id?: string
  well_id?: string
  content?: string
  step?: string
}

export interface DiagnosisRequest {
  well_id: string
  alert_type: string
  alert_triggered_at?: string
  alert_threshold?: {
    field: string
    condition: string
    threshold: number
    current_value: number
  }
  samples?: any[]
  context?: {
    current_depth?: number
    formation_type?: string
    drilling_phase?: string
  }
  callback_url?: string
  stream?: boolean
}

/**
 * 流式对话 API
 *
 * @param message 用户消息
 * @param sessionId 会话ID（可选）
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function streamChat(
  message: string,
  sessionId: string | undefined,
  onEvent: (event: ChatSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
    body: JSON.stringify({ message, session_id: sessionId, stream: true }),
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: ChatSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}

/**
 * 诊断分析 API
 *
 * @param request 诊断请求
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function analyzeDiagnosis(
  request: DiagnosisRequest,
  onEvent: (event: DiagnosisSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/diagnosis/analyze`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
    body: JSON.stringify({ ...request, stream: true }),
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: DiagnosisSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}

/**
 * 查询诊断结果 API
 *
 * @param taskId 任务ID
 * @param onEvent SSE 事件回调
 * @param onError 错误回调（可选）
 */
export async function getDiagnosisResult(
  taskId: string,
  onEvent: (event: DiagnosisSSEEvent) => void,
  onError?: (error: string) => void
): Promise<void> {
  const token = localStorage.getItem('token');

  const response = await fetch(`${API_BASE}/diagnosis/${taskId}`, {
    method: 'GET',
    headers: {
      'Authorization': token ? `Bearer ${token}` : '',
    },
  });

  if (!response.ok) {
    const errorMsg = `HTTP ${response.status}: ${response.statusText}`;
    onError?.(errorMsg);
    throw new Error(errorMsg);
  }

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();

  if (!reader) {
    onError?.('Response body is null');
    throw new Error('Response body is null');
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value);
      const lines = chunk.split('\n');

      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const event: DiagnosisSSEEvent = JSON.parse(line.slice(6));
            onEvent(event);
          } catch (e) {
            console.error('Failed to parse SSE:', line, e);
          }
        }
      }
    }
  } catch (e) {
    onError?.(e instanceof Error ? e.message : String(e));
  } finally {
    reader.releaseLock();
  }
}
```

**步骤 2：提交**

```bash
git add docs/frontend/api/ai.ts
git commit -m "docs: 添加前端 AI API 参考"
```

---

## 第十阶段：最终验证

### 任务 16：最终构建和验证

**文件：**
- 验证所有更改

**步骤 1：运行所有单元测试**

运行：`mvn test`
预期：所有测试通过

**步骤 2：构建项目**

运行：`mvn clean package -DskipTests`
预期：构建成功，在 `target/` 中创建 jar 文件

**步骤 3：验证应用启动**

运行：`java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar --spring.profiles.active=dev`
预期：应用启动无错误
停止：`Ctrl+C`

**步骤 4：创建总结文档**

```markdown
# AI 集成实施总结

## 已实现内容

### 1. 基础架构
- WebFlux 依赖用于 SSE 流式传输
- application.yml 中的 Agent 服务配置
- AgentWebClientConfig 用于连接 FastAPI Agent 的 HTTP 客户端

### 2. DTOs
- ChatRequest: AI 对话请求 DTO
- DiagnosisRequest: 诊断分析请求 DTO
- SSEEvent: SSE 事件类型 (ChatSSEEvent, DiagnosisSSEEvent)
- SSEEventType: 事件类型枚举

### 3. 服务
- SSEForwardService: 核心 SSE 流式转发服务
- AgentCallbackService: 处理来自 Agent 的异步回调

### 4. 控制器
- ChatController: POST /api/ai/chat/stream 用于流式对话
- DiagnosisController: POST /api/ai/diagnosis/analyze, GET /api/ai/diagnosis/{taskId}
- AgentCallbackController: POST /api/ai/callback/diagnosis 用于异步回调

### 5. 配置
- SecurityConfig 更新为放行 AI 端点

### 6. 测试
- 所有组件的单元测试
- 集成测试
- 测试指南文档

### 7. 前端
- Vue 前端集成的 API 参考

## API 端点

| 方法 | 路径 | 描述 |
|--------|------|-------------|
| POST | /api/ai/chat/stream | 流式对话 (SSE) |
| POST | /api/ai/diagnosis/analyze | 诊断分析 (SSE) |
| GET | /api/ai/diagnosis/{taskId} | 获取诊断结果 (SSE) |
| POST | /api/ai/callback/diagnosis | Agent 回调端点 |

## 后续步骤

1. 实现诊断结果的数据库存储
2. 添加 WebSocket 通知完成的诊断
3. 实现前端 Vue 组件
4. 添加监控和指标
5. 配置生产环境部署

## 需要的配置

添加到环境变量：
```
AGENT_BASE_URL=http://your-agent-service:8000
AGENT_API_KEY=your-api-key
```
```

**步骤 5：最终提交总结**

```bash
git add docs/
git commit -m "docs: 添加 AI 集成实施总结"
```

---

## 任务总结

本实施计划包含 **16 个任务**，组织成 **10 个阶段**：

1. 任务 1: 添加 WebFlux 依赖
2. 任务 2: 添加 Agent 服务配置
3. 任务 3: 创建 AgentWebClientConfig
4. 任务 4: 创建 ChatRequest DTO
5. 任务 5: 创建 DiagnosisRequest DTO
6. 任务 6: 创建 SSEEvent DTO
7. 任务 7: 创建 SSEForwardService
8. 任务 8: 创建 AgentCallbackService
9. 任务 9: 创建 ChatController
10. 任务 10: 创建 DiagnosisController
11. 任务 11: 创建 AgentCallbackController
12. 任务 12: 更新 SecurityConfig
13. 任务 13: 创建集成测试
14. 任务 14: 创建测试指南
15. 任务 15: 创建前端 API 参考
16. 任务 16: 最终验证

每个任务遵循 TDD 原则：
1. 编写失败的测试
2. 运行测试验证失败
3. 编写最小实现
4. 运行测试验证通过
5. 提交更改

---

**总预估时间**: 4-6 小时完整实施

**所需技能**:
- Java 11
- Spring Boot (Web + WebFlux)
- 响应式编程 (Project Reactor)
- SSE (Server-Sent Events)
- JWT 认证
- Maven

---

**计划版本**: 1.0.0
**创建日期**: 2026-02-26
**设计文档参考**: 2026-02-25-springboot-ai-integration-design.md
