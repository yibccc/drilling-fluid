package com.kira.common.sevice;

import com.kira.common.pojo.ModbusData;

import java.util.List;

/**
 * Modbus数据通知服务接口
 */
public interface IModbusNotifyService {

   void notifyModbusUpdate(ModbusData modbusData);

   void notifyBatchModbusUpdate(List<ModbusData> modbusDataList);
}