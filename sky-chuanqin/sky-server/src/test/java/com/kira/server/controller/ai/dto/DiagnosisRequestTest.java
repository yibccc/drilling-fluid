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
