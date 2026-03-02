package com.kira.server.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 井配置管理服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class WellConfigServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    private WellConfigService wellConfigService;

    private static final String ACTIVE_WELLS_KEY = "well:active";

    @BeforeEach
    void setUp() {
        wellConfigService = new WellConfigService();
        // 使用反射注入RedisTemplate（因为@Autowired在此不可用）
        try {
            java.lang.reflect.Field field = WellConfigService.class.getDeclaredField("redisTemplate");
            field.setAccessible(true);
            field.set(wellConfigService, redisTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Mock RedisTemplate的opsForSet方法
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void testAddWell() {
        // 执行
        wellConfigService.addWell("SHB001");

        // 验证
        verify(setOperations).add(ACTIVE_WELLS_KEY, "SHB001");
    }

    @Test
    void testRemoveWell() {
        // 执行
        wellConfigService.removeWell("SHB001");

        // 验证
        verify(setOperations).remove(ACTIVE_WELLS_KEY, "SHB001");
    }

    @Test
    void testGetActiveWells() {
        // 准备Mock数据
        Set<Object> mockWells = new HashSet<>();
        mockWells.add("SHB001");
        mockWells.add("SHB002");
        mockWells.add("SHB003");
        when(setOperations.members(ACTIVE_WELLS_KEY)).thenReturn(mockWells);

        // 执行
        Set<String> activeWells = wellConfigService.getActiveWells();

        // 验证
        assertNotNull(activeWells);
        assertEquals(3, activeWells.size());
        assertTrue(activeWells.contains("SHB001"));
        assertTrue(activeWells.contains("SHB002"));
        assertTrue(activeWells.contains("SHB003"));
    }

    @Test
    void testGetActiveWellsWhenEmpty() {
        // 准备Mock数据
        when(setOperations.members(ACTIVE_WELLS_KEY)).thenReturn(null);

        // 执行
        Set<String> activeWells = wellConfigService.getActiveWells();

        // 验证
        assertNull(activeWells);
    }

    @Test
    void testIsWellActive() {
        // 准备Mock数据
        when(setOperations.isMember(ACTIVE_WELLS_KEY, "SHB001")).thenReturn(true);
        when(setOperations.isMember(ACTIVE_WELLS_KEY, "SHB999")).thenReturn(false);

        // 执行并验证
        assertTrue(wellConfigService.isWellActive("SHB001"));
        assertFalse(wellConfigService.isWellActive("SHB999"));
    }
}
