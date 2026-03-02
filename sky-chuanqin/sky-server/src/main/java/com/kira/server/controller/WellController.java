package com.kira.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kira.server.domain.dto.WellDTO;
import com.kira.server.domain.entity.Well;
import com.kira.common.result.Result;
import com.kira.server.service.IWellService;
import com.kira.server.service.WellConfigService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  井管理控制器
 * </p>
 *
 * @author kira
 * @since 2025-05-29
 */
@RestController
@RequestMapping("/well")
@Api(tags = "井管理接口")
public class WellController {
    
    @Autowired
    private IWellService wellService;

    @Autowired
    private WellConfigService wellConfigService;
    
    @GetMapping("/list")
    @ApiOperation(value = "获取所有井")
    public Result<List<Well>> list() {
        List<Well> list = wellService.list();
        return Result.success(list);
    }
    
    @GetMapping("/location/{locationId}")
    @ApiOperation(value = "根据区域ID获取井")
    public Result<List<Well>> getByLocationId(@PathVariable String locationId) {
        LambdaQueryWrapper<Well> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Well::getLocationId, locationId);
        List<Well> wells = wellService.list(queryWrapper);
        return Result.success(wells);
    }
    
    @GetMapping("/{id}")
    @ApiOperation(value = "根据ID获取井")
    public Result<Well> getById(@PathVariable Long id) {
        Well well = wellService.getById(id);
        if (well != null) {
            return Result.success(well);
        }
        return Result.error("未找到该井");
    }
    
    @PostMapping
    @ApiOperation(value = "添加新井")
    public Result<Well> save(@RequestBody WellDTO dto) {
        Well well = new Well();
        well.setName(dto.getName());
        well.setLocationId(dto.getLocation_id());
        // 设置创建时间和更新时间为当前时间
        LocalDateTime now = LocalDateTime.now();
        well.setCreateTime(now);
        well.setUpdateTime(now);
        
        wellService.save(well);
        return Result.success(well);
    }
    
    @PutMapping
    @ApiOperation(value = "更新井")
    public Result<String> update(@RequestBody Well well) {
        // 设置更新时间为当前时间
        well.setUpdateTime(LocalDateTime.now());
        
        wellService.updateById(well);
        return Result.success("井更新成功");
    }
    
    @DeleteMapping("/{id}")
    @ApiOperation(value = "删除井")
    public Result<String> delete(@PathVariable Long id) {
        wellService.removeById(id);
        return Result.success("井删除成功");
    }

    // ========== 井配置管理接口 ==========

    /**
     * 添加井到监控列表
     */
    @PostMapping("/add")
    @ApiOperation(value = "添加井到监控列表")
    public Result<Void> addWell(
            @ApiParam(value = "井号", required = true, example = "SHB001")
            @RequestParam String wellId) {
        wellConfigService.addWell(wellId);
        return Result.success();
    }

    /**
     * 从监控列表移除井
     */
    @DeleteMapping("/remove")
    @ApiOperation(value = "从监控列表移除井")
    public Result<Void> removeWell(
            @ApiParam(value = "井号", required = true, example = "SHB001")
            @RequestParam String wellId) {
        wellConfigService.removeWell(wellId);
        return Result.success();
    }

    /**
     * 获取所有活跃井
     */
    @GetMapping("/active")
    @ApiOperation(value = "获取所有活跃井")
    public Result<Set<String>> getActiveWells() {
        Set<String> activeWells = wellConfigService.getActiveWells();
        return Result.success(activeWells);
    }

    /**
     * 检查井是否活跃
     */
    @GetMapping("/check")
    @ApiOperation(value = "检查井是否活跃")
    public Result<Boolean> checkWellActive(
            @ApiParam(value = "井号", required = true, example = "SHB001")
            @RequestParam String wellId) {
        boolean isActive = wellConfigService.isWellActive(wellId);
        return Result.success(isActive);
    }
}
