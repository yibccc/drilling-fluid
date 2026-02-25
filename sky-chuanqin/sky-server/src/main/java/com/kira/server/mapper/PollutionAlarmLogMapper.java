package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.server.domain.entity.PollutionAlarmLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 钻井液污染报警日志Mapper接口
 *
 * @author kira
 */
@Mapper
public interface PollutionAlarmLogMapper extends BaseMapper<PollutionAlarmLog> {
} 