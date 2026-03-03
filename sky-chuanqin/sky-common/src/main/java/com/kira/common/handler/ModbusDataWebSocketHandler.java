package com.kira.common.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kira.common.pojo.ModbusData;
import com.kira.common.pojo.ModbusRealtimeVO;
import com.kira.common.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Modbus数据WebSocket推送处理器
 * 从 Kafka 消费者接收数据后推送到 WebSocket 客户端
 */
@Slf4j
@Component
public class ModbusDataWebSocketHandler {

   @Autowired
   private WebSocketServer webSocketServer;

   private final ObjectMapper objectMapper;

   public ModbusDataWebSocketHandler() {
      this.objectMapper = new ObjectMapper();
      this.objectMapper.registerModule(new JavaTimeModule());
   }

   /**
    * 推送 Modbus 数据到 WebSocket 客户端
    * 由 Kafka 消费者服务调用
    *
    * @param modbusData Modbus数据对象
    */
   public void pushModbusData(ModbusData modbusData) {
      try {
         log.debug("推送Modbus数据到WebSocket：wellId={}", modbusData.getWellId());

         ModbusRealtimeVO realtimeVO = buildRealtimeVO(modbusData);
         String jsonMessage = objectMapper.writeValueAsString(realtimeVO);

         if (modbusData.getWellId() != null && !modbusData.getWellId().isEmpty()) {
            webSocketServer.sendToWell(modbusData.getWellId(), jsonMessage);
            log.info("已推送Modbus数据到井 {} 的订阅客户端", modbusData.getWellId());
         } else {
            webSocketServer.sendToAllClient(jsonMessage);
            log.info("已广播Modbus数据到所有客户端");
         }

      } catch (Exception e) {
         log.error("推送Modbus数据失败", e);
      }
   }

   private ModbusRealtimeVO buildRealtimeVO(ModbusData data) {
      return ModbusRealtimeVO.builder()
              .type("modbus_update")
              .wellId(data.getWellId())
              .samplingTime(data.getSamplingTime())
              .timestamp(System.currentTimeMillis())
              .drillingFluidDensity(data.getDrillingFluidDensity())
              .outletTemperature(data.getOutletTemperature())
              .shearForce10s(data.getShearForce10s())
              .shearForce10m(data.getShearForce10m())
              .rpm3(data.getRpm3())
              .rpm6(data.getRpm6())
              .rpm100(data.getRpm100())
              .rpm200(data.getRpm200())
              .rpm300(data.getRpm300())
              .rpm600(data.getRpm600())
              .plasticViscosity(data.getPlasticViscosity())
              .yieldPoint(data.getYieldPoint())
              .apparentViscosity(data.getApparentViscosity())
              .consistencyK(data.getConsistencyK())
              .ypPv(data.getYpPv())
              .phValue(data.getPhValue())
              .conductivity(data.getConductivity())
              .emulsionBreakdownVoltage(data.getEmulsionBreakdownVoltage())
              .oilContent(data.getOilContent())
              .waterContent(data.getWaterContent())
              .solidContent(data.getSolidContent())
              .chlorideIonContent(data.getChlorideIonContent())
              .calciumIonContent(data.getCalciumIonContent())
              .potassiumIonContent(data.getPotassiumIonContent())
              .bromideContent(data.getBromideContent())
              .strontiumContent(data.getStrontiumContent())
              .apiFiltrationLoss(data.getApiFiltrationLoss())
              .apiFilterCakeThickness(data.getApiFilterCakeThickness())
              .hthpFiltrationLoss(data.getHthpFiltrationLoss())
              .hthpFilterCakeThickness(data.getHthpFilterCakeThickness())
              .build();
   }
}