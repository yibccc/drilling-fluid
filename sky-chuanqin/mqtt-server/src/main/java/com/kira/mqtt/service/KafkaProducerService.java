package com.kira.mqtt.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * Kafka生产者服务
 * 负责将消息发送到Kafka主题
 */
@Service
@Slf4j
public class KafkaProducerService {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发送消息到Kafka（带回调处理）
     *
     * @param topic   Kafka主题
     * @param key     消息键（用于分区路由）
     * @param message 消息内容
     */
    public void sendMessage(String topic, String key, Object message) {
        try {
            log.debug("发送消息到Kafka: topic={}, key={}", topic, key);

            kafkaTemplate.send(topic, key, message).addCallback(
                // 成功回调
                new ListenableFutureCallback<SendResult<String, Object>>() {
                    @Override
                    public void onSuccess(SendResult<String, Object> result) {
                        RecordMetadata metadata = result.getRecordMetadata();
                        log.debug("消息发送成功: topic={}, partition={}, offset={}",
                            metadata.topic(), metadata.partition(), metadata.offset());
                    }

                    @Override
                    public void onFailure(Throwable ex) {
                        log.error("消息发送失败: topic={}, key={}, error={}",
                            topic, key, ex.getMessage(), ex);
                        handleSendFailure(topic, key, message, ex);
                    }
                }
            );
        } catch (Exception e) {
            log.error("发送消息到Kafka异常: topic={}, key={}", topic, key, e);
            handleSendFailure(topic, key, message, e);
        }
    }

    /**
     * 处理发送失败（记录到本地日志或发送到降级通道）
     *
     * @param topic   Kafka主题
     * @param key     消息键
     * @param message 消息内容
     * @param ex      异常信息
     */
    private void handleSendFailure(String topic, String key, Object message, Throwable ex) {
        // 方案1: 记录到本地日志文件，后续有补偿任务重发
        // 方案2: 发送到Redis作为降级通道
        // 方案3: 关键消息直接写数据库

        log.warn("消息发送失败已记录，等待补偿: topic={}, key={}", topic, key);

        // TODO: 实现具体的失败处理逻辑
        // 可以记录到数据库或发送到降级队列
    }

}
