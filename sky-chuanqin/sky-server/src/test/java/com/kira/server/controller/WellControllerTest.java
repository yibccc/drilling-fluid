package com.kira.server.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WellController集成测试
 */
class WellControllerTest {

    private MockMvc mockMvc;

    private WellController wellController;

    private RedisTemplate<String, Object> redisTemplate;

    private SetOperations<String, Object> setOperations;

    @BeforeEach
    void setUp() {
        // Create mock services
        redisTemplate = mock(RedisTemplate.class);
        setOperations = mock(SetOperations.class);

        // Create WellConfigService with mocked RedisTemplate
        com.kira.server.service.WellConfigService wellConfigService =
            new com.kira.server.service.WellConfigService();
        try {
            java.lang.reflect.Field field = com.kira.server.service.WellConfigService.class
                .getDeclaredField("redisTemplate");
            field.setAccessible(true);
            field.set(wellConfigService, redisTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        // Create controller
        wellController = new WellController();
        try {
            java.lang.reflect.Field field = WellController.class.getDeclaredField("wellConfigService");
            field.setAccessible(true);
            field.set(wellController, wellConfigService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Build standalone MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(wellController).build();
    }

    @Test
    void testAddWell() throws Exception {
        mockMvc.perform(post("/well/add")
                        .param("wellId", "SHB001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证调用了Redis的add方法
        verify(setOperations).add("well:active", "SHB001");
    }

    @Test
    void testRemoveWell() throws Exception {
        mockMvc.perform(delete("/well/remove")
                        .param("wellId", "SHB001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证调用了Redis的remove方法
        verify(setOperations).remove("well:active", "SHB001");
    }

    @Test
    void testGetActiveWells() throws Exception {
        // 准备Mock数据
        Set<Object> mockWells = new HashSet<>();
        mockWells.add("SHB001");
        mockWells.add("SHB002");
        when(setOperations.members("well:active")).thenReturn(mockWells);

        mockMvc.perform(get("/well/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testCheckWellActive() throws Exception {
        // 准备Mock数据
        when(setOperations.isMember("well:active", "SHB001")).thenReturn(true);
        when(setOperations.isMember("well:active", "SHB999")).thenReturn(false);

        mockMvc.perform(get("/well/check")
                        .param("wellId", "SHB001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/well/check")
                        .param("wellId", "SHB999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
    }
}
