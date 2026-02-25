package com.kira.server.mapper;

import com.kira.server.domain.entity.FullPerformance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.common.pojo.ModbusData;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 钻井液全性能参数 Mapper 接口
 * </p>
 *
 * @author kira
 * @since 2024-10-25
 */
public interface FullPerformanceMapper extends BaseMapper<ModbusData> {

    @Select("select * from modbus_data where well_id = #{wellId} and apparent_viscosity != 0 ORDER BY sampling_time DESC limit 20")
    List<FullPerformance> selectListL20(String wellId);

    @Select("select * from modbus_data where well_id = #{wellId} and apparent_viscosity != 0 ORDER BY sampling_time DESC limit 10")
    List<FullPerformance> selectListL10(String wellId);
}
