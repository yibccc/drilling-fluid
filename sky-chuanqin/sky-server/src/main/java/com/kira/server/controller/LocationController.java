package com.kira.server.controller;

import com.kira.server.domain.entity.Location;
import com.kira.common.result.Result;
import com.kira.server.service.ILocationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 区域管理控制器
 * </p>
 *
 * @author kira
 * @since 2025-05-29
 */
@RestController
@RequestMapping("/location")
@Api(tags = "区域管理接口")
public class LocationController {
    
    @Autowired
    private ILocationService locationService;
    
    @GetMapping("/list")
    @ApiOperation(value = "获取所有区域")
    public Result<List<Location>> list() {
        List<Location> list = locationService.list();
        return Result.success(list);
    }
    
    @GetMapping("/{id}")
    @ApiOperation(value = "根据ID获取区域")
    public Result<Location> getById(@PathVariable Long id) {
        Location location = locationService.getById(id);
        if (location != null) {
            return Result.success(location);
        }
        return Result.error("未找到该区域");
    }
    
    @PostMapping("/{name}")
    @ApiOperation(value = "添加新区域")
    public Result<Location> save(@PathVariable String name) {
        // 设置创建时间和更新时间为当前时间
        Location location = new Location();
        location.setName(name);
        LocalDateTime now = LocalDateTime.now();
        location.setCreateTime(now);
        location.setUpdateTime(now);
        
        locationService.save(location);
        return Result.success(location);
    }
    
    @PutMapping
    @ApiOperation(value = "更新区域")
    public Result<String> update(@RequestBody Location location) {
        // 设置更新时间为当前时间
        location.setUpdateTime(LocalDateTime.now());
        
        locationService.updateById(location);
        return Result.success("区域更新成功");
    }
    
    @DeleteMapping("/{id}")
    @ApiOperation(value = "删除区域")
    public Result<String> delete(@PathVariable Long id) {
        locationService.removeById(id);
        return Result.success("区域删除成功");
    }
}
