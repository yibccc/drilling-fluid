package com.kira.server.service.ai;

import com.kira.server.controller.ai.dto.DiagnosisRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 诊断触发服务
 * 触发钻井液异常智能诊断分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDiagnosisTriggerService {

    private final SSEForwardService sseForwardService;

    /**
     * 触发 AI 诊断
     *
     * @param alertId  预警ID
     * @param wellId   井号
     * @param alertType 预警类型
     * @param request  诊断请求
     * @return 是否触发成功
     */
    public boolean triggerDiagnosis(String alertId, String wellId, String alertType,
                                    DiagnosisRequest request) {
        try {
            // 设置 alertId
            request.setAlertId(alertId);

            log.info("触发 AI 诊断: alertId={}, wellId={}, alertType={}", alertId, wellId, alertType);

            // 调用 SSE 转发服务
            Flux<String> stream = sseForwardService.forwardSSEWithCache(
                    "/api/v1/diagnosis/analyze",
                    request,
                    Duration.ofMinutes(5),
                    alertId
            );

            // 订阅流并等待完成
            stream.blockLast();

            log.info("AI 诊断完成: alertId={}", alertId);
            return true;
        } catch (Exception e) {
            log.error("AI 诊断失败: alertId={}, error={}", alertId, e.getMessage(), e);
            return false;
        }
    }
}