package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.common.pojo.DrillingData;
import com.kira.server.domain.dto.DrillingOperatingConditions;
import com.kira.server.domain.dto.HandwrittenConditionsDTO;
import com.kira.server.domain.dto.ManualQuery;
import com.kira.server.domain.dto.ParametersDTO;
import com.kira.server.domain.query.DrillingDataQuery;
import com.kira.server.domain.query.DrillingDataQueryVO;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.vo.DrillingDataLatestVO;
import com.kira.server.domain.vo.DrillingDataThisTripQueryVO;
import com.kira.server.domain.vo.ParameterKVO;
import com.kira.server.domain.vo.ParameterVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author kira
 * @since 2024-09-23
 */
public interface IDrillingDataService extends IService<DrillingData> {

    Map<String, List<ParameterVO>> queryParametersByDTO(ParametersDTO parameterDTO);

    Map<String, List<ParameterVO>> queryParametersByDTOApriori(ParametersDTO parameterDTO);

    List<ParameterKVO> queryKByDTO(ParametersDTO parameterDTO);

    PageDTO<DrillingDataQueryVO> queryDrillingData(DrillingDataQuery query);

    void updateDrillingOperatingConditions(DrillingOperatingConditions conditions);

    DrillingDataQueryVO updateDrillingManual(HandwrittenConditionsDTO dto);

//    Map<String, ParametersSetVO> quertParametersSet(ParametersSetDTO dto);

    PageDTO<DrillingDataThisTripQueryVO> queryDrillingThisTripData(DrillingDataQuery query);

    List<DrillingDataQueryVO> queryDrillingDataManual(ManualQuery query);

    DrillingDataLatestVO quertEvery5min(String id);

    Map<String, List<ParameterVO>> queryParametersByDTORpm(ParametersDTO parameterDTO);

    Map<String, List<ParameterVO>> queryParametersByDTOShear(ParametersDTO parameterDTO);
}
