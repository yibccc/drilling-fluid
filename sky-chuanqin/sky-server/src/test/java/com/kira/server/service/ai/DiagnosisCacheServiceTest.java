package com.kira.server.service.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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

    @Test
    void testSaveAlertInfo() {
        String alertInfo = "{\"type\":\"alert\"}";

        cacheService.saveAlertInfo(TEST_ALERT_ID, alertInfo);

        String cached = (String) redisTemplate.opsForValue().get("alert:" + TEST_ALERT_ID);
        assertEquals(alertInfo, cached);
    }

    @Test
    void testGetAlertInfo() {
        String alertInfo = "{\"type\":\"alert\"}";
        redisTemplate.opsForValue().set("alert:" + TEST_ALERT_ID, alertInfo, Duration.ofMinutes(15));

        String cached = cacheService.getAlertInfo(TEST_ALERT_ID);
        assertEquals(alertInfo, cached);
    }
}
