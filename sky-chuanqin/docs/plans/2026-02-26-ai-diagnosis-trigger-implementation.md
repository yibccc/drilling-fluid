# XXL-Job 触发 AI 诊断功能实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 当 XXL-Job 定时任务检测到钻井液异常时，自动触发 AI 诊断分析，缓存结果并通过 WebSocket 推送预警，前端可通过 SSE 获取流式诊断结果。

**架构:** XXL-Job 检测异常 → 同步调用 AI 诊断 → Redis 缓存结果 → WebSocket 推送预警 → 前端通过 SSE 查询缓存结果

**技术栈:** SpringBoot 2.7.3, XXL-Job, Spring WebFlux, Redis, WebSocket, FastAPI Agent Service

---

## Task 1: 添加诊断配置类

**文件:**
- 创建: `sky-server/src/main/java/com/kira/server/config/DiagnosisProperties.java`

**Step 1: 创建诊断配置属性类**

```java
package com.kira.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 诊断配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "diagnosis")
public class DiagnosisProperties {

    /**
     * 预警信息 TTL（分钟）
     */
    private int alertCacheTtl = 15;

    /**
     * 诊断结果 TTL（分钟）
     */
    private int resultCacheTtl = 15;

    /**
     * 诊断超时时间（分钟）
     */
    private int timeoutMinutes = 5;

    /**
     * 是否启用 AI 诊断
     */
    private boolean enabled = true;
}
```

**Step 2: 启用配置属性处理**

在 `sky-server/src/main/resources/application.yml` 添加：

```yaml
# AI 诊断配置
diagnosis:
  alert-cache-ttl: 15
  result-cache-ttl: 15
  timeout-minutes: 5
  enabled: true
```

**Step 3: 添加启用注解到启动类**

修改 `sky-server/src/main/java/com/kira/server/SkyServerApplication.java`：

```java
@SpringBootApplication
@EnableConfigurationProperties(DiagnosisProperties.class)  // 新增
public class SkyServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkyServerApplication.class, args);
    }
}
```

**Step 4: 编写配置类单元测试**

创建 `sky-server/src/test/java/com/kira/server/config/DiagnosisPropertiesTest.java`：

```java
package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DiagnosisPropertiesTest {

    @Autowired
    private DiagnosisProperties properties;

    @Test
    void testDefaultValues() {
        assertEquals(15, properties.getAlertCacheTtl());
        assertEquals(15, properties.getResultCacheTtl());
        assertEquals(5, properties.getTimeoutMinutes());
        assertTrue(properties.isEnabled());
    }
}
```

**Step 5: 运行测试验证**

运行: `mvn test -Dtest=DiagnosisPropertiesTest`
预期: PASS

**Step 6: 提交**

```bash
git add sky-server/src/main/java/com/kira/server/config/DiagnosisProperties.java
git add sky-server/src/main/resources/application.yml
git add sky-server/src/main/java/com/kira/server/SkyServerApplication.java
git add sky-server/src/test/java/com/kira/server/config/DiagnosisPropertiesTest.java
git commit -m "feat: 添加 AI 诊断配置属性类

- 添加 DiagnosisProperties 配置类
- 支持配置 TTL、超时时间和功能开关
- 添加配置类单元测试"
```

---

## Task 2: 创建 Redis 缓存服务

**文件:**
- 创建: `sky-server/src/main/java/com/kira/server/service/ai/DiagnosisCacheService.java`
- 测试: `sky-server/src/test/java/com/kira/server/service/ai/DiagnosisCacheServiceTest.java`

**Step 1: 编写失败的测试**

创建 `sky-server/src/test/java/com/kira/server/service/ai/DiagnosisCacheServiceTest.java`：

```java
package com.kira.server.service.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DiagnosisCacheServiceTest {

    @Autowired
    private DiagnosisCacheService cacheService;

    @Autowired
    private RedisTemplate redisTemplate;

    private static final String TEST_ALERT_ID = "TEST-ALERT-123";

    @AfterEach
    void cleanup() {
        redisTemplate.delete("diagnosis:" + TEST_ALERT_ID);
        redisTemplate.delete("alert:" + TEST_ALERT_ID);
    }

    @Test
    void testSaveDiagnosisResult() {
        String result = "data: {\"type\":\"test\"}\\n\\n";

        cacheService.saveDiagnosisResult(TEST_ALERT_ID, result);

        String cached = (String) redisTemplate.opsForValue().get("diagnosis:" + TEST_ALERT_ID);
        assertEquals(result, cached);
    }

    @Test
    void testGetDiagnosisResult() {
        String result = "data: {\"type\":\"test\"}\\n\\n";
        redisTemplate.opsForValue().set("diagnosis:" + TEST_ALERT_ID, result, Duration.ofMinutes(15));

        String cached = cacheService.getDiagnosisResult(TEST_ALERT_ID);
        assertEquals(result, cached);
    }

    @Test
    void testGetNonExistentResult() {
        String cached = cacheService.getDiagnosisResult("NON-EXISTENT");
        assertNull(cached);
    }
}
```

**Step 2: 运行测试验证失败**

运行: `mvn test -Dtest=DiagnosisCacheServiceTest`
预期: FAIL with "DiagnosisCacheService not found"

**Step 3: 实现缓存服务**

创建 `sky-server/src/main/java/com/kira/server/service/ai/DiagnosisCacheService.java`：

```java
package com.kira.server.service.ai;

import com.kira.server.config.DiagnosisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 诊断结果缓存服务
 * 缓存 AI 诊断结果供前端查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DiagnosisProperties properties;

    private static final String DIAGNOSIS_PREFIX = "diagnosis:";
    private static final String ALERT_PREFIX = "alert:";

    /**
     * 保存诊断结果
     */
    public void saveDiagnosisResult(String alertId, String diagnosisResult) {
        String key = DIAGNOSIS_PREFIX + alertId;
        redisTemplate.opsForValue().set(
                key,
                diagnosisResult,
                Duration.ofMinutes(properties.getResultCacheTtl())
        );
        log.info("已缓存诊断结果: alertId={}", alertId);
    }

    /**
     * 获取诊断结果
     */
    public String getDiagnosisResult(String alertId) {
        String key = DIAGNOSIS_PREFIX + alertId;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 保存预警信息
     */
    public void saveAlertInfo(String alertId, String alertInfo) {
        String key = ALERT_PREFIX + alertId;
        redisTemplate.opsForValue().set(
                key,
                alertInfo,
                Duration.ofMinutes(properties.getAlertCacheTtl())
        );
        log.info("已缓存预警信息: alertId={}", alertId);
    }

    /**
     * 获取预警信息
     */
    public String getAlertInfo(String alertId) {
        String key = ALERT_PREFIX + alertId;
        return redisTemplate.opsForValue().get(key);
    }
}
```

**Step 4: 运行测试验证通过**

运行: `mvn test -Dtest=DiagnosisCacheServiceTest`
预期: PASS

**Step 5: 提交**

```bash
git add sky-server/src/main/java/com/kira/server/service/ai/DiagnosisCacheService.java
git add sky-server/src/test/java/com/kira/server/service/ai/DiagnosisCacheServiceTest.java
git commit -m "feat: 添加诊断结果缓存服务

- 实现 Redis 缓存服务存储诊断结果
- 支持按 alertId 读写缓存
- 可配置 TTL"
```

---

## Task 3: 扩展 DiagnosisController 添加缓存查询端点

**文件:**
- 修改: `sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java:53-64`
- 测试: `sky-server/src/test/java/com/kira/server/controller/ai/DiagnosisControllerTest.java`

**Step 1: 编写失败的测试**

修改 `sky-server/src/test/java/com/kira/server/controller/ai/DiagnosisControllerTest.java`：

```java
package com.kira.server.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.service.ai.DiagnosisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DiagnosisCacheService cacheService;

    private static final String TEST_ALERT_ID = "TEST-ALERT-456";
    private static final String CACHED_RESULT = "data: {\"type\":\"diagnosis\",\"content\":\"测试结果\"}\\n\\n";

    @BeforeEach
    void setup() {
        cacheService.saveDiagnosisResult(TEST_ALERT_ID, CACHED_RESULT);
    }

    @Test
    void testGetCachedDiagnosis() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/ai/diagnosis/stream")
                        .param("alertId", TEST_ALERT_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/event-stream;charset=UTF-8"));
    }

    @Test
    void testGetNonExistentDiagnosis() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/ai/diagnosis/stream")
                        .param("alertId", "NON-EXISTENT")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/event-stream;charset=UTF-8"));
    }
}
```

**Step 2: 运行测试验证失败**

运行: `mvn test -Dtest=DiagnosisControllerTest`
预期: FAIL with 404 or endpoint not found

**Step 3: 添加缓存查询端点**

