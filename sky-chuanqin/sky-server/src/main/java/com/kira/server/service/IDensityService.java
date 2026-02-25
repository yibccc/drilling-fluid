package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.server.domain.dto.ParametersDTO;
import com.kira.common.pojo.Density;
import com.kira.server.domain.vo.ParameterVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author kira
 * @since 2025-06-27
 */
public interface IDensityService extends IService<Density> {

    /**
     * 查询密度数据
     * @param parameterDTO
     * @return
     */
    Map<String, List<ParameterVO>> queryDensity(ParametersDTO parameterDTO);
}
