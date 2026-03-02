package com.kira.server.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.controller.ai.dto.DiagnosisRequest;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.service.IFullPerformanceService;
import com.kira.server.service.IPollutionAlarmLogService;
import com.kira.server.service.WellConfigService;
import com.kira.server.service.ai.AiDiagnosisTriggerService;
import com.kira.common.websocket.WebSocketServer;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钻井液污染检测定时任务
 *
 * @author Kira
 * @create 2025-04-27 14:15
 */
@Component
@Slf4j
public class PollutionDetectionTest {

    private static final String ALERT_ID_PREFIX = "ALERT-";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private IFullPerformanceService fullPerformanceService;

    @Autowired
    private IPollutionAlarmLogService pollutionAlarmLogService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebSocketServer webSocketServer;

    @Autowired
    private AiDiagnosisTriggerService aiDiagnosisTriggerService;

    @Autowired
    private WellConfigService wellConfigService;

    /**
     * 钙污染检测定时任务
     * 改造为多井循环检测
     */
    @XxlJob("caPollutionDetectionJob")
    public void caPollutionDetection() {
        log.info("开始执行钙污染检测定时任务：{}", LocalDateTime.now().format(FORMATTER));

        // 获取所有活跃井
        Set<String> activeWells = wellConfigService.getActiveWells();

        if (activeWells == null || activeWells.isEmpty()) {
            log.warn("没有活跃的井需要检测");
            return;
        }

        log.info("开始检测 {} 口井", activeWells.size());

        // 遍历每口井执行检测
        for (String wellId : activeWells) {
            String location = getWellLocation(wellId);
            detectPollutionForWell(wellId, location, "钙污染", this::isCaPollution);
        }

        log.info("钙污染检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 为指定井执行钙污染检测
     *
     * @param wellId 井号
     * @param location 井位置
     * @param pollutionType 污染类型
     * @param detection 检测函数
     */
    private void detectPollutionForWell(String wellId, String location,
                                        String pollutionType,
                                        java.util.function.Supplier<Map<String, List<ParameterVO>>> detection) {
        try {
            log.info("开始检测井 {} 的{}", wellId, pollutionType);

            // 执行污染检测
            Map<String, List<ParameterVO>> result = detection.get();

            // 检查结果
            boolean isPolluted = false;
            if (result.containsKey("pollution") && !result.get("pollution").isEmpty()) {
                isPolluted = result.get("pollution").get(0).isRed();
            }

            // 记录检测结果
            if (isPolluted) {
                log.error("【定时检测】井 {} 检测到{}", wellId, pollutionType);
                triggerAiDiagnosis(wellId, pollutionType, result, location);
            } else {
                log.info("【定时检测】井 {} {}检测正常", wellId, pollutionType);
            }
        } catch (Exception e) {
            log.error("井 {} {}检测失败", wellId, pollutionType, e);
        }
    }

    /**
     * 执行钙污染检测
     */
    private Map<String, List<ParameterVO>> isCaPollution() {
        return fullPerformanceService.isCaPollution();
    }

    /**
     * 获取井位置
     */
    private String getWellLocation(String wellId) {
        // TODO: 从数据库或配置中获取井位置
        // 这里简化处理，使用Redis中的LOCATION_NAME作为默认值
        String location = (String) redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME.getKey());
        if (StringUtils.hasText(location)) {
            return location;
        }
        return "未知位置";
    }

    /**
     * 二氧化碳污染检测定时任务
     * 改造为多井循环检测
     */
    @XxlJob("co2PollutionDetectionJob")
    public void co2PollutionDetection() {
        log.info("开始执行二氧化碳污染检测定时任务：{}", LocalDateTime.now().format(FORMATTER));

        // 获取所有活跃井
        Set<String> activeWells = wellConfigService.getActiveWells();

        if (activeWells == null || activeWells.isEmpty()) {
            log.warn("没有活跃的井需要检测");
            return;
        }

        log.info("开始检测 {} 口井", activeWells.size());

        // 遍历每口井执行检测
        for (String wellId : activeWells) {
            String location = getWellLocation(wellId);
            detectPollutionForWell(wellId, location, "二氧化碳污染", this::isCo2Pollution);
        }

        log.info("二氧化碳污染检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 执行二氧化碳污染检测
     */
    private Map<String, List<ParameterVO>> isCo2Pollution() {
        return fullPerformanceService.isCo2Pollution();
    }

    /**
     * 钻井液长效稳定检测定时任务
     * 改造为多井循环检测
     */
    @XxlJob("drillingFluidStabilityDetectionJob")
    public void drillingFluidStabilityDetection() {
        log.info("开始执行钻井液长效稳定检测定时任务：{}", LocalDateTime.now().format(FORMATTER));

        // 获取所有活跃井
        Set<String> activeWells = wellConfigService.getActiveWells();

        if (activeWells == null || activeWells.isEmpty()) {
            log.warn("没有活跃的井需要检测");
            return;
        }

        log.info("开始检测 {} 口井", activeWells.size());

        // 遍历每口井执行检测
        for (String wellId : activeWells) {
            String location = getWellLocation(wellId);
            detectPollutionForWell(wellId, location, "钻井液稳定性问题", this::notTreatedForLongTime);
        }

        log.info("钻井液长效稳定检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 执行钻井液长效稳定检测
     */
    private Map<String, List<ParameterVO>> notTreatedForLongTime() {
        return fullPerformanceService.notTreatedForLongTimeNew();
    }

    /**
     * 发送污染预警WebSocket消息
     *
     * @param pollutionType 污染类型
     * @param wellId        井ID
     * @param wellLocation  井位置
     */
    private void sendPollutionAlert(String pollutionType, String wellId, String wellLocation) {
        try {
            // 构建预警消息
            Map<String, Object> alertMessage = new HashMap<>();
            alertMessage.put("type", "POLLUTION_ALERT");
            alertMessage.put("pollutionType", pollutionType);
            alertMessage.put("wellId", wellId);
            alertMessage.put("wellLocation", wellLocation);
            alertMessage.put("timestamp", System.currentTimeMillis());

            // 只在需要发送WebSocket消息时序列化为JSON
            String jsonMessage = objectMapper.writeValueAsString(alertMessage);
            log.info("发送污染预警WebSocket消息: {}", jsonMessage);

            // 调用WebSocket服务发送消息
            webSocketServer.sendToAllClient(jsonMessage);

        } catch (Exception e) {
            log.error("发送污染预警WebSocket消息失败: {}", e.getMessage());
        }
    }

    /**
     * 触发 AI 诊断分析
     *
     * @param wellId         井ID
     * @param alertType      预警类型
     * @param detectionResult 检测结果
     * @param wellLocation   井位置
     */
    private void triggerAiDiagnosis(String wellId, String alertType,
                                     Map<String, List<ParameterVO>> detectionResult,
                                     String wellLocation) {
        String alertId = ALERT_ID_PREFIX + System.currentTimeMillis();

        try {
            // 1. 构造诊断请求
            DiagnosisRequest request = buildDiagnosisRequest(wellId, alertType, detectionResult);

            // 2. 触发 AI 诊断
            boolean success = aiDiagnosisTriggerService.triggerDiagnosis(
                    alertId, wellId, alertType, request
            );

            // 3. 发送 WebSocket 预警
            sendAiDiagnosisAlert(alertId, wellId, wellLocation, alertType,
                    success ? "COMPLETED" : "FAILED");

        } catch (Exception e) {
            log.error("触发 AI 诊断异常: alertId={}, error={}", alertId, e.getMessage(), e);
            // 即使失败也发送预警
            sendAiDiagnosisAlert(alertId, wellId, wellLocation, alertType, "ERROR");
        }
    }

    /**
     * 构造诊断请求
     */
    private DiagnosisRequest buildDiagnosisRequest(String wellId, String alertType,
                                                    Map<String, List<ParameterVO>> detectionResult) {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setWellId(wellId);
        request.setAlertType(alertType);
        request.setAlertTriggeredAt(LocalDateTime.now());
        request.setStream(true);

        // 如果有污染详情数据，可以转换为 samples
        // 这里简化处理，实际可以添加更多上下文信息
        return request;
    }

    /**
     * 发送 AI 诊断预警消息
     */
    private void sendAiDiagnosisAlert(String alertId, String wellId, String wellLocation,
                                      String alertType, String status) {
        try {
            Map<String, Object> alertMessage = new HashMap<>();
            alertMessage.put("type", "AI_DIAGNOSIS_ALERT");
            alertMessage.put("alertId", alertId);
            alertMessage.put("wellId", wellId);
            alertMessage.put("wellLocation", wellLocation);
            alertMessage.put("alertType", alertType);
            alertMessage.put("severity", "HIGH");
            alertMessage.put("triggeredAt", System.currentTimeMillis());
            alertMessage.put("status", status);
            alertMessage.put("diagnosisUrl", "/api/ai/diagnosis/stream?alertId=" + alertId);

            String jsonMessage = objectMapper.writeValueAsString(alertMessage);
            log.info("发送 AI 诊断预警: {}", jsonMessage);

            webSocketServer.sendToAllClient(jsonMessage);

        } catch (Exception e) {
            log.error("发送 AI 诊断预警失败: {}", e.getMessage());
        }
    }
}
