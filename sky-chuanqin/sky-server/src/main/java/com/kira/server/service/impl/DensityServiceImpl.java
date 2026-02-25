package com.kira.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.server.domain.dto.ParametersDTO;
import com.kira.common.pojo.Density;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.mapper.DensityMapper;
import com.kira.server.service.IDensityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author kira
 * @since 2025-06-27
 */
@Service
public class DensityServiceImpl extends ServiceImpl<DensityMapper, Density> implements IDensityService {

    @Autowired
    private DensityMapper mapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Map<String, List<ParameterVO>> queryDensity(ParametersDTO parameterDTO) {
        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));

        // 构建查询条件
        LambdaQueryWrapper<Density> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<Density>()
                .eq(Density::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
                .ge(Density::getSamplingTime, parameterDTO.getStartTime()) // 查询开始时间大于等于指定时间
                .le(Density::getSamplingTime, parameterDTO.getEndTime()) // 查询结束时间小于等于指定时间
                .orderByAsc(Density::getSamplingTime); // 按日期升序排列结果

        // 查询数据
        List<Density> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

        // 初始化结果Map，用于存放参数名和对应的ParameterVO列表
        Map<String, List<ParameterVO>> resultMap = new HashMap<>();

        resultMap.put("drilling_fluid_density", new ArrayList<>());

        // 遍历每条DrillingData记录
        for (Density drillingData : drillingDatas) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime()); // 设置参数的创建时间

                Double value = Double.valueOf(drillingData.getDrillingFluidDensity()); // 获取参数值
                parameterVO.setValue(value); // 设置参数值，如果为null则设置为0

                parameterVO.setRed(false);

                // 将ParameterVO添加到对应的List中
                resultMap.get("drilling_fluid_density").add(parameterVO);
            }

        return resultMap; // 返回最终的结果Map
    }
}