修改 `sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java`，在类末尾添加：

```java
@Autowired
private DiagnosisCacheService diagnosisCacheService;  // 新增依赖注入

// 在 getResult 方法后添加新方法

/**
 * 查询已有诊断结果（SSE 流式返回缓存的结果）
 * 前端收到 WebSocket 预警后调用此接口
 *
 * @param alertId 预警ID
 * @return SSE 事件流
 */
@ApiOperation("查询缓存诊断结果")
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> getCachedDiagnosis(@RequestParam String alertId) {
    log.info("查询缓存诊断结果: alertId={}", alertId);

    // 从 Redis 获取诊断结果
    String cachedResult = diagnosisCacheService.getDiagnosisResult(alertId);

    if (cachedResult == null) {
        log.warn("诊断结果不存在或已过期: alertId={}", alertId);
        return Flux.just(
                "data: {\"type\":\"error\",\"content\":\"诊断结果不存在或已过期\"}\\n\\n"
        );
    }

    log.info("返回缓存的诊断结果: alertId={}, length={}", alertId, cachedResult.length());
    // 将缓存的 SSE 内容重新作为流返回
    return Flux.fromArray(cachedResult.split("\\n"));
}
```

**Step 4: 运行测试验证通过**

运行: `mvn test -Dtest=DiagnosisControllerTest`
预期: PASS

**Step 5: 提交**

```bash
git add sky-server/src/main/java/com/kira/server/controller/ai/DiagnosisController.java
git add sky-server/src/test/java/com/kira/server/controller/ai/DiagnosisControllerTest.java
git commit -m "feat: DiagnosisController 添加缓存查询端点

- 新增 GET /api/ai/diagnosis/stream?alertId=xxx
- 从 Redis 读取缓存的诊断结果
- 以 SSE 格式流式返回"
```

---

## Task 4: 创建 AI 诊断触发服务

**文件:**
- 创建: `sky-server/src/main/java/com/kira/server/service/ai/AiDiagnosisTriggerService.java`
- 测试: `sky-server/src/test/java/com/kira/server/service/ai/AiDiagnosisTriggerServiceTest.java`

**Step 1: 编写失败的测试**

创建 `sky-server/src/test/java/com/kira/server/service/ai/AiDiagnosisTriggerServiceTest.java`：

```java
package com.kira.server.service.ai;

import com.kira.server.config.DiagnosisProperties;
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
class AiDiagnosisTriggerServiceTest {

    @Autowired
    private AiDiagnosisTriggerService triggerService;

    @MockBean
    private SSEForwardService sseForwardService;

    @MockBean
    private DiagnosisCacheService cacheService;

    @Test
    void testTriggerDiagnosisWhenEnabled() {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId("test-well");
        request.setAlertType("TEST_ALERT");
        request.setAlertTriggeredAt(LocalDateTime.now());
        request.setStream(true);

        triggerService.triggerDiagnosis("ALERT-001", "test-well", "钙污染", request);

        verify(sseForwardService).forwardSSE(eq("/api/v1/diagnosis/analyze"), any(), any());
    }
}
```

**Step 2: 运行测试验证失败**

运行: `mvn test -Dtest=AiDiagnosisTriggerServiceTest`
预期: FAIL with class not found

**Step 3: 实现触发服务**

创建 `sky-server/src/main/java/com/kira/server/service/ai/AiDiagnosisTriggerService.java`：

```java
package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.config.DiagnosisProperties;
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 诊断触发服务
 * 当 XXL-Job 检测到异常时，调用此服务触发 AI 诊断
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnosisTriggerService {

    private final SSEForwardService sseForwardService;
    private final DiagnosisCacheService cacheService;
    private final DiagnosisProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 触发 AI 诊断
     *
     * @param alertId    预警ID
     * @param wellId     井ID
     * @param alertType  预警类型
     * @param request    诊断请求
     * @return 是否成功触发
     */
    public boolean triggerDiagnosis(String alertId, String wellId, String alertType,
                                     DiagnosisRequest request) {
        if (!properties.isEnabled()) {
            log.info("AI 诊断功能未启用，跳过诊断: alertId={}", alertId);
            return false;
        }

        try {
            log.info("开始触发 AI 诊断: alertId={}, wellId={}, alertType={}",
                    alertId, wellId, alertType);

            // 同步调用 AI 诊断
            Flux<String> diagnosisStream = sseForwardService.forwardSSE(
                    "/api/v1/diagnosis/analyze",
                    request,
                    Duration.ofMinutes(properties.getTimeoutMinutes())
            );

            // 收集完整结果
            StringBuilder resultBuilder = new StringBuilder();
            diagnosisStream
                    .doOnNext(resultBuilder::append)
                    .blockLast();  // 阻塞等待完成

            String fullResult = resultBuilder.toString();
            log.info("AI 诊断完成: alertId={}, resultLength={}", alertId, fullResult.length());

            // 缓存结果
            cacheService.saveDiagnosisResult(alertId, fullResult);

            return true;

        } catch (Exception e) {
            log.error("AI 诊断失败: alertId={}, error={}", alertId, e.getMessage(), e);

            // 缓存失败信息
            String errorResult = String.format(
                    "data: {\"type\":\"error\",\"content\":\"诊断失败: %s\"}\\n\\n",
                    e.getMessage()
            );
            cacheService.saveDiagnosisResult(alertId, errorResult);

            return false;
        }
    }
}
```

