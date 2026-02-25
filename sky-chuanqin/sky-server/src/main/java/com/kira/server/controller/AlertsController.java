package com.kira.server.controller;


import com.kira.server.domain.vo.AlertQueryVO;
import com.kira.server.domain.dto.ExpertDTO;
import com.kira.server.domain.dto.ExpertMDTO;
import com.kira.server.domain.query.AlertQuery;
import com.kira.server.domain.query.PageDTO;
import com.kira.common.result.Result;
import com.kira.server.service.IAlertsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author kira
 * @since 2024-10-06
 */
@Api(tags = "预警管理")
@RestController
@RequestMapping("/alerts")
public class AlertsController {

    @Autowired
    private IAlertsService service;

    @ApiOperation("专家介入")
    @PutMapping("/expert")
    public Result expertChange(@RequestBody ExpertDTO dto) {
        service.expertChange(dto);
        return Result.success();
    }

    @ApiOperation("分页查询预警记录")
    @GetMapping("/page")
    public Result<PageDTO<AlertQueryVO>> queryVOResult(AlertQuery query){
        return Result.success(service.queryByDTO(query));
    }

    @ApiOperation("完善预警信息")
    @PutMapping("/messege")
    public Result expertMessage(@RequestBody ExpertMDTO dto) {
        service.expertMessage(dto);
        return Result.success();
    }

    @ApiOperation("展示详情")
    @GetMapping("/detail")
    public Result<String> queryVOById(Long id) {
        return Result.success(service.queryVOById(id));
    }

//    @ApiOperation("查询预警详情")
//    @GetMapping("/{id}")
//    public Result<String> queryVO(@PathVariable Long id){
//        return Result.success(service.queryDetailById(id));
//    }

}
