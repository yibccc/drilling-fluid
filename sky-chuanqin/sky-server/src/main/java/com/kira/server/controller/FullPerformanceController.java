package com.kira.server.controller;


import com.kira.server.domain.dto.CaThresholdDTO;
import com.kira.server.domain.dto.FullPerformancePageDTO;
import com.kira.server.domain.dto.NotTreatedForLongTimeNewThresholdDTO;
import com.kira.server.domain.dto.SaltLayerThresholdDTO;
import com.kira.server.domain.entity.Location;
import com.kira.server.domain.entity.Well;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.vo.FullPerformancePageVO;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.common.result.Result;
import com.kira.server.service.IFullPerformanceService;
import com.kira.server.service.ILocationService;
import com.kira.server.service.IWellService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 钻井液全性能参数 前端控制器
 * </p>
 *
 * @author kira
 * @since 2024-10-25
 */
@Api(tags = "钻井液全性能参数")
@RestController
@RequestMapping("/drilling/fullperformance")
public class FullPerformanceController {

    @Autowired
    private IFullPerformanceService service;

    @Autowired
    private IWellService wellService;

    @Autowired
    private ILocationService locationService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 查询水基油基
     */
    @ApiOperation("查询水基油基")
    @GetMapping("/status")
    public Result<Integer> getStatus() {
        String statusStr = (String) redisTemplate.opsForValue().get(RedisKeys.STATUS.getKey());
        Integer status = statusStr != null ? Integer.parseInt(statusStr) : null;
        return Result.success(status);
    }

    /**
     * 查询井号
     */
    @ApiOperation("查询井号")
    @GetMapping("/wellid")
    public Result<String> getWellId() {
        String wellId = (String) redisTemplate.opsForValue().get("well");
        return Result.success(wellId);
    }

    /**
     * 查询层位
     */
    @ApiOperation("查询层位")
    @GetMapping("/welllocation")
    public Result<String> getWellLocation() {
        String wellLocation = (String) redisTemplate.opsForValue().get("location");
        return Result.success(wellLocation);
    }

    /**
     * 查询采集中的井号
     */
    @ApiOperation("查询采集中的井号")
    @GetMapping("/wellid/harvest")
    public Result<String> getWellIdHarvest() {
        String wellIdHarvest = (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME_HARVEST.getKey());
        return Result.success(wellIdHarvest);
    }

    /**
     * 查询采集中的区块号
     */
    @ApiOperation("查询采集中的区块号")
    @GetMapping("/welllocation/harvest")
    public Result<String> getWellLocationHarvest() {
        String locationHarvest = (String) redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME_HARVEST.getKey());
        return Result.success(locationHarvest);
    }

    /**
     * 设置水基油基
     * @param status
     * @return
     */
    @PutMapping("/{status}")
    @ApiOperation("设置水基油基")
    public Result setStatus(@PathVariable Integer status) {
        redisTemplate.opsForValue().set(RedisKeys.STATUS.getKey(), status.toString());
        return Result.success();
    }

    /**
     * 设置井号
     *
     * @param well_id
     * @return
     */
    @PutMapping("wellid/{well_id}")
    @ApiOperation("设置井号")
    public Result setWellId(@PathVariable String well_id) {
        redisTemplate.opsForValue().set("well", well_id);
        Well well = wellService.getById(well_id);
        redisTemplate.opsForValue().set(RedisKeys.WELL_NAME.getKey(), well.getName());
        return Result.success();
    }

    /**
     * 设置层位
     *
     * @param location
     * @return
     */
    @PutMapping("welllocation/{location}")
    @ApiOperation("设置层位")
    public Result setWellLocation(@PathVariable String location) {
        redisTemplate.opsForValue().set("location", location);

        Location locationName = locationService.getById(location);
        redisTemplate.opsForValue().set(RedisKeys.LOCATION_NAME.getKey(), locationName.getName());
        return Result.success();
    }

    /**
     * 设置采集中的井号
     *
     * @param well_id
     * @return
     */
    @PutMapping("wellid/harvest/{well_id}")
    @ApiOperation("设置采集中的井号")
    public Result setWellIdHarvest(@PathVariable String well_id) {
        redisTemplate.opsForValue().set(RedisKeys.WELL_NAME_HARVEST.getKey(), well_id);
        return Result.success();
    }

    /**
     * 设置采集中的区块号
     *
     * @param location
     * @return
     */
    @PutMapping("welllocation/harvest/{location}")
    @ApiOperation("设置采集中的区块号")
    public Result setWellLocationHarvest(@PathVariable String location) {
        redisTemplate.opsForValue().set(RedisKeys.LOCATION_NAME_HARVEST.getKey(), location);
        return Result.success();
    }

    @ApiOperation("分页查询历史数据")
    @GetMapping("/page")
    public Result<PageDTO<FullPerformancePageVO>> queryDrillingData(FullPerformancePageDTO query) {
        return Result.success(service.queryByDTO(query));
    }

    @ApiOperation("钙污染判定")
    @GetMapping("/capollution")
    public Result<Map<String, List<ParameterVO>>> isCaPollution(CaThresholdDTO threshold) {
        return Result.success(service.isCaPollution());
    }

    @ApiOperation("二氧化碳污染判定")
    @GetMapping("/co2pollution")
    public Result<Map<String, List<ParameterVO>>> isCo2Pollution(SaltLayerThresholdDTO threshold) {
        return Result.success(service.isCo2Pollution());
    }

    @ApiOperation("钻井液长效稳定判定")
    @GetMapping("/LongTime")
    public Result<Map<String, List<ParameterVO>>> NotTreatedForLongTimeNew(NotTreatedForLongTimeNewThresholdDTO threshold) {
        return Result.success(service.notTreatedForLongTimeNew());
    }

    @ApiOperation("测试污染判定")
    @GetMapping("test")
    public Result<Map<String, Boolean>> testAllPollution() {

        // 设置测试标志
        redisTemplate.opsForValue().set("isTest", "true", 8, TimeUnit.SECONDS);
        // 执行三种污染判定
        Map<String, List<ParameterVO>> caPollution = service.isCaPollution();
        Map<String, List<ParameterVO>> co2Pollution = service.isCo2Pollution();
        Map<String, List<ParameterVO>> stringListMap = service.notTreatedForLongTimeNew();

        Map<String, Boolean> pollution = new HashMap<>();
        pollution.put("钙污染检测", caPollution.get("pollution").get(0).isRed());
        pollution.put("co2污染检测", co2Pollution.get("pollution").get(0).isRed());
        pollution.put("长效稳定性检测", stringListMap.get("pollution").get(0).isRed());

        return Result.success(pollution);
    }

