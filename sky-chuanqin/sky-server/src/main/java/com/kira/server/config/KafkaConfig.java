package com.kira.server.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka配置类
 * 配置生产者、消费者和监听器容器工厂
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 生产者工厂（用于发送 Object）
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // 可靠性配置
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 5);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // 批量发送配置
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);

        // 超时配置
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * 生产者工厂（用于发送 String，用于 DLT）
     */
    @Bean("producerFactoryString")
    public ProducerFactory<String, String> producerFactoryString() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // 可靠性配置
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 5);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // 批量发送配置
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);

        // 超时配置
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * KafkaTemplate (生产者使用）
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * KafkaTemplate for String (用于 DLT）
     */
    @Bean("kafkaTemplateString")
    public KafkaTemplate<String, String> kafkaTemplateString() {
        return new KafkaTemplate<>(producerFactoryString());
    }

    /**
     * 消费者工厂
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sky-server");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * 监听器容器工厂（手动确认模式）
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            kafkaListenerContainerFactory() {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // 手动确认模式
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // 并发消费
        factory.setConcurrency(3);

        // 批量监听
        factory.setBatchListener(false);

        return factory;
    }

    /**
     * 死信队列（DLT）处理器工厂
     * 用于 DltProcessor 监听 mqtt.raw-dlt 主题
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            dltListenerContainerFactory() {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1);  // DLT 单线程处理
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setBatchListener(false);

        return factory;
    }

    /**
     * 重试和死信队列（DLT）配置
     * 使用 RetryTopicConfiguration 配置自动重试和 DLT
     */
    @Bean
    public RetryTopicConfiguration retryTopicConfiguration(
            @Qualifier("kafkaTemplateString") KafkaTemplate<String, String> kafkaTemplate) {
        return RetryTopicConfigurationBuilder
                .newInstance()
                // 指数退避：初始1秒，倍数2，最大10秒
                .exponentialBackoff(1000L, 2, 10000L)
                // 最大尝试次数：4次（1次原始 + 3次重试）
                .maxAttempts(4)
                .create(kafkaTemplate);
    }
}
