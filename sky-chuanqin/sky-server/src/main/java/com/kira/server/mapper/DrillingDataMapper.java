package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.common.pojo.DrillingData;
import org.apache.ibatis.annotations.Select;

import java.util.ArrayList;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author kira
 * @since 2024-09-23
 */
public interface DrillingDataMapper extends BaseMapper<DrillingData> {


    @Select("Select distinct well_id from drilling_data")
    ArrayList<String> queryWell();
}
