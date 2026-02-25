package com.kira.server.controller;

import com.kira.server.domain.dto.ParametersDTO;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.common.result.Result;
import com.kira.server.service.IDensityService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @author Kira
 * @create 2025-06-27 20:24
 */
@RestController
@RequestMapping("/drilling/density")
@Api(tags = "密度相关接口")
public class DensityController {

    @Autowired
    private IDensityService service;

    @GetMapping
    @ApiOperation("查询密度曲线")
    public Result<Map<String, List<ParameterVO>>> queryDensity(ParametersDTO parameterDTO){
        return Result.success(service.queryDensity(parameterDTO));
    }

}