**Step 4: 运行测试验证通过**

运行: `mvn test -Dtest=AiDiagnosisTriggerServiceTest`
预期: PASS

**Step 5: 提交**

```bash
git add sky-server/src/main/java/com/kira/server/service/ai/AiDiagnosisTriggerService.java
git add sky-server/src/test/java/com/kira/server/service/ai/AiDiagnosisTriggerServiceTest.java
git commit -m "feat: 添加 AI 诊断触发服务

- 实现同步调用 AI 诊断并收集结果
- 自动缓存诊断结果到 Redis
- 支持配置开关控制"
```

---

## Task 5: 修改 PollutionDetectionTest 集成 AI 诊断

**文件:**
- 修改: `sky-server/src/main/java/com/kira/server/task/PollutionDetectionTest.java`
- 测试: `sky-server/src/test/java/com/kira/server/task/PollutionDetectionTestTest.java`

**Step 1: 编写失败的测试**

创建 `sky-server/src/test/java/com/kira/server/task/PollutionDetectionTestTest.java`：

```java
package com.kira.server.task;

import com.kira.server.service.ai.AiDiagnosisTriggerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
class PollutionDetectionTestTest {

    @Autowired
    private PollutionDetectionTest pollutionDetectionTest;

    @MockBean
    private AiDiagnosisTriggerService triggerService;

    @MockBean
    private RedisTemplate redisTemplate;

    @Test
    void testCaPollutionDetectionTriggersDiagnosis() {
        // 模拟 Redis 返回井ID
        when(redisTemplate.opsForValue().get(any())).thenReturn("test-well");

        // 验证调用
        pollutionDetectionTest.caPollutionDetection();
        // 如果检测到污染，应该触发诊断（需要在实际环境中测试）
    }
}
```

**Step 2: 运行测试验证失败**

运行: `mvn test -Dtest=PollutionDetectionTestTest`
预期: 测试框架可运行

**Step 3: 修改 PollutionDetectionTest 添加依赖**

修改 `sky-server/src/main/java/com/kira/server/task/PollutionDetectionTest.java`：

在类的依赖注入部分添加：

```java
@Autowired
private AiDiagnosisTriggerService aiDiagnosisTriggerService;  // 新增
```

**Step 4: 添加私有常量**

在类的开头添加：

```java
private static final String ALERT_ID_PREFIX = "ALERT-";
```

**Step 5: 修改钙污染检测方法**

修改 `caPollutionDetection()` 方法第 77-80 行：

```java
// 记录检测结果
if (isPolluted) {
    log.error("【定时检测】检测到钙污染，井ID：{}", wellId);

    // 触发 AI 诊断
    triggerAiDiagnosis(wellId, "钙污染", result, wellLocation);
} else {
    log.info("【定时检测】钙污染检测正常，井ID：{}", wellId);
}
```

**Step 6: 修改二氧化碳污染检测方法**

修改 `co2PollutionDetection()` 方法第 116-119 行：

```java
// 记录检测结果
if (isPolluted) {
    log.error("【定时检测】检测到二氧化碳污染，井ID：{}", wellId);

    // 触发 AI 诊断
    triggerAiDiagnosis(wellId, "二氧化碳污染", result, wellLocation);
} else {
    log.info("【定时检测】二氧化碳污染检测正常，井ID：{}", wellId);
}
```

**Step 7: 修改稳定性检测方法**

修改 `drillingFluidStabilityDetection()` 方法第 156-159 行：

