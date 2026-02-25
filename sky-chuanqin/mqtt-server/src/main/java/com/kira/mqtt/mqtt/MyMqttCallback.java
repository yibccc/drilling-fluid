package com.kira.mqtt.mqtt;

import com.kira.mqtt.service.MqttMessageService;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MyMqttCallback implements MqttCallback {
    private static final Logger log = LoggerFactory.getLogger(MyMqttCallback.class);
    
    private final MyMqttClient mqttClient;
    private final MqttMessageService mqttMessageService;

    @Autowired
    public MyMqttCallback(MyMqttClient mqttClient, MqttMessageService mqttMessageService) {
        this.mqttClient = mqttClient;
        this.mqttMessageService = mqttMessageService;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.error("MQTT连接丢失", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        log.info("收到原始消息: topic={}, payload={}", topic, payload);
        
        // 使用服务类处理消息
        mqttMessageService.processMessage(topic, payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        log.info("消息发送完成");
    }
} 