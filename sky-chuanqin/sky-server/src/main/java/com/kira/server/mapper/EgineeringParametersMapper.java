package com.kira.server.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kira.server.domain.entity.EgineeringParameters;

import java.util.List;

/**
 * <p>
 * 工程参数 Mapper 接口
 * </p>
 *
 * @author kira
 * @since 2024-11-16
 */
public interface EgineeringParametersMapper extends BaseMapper<EgineeringParameters> {

    /**
     * 根据条件查询工程参数
     * @param wellId 钻井ID
     * @param startDate 开始日期
     * @param startTime 开始时间
     * @param endDate 结束日期
     * @param endTime 结束时间
     * @return 查询结果
     */
    default List<EgineeringParameters> selectByDateAndTime(String wellId, String startDate, String startTime, String endDate, String endTime) {
        // 构造查询条件
        QueryWrapper<EgineeringParameters> wrapper = new QueryWrapper<>();
        wrapper.eq("well_id", wellId);
        wrapper.and(qw -> qw
                .and(subQw -> subQw.eq("WELLDATE", startDate).ge("WELLTIME", startTime))
                .or(subQw -> subQw.eq("WELLDATE", endDate).le("WELLTIME", endTime))
                .or(subQw -> subQw.gt("WELLDATE", startDate).lt("WELLDATE", endDate))
        );

        // 使用 MyBatis-Plus 的基础方法执行查询
        return this.selectList(wrapper);
    }}
