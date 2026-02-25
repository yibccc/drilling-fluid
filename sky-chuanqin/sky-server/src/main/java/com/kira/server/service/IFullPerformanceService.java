package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.server.domain.dto.FullPerformancePageDTO;
import com.kira.common.pojo.ModbusData;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.vo.FullPerformancePageVO;
import com.kira.server.domain.vo.ParameterVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 钻井液全性能参数 服务类
 * </p>
 *
 * @author kira
 * @since 2024-10-25
 */
public interface IFullPerformanceService extends IService<ModbusData> {

    PageDTO<FullPerformancePageVO> queryByDTO(FullPerformancePageDTO query);

    Map<String, List<ParameterVO>> isCo2Pollution();

    Map<String, List<ParameterVO>> isCaPollution();

    Map<String, List<ParameterVO>> notTreatedForLongTimeNew();

}