//    @ApiOperation("测试污染判定(推荐措施版)")
//    @GetMapping("test-new")
//    public Result<Map<String, String>> testAllPollutionNew() {
//
////        // 设置测试标志
////        redisTemplate.opsForValue().set("isTest",true,8, TimeUnit.SECONDS);
//        // 执行三种污染判定
//        Map<String, List<ParameterVO>> caPollution = service.isCaPollution();
//        Map<String, List<ParameterVO>> co2Pollution = service.isCo2Pollution();
//        Map<String, List<ParameterVO>> stringListMap = service.notTreatedForLongTimeNew();
//
//        final String caTestimonials = "ca";
//        final String co2Testimonials = "co2";
//        final String longTimeTestimonials = "longTime";
//
//        Map<String, String> pollution = new HashMap<>();
//        if (caPollution.get("pollution").get(0).isRed())
//            pollution.put("钙污染检测", caTestimonials);
//        if (co2Pollution.get("pollution").get(0).isRed())
//            pollution.put("co2污染检测", co2Testimonials);
//        if (stringListMap.get("pollution").get(0).isRed())
//            pollution.put("长效稳定性检测", longTimeTestimonials);
//        return Result.success(pollution);
//    }

    // 阈值（暂时固定）
//    @ApiOperation("钙污染阈值设置")
//    @GetMapping("/capollution-t")
//    public Result isCaPollutionT(CaThresholdDTO threshold) {
//        redisTemplate.opsForValue().set("drilling:threshold:ca", threshold);
//        return Result.success();
//    }
//
//    @ApiOperation("钙污染阈值查询")
//    @GetMapping("/capollution-s")
//    public Result<CaThresholdDTO> isCaPollutionS() {
//        CaThresholdDTO thresholdDTO = (CaThresholdDTO) redisTemplate.opsForValue().get("drilling:threshold:ca");
//        return Result.success(thresholdDTO);
//    }
//
//    @ApiOperation("二氧化碳污染阈值设置")
//    @GetMapping("/co2pollution-t")
//    public Result isCo2PollutionT(SaltLayerThresholdDTO threshold) {
//        redisTemplate.opsForValue().set("drilling:threshold:co2", threshold);
//        return Result.success();
//    }
//
//    @ApiOperation("二氧化碳污染阈值查询")
//    @GetMapping("/co2pollution-s")
//    public Result<SaltLayerThresholdDTO> isCo2PollutionS() {
//        SaltLayerThresholdDTO thresholdDTO = (SaltLayerThresholdDTO) redisTemplate.opsForValue().get("drilling:threshold:co2");
//        return Result.success(thresholdDTO);
//    }
//
//    @ApiOperation("钻井液长效稳定阈值设置")
//    @GetMapping("/LongTime-t")
//    public Result NotTreatedForLongTimeNewT(NotTreatedForLongTimeNewThresholdDTO threshold) {
//        redisTemplate.opsForValue().set("drilling:threshold:stability", threshold);
//        return Result.success();
//    }
//
//    @ApiOperation("钻井液长效稳定阈值查询")
//    @GetMapping("/LongTime-s")
//    public Result<NotTreatedForLongTimeNewThresholdDTO> NotTreatedForLongTimeNewST() {
//        NotTreatedForLongTimeNewThresholdDTO thresholdDTO = (NotTreatedForLongTimeNewThresholdDTO) redisTemplate.opsForValue().get("drilling:threshold:stability");
//        return Result.success(thresholdDTO);
//    }
}
