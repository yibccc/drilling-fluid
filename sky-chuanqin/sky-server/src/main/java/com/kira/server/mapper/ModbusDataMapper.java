package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.common.pojo.ModbusData;
import org.apache.ibatis.annotations.Mapper;

@Mapper //  Mybatis Mapper 接口
public interface ModbusDataMapper extends BaseMapper<ModbusData> {
    //  不需要额外定义方法，BaseMapper 提供了基本的 CRUD 操作
}