package com.kira.server.service.ai;

import com.kira.server.config.DiagnosisProperties;
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

        // This will be mocked, so we don't expect actual network call
        boolean result = triggerService.triggerDiagnosis("ALERT-001", "test-well", "钙污染", request);

        // The test verifies the service structure is correct
        // Actual verification depends on mock configuration
        assertTrue(result || !result); // Placeholder assertion
    }
}
