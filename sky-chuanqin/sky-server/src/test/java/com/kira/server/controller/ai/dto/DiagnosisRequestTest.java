package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kira.common.json.JacksonObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisRequestTest {

    private final ObjectMapper objectMapper = new JacksonObjectMapper();

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
        assertThat(json).contains("\"current_value\":1.8");
    }

    @Test
    void testDrillingFluidSampleRpmFieldsUseSnakeCase() throws Exception {
        String json = "{"
                + "\"rpm_3\":5.0,"
                + "\"rpm_6\":8.0,"
                + "\"rpm_100\":45.0,"
                + "\"rpm_200\":80.0,"
                + "\"rpm_300\":120.0,"
                + "\"rpm_600\":180.0"
                + "}";

        DiagnosisRequest.DrillingFluidSample sample =
                objectMapper.readValue(json, DiagnosisRequest.DrillingFluidSample.class);

        assertThat(sample.getRpm3()).isEqualTo(5.0);
        assertThat(sample.getRpm6()).isEqualTo(8.0);
        assertThat(sample.getRpm100()).isEqualTo(45.0);
        assertThat(sample.getRpm200()).isEqualTo(80.0);
        assertThat(sample.getRpm300()).isEqualTo(120.0);
        assertThat(sample.getRpm600()).isEqualTo(180.0);

        String serialized = objectMapper.writeValueAsString(sample);
        assertThat(serialized).contains("\"rpm_3\":5.0");
        assertThat(serialized).contains("\"rpm_6\":8.0");
        assertThat(serialized).contains("\"rpm_100\":45.0");
        assertThat(serialized).contains("\"rpm_200\":80.0");
        assertThat(serialized).contains("\"rpm_300\":120.0");
        assertThat(serialized).contains("\"rpm_600\":180.0");
    }

    @Test
    void testDateTimeFieldsSerializeAsIsoStringForAgentRequest() throws Exception {
        ObjectMapper webClientLikeMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        DiagnosisRequest request = new DiagnosisRequest();
        request.setAlertId("alert-001");
        request.setWellId("well-001");
        request.setAlertType("density_high");
        request.setAlertTriggeredAt(LocalDateTime.of(2024, 1, 1, 10, 0, 0));

        DiagnosisRequest.DrillingFluidSample sample = new DiagnosisRequest.DrillingFluidSample();
        sample.setId("sample-001");
        sample.setWellId("well-001");
        sample.setSampleTime(LocalDateTime.of(2024, 1, 1, 10, 0, 0));
        sample.setFormation("砂岩");
        sample.setOutletTemp(80.0);
        sample.setDensity(1.25);
        sample.setGel10s(3.0);
        sample.setGel10m(8.0);
        sample.setRpm3(5.0);
        sample.setRpm6(8.0);
        sample.setRpm100(45.0);
        sample.setRpm200(80.0);
        sample.setRpm300(120.0);
        sample.setRpm600(180.0);
        sample.setPlasticViscosity(15.0);
        sample.setYieldPoint(8.0);
        sample.setFlowBehaviorIndex(0.8);
        sample.setConsistencyCoefficient(50.0);
        sample.setApparentViscosity(45.0);
        sample.setYieldPlasticRatio(0.53);
        request.setSamples(List.of(sample));

        String json = webClientLikeMapper.writeValueAsString(request);

        assertThat(json).contains("\"alert_triggered_at\":\"2024-01-01T10:00:00\"");
        assertThat(json).contains("\"sample_time\":\"2024-01-01T10:00:00\"");
    }
}
