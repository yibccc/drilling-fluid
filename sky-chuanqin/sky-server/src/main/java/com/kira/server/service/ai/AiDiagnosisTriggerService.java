package com.kira.server.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.config.DiagnosisProperties;
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 诊断触发服务
 * 当 XXL-Job 检测到异常时，调用此服务触发 AI 诊断
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnosisTriggerService {

    private final SSEForwardService sseForwardService;
    private final DiagnosisCacheService cacheService;
    private final DiagnosisProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 触发 AI 诊断
     *
     * @param alertId    预警ID
     * @param wellId     井ID
     * @param alertType  预警类型
     * @param request    诊断请求
     * @return 是否成功触发
     */
    public boolean triggerDiagnosis(String alertId, String wellId, String alertType,
                                     DiagnosisRequest request) {
        if (!properties.isEnabled()) {
            log.info("AI 诊断功能未启用，跳过诊断: alertId={}", alertId);
            return false;
        }

        try {
            log.info("开始触发 AI 诊断: alertId={}, wellId={}, alertType={}",
                    alertId, wellId, alertType);

            // 同步调用 AI 诊断
            Flux<String> diagnosisStream = sseForwardService.forwardSSE(
                    "/api/v1/diagnosis/analyze",
                    request,
                    Duration.ofMinutes(properties.getTimeoutMinutes())
            );

            // 收集完整结果
            StringBuilder resultBuilder = new StringBuilder();
            diagnosisStream
                    .doOnNext(resultBuilder::append)
                    .blockLast();  // 阻塞等待完成

            String fullResult = resultBuilder.toString();
            log.info("AI 诊断完成: alertId={}, resultLength={}", alertId, fullResult.length());

            // 缓存结果
            cacheService.saveDiagnosisResult(alertId, fullResult);

            return true;

        } catch (Exception e) {
            log.error("AI 诊断失败: alertId={}, error={}", alertId, e.getMessage(), e);

            // 缓存失败信息
            String errorResult = String.format(
                    "data: {\"type\":\"error\",\"content\":\"诊断失败: %s\"}\n\n",
                    e.getMessage()
            );
            cacheService.saveDiagnosisResult(alertId, errorResult);

            return false;
        }
    }
}