```java
// 记录检测结果
if (isUnstable) {
    log.error("【定时检测】检测到钻井液长效稳定问题，井ID：{}", wellId);

    // 触发 AI 诊断
    triggerAiDiagnosis(wellId, "钻井液稳定性问题", result, wellLocation);
} else {
    log.info("【定时检测】钻井液长效稳定检测正常，井ID：{}", wellId);
}
```

**Step 8: 添加 AI 诊断触发方法**

在 `sendPollutionAlert` 方法后添加：

```java
/**
 * 触发 AI 诊断分析
 *
 * @param wellId         井ID
 * @param alertType      预警类型
 * @param detectionResult 检测结果
 * @param wellLocation   井位置
 */
private void triggerAiDiagnosis(String wellId, String alertType,
                                 Map<String, List<ParameterVO>> detectionResult,
                                 String wellLocation) {
    String alertId = ALERT_ID_PREFIX + System.currentTimeMillis();

    try {
        // 1. 构造诊断请求
        DiagnosisRequest request = buildDiagnosisRequest(wellId, alertType, detectionResult);

        // 2. 触发 AI 诊断
        boolean success = aiDiagnosisTriggerService.triggerDiagnosis(
                alertId, wellId, alertType, request
        );

        // 3. 发送 WebSocket 预警
        sendAiDiagnosisAlert(alertId, wellId, wellLocation, alertType,
                success ? "COMPLETED" : "FAILED");

    } catch (Exception e) {
        log.error("触发 AI 诊断异常: alertId={}, error={}", alertId, e.getMessage(), e);
        // 即使失败也发送预警
        sendAiDiagnosisAlert(alertId, wellId, wellLocation, alertType, "ERROR");
    }
}

/**
 * 构造诊断请求
 */
private DiagnosisRequest buildDiagnosisRequest(String wellId, String alertType,
                                                Map<String, List<ParameterVO>> detectionResult) {
    DiagnosisRequest request = new DiagnosisRequest();
    request.setWellId(wellId);
    request.setAlertType(alertType);
    request.setAlertTriggeredAt(LocalDateTime.now());
    request.setStream(true);

    // 如果有污染详情数据，可以转换为 samples
    // 这里简化处理，实际可以添加更多上下文信息
    return request;
}

/**
 * 发送 AI 诊断预警消息
 */
private void sendAiDiagnosisAlert(String alertId, String wellId, String wellLocation,
                                   String alertType, String status) {
    try {
        Map<String, Object> alertMessage = new HashMap<>();
        alertMessage.put("type", "AI_DIAGNOSIS_ALERT");
        alertMessage.put("alertId", alertId);
        alertMessage.put("wellId", wellId);
        alertMessage.put("wellLocation", wellLocation);
        alertMessage.put("alertType", alertType);
        alertMessage.put("severity", "HIGH");
        alertMessage.put("triggeredAt", System.currentTimeMillis());
        alertMessage.put("status", status);
        alertMessage.put("diagnosisUrl", "/api/ai/diagnosis/stream?alertId=" + alertId);

        String jsonMessage = objectMapper.writeValueAsString(alertMessage);
        log.info("发送 AI 诊断预警: {}", jsonMessage);

        webSocketServer.sendToAllClient(jsonMessage);

    } catch (Exception e) {
        log.error("发送 AI 诊断预警失败: {}", e.getMessage());
    }
}
```

**Step 9: 添加必要的 import**

在文件头部添加：

```java
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.service.ai.AiDiagnosisTriggerService;
```

**Step 10: 运行测试验证通过**

运行: `mvn test -Dtest=PollutionDetectionTestTest`
预期: PASS

**Step 11: 提交**

```bash
git add sky-server/src/main/java/com/kira/server/task/PollutionDetectionTest.java
git add sky-server/src/test/java/com/kira/server/task/PollutionDetectionTestTest.java
git commit -m "feat: PollutionDetectionTest 集成 AI 诊断

- 检测到污染时自动触发 AI 诊断
- 生成唯一 alertId 并缓存结果
- 通过 WebSocket 推送 AI_DIAGNOSIS_ALERT 类型预警
- 支持三种检测类型：钙污染、CO2污染、稳定性"
```

---

## Task 6: 添加集成测试

**文件:**
- 修改: `sky-server/src/test/java/com/kira/server/integration/AgentIntegrationTest.java`

**Step 1: 添加完整流程测试**

