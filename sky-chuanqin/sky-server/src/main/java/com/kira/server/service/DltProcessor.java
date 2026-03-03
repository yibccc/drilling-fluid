package com.kira.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
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
     * @param message 失败的消息内容
     */
    @KafkaListener(
            topics = "mqtt.raw-dlt",
            groupId = "sky-server-dlt"
    )
    public void processDltMessage(String message) {
        log.error("消息进入死信队列: {}", message);

        // TODO: 实现死信队列处理逻辑
        // 1. 记录到数据库，供后续分析
        // 2. 发送告警通知
        // 3. 尝试修复后重新投递

        // 简单实现：记录日志
        log.warn("DLT消息需要人工介入: {}", message);
    }
}
