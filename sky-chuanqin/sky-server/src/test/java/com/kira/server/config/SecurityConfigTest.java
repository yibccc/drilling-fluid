package com.kira.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