在 `AgentIntegrationTest.java` 末尾添加：

```java
@Test
void testDiagnosisCacheFlow() {
    // 1. 创建诊断请求
    String requestJson = "{\\n" +
            "    \\\"well_id\\\": \\\"well-001\\\",\\n" +
            "    \\\"alert_type\\\": \\\"HIGH_DENSITY\\\",\\n" +
            "    \\\"alert_triggered_at\\\": \\\"2026-02-26T10:00:00\\\",\\n" +
            "    \\\"stream\\\": true\\n" +
            "}";

    // 2. 发起诊断分析
    webTestClient.post()
            .uri("/api/ai/diagnosis/analyze")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestJson)
            .exchange()
            .expectStatus().isOk();

    // 注意：此测试需要 Agent 服务运行，且需要知道 taskId
    // 完整流程测试需要在实际环境中验证
}
```

**Step 2: 运行集成测试**

运行: `mvn test -Dtest=AgentIntegrationTest`
预期: 测试通过（需要 Agent 服务运行）

**Step 3: 提交**

```bash
git add sky-server/src/test/java/com/kira/server/integration/AgentIntegrationTest.java
git commit -m "test: 添加诊断缓存流程集成测试"
```

---

## Task 7: 验证和文档

**Step 1: 运行所有测试**

运行: `mvn test`
预期: 所有测试通过

**Step 2: 本地验证**

1. 启动应用: `mvn spring-boot:run`
2. 确保 Agent 服务运行
3. 手动触发 XXL-Job 或等待定时任务
4. 观察 WebSocket 消息
5. 使用 curl 测试查询端点:

```bash
curl -N "http://localhost:18080/api/ai/diagnosis/stream?alertId=ALERT-xxx"
```

**Step 3: 更新 API 文档**

在 `docs/api/ai-diagnosis.md` 添加新端点说明：

```markdown
## 查询缓存诊断结果

**端点**: `GET /api/ai/diagnosis/stream?alertId={alertId}`

**说明**: 当前端收到 WebSocket 预警后，调用此接口获取缓存的诊断结果

**参数**:
- `alertId`: 预警ID（从 WebSocket 消息中获取）

**返回**: SSE 流式响应

**示例**:
\`\`\`javascript
// WebSocket 监听
websocket.onmessage = (event) => {
  const alert = JSON.parse(event.data);
  if (alert.type === 'AI_DIAGNOSIS_ALERT') {
    // 获取诊断结果
    const eventSource = new EventSource(
      \`/api/ai/diagnosis/stream?alertId=\${alert.alertId}\`
    );
    eventSource.onmessage = (e) => console.log(e.data);
  }
};
\`\`\`
```

**Step 4: 更新设计文档链接**

在实施文档末尾添加：

```markdown
---

## 验证清单

- [ ] 单元测试全部通过
- [ ] 集成测试通过（需 Agent 服务）
- [ ] 本地手动验证完整流程
- [ ] XXL-Job 能正确触发诊断
- [ ] WebSocket 预警正确推送
- [ ] 缓存查询端点返回正确结果
```

**Step 5: 最终提交**

```bash
git add docs/api/ai-diagnosis.md
git commit -m "docs: 添加 AI 诊断触发功能 API 文档"
```

---

## 实施总结

实施完成后，系统将具备以下能力：

1. **自动诊断**: XXL-Job 检测到异常时自动调用 AI 诊断
2. **结果缓存**: 诊断结果缓存到 Redis，TTL 15 分钟
3. **实时预警**: 通过 WebSocket 推送 `AI_DIAGNOSIS_ALERT` 类型消息
4. **按需查询**: 前端可根据 alertId 查询缓存结果

### 文件变更清单

- **新增**: `DiagnosisProperties.java` - 配置类
- **新增**: `DiagnosisCacheService.java` - 缓存服务
- **新增**: `AiDiagnosisTriggerService.java` - 触发服务
- **修改**: `DiagnosisController.java` - 添加查询端点
- **修改**: `PollutionDetectionTest.java` - 集成 AI 诊断
- **修改**: `application.yml` - 添加配置

### 关键常量

```java
// Redis Key 前缀
private static final String DIAGNOSIS_PREFIX = "diagnosis:";
private static final String ALERT_PREFIX = "alert:";
private static final String ALERT_ID_PREFIX = "ALERT-";

// WebSocket 消息类型
private static final String MSG_TYPE = "AI_DIAGNOSIS_ALERT";
```
