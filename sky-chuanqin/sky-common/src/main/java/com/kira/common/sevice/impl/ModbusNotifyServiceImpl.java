package com.kira.common.sevice.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kira.common.handler.ModbusDataWebSocketHandler;
import com.kira.common.pojo.ModbusData;
import com.kira.common.sevice.IModbusNotifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Modbus数据通知服务实现
 */
@Slf4j
@Service
public class ModbusNotifyServiceImpl implements IModbusNotifyService {

   @Autowired
   private RedisTemplate<String, String> redisTemplate;

   private final ObjectMapper objectMapper;

   public ModbusNotifyServiceImpl() {
      this.objectMapper = new ObjectMapper();
      this.objectMapper.registerModule(new JavaTimeModule());
   }

   @Override
   public void notifyModbusUpdate(ModbusData modbusData) {
      try {
         String message = objectMapper.writeValueAsString(modbusData);
         redisTemplate.convertAndSend(ModbusDataWebSocketHandler.MODBUS_CHANNEL, message);

         log.info("已发布Modbus数据更新通知：wellId={}, samplingTime={}",
                 modbusData.getWellId(), modbusData.getSamplingTime());

      } catch (JsonProcessingException e) {
         log.error("Modbus数据序列化失败", e);
      } catch (Exception e) {
         log.error("发布Modbus数据更新通知失败", e);
      }
   }

   @Override
   public void notifyBatchModbusUpdate(List<ModbusData> modbusDataList) {
      if (modbusDataList == null || modbusDataList.isEmpty()) {
         return;
      }

      log.info("批量发布Modbus数据更新通知，数量：{}", modbusDataList.size());

      for (ModbusData modbusData : modbusDataList) {
         notifyModbusUpdate(modbusData);
      }
   }
}