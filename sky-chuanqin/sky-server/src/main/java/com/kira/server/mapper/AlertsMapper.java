package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.server.domain.entity.Alerts;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author kira
 * @since 2024-10-06
 */
public interface AlertsMapper extends BaseMapper<Alerts> {
    // 根据时间段查询已经报警过的记录
    @Select("SELECT * FROM alerts WHERE create_time >= #{startTime} AND create_time <= #{endTime} AND alerted = TRUE")
    List<Alerts> findAlertedRecords(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Select("SELECT COUNT(*) FROM alerts WHERE alert_time BETWEEN #{startTime} AND #{endTime} AND alerted = TRUE")
    int countAlertsWithinTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Select("select count(*) from alerts where parameter_name = #{paramName} and create_time = #{createTime}")
    int countAlert(String paramName, LocalDateTime createTime);

    @Select("select username from employee where id = #{createUser}")
    String findUserName(Long createUser);
}
