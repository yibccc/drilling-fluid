package com.kira.mqtt.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.mqtt.mapper.ModbusDataMapper;
import com.kira.mqtt.service.IModbusDataService;
import com.kira.common.pojo.ModbusData;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author kira
 * @since 2025-02-21
 */
@Service
public class ModbusDataServiceImpl extends ServiceImpl<ModbusDataMapper, ModbusData> implements IModbusDataService {

}
