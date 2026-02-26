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
