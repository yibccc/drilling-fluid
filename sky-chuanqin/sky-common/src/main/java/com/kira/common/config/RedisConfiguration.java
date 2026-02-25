package com.kira.common.config;

import com.kira.common.handler.ModbusDataWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建redis模板对象...");
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        //设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        //设置redis value的序列化器为JSON序列化器
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        //设置hash key的序列化器
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        //设置hash value的序列化器
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        return redisTemplate;
    }



    /**
     * 配置Redis消息监听容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ModbusDataWebSocketHandler modbusDataHandler) {

        log.info("配置Redis消息监听容器...");

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 订阅Modbus数据更新频道
        container.addMessageListener(
                modbusDataHandler,
                new PatternTopic(ModbusDataWebSocketHandler.MODBUS_CHANNEL)
        );

        log.info("已订阅Redis频道：{}", ModbusDataWebSocketHandler.MODBUS_CHANNEL);

        return container;
    }
}