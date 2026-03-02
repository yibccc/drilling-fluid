package com.kira.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 井配置管理服务
 */
@Service
@Slf4j
public class WellConfigService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String ACTIVE_WELLS_KEY = "well:active";

    /**
     * 添加活跃井
     */
    public void addWell(String wellId) {
        redisTemplate.opsForSet().add(ACTIVE_WELLS_KEY, wellId);
        log.info("添加井 {} 到活跃列表", wellId);
    }

    /**
     * 移除井
     */
    public void removeWell(String wellId) {
        Long removed = redisTemplate.opsForSet().remove(ACTIVE_WELLS_KEY, wellId);
        if (removed != null && removed > 0) {
            log.info("从活跃列表移除井 {}", wellId);
        }
    }

    /**
     * 获取所有活跃井
     */
    public Set<String> getActiveWells() {
        return redisTemplate.opsForSet().members(ACTIVE_WELLS_KEY);
    }

    /**
     * 检查井是否活跃
     */
    public boolean isWellActive(String wellId) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForSet().isMember(ACTIVE_WELLS_KEY, wellId)
        );
    }
}
