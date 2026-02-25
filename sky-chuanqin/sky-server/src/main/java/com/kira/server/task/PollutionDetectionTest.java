package com.kira.server.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.service.IFullPerformanceService;
import com.kira.server.service.IPollutionAlarmLogService;
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

/**
 * 钻井液污染检测定时任务
 *
 * @author Kira
 * @create 2025-04-27 14:15
 */
@Component
@Slf4j
public class PollutionDetectionTest {

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

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 钙污染检测定时任务
     */
    @XxlJob("caPollutionDetectionJob")
    public void caPollutionDetection() {
        log.info("开始执行钙污染检测定时任务：{}", LocalDateTime.now().format(FORMATTER));
        try {

            // 从Redis获取井号
            String wellId = (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
            if (!StringUtils.hasText(wellId)) {
                log.warn("钙污染检测失败：阈值中无井号且未找到有效的井ID");
                return;
            }

            // 获取井位置
            String wellLocation = (String) redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME.getKey());

            // 执行钙污染检测
            Map<String, List<ParameterVO>> result = fullPerformanceService.isCaPollution();

            // 检查结果
            boolean isPolluted = false;
            if (result.containsKey("pollution") && !result.get("pollution").isEmpty()) {
                isPolluted = result.get("pollution").get(0).isRed();
            }

            // 记录检测结果
            if (isPolluted) {
                log.error("【定时检测】检测到钙污染，井ID：{}", wellId);

//                sendPollutionAlert("钙污染", wellId, wellLocation);
            } else {
                log.info("【定时检测】钙污染检测正常，井ID：{}", wellId);
            }
        } catch (Exception e) {
            log.error("钙污染检测任务执行异常：", e);
        }
        log.info("钙污染检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 二氧化碳污染检测定时任务
     */
    @XxlJob("co2PollutionDetectionJob")
    public void co2PollutionDetection() {
        log.info("开始执行二氧化碳污染检测定时任务：{}", LocalDateTime.now().format(FORMATTER));
        try {
               String wellId = (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
                if (!StringUtils.hasText(wellId)) {
                    log.warn("二氧化碳污染检测失败：阈值中无井号且未找到有效的井ID");
                    return;
            }

            // 获取井位置
            String wellLocation = (String) redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME.getKey());

            // 执行二氧化碳污染检测
            Map<String, List<ParameterVO>> result = fullPerformanceService.isCo2Pollution();

            // 检查结果
            boolean isPolluted = false;
            if (result.containsKey("pollution") && !result.get("pollution").isEmpty()) {
                isPolluted = result.get("pollution").get(0).isRed();
            }

            // 记录检测结果
            if (isPolluted) {
                log.error("【定时检测】检测到二氧化碳污染，井ID：{}", wellId);

//                sendPollutionAlert("二氧化碳污染", wellId, wellLocation);
            } else {
                log.info("【定时检测】二氧化碳污染检测正常，井ID：{}", wellId);
            }
        } catch (Exception e) {
            log.error("二氧化碳污染检测任务执行异常：", e);
        }
        log.info("二氧化碳污染检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
    }

    /**
     * 钻井液长效稳定检测定时任务
     */
    @XxlJob("drillingFluidStabilityDetectionJob")
    public void drillingFluidStabilityDetection() {
        log.info("开始执行钻井液长效稳定检测定时任务：{}", LocalDateTime.now().format(FORMATTER));
        try {
                String wellId = (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
                if (!StringUtils.hasText(wellId)) {
                    log.warn("钻井液长效稳定检测失败：阈值中无井号且未找到有效的井ID");
                    return;
                }


            // 获取井位置
            String wellLocation = (String) redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME.getKey());

            // 执行钻井液长效稳定检测
            Map<String, List<ParameterVO>> result = fullPerformanceService.notTreatedForLongTimeNew();

            // 检查结果
            boolean isUnstable = false;
            if (result.containsKey("pollution") && !result.get("pollution").isEmpty()) {
                isUnstable = result.get("pollution").get(0).isRed();
            }

            // 记录检测结果
            if (isUnstable) {
                log.error("【定时检测】检测到钻井液长效稳定问题，井ID：{}", wellId);

//                sendPollutionAlert("钻井液稳定性问题", wellId, wellLocation);
            } else {
                log.info("【定时检测】钻井液长效稳定检测正常，井ID：{}", wellId);
            }
        } catch (Exception e) {
            log.error("钻井液长效稳定检测任务执行异常：", e);
        }
        log.info("钻井液长效稳定检测定时任务执行结束：{}", LocalDateTime.now().format(FORMATTER));
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
}
