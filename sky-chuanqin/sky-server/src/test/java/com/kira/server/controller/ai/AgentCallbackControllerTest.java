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
