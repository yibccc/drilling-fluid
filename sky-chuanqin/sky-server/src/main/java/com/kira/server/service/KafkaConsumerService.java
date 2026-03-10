package com.kira.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.common.handler.ModbusDataWebSocketHandler;
import com.kira.common.pojo.ModbusData;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Kafka消费者服务
 * 负责消费Kafka主题中的消息
 */
@Service
@Slf4j
public class KafkaConsumerService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ModbusDataWebSocketHandler modbusDataWebSocketHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 监听mqtt.raw主题
     * 重试和DLT配置在 KafkaConfig 中通过 Bean 方式配置
     *
     * @param record         Kafka消息记录
     * @param acknowledgment 手动确认对象
     */
    @KafkaListener(
            topics = "mqtt.raw",
            groupId = "sky-server",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeModbusData(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment) throws Exception {

        String message = record.value();
        log.debug("收到Kafka消息: partition={}, offset={}",
                record.partition(), record.offset());

        // 业务处理
        processModbusData(message);

        // 处理后手动确认
        acknowledgment.acknowledge();
        log.info("消息处理成功: partition={}, offset={}",
                record.partition(), record.offset());
    }

    /**
     * 处理Modbus数据（业务逻辑）
     *
     * @param message 消息内容
     * @throws Exception 处理失败时抛出异常，触发重试或进入DLT
     */
    private void processModbusData(String message) throws Exception {
        // 验证消息是否为空
        if (message == null || message.trim().isEmpty()) {
            log.warn("收到空消息，跳过处理");
            return;
        }

        // 验证是否为有效 JSON 格式（简单检查：以 { 或 [ 开头）
        String trimmed = message.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            log.warn("收到非JSON格式的消息，跳过处理: message={}", message);
            return;
        }

        // 使用 Jackson 解析 JSON 消息为 ModbusData 对象
        ModbusData modbusData = objectMapper.readValue(message, ModbusData.class);

        // 幂等性检查（基于 wellId + samplingTime）
        if (modbusData.getWellId() != null && modbusData.getSamplingTime() != null) {
            if (isDuplicateMessage(modbusData.getWellId(), modbusData.getSamplingTime())) {
                log.warn("重复消息，跳过处理: wellId={}, samplingTime={}",
                        modbusData.getWellId(), modbusData.getSamplingTime());
                return;
            }
        }

        // 推送到 WebSocket 客户端
        modbusDataWebSocketHandler.pushModbusData(modbusData);
        log.info("Modbus数据处理ktai: wellId={}, samplingTime={}",
                modbusData.getWellId(), modbusData.getSamplingTime());
    }

    /**
     * 业务幂等性检查
     * 基于 wellId + samplingTime 判断是否已处理
     *
     * @param wellId 井编号
     * @param timestamp 时间戳
     * @return true如果已处理
     */
    private boolean isDuplicateMessage(String wellId, LocalDateTime timestamp) {
        String key = "kafka:processed:" + wellId + ":" + timestamp;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofHours(24));
        return Boolean.FALSE.equals(isNew);
    }
}
