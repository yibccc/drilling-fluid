package com.kira.server.controller;

import com.kira.server.domain.dto.SolutionDTO;
import com.kira.server.domain.entity.PollutionAlarmLog;
import com.kira.server.domain.query.PollutionAlarmLogQuery;
import com.kira.server.domain.vo.PollutionAlarmLogQueryVO;
import com.kira.server.domain.query.PageDTO;
import com.kira.common.result.Result;
import com.kira.server.service.IPollutionAlarmLogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@Api(tags = "报警记录模块")
@RequestMapping("/alarm")
public class PollutionAlarmLogController {

    @Autowired
    private IPollutionAlarmLogService service;

    @ApiOperation("查询报警记录")
    @GetMapping("/page")
    public Result<PageDTO<PollutionAlarmLogQueryVO>> queryAlarmLog(PollutionAlarmLogQuery query) {
        return Result.success(service.queryAlarmLog(query));
    }

    @ApiOperation("输入实际处理方案")
    @PutMapping("/solution")
    public Result inputSolution(@RequestBody SolutionDTO dto){
        PollutionAlarmLog alarmLog = new PollutionAlarmLog();
        alarmLog.setId(dto.getId());
        alarmLog.setHandleApproach(dto.getHandleApproach() == null? null : dto.getHandleApproach());
        alarmLog.setHandleChemicals(dto.getHandleChemicals() == null? null : dto.getHandleChemicals());
        service.updateById(alarmLog);
        return Result.success();
    }


}
