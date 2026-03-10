package com.kira.server.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 死信队列处理器
 * 处理Kafka消费失败的消息
 */
@Service
@Slf4j
public class DltProcessor {

    /**
     * 处理mqtt.raw-dlt死信队列中的消息
     *
     * @param record Kafka消息记录
     * @param acknowledgment 手动确认对象
     */
    @KafkaListener(
            topics = "mqtt.raw-dlt",
            groupId = "sky-server-dlt",
            containerFactory = "dltListenerContainerFactory"
    )
    public void processDltMessage(ConsumerRecord<String, String> record,
                                   Acknowledgment acknowledgment) {
        String message = record.value();
        log.error("消息进入死信队列: topic={}, partition={}, offset={}, message={}",
                record.topic(), record.partition(), record.offset(), message);

        try {
            // TODO: 实现死信队列处理逻辑
            // 1. 记录到数据库，供后续分析
            // 2. 发送告警通知
            // 3. 尝试修复后重新投递

            log.warn("DLT消息需要人工介入: {}", message);

            // 处理完成后确认消息
            acknowledgment.acknowledge();
            log.info("DLT消息已处理: offset={}", record.offset());

        } catch (Exception e) {
            log.error("处理DLT消息失败: offset={}", record.offset(), e);
            // 不确认，等待下次重试
        }
    }
}
