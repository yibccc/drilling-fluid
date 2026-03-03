package com.kira.server.controller;


import com.kira.common.pojo.DrillingData;
import com.kira.common.result.Result;
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
import com.kira.server.enums.RedisKeys;
import com.kira.server.service.IDrillingDataService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * @author kira
 * @since 2024-09-23
 */
@RestController
@Api(tags = "性能参数相关接口")
@RequestMapping("/drilling/data")
@Slf4j
public class DrillingDataController {

    @Autowired
    private IDrillingDataService drillingDataService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @ApiOperation("查询最新的一组数据")
    @GetMapping("/every5min/{id}")
    public Result<DrillingDataLatestVO> queryEvery5min(@PathVariable String id) {
        id = redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()).toString();
        return Result.success(drillingDataService.quertEvery5min(id));
    }

    @ApiOperation("图表展示（多选参数）-异常分析页面（左）")
    @GetMapping("/search-apriori")
    public Result<Map<String, List<ParameterVO>>> realTimeParametersApriori(ParametersDTO parameterDTO) {
        return Result.success(drillingDataService.queryParametersByDTOApriori(parameterDTO));
    }

    @ApiOperation("图表展示（多选参数）-性能参数页面")
    @GetMapping("/search")
    public Result<Map<String, List<ParameterVO>>> realTimeParameters(ParametersDTO parameterDTO) {
        return Result.success(drillingDataService.queryParametersByDTO(parameterDTO));
    }

    @ApiOperation("图表展示（多选参数）-钻速定制顺序版")
    @GetMapping("/search-rpm")
    public Result<Map<String, List<ParameterVO>>> realTimeParametersRpm(ParametersDTO parameterDTO) {
        return Result.success(drillingDataService.queryParametersByDTORpm(parameterDTO));
    }

    @ApiOperation("图表展示（多选参数）-切力定制顺序版")
    @GetMapping("/search-shear")
    public Result<Map<String, List<ParameterVO>>> realTimeParametersShear(ParametersDTO parameterDTO) {
        return Result.success(drillingDataService.queryParametersByDTOShear(parameterDTO));
    }

    @ApiOperation("斜率展示与预警-异常分析页面（右）")
    @GetMapping("/apriori")
    public Result<List<ParameterKVO>> aprioriParameters(ParametersDTO parameterKDTO) {
        return Result.success(drillingDataService.queryKByDTO(parameterKDTO));
    }

//    @ApiOperation("实时更新参数")
//    @GetMapping("/everymins")
//    public Result<Map<String, ParametersSetVO>> quertEvery5s(ParametersSetDTO dto){
//        return Result.success(drillingDataService.quertParametersSet(dto));
//    }

    @ApiOperation("分页查询本趟钻历史数据")
    @GetMapping("/pagethistrip")
    public Result<PageDTO<DrillingDataThisTripQueryVO>> queryDrillingThisTripData(DrillingDataQuery query) {
        return Result.success(drillingDataService.queryDrillingThisTripData(query));
    }

    //数据校验页面

    @ApiOperation("分页查询历史数据")
    @GetMapping("/page")
    public Result<PageDTO<DrillingDataQueryVO>> queryDrillingData(DrillingDataQuery query) {
        return Result.success(drillingDataService.queryDrillingData(query));
    }

    @ApiOperation("查询手动或对比数据列表")
    @GetMapping("/manual-list")
    public Result<List<DrillingDataQueryVO>> queryDrillingDataManual(ManualQuery query) {
        return Result.success(drillingDataService.queryDrillingDataManual(query));
    }

    @ApiOperation("填写工况")
    @PutMapping("/conditions")
    public Result updateDrillingOperatingConditions(@RequestBody DrillingOperatingConditions conditions) {
        drillingDataService.updateDrillingOperatingConditions(conditions);
        return Result.success();
    }

    @ApiOperation("手动输入数据")
    @PutMapping("/manual")
    public Result<DrillingDataQueryVO> updateDrillingManual(@RequestBody HandwrittenConditionsDTO dto) {
        DrillingDataQueryVO vo = drillingDataService.updateDrillingManual(dto);
        return Result.success(vo);
    }

    @ApiOperation("修改实测参数")
    @PutMapping("/real")
    public Result updateDrillingReal(@RequestBody DrillingDataQueryVO vo) {
        DrillingData drillingData = new DrillingData();
        BeanUtils.copyProperties(vo, drillingData);
        drillingDataService.updateById(drillingData);
        return Result.success();
    }
}
