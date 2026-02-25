package com.kira.mqtt.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.common.pojo.Density;
import com.kira.common.pojo.DrillingData;
import com.kira.common.pojo.ModbusData;
import com.kira.mqtt.domain.dto.ModbusDataDTO;
import com.kira.mqtt.mapper.DensityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class MqttMessageService {
    private static final Logger log = LoggerFactory.getLogger(MqttMessageService.class);

    private static final Integer NORMAL_TYPE = 0;
    private static final Long HANDWRITTEN_TYPE = 0L;

    public static final String WELL_ID = "well_name_harvest";
    public static final String WELL_LOCATION = "location_name_harvest";

    public static final String IS_OIL = "STATUS";
    public static final String TOPIC_SKY_TEST = "sky/test";

    private Double previousRpm600;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DensityMapper densityMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 处理MQTT消息
     * @param topic 消息主题
     * @param payload 消息内容
     */
    public void processMessage(String topic, String payload) {
        // 参数校验
        if (topic == null || payload == null) {
            log.error("MQTT消息处理失败: topic或payload为null");
            return;
        }

        try {
            // 处理 sky/test 主题的消息
            if (TOPIC_SKY_TEST.equals(topic)) {
                processTestTopicMessage(topic, payload);
            } else {
                log.info("未处理的MQTT主题: {}", topic);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息失败: topic={}, payload={}", topic, payload, e);
        }
    }

    /**
     * 处理sky/test主题的消息
     */
    private void processTestTopicMessage(String topic, String payload) {
        ModbusDataDTO modbusData;
        try {
            modbusData = JSON.parseObject(payload, ModbusDataDTO.class);
            if (modbusData == null) {
                log.error("解析MQTT消息失败，无法转换为ModbusDataDTO: {}", payload);
                return;
            }
        } catch (JSONException e) {
            log.error("解析MQTT消息JSON失败: {}", payload, e);
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // 获取Redis中的配置信息
        String wellId = getRedisValue(WELL_ID, String.class);
        String location = getRedisValue(WELL_LOCATION, String.class);
        Integer isOil = getRedisValue(IS_OIL, Integer.class);

        if (wellId == null || location == null) {
            log.warn("Redis中缺少必要的配置信息: wellId={}, location={}", wellId, location);
        }

        try {
            // 存储密度数据
            saveDensityData(modbusData, wellId, location, isOil, now);

            // 处理RPM数据
            processRpmData(topic, payload, modbusData, wellId, location, isOil, now);

            // 直接发布Redis消息
            ModbusData websocketData = new ModbusData();
            BeanUtils.copyProperties(modbusData, websocketData);
            websocketData.setSamplingTime(now);
            websocketData.setSamplingLocation(location);
            websocketData.setWellId(wellId);
            websocketData.setIsOil(isOil);
            websocketData.setDrillingFluidDensity(modbusData.getDrillingFluidFensity());
            String json = objectMapper.writeValueAsString(websocketData);
            redisTemplate.convertAndSend("modbus:update", json);
        } catch (Exception e) {
            log.error("处理ModbusData数据失败", e);
        }
    }

    /**
     * 从Redis获取值并转换为指定类型
     */
    @SuppressWarnings("unchecked")
    private <T> T getRedisValue(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            if (clazz.isInstance(value)) {
                return (T) value;
            } else {
                log.warn("Redis值类型不匹配: key={}, expectedType={}, actualType={}", 
                    key, clazz.getName(), value.getClass().getName());
                return null;
            }
        } catch (Exception e) {
            log.error("从Redis获取值失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 保存密度数据
     */
    private void saveDensityData(ModbusDataDTO modbusData, String wellId, String location, 
                                Integer isOil, LocalDateTime now) {
        try {
            Density density = new Density();
            BeanUtils.copyProperties(modbusData, density);
            density.setDrillingFluidDensity(modbusData.getDrillingFluidFensity());
            density.setSamplingTime(now);
            density.setSamplingLocation(location);
            density.setWellId(wellId);
            density.setIsOil(isOil);
            densityMapper.insert(density);

            log.debug("密度数据保存成功");
        } catch (Exception e) {
            log.error("保存密度数据失败", e);
        }
    }

    /**
     * 处理RPM数据
     */
    private void processRpmData(String topic, String payload, ModbusDataDTO modbusData, 
                               String wellId, String location, Integer isOil, LocalDateTime now) {
        // 只获取当前消息的rpm600值
        Double currentRpm600 = modbusData.getRpm600();
        
        // 检查是否需要存储数据，只基于rpm600的变化
        boolean shouldStore = shouldStoreRpmData(currentRpm600);
        
        // 更新上一次的rpm600值，用于下次比较
        previousRpm600 = currentRpm600;
        
        // 只有当rpm600值发生变化时才存储数据
        if (shouldStore) {
            log.info("检测到rpm600值变化，存储新数据");
            
            // 保存钻井数据
            saveDrillingData(modbusData, wellId, location, isOil, topic, payload, now);
            
            // 保存Modbus数据
            saveModbusData(modbusData, wellId, location, isOil, topic, payload, now);
        } else {
            log.info("rpm600值未变化，跳过存储: rpm600={}", currentRpm600);
        }
    }

    /**
     * 判断是否应该存储RPM数据，只基于rpm600的变化
     */
    private boolean shouldStoreRpmData(Double currentRpm600) {
        // 如果当前rpm600值为null，不应该存储
        if (currentRpm600 == null) {
            log.warn("当前rpm600值为null");
            return false;
        }
        
        // 如果previousRpm600为null（首次接收消息）或者rpm600值发生变化，则存储数据
        return previousRpm600 == null || !Objects.equals(previousRpm600, currentRpm600);
    }

    /**
     * 保存钻井数据
     */
    private void saveDrillingData(ModbusDataDTO modbusData, String wellId, String location, 
                                 Integer isOil, String topic, String payload, LocalDateTime now) {
        try {
            DrillingData drillingData = new DrillingData();
            BeanUtils.copyProperties(modbusData, drillingData);
            drillingData.setLowDensitySolidContent(modbusData.getDrillingFluidFensity3());
            drillingData.setBentoniteContent(modbusData.getOutletTemperature3());
            drillingData.setDrillingFluidDensity(modbusData.getDrillingFluidFensity());
            drillingData.setType(NORMAL_TYPE);
            drillingData.setIsHandwritten(HANDWRITTEN_TYPE);

            // 共有
            drillingData.setSamplingTime(now);
            drillingData.setSamplingLocation(location);
            drillingData.setWellId(wellId);
            drillingData.setIsOil(isOil);

            // 存储
            boolean flag = Db.save(drillingData);
            if (flag) {
                log.info("存储钻井数据成功: topic={}", topic);
                log.debug("存储钻井数据成功: topic={}, payload={}", topic, payload);
            } else {
                log.error("存储钻井数据失败: topic={}, payload={}", topic, payload);
            }
        } catch (Exception e) {
            log.error("保存钻井数据失败", e);
        }
    }

    /**
     * 保存Modbus数据
     */
    private void saveModbusData(ModbusDataDTO modbusData, String wellId, String location, 
                               Integer isOil, String topic, String payload, LocalDateTime now) {
        try {
            ModbusData data = new ModbusData();
            BeanUtils.copyProperties(modbusData, data);
            data.setLowDensitySolidContent(modbusData.getDrillingFluidFensity3());
            data.setBentoniteContent(modbusData.getOutletTemperature3());
            data.setDrillingFluidDensity(modbusData.getDrillingFluidFensity());
            
            // 共有
            data.setSamplingTime(now);
            data.setSamplingLocation(location);
            data.setWellId(wellId);
            data.setIsOil(isOil);

            // 存储
            boolean flag = Db.save(data);
            if (flag) {
                log.info("存储Modbus数据成功: topic={}", topic);
                log.debug("存储Modbus数据成功: topic={}, payload={}", topic, payload);
            } else {
                log.error("存储Modbus数据失败: topic={}, payload={}", topic, payload);
            }
        } catch (Exception e) {
            log.error("保存Modbus数据失败", e);
        }
    }
} 