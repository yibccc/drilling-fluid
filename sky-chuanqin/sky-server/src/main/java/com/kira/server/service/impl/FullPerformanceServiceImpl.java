package com.kira.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.domain.dto.FullPerformancePageDTO;
import com.kira.common.pojo.DrillingData;
import com.kira.server.domain.entity.EgineeringParameters;
import com.kira.common.pojo.ModbusData;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.vo.FullPerformancePageVO;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.mapper.DrillingDataMapper;
import com.kira.server.mapper.EgineeringParametersMapper;
import com.kira.server.mapper.FullPerformanceMapper;
import com.kira.server.service.IFullPerformanceService;
import com.kira.server.service.IPollutionAlarmLogService;
import com.kira.common.websocket.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 钻井液全性能参数 服务实现类
 * </p>
 *
 * @author kira
 * @since 2024-10-25
 */
@Service
public class FullPerformanceServiceImpl extends ServiceImpl<FullPerformanceMapper, ModbusData> implements IFullPerformanceService {

    private static final Logger log = LoggerFactory.getLogger(FullPerformanceServiceImpl.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebSocketServer webSocketServer;

    // Redis日志键前缀
    private static final String LOG_KEY_PREFIX = "drilling:log:";
    private static final String CA_POLLUTION_LOG_KEY = LOG_KEY_PREFIX + "ca";
    private static final String CO2_POLLUTION_LOG_KEY = LOG_KEY_PREFIX + "co2";
    private static final String STABILITY_LOG_KEY = LOG_KEY_PREFIX + "stability";

    // Redis采样时间键
    private static final String SAMPLING_TIME_KEY = "drilling:sampling_time";

    // 采样时间保留时间（小时）
    private static final long SAMPLING_TIME_EXPIRY_HOURS = 24;


    @Autowired
    private FullPerformanceMapper mapper;

    @Autowired
    private EgineeringParametersMapper egineeringParametersMapper;

    @Autowired
    private DrillingDataMapper drillingDataMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IPollutionAlarmLogService pollutionAlarmLogService;


    /**
     * 保存日志到数据库（带参数）
     *
     * @param key          日志类型键
     * @param logLevel     日志级别
     * @param message      日志消息
     * @param samplingTime 采样时间
     * @param details      详细信息（可选）
     * @param detailsMap   参数详细Map（可选）
     */
    private boolean saveLogToDbWithParams(String key, String logLevel, String message,
                                          LocalDateTime samplingTime, String[] details,
                                          Map<String, Object> detailsMap) {
        try {
            // 获取井ID和位置
            Object wellId = redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
            Object wellLocation = redisTemplate.opsForValue().get(RedisKeys.LOCATION_NAME.getKey());

            String wellIdStr = wellId != null ? wellId.toString() : "";
            String wellLocationStr = wellLocation != null ? wellLocation.toString() : "";

            // 合并详细信息
            StringBuilder detailsBuilder = new StringBuilder();
            if (details != null && details.length > 0) {
                detailsBuilder.append(String.join("\n", details));
            }

            // 若有采样时间，添加到详细信息中
            if (samplingTime != null) {
                detailsBuilder.append("\n采样时间: ").append(samplingTime);
            }

            // 将detailsMap序列化为JSON字符串
            String jsonDetails = null;
            if (detailsMap != null) {
                try {
                    // 确保使用注入的objectMapper来避免序列化问题
                    jsonDetails = objectMapper.writeValueAsString(detailsMap);
                    
                    // 记录异常参数到日志，帮助调试
                    if (detailsMap.containsKey("abnormalParameters")) {
                        log.info("异常参数列表: {}", detailsMap.get("abnormalParameters"));
                    }
                } catch (Exception e) {
                    log.error("序列化详情Map失败: {}", e.getMessage());
                }
            }

            // 根据日志类型直接调用相应的服务方法
            if (key.equals(CA_POLLUTION_LOG_KEY)) {
                return pollutionAlarmLogService.saveCaPollutionLog(
                        wellIdStr, wellLocationStr, true, detailsBuilder.toString(), jsonDetails);
            } else if (key.equals(CO2_POLLUTION_LOG_KEY)) {
                return pollutionAlarmLogService.saveCO2PollutionLog(
                        wellIdStr, wellLocationStr, true, detailsBuilder.toString(), jsonDetails);
            } else if (key.equals(STABILITY_LOG_KEY)) {
                return pollutionAlarmLogService.saveStabilityPollutionLog(
                        wellIdStr, wellLocationStr, true, detailsBuilder.toString(), jsonDetails);
            } else {
                log.warn("未知的日志类型键: {}", key);
            }
        } catch (Exception e) {
            log.error("保存日志到数据库失败: {}", e.getMessage(), e);
        }
        return false;
    }

    /**
     * 通用的数据查询方法
     *
     * @param wellId 井ID
     * @param limit  查询数量限制
     * @return 查询结果列表
     */
    private List<ModbusData> queryPerformanceRecords(String wellId, int limit) {
        try {
            LambdaQueryWrapper<ModbusData> queryWrapper = new LambdaQueryWrapper<ModbusData>()
                    .eq(ModbusData::getWellId, wellId)
                    .orderByDesc(ModbusData::getSamplingTime)
                    .last("limit " + limit);
            List<ModbusData> records = mapper.selectList(queryWrapper);
            if (records != null && !records.isEmpty()) {
                // 按采样时间升序排序
                records.sort(Comparator.comparing(ModbusData::getSamplingTime));
            }
            return records != null ? records : new ArrayList<>();
        } catch (Exception e) {
            System.err.println("查询数据失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 测试数据查询方法
     *
     * @param wellId 井ID
     * @param limit  查询数量限制
     * @return 查询结果列表
     */
    private List<ModbusData> queryPerformanceRecordsTest(String wellId, int limit) {
        try {
            limit = limit + 6;
            // 构建查询条件
            LambdaQueryWrapper<DrillingData> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.in(DrillingData::getIsHandwritten, 0, 1)
                    .eq(DrillingData::getWellId, wellId)
                    .orderByDesc(DrillingData::getSamplingTime)
                    .last("limit " + limit);

            // 执行查询
            List<DrillingData> records = drillingDataMapper.selectList(queryWrapper);
            if (records == null || records.isEmpty()) {
                return new ArrayList<>();
            }

            // 使用Map按采样时间分组，保留isHandwritten为1的记录
            Map<LocalDateTime, DrillingData> uniqueRecords = new LinkedHashMap<>();
            for (DrillingData record : records) {
                LocalDateTime samplingTime = record.getSamplingTime();
                DrillingData existingRecord = uniqueRecords.get(samplingTime);

                if (existingRecord == null || record.getIsHandwritten() == 1) {
                    uniqueRecords.put(samplingTime, record);
                }
            }

            // 将DrillingData转换为ModbusData
            List<ModbusData> result = new ArrayList<>();
            for (DrillingData drillingData : uniqueRecords.values()) {
                ModbusData modbusData = new ModbusData();
                // 复制相同的字段
                BeanUtil.copyProperties(drillingData, modbusData);
                result.add(modbusData);
            }

            // 按采样时间升序排序
            result.sort(Comparator.comparing(ModbusData::getSamplingTime));
            
            return result;
        } catch (Exception e) {
            log.error("查询数据失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 通用的结果Map创建方法（不带参数名称列表）
     *
     * @param records    数据记录
     * @param isPolluted 是否污染
     * @return 结果Map
     */
    private Map<String, List<ParameterVO>> createCommonResultMap(List<ModbusData> records, boolean isPolluted) {
        return createResultMap(records,
                Arrays.asList("rpm6", "rpm3", "drilling_fluid_density", "apparent_viscosity",
                        "plastic_viscosity", "shear_force10m", "shear_force10s", "api_filtration_loss"),
                isPolluted);
    }

    /**
     * 查询全性能参数与部分工程参数
     *
     * @param query
     * @return
     */
    public PageDTO<FullPerformancePageVO> queryByDTO(FullPerformancePageDTO query) {
        // 1.构建分页条件
        Page<ModbusData> page = Page.of(query.getPageNo(), query.getPageSize());


        // 2.构建查询条件
        LambdaQueryWrapper<ModbusData> queryWrapper = new LambdaQueryWrapper<ModbusData>()
                .eq(ModbusData::getWellId, query.getWellId())
                .ge(ModbusData::getSamplingTime, query.getStartTime())
                .le(ModbusData::getSamplingTime, query.getEndTime());


        // 3.查询分页数据
        mapper.selectPage(page, queryWrapper);


        // 4.数据非空校验
        List<ModbusData> records = page.getRecords();
        if (records == null || records.isEmpty()) {
            return new PageDTO<>(page.getTotal(), page.getPages(), Collections.emptyList());
        }


        // 5.查询额外参数
        String startDate = query.getStartTime().toLocalDate().toString();
        String startTime = query.getStartTime().toLocalTime().toString();
        String endDate = query.getEndTime().toLocalDate().toString();
        String endTime = query.getEndTime().toLocalTime().toString();


        // 查询条件范围内的工程参数
        List<EgineeringParameters> engineeringParams = egineeringParametersMapper.selectByDateAndTime(
                query.getWellId(), startDate, startTime, endDate, endTime);


        // 数据映射：根据日期和时间组合成 key
        Map<String, EgineeringParameters> paramMap = new HashMap<>();
        for (EgineeringParameters param : engineeringParams) {
            paramMap.put(param.getWelldate() + " " + param.getWelltime(), param);
        }


        // 6.转换分页数据并追加参数
        List<FullPerformancePageVO> resultList = new ArrayList<>();
        for (ModbusData record : records) {
            FullPerformancePageVO vo = BeanUtil.copyProperties(record, FullPerformancePageVO.class);
            LocalDateTime samplingTime = record.getSamplingTime();


            // 格式化日期和时间，确保精度一致
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String samplingTimeKey = samplingTime.format(formatter);


            // 查找参数并追加
            EgineeringParameters params = paramMap.get(samplingTimeKey);
            if (params != null) {
                vo.setCw(params.getCw());
                vo.setDep(params.getDep());
                vo.setHokhei(params.getHokhei());
                vo.setRpm(params.getRpm());
            }


            resultList.add(vo);
        }


        // 7.封装并返回
        return new PageDTO<>(page.getTotal(), page.getPages(), resultList);
    }

    /**
     * 计算参数变化量
     *
     * @param currentValue  当前值
     * @param previousValue 前一个值
     * @return 变化量
     */
    private double calculateChangeRate(Double currentValue, Double previousValue) {
        // 确保值不为null
        currentValue = currentValue != null ? currentValue : 0.0;
        previousValue = previousValue != null ? previousValue : 0.0;

        // 处理除以零的情况
        if (previousValue == 0) {
            // 如果前值为0，根据当前值情况返回适当的变化率
            if (currentValue == 0) {
                return 0.0; // 两个值都是0，没有变化
            } else if (currentValue > 0) {
                return 1.0; // 从0变为正数，返回100%变化率
            } else {
                return -1.0; // 从0变为负数，返回-100%变化率
            }
        }

        // 计算变化率
        double changeRate = (currentValue - previousValue) / Math.abs(previousValue);
        
        // 限制变化率的极值，防止过大的数值
        if (changeRate > 10.0) {
            return 10.0; // 最大变化率为1000%
        } else if (changeRate < -10.0) {
            return -10.0; // 最小变化率为-1000%
        }
        
        return changeRate;
    }

    /**
     * co2污染判定
     *
     */
    public Map<String, List<ParameterVO>> isCo2Pollution() {

        boolean isTest;

        String testStr = (String) redisTemplate.opsForValue().get("isTest");
        if (testStr == null){
            isTest = false;
        }else {
            isTest = Boolean.parseBoolean(testStr);
        }
        List<ModbusData> fullPerformances = null;
        if (isTest) {
            fullPerformances = queryPerformanceRecordsTest((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 10);
        } else {
            fullPerformances = queryPerformanceRecords((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 16);
        }

        // 专家经验阈值（根据表格设置）
        // 钙离子变化率阈值
        final double CA_YELLOW_THRESHOLD = -0.20; // 黄色预警（轻度污染）-20%
        final double CA_ORANGE_THRESHOLD = -0.40; // 橙色预警（中度污染）-40%
        final double CA_RED_THRESHOLD = -0.60;    // 红色预警（重度污染）-60%

        // pH值变化率阈值
        final double PH_YELLOW_THRESHOLD = -0.05; // 黄色预警（轻度污染）-5%
        final double PH_ORANGE_THRESHOLD = -0.10; // 橙色预警（中度污染）-10%
        final double PH_RED_THRESHOLD = -0.15;    // 红色预警（重度污染）-15%

        // 表观粘度(AV)变化率阈值
        final double AV_YELLOW_THRESHOLD = 0.10;  // 黄色预警（轻度污染）+10%
        final double AV_ORANGE_THRESHOLD = 0.20;  // 橙色预警（中度污染）+20%
        final double AV_RED_THRESHOLD = 0.40;     // 红色预警（重度污染）+40%

        // 切力(YP)变化率阈值
        final double YP_YELLOW_THRESHOLD = 0.20;  // 黄色预警（轻度污染）+20%
        final double YP_ORANGE_THRESHOLD = 0.50;  // 橙色预警（中度污染）+50%
        final double YP_RED_THRESHOLD = 1.00;     // 红色预警（重度污染）+100%

        // API滤失量变化率阈值
        final double API_YELLOW_THRESHOLD = 0.30;  // 黄色预警（轻度污染）+30%
        final double API_ORANGE_THRESHOLD = 0.80;  // 橙色预警（中度污染）+80%
        final double API_RED_THRESHOLD = 2.00;     // 红色预警（重度污染）+200%

        // 初始化变化率记录
        double caIonChangeRate = 0;
        double phChangeRate = 0;
        double avChangeRate = 0;
        double ypChangeRate = 0;
        double apiLossChangeRate = 0;

        // 污染级别计数
        int yellowCount = 0;
        int orangeCount = 0;
        int redCount = 0;

        // 如果数据不足，无法进行分析
        if (fullPerformances.size() < 2) {
            Map<String, List<ParameterVO>> resultMap = initializeResultMap(fullPerformances);
            addPollutionFlag(resultMap, false);
            return resultMap;
        }

        // 计算各参数的变化率
        for (int i = 1; i < fullPerformances.size(); i++) {
            ModbusData current = fullPerformances.get(i);
            ModbusData previous = fullPerformances.get(i - 1);

            // 计算钙离子变化率（CO2污染会导致钙离子下降）
            if (current.getCalciumIonContent() != null && previous.getCalciumIonContent() != null && previous.getCalciumIonContent() > 0) {
                double currentCa = current.getCalciumIonContent();
                double previousCa = previous.getCalciumIonContent();
                caIonChangeRate = (currentCa - previousCa) / previousCa;
            }

            // 计算pH值变化率（CO2污染会导致pH值下降）
            if (current.getPhValue() != null && previous.getPhValue() != null && previous.getPhValue() > 0) {
                double currentPh = current.getPhValue();
                double previousPh = previous.getPhValue();
                phChangeRate = (currentPh - previousPh) / previousPh;
            }

            // 计算表观粘度变化率（CO2污染会导致表观粘度上升）
            if (current.getApparentViscosity() != null && previous.getApparentViscosity() != null && previous.getApparentViscosity() > 0) {
                double currentAv = current.getApparentViscosity();
                double previousAv = previous.getApparentViscosity();
                avChangeRate = (currentAv - previousAv) / previousAv;
            }

            // 计算切力变化率（CO2污染会导致切力上升）
            if (current.getYieldPoint() != null && previous.getYieldPoint() != null && previous.getYieldPoint() > 0) {
                double currentYp = current.getYieldPoint();
                double previousYp = previous.getYieldPoint();
                ypChangeRate = (currentYp - previousYp) / previousYp;
            }

            // 计算API滤失量变化率（CO2污染会导致API滤失量上升）
            if (current.getApiFiltrationLoss() != null && previous.getApiFiltrationLoss() != null && previous.getApiFiltrationLoss() > 0) {
                double currentApiLoss = current.getApiFiltrationLoss();
                double previousApiLoss = previous.getApiFiltrationLoss();
                apiLossChangeRate = (currentApiLoss - previousApiLoss) / previousApiLoss;
            }
            // 根据变化率判断污染级别
            // 钙离子判断
            if (caIonChangeRate <= CA_RED_THRESHOLD) {
                redCount++;
            } else if (caIonChangeRate <= CA_ORANGE_THRESHOLD) {
                orangeCount++;
            } else if (caIonChangeRate <= CA_YELLOW_THRESHOLD) {
                yellowCount++;
            }

            // pH值判断
            if (phChangeRate <= PH_RED_THRESHOLD) {
                redCount++;
            } else if (phChangeRate <= PH_ORANGE_THRESHOLD) {
                orangeCount++;
            } else if (phChangeRate <= PH_YELLOW_THRESHOLD) {
                yellowCount++;
            }

            // 表观粘度判断
            if (avChangeRate >= AV_RED_THRESHOLD) {
                redCount++;
            } else if (avChangeRate >= AV_ORANGE_THRESHOLD) {
                orangeCount++;
            } else if (avChangeRate >= AV_YELLOW_THRESHOLD) {
                yellowCount++;
            }

            // 切力判断
            if (ypChangeRate >= YP_RED_THRESHOLD) {
                redCount++;
            } else if (ypChangeRate >= YP_ORANGE_THRESHOLD) {
                orangeCount++;
            } else if (ypChangeRate >= YP_YELLOW_THRESHOLD) {
                yellowCount++;
            }

            // API滤失量判断
            if (apiLossChangeRate >= API_RED_THRESHOLD) {
                redCount++;
            } else if (apiLossChangeRate >= API_ORANGE_THRESHOLD) {
                orangeCount++;
            } else if (apiLossChangeRate >= API_YELLOW_THRESHOLD) {
                yellowCount++;
            }
        }

        // 初始化结果Map
        Map<String, List<ParameterVO>> resultMap = initializeResultMapForCO2(fullPerformances);

        // 综合判断污染级别
        String pollutionLevel = "NONE";
        boolean isPolluted = false;

        // 如果有3个或以上指标达到红色预警，判定为重度污染
        if (redCount >= 3) {
            pollutionLevel = "SEVERE";
            isPolluted = true;
            log.warn("【CO₂污染警报】检测到重度CO₂污染");
        }
        // 如果有3个或以上指标达到橙色预警，判定为中度污染
        else if (orangeCount + redCount >= 3) {
            pollutionLevel = "MEDIUM";
            isPolluted = true;
            log.warn("【CO₂污染警报】检测到中度CO₂污染");
        }
        // 如果有3个或以上指标达到黄色预警，判定为轻度污染
        else if (yellowCount + orangeCount + redCount >= 3) {
            pollutionLevel = "LIGHT";
            isPolluted = true;
            log.warn("【CO₂污染警报】检测到轻度CO₂污染");
        }

        // 记录污染参数
        if (isPolluted) {
            logCO2PollutionParameters(caIonChangeRate, phChangeRate, avChangeRate, ypChangeRate, apiLossChangeRate, pollutionLevel);
        }

        // 添加污染标志
        addPollutionFlag(resultMap, isPolluted);

        return resultMap;
    }

    /**
     * 记录CO2污染参数
     *
     * @param caIonChangeRate   钙离子变化率
     * @param phChangeRate      pH值变化率
     * @param avChangeRate      表观粘度变化率
     * @param ypChangeRate      切力变化率
     * @param apiLossChangeRate API滤失量变化率
     * @param pollutionLevel    污染级别 (LIGHT, MEDIUM, SEVERE)
     */
    private void logCO2PollutionParameters(double caIonChangeRate, double phChangeRate,
                                           double avChangeRate, double ypChangeRate,
                                           double apiLossChangeRate, String pollutionLevel) {
        log.info("CO₂污染参数变化分析：");

        // 钙离子变化分析
        String caStatus = getPollutionLevelDescription(caIonChangeRate, -0.20, -0.40, -0.60, true);
        log.info("钙离子变化率: {}% - {}", caIonChangeRate * 100, caStatus);

        // pH值变化分析
        String phStatus = getPollutionLevelDescription(phChangeRate, -0.05, -0.10, -0.15, true);
        log.info("pH值变化率: {}% - {}", phChangeRate * 100, phStatus);

        // 表观粘度变化分析
        String avStatus = getPollutionLevelDescription(avChangeRate, 0.10, 0.20, 0.40, false);
        log.info("表观粘度(AV)变化率: {}% - {}", avChangeRate * 100, avStatus);

        // 切力变化分析
        String ypStatus = getPollutionLevelDescription(ypChangeRate, 0.20, 0.50, 1.00, false);
        log.info("切力(YP)变化率: {}% - {}", ypChangeRate * 100, ypStatus);

        // API滤失量变化分析
        String apiStatus = getPollutionLevelDescription(apiLossChangeRate, 0.30, 0.80, 2.00, false);
        log.info("API滤失量变化率: {}% - {}", apiLossChangeRate * 100, apiStatus);

        // 综合评估
        log.info("综合评估: {} CO₂污染", pollutionLevel.equals("SEVERE") ? "重度" :
                pollutionLevel.equals("MEDIUM") ? "中度" : "轻度");

        // 构建文本格式的details数组
        String[] details = {
                String.format("钙离子变化率: %.2f%% - %s", caIonChangeRate * 100, caStatus),
                String.format("pH值变化率: %.2f%% - %s", phChangeRate * 100, phStatus),
                String.format("表观粘度(AV)变化率: %.2f%% - %s", avChangeRate * 100, avStatus),
                String.format("切力(YP)变化率: %.2f%% - %s", ypChangeRate * 100, ypStatus),
                String.format("API滤失量变化率: %.2f%% - %s", apiLossChangeRate * 100, apiStatus),
                String.format("综合评估: %s CO₂污染",
                        pollutionLevel.equals("SEVERE") ? "重度" :
                                pollutionLevel.equals("MEDIUM") ? "中度" : "轻度")
        };

        // 创建包含参数变化和污染程度的HashMap
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("caIonChange", caIonChangeRate * 100);
        detailsMap.put("phChange", phChangeRate * 100);
        detailsMap.put("viscosityChange", avChangeRate * 100);
        detailsMap.put("shearForceChange", ypChangeRate * 100);
        detailsMap.put("apiLossChange", apiLossChangeRate * 100);
        detailsMap.put("severity", pollutionLevel);

        // 确定日志级别
        String logLevel = pollutionLevel.equals("SEVERE") ? "ERROR" :
                pollutionLevel.equals("MEDIUM") ? "WARN" : "INFO";

        // 获取当前采样时间
        LocalDateTime samplingTime = getCurrentSamplingTime();

        // 保存日志到数据库
        boolean pollutionFlag = saveLogToDbWithParams(CO2_POLLUTION_LOG_KEY, logLevel,
                String.format("CO₂污染警报：检测到%s CO₂污染",
                        pollutionLevel.equals("SEVERE") ? "重度" :
                                pollutionLevel.equals("MEDIUM") ? "中度" : "轻度"),
                samplingTime, details, detailsMap);

        if (pollutionFlag) {
            // 通过WebSocket发送预警
            sendPollutionAlert("CO2污染", (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));
        }
    }

    /**
     * 获取污染级别描述
     *
     * @param changeRate      变化率
     * @param yellowThreshold 黄色预警阈值
     * @param orangeThreshold 橙色预警阈值
     * @param redThreshold    红色预警阈值
     * @param isNegative      是否为负向变化（如钙离子、pH值下降为污染特征）
     * @return 污染级别描述
     */
    private String getPollutionLevelDescription(double changeRate, double yellowThreshold,
                                                double orangeThreshold, double redThreshold,
                                                boolean isNegative) {
        if (isNegative) {
            // 负向变化（如钙离子、pH值）
            if (changeRate <= redThreshold) {
                return "重度污染";
            } else if (changeRate <= orangeThreshold) {
                return "中度污染";
            } else if (changeRate <= yellowThreshold) {
                return "轻度污染";
            } else {
                return "正常";
            }
        } else {
            // 正向变化（如表观粘度、切力、API滤失量）
            if (changeRate >= redThreshold) {
                return "重度污染";
            } else if (changeRate >= orangeThreshold) {
                return "中度污染";
            } else if (changeRate >= yellowThreshold) {
                return "轻度污染";
            } else {
                return "正常";
            }
        }
    }

    // 初始化结果Map
    private Map<String, List<ParameterVO>> initializeResultMap(List<ModbusData> fullPerformances) {
        Map<String, List<ParameterVO>> resultMap = new HashMap<>();
        List<String> paramNames = Arrays.asList("drilling_fluid_density", "apparent_viscosity", "plastic_viscosity", "shear_force10m", "shear_force10s", "api_filtration_loss");


        for (String paramName : paramNames) {
            resultMap.put(paramName, new ArrayList<>());
        }


        for (ModbusData drillingData : fullPerformances) {
            for (String paramName : paramNames) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime());
                Double value = getParameterValue(drillingData, paramName);
                parameterVO.setValue(value != null ? value : 0);
                resultMap.get(paramName).add(parameterVO);
            }
        }


        return resultMap;
    }

    // 初始化结果Map
    private Map<String, List<ParameterVO>> initializeResultMapForCO2(List<ModbusData> fullPerformances) {
        Map<String, List<ParameterVO>> resultMap = new HashMap<>();
        List<String> paramNames = Arrays.asList("ph_value", "apparent_viscosity", "calcium_ion_content", "yield_point", "api_filtration_loss");


        for (String paramName : paramNames) {
            resultMap.put(paramName, new ArrayList<>());
        }


        for (ModbusData drillingData : fullPerformances) {
            for (String paramName : paramNames) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime());
                Double value = getParameterValue(drillingData, paramName);
                parameterVO.setValue(value != null ? value : 0);
                resultMap.get(paramName).add(parameterVO);
            }
        }


        return resultMap;
    }

    // 添加污染标志
    private void addPollutionFlag(Map<String, List<ParameterVO>> resultMap, boolean isPolluted) {
        resultMap.put("pollution", new ArrayList<>());
        ParameterVO parameterVO = new ParameterVO();
        parameterVO.setRed(isPolluted);
        resultMap.get("pollution").add(parameterVO);
    }

    /**
     * 钙污染
     *
     * @return
     */
    public Map<String, List<ParameterVO>> isCaPollution() {
        // 查询数据
        boolean isTest;

        String testStr = (String) redisTemplate.opsForValue().get("isTest");
        if (testStr == null){
            isTest = false;
        }else {
            isTest = Boolean.parseBoolean(testStr);
        }
        List<ModbusData> records;
        if (isTest) {
            records = queryPerformanceRecordsTest((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 10);
        } else {
            records = queryPerformanceRecords((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 16);
        }

        // 处理 records 为 null 或空的情况
        if (records.isEmpty()) {
            return createCommonResultMap(new ArrayList<>(), false);
        }

        // 专家经验阈值 - 初期预警阶段
        final double AV_EARLY_MIN = 0.10; // 表观黏度 +10%
        final double AV_EARLY_MAX = 0.30; // 表观黏度 +30%
        final double PV_EARLY_MIN = 0.05; // 塑性黏度 +5%
        final double PV_EARLY_MAX = 0.15; // 塑性黏度 +15%
        final double YP_EARLY_MIN = 0.20; // 切力 +20%
        final double YP_EARLY_MAX = 0.50; // 切力 +50%
        final double SF10S_EARLY_MIN = 0.10; // 初切力10s +10%
        final double SF10S_EARLY_MAX = 0.30; // 初切力10s +30%
        final double SF10M_EARLY_MIN = 0.15; // 终切力10min +15%
        final double SF10M_EARLY_MAX = 0.40; // 终切力10min +40%
        final double RPM6_EARLY_MIN = 0.10; // 6转 +10%
        final double RPM6_EARLY_MAX = 0.30; // 6转 +30%
        final double RPM3_EARLY_MIN = 0.15; // 3转 +15%
        final double RPM3_EARLY_MAX = 0.40; // 3转 +40%
        final double CA_EARLY_MIN = 0.05; // 钙离子 +5%
        final double CA_EARLY_MAX = 0.15; // 钙离子 +15%

        // 专家经验阈值 - 后期确认阶段
        final double AV_CONFIRM_MIN = 0.20; // 表观黏度 +20%
        final double AV_CONFIRM_MAX = 0.50; // 表观黏度 +50%
        final double PV_CONFIRM_MIN = 0.10; // 塑性黏度 +10%
        final double PV_CONFIRM_MAX = 0.25; // 塑性黏度 +25%
        final double YP_CONFIRM_MIN = 0.50; // 切力 +50%
        final double YP_CONFIRM_MAX = 1.00; // 切力 +100%
        final double SF10S_CONFIRM_MIN = 0.20; // 初切力10s +20%
        final double SF10S_CONFIRM_MAX = 0.50; // 初切力10s +50%
        final double SF10M_CONFIRM_MIN = 0.30; // 终切力10min +30%
        final double SF10M_CONFIRM_MAX = 0.70; // 终切力10min +70%
        final double RPM6_CONFIRM_MIN = 0.20; // 6转 +20%
        final double RPM6_CONFIRM_MAX = 0.50; // 6转 +50%
        final double RPM3_CONFIRM_MIN = 0.30; // 3转 +30%
        final double RPM3_CONFIRM_MAX = 0.70; // 3转 +70%
        final double CA_CONFIRM_MIN = 0.10; // 钙离子 +10%
        final double CA_CONFIRM_MAX = 0.30; // 钙离子 +30%
        final double API_LOSS_MIN = 0.20; // API滤失量 +20%
        final double API_LOSS_MAX = 0.50; // API滤失量 +50%
        final double API_CAKE_MIN = 0.30; // API滤饼厚度 +30%
        final double API_CAKE_MAX = 0.80; // API滤饼厚度 +80%
        final double PH_MIN = -0.10; // pH值 -10%
        final double PH_MAX = -0.05; // pH值 -5%

        boolean initialAlarmTriggered = false;
        boolean confirmationAlarmTriggered = false;
        
        log.info("开始钙污染检测，数据点数量: {}", records.size());
        
        // 修改为相邻数据点之间的比较
        if (records.size() >= 2) {
            // 遍历数据点，比较相邻数据点之间的变化
            for (int i = 1; i < records.size(); i++) {
                ModbusData current = records.get(i);
                ModbusData previous = records.get(i-1);
                
                log.info("比较数据点 {} 和 {}: {} -> {}", 
                    i-1, i, previous.getSamplingTime(), current.getSamplingTime());
                
                // 计算各参数变化率
                double avChangeRate = calculateChangeRate(current.getApparentViscosity(), previous.getApparentViscosity());
                double pvChangeRate = calculateChangeRate(current.getPlasticViscosity(), previous.getPlasticViscosity());
                double ypChangeRate = 0;
                if (current.getYieldPoint() != null && previous.getYieldPoint() != null) {
                    ypChangeRate = calculateChangeRate(current.getYieldPoint(), previous.getYieldPoint());
                } else {
                    // 如果YieldPoint为空，使用计算公式：YP = SF10m - PV
                    double currentYP = current.getShearForce10m() != null && current.getPlasticViscosity() != null ? 
                            current.getShearForce10m() - current.getPlasticViscosity() : 0;
                    double previousYP = previous.getShearForce10m() != null && previous.getPlasticViscosity() != null ? 
                            previous.getShearForce10m() - previous.getPlasticViscosity() : 0;
                    ypChangeRate = calculateChangeRate(currentYP, previousYP);
                }
                double sf10sChangeRate = calculateChangeRate(current.getShearForce10s(), previous.getShearForce10s());
                double sf10mChangeRate = calculateChangeRate(current.getShearForce10m(), previous.getShearForce10m());
                double rpm6ChangeRate = calculateChangeRate(current.getRpm6(), previous.getRpm6());
                double rpm3ChangeRate = calculateChangeRate(current.getRpm3(), previous.getRpm3());
                double caChangeRate = calculateChangeRate(current.getCalciumIonContent(), previous.getCalciumIonContent());
                double apiLossChangeRate = calculateChangeRate(current.getApiFiltrationLoss(), previous.getApiFiltrationLoss());
                double apiCakeChangeRate = calculateChangeRate(current.getApiFilterCakeThickness(), previous.getApiFilterCakeThickness());
                double phChangeRate = calculateChangeRate(current.getPhValue(), previous.getPhValue());
                
                // 日志输出变化率，便于调试
                log.info("参数变化率: AV={}, PV={}, YP={}, SF10s={}, SF10m={}, RPM6={}, RPM3={}, Ca={}",
                    String.format("%.2f%%", avChangeRate*100),
                    String.format("%.2f%%", pvChangeRate*100),
                    String.format("%.2f%%", ypChangeRate*100),
                    String.format("%.2f%%", sf10sChangeRate*100),
                    String.format("%.2f%%", sf10mChangeRate*100),
                    String.format("%.2f%%", rpm6ChangeRate*100),
                    String.format("%.2f%%", rpm3ChangeRate*100),
                    String.format("%.2f%%", caChangeRate*100));
                log.info("滞后参数变化率: API滤失={}, 滤饼厚度={}, pH={}",
                    String.format("%.2f%%", apiLossChangeRate*100),
                    String.format("%.2f%%", apiCakeChangeRate*100),
                    String.format("%.2f%%", phChangeRate*100));
                
                // 后期确认阶段检查: 至少4个流变参数达到后期确认阈值 + 滞后参数异常
                int confirmRheologyCount = 0;
                
                // 检查各流变参数是否在后期确认范围内
                if (avChangeRate >= AV_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("表观黏度变化率{}%达到后期确认阈值", avChangeRate * 100);
                }
                if (pvChangeRate >= PV_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("塑性黏度变化率{}%达到后期确认阈值", pvChangeRate * 100);
                }
                if (ypChangeRate >= YP_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("切力变化率{}%达到后期确认阈值", ypChangeRate * 100);
                }
                if (sf10sChangeRate >= SF10S_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("初切力变化率{}%达到后期确认阈值", sf10sChangeRate * 100);
                }
                if (sf10mChangeRate >= SF10M_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("终切力变化率{}%达到后期确认阈值", sf10mChangeRate * 100);
                }
                if (rpm6ChangeRate >= RPM6_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("6转变化率{}%达到后期确认阈值", rpm6ChangeRate * 100);
                }
                if (rpm3ChangeRate >= RPM3_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("3转变化率{}%达到后期确认阈值", rpm3ChangeRate * 100);
                }
                if (caChangeRate >= CA_CONFIRM_MIN) {
                    confirmRheologyCount++;
                    log.info("钙离子变化率{}%达到后期确认阈值", caChangeRate * 100);
                }
                
                boolean filterCondition = (apiLossChangeRate >= API_LOSS_MIN) || 
                                         (apiCakeChangeRate >= API_CAKE_MIN) || 
                                         (phChangeRate <= PH_MIN && phChangeRate >= PH_MAX);
                
                if (filterCondition) {
                    log.info("滞后参数异常: API滤失={}, 滤饼厚度={}, pH={}",
                        apiLossChangeRate >= API_LOSS_MIN ? "异常" : "正常",
                        apiCakeChangeRate >= API_CAKE_MIN ? "异常" : "正常",
                        (phChangeRate <= PH_MIN && phChangeRate >= PH_MAX) ? "异常" : "正常");
                }
                
                // 后期确认触发条件: 至少4个流变参数严重异常且滤失量/滤饼厚度/pH值异常
                if (confirmRheologyCount >= 4 && filterCondition) {
                    log.warn("【钙污染警报】后期确认阶段：已检测到钙污染，异常参数数量: {}", confirmRheologyCount);
                    // 记录后期确认阶段参数到数据库
                    logCaPollutionConfirmedParameters(current, previous);
                    confirmationAlarmTriggered = true;
                    return createCommonResultMap(records, true);
                }
                
                // 初期预警检测: 钙离子上升 + 至少3个流变参数在初期预警范围内
                boolean caCondition = caChangeRate >= CA_EARLY_MIN;
                
                if (!caCondition) {
                    log.info("钙离子变化率{}%未达到初期预警阈值{}%", caChangeRate*100, CA_EARLY_MIN*100);
                }
                
                int rheologyAlarmCount = 0;
                
                // 检查各流变参数是否在初期预警范围内
                if (avChangeRate >= AV_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("表观黏度变化率{}%达到初期预警阈值", avChangeRate * 100);
                }
                if (pvChangeRate >= PV_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("塑性黏度变化率{}%达到初期预警阈值", pvChangeRate * 100);
                }
                if (ypChangeRate >= YP_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("切力变化率{}%达到初期预警阈值", ypChangeRate * 100);
                }
                if (sf10sChangeRate >= SF10S_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("初切力变化率{}%达到初期预警阈值", sf10sChangeRate * 100);
                }
                if (sf10mChangeRate >= SF10M_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("终切力变化率{}%达到初期预警阈值", sf10mChangeRate * 100);
                }
                if (rpm6ChangeRate >= RPM6_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("6转变化率{}%达到初期预警阈值", rpm6ChangeRate * 100);
                }
                if (rpm3ChangeRate >= RPM3_EARLY_MIN) {
                    rheologyAlarmCount++;
                    log.info("3转变化率{}%达到初期预警阈值", rpm3ChangeRate * 100);
                }
                
                // 初期预警触发条件: 钙离子上升且至少3个流变参数异常
                if (caCondition && rheologyAlarmCount >= 3) {
                    log.warn("【钙污染预警】初期预警检测：可能存在钙污染迹象，异常参数数量: {}", rheologyAlarmCount);
                    // 记录初期预警参数
                    logCaPollutionEarlyParameters(current, previous);
                    initialAlarmTriggered = true;
                    return createCommonResultMap(records, true);
                } else {
                    log.info("未触发初期预警：钙离子条件满足={}, 异常参数数量={}/3", caCondition, rheologyAlarmCount);
                }
            }
        } else {
            log.info("数据点不足，无法进行钙污染检测");
        }

        // 返回结果
        return createCommonResultMap(records, initialAlarmTriggered || confirmationAlarmTriggered);
    }

    /**
     * 记录钙污染初期预警参数
     */
    private void logCaPollutionEarlyParameters(ModbusData current, ModbusData previous) {
        // 专家经验阈值 - 初期预警阶段
        final double AV_EARLY_MIN = 0.10; // 表观黏度 +10%
        final double PV_EARLY_MIN = 0.05; // 塑性黏度 +5%
        final double YP_EARLY_MIN = 0.20; // 切力 +20%
        final double SF10S_EARLY_MIN = 0.10; // 初切力10s +10%
        final double SF10M_EARLY_MIN = 0.15; // 终切力10min +15%
        final double RPM6_EARLY_MIN = 0.10; // 6转 +10%
        final double RPM3_EARLY_MIN = 0.15; // 3转 +15%
        final double CA_EARLY_MIN = 0.05; // 钙离子 +5%

        double avChangeRate = calculateChangeRate(current.getApparentViscosity(), previous.getApparentViscosity());
        double pvChangeRate = calculateChangeRate(current.getPlasticViscosity(), previous.getPlasticViscosity());
        
        // 计算切力变化率，处理YieldPoint可能为null的情况
        double ypChangeRate;
        if (current.getYieldPoint() != null && previous.getYieldPoint() != null) {
            ypChangeRate = calculateChangeRate(current.getYieldPoint(), previous.getYieldPoint());
        } else {
            // 如果YieldPoint为空，使用计算公式：YP = SF10m - PV
            double currentYP = current.getShearForce10m() != null && current.getPlasticViscosity() != null ? 
                    current.getShearForce10m() - current.getPlasticViscosity() : 0;
            double previousYP = previous.getShearForce10m() != null && previous.getPlasticViscosity() != null ? 
                    previous.getShearForce10m() - previous.getPlasticViscosity() : 0;
            ypChangeRate = calculateChangeRate(currentYP, previousYP);
        }
        
        double sf10sChangeRate = calculateChangeRate(current.getShearForce10s(), previous.getShearForce10s());
        double sf10mChangeRate = calculateChangeRate(current.getShearForce10m(), previous.getShearForce10m());
        double rpm6ChangeRate = calculateChangeRate(current.getRpm6(), previous.getRpm6());
        double rpm3ChangeRate = calculateChangeRate(current.getRpm3(), previous.getRpm3());
        double caChangeRate = calculateChangeRate(current.getCalciumIonContent(), previous.getCalciumIonContent());

        log.info("钙污染初期预警参数变化率分析：");
        log.info("表观黏度(AV)变化率: {}% (参考范围: 10%~30%)", formatChangeRate(avChangeRate));
        log.info("塑性黏度(PV)变化率: {}% (参考范围: 5%~15%)", formatChangeRate(pvChangeRate));
        log.info("切力(YP)变化率: {}% (参考范围: 20%~50%)", formatChangeRate(ypChangeRate));
        log.info("初切力(10s)变化率: {}% (参考范围: 10%~30%)", formatChangeRate(sf10sChangeRate));
        log.info("终切力(10min)变化率: {}% (参考范围: 15%~40%)", formatChangeRate(sf10mChangeRate));
        log.info("6转(Φ6)变化率: {}% (参考范围: 10%~30%)", formatChangeRate(rpm6ChangeRate));
        log.info("3转(Φ3)变化率: {}% (参考范围: 15%~40%)", formatChangeRate(rpm3ChangeRate));
        log.info("钙离子(Ca²⁺)变化率: {}% (参考范围: 5%~15%)", formatChangeRate(caChangeRate));

        String[] details = {
                String.format("表观黏度(AV)变化率: %.2f%% (参考范围: 10%%~30%%)", avChangeRate * 100),
                String.format("塑性黏度(PV)变化率: %.2f%% (参考范围: 5%%~15%%)", pvChangeRate * 100),
                String.format("切力(YP)变化率: %.2f%% (参考范围: 20%%~50%%)", ypChangeRate * 100),
                String.format("初切力(10s)变化率: %.2f%% (参考范围: 10%%~30%%)", sf10sChangeRate * 100),
                String.format("终切力(10min)变化率: %.2f%% (参考范围: 15%%~40%%)", sf10mChangeRate * 100),
                String.format("6转(Φ6)变化率: %.2f%% (参考范围: 10%%~30%%)", rpm6ChangeRate * 100),
                String.format("3转(Φ3)变化率: %.2f%% (参考范围: 15%%~40%%)", rpm3ChangeRate * 100),
                String.format("钙离子(Ca²⁺)变化率: %.2f%% (参考范围: 5%%~15%%)", caChangeRate * 100)
        };

        // 创建包含参数变化的HashMap
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("avChange", formatChangeRateForJSON(avChangeRate * 100));
        detailsMap.put("pvChange", formatChangeRateForJSON(pvChangeRate * 100));
        detailsMap.put("ypChange", formatChangeRateForJSON(ypChangeRate * 100));
        detailsMap.put("sf10sChange", formatChangeRateForJSON(sf10sChangeRate * 100));
        detailsMap.put("sf10mChange", formatChangeRateForJSON(sf10mChangeRate * 100));
        detailsMap.put("rpm6Change", formatChangeRateForJSON(rpm6ChangeRate * 100));
        detailsMap.put("rpm3Change", formatChangeRateForJSON(rpm3ChangeRate * 100));
        detailsMap.put("caChange", formatChangeRateForJSON(caChangeRate * 100));
        detailsMap.put("stage", "EARLY_WARNING");

        // 评估严重程度
        String severity = "LIGHT";
        detailsMap.put("severity", severity);

        // 构建异常参数JSON对象
        Map<String, Object> abnormalParameters = new HashMap<>();
        
        // 检查各参数是否达到异常阈值并记录
        // 表观黏度
        if (avChangeRate >= AV_EARLY_MIN) {
            abnormalParameters.put("表观黏度变化", formatChangeRateForJSON(avChangeRate * 100));
        }
        
        // 塑性黏度
        if (pvChangeRate >= PV_EARLY_MIN) {
            abnormalParameters.put("塑性黏度变化", formatChangeRateForJSON(pvChangeRate * 100));
        }
        
        // 切力
        if (ypChangeRate >= YP_EARLY_MIN) {
            abnormalParameters.put("切力变化", formatChangeRateForJSON(ypChangeRate * 100));
        }
        
        // 初切力
        if (sf10sChangeRate >= SF10S_EARLY_MIN) {
            abnormalParameters.put("初切力变化", formatChangeRateForJSON(sf10sChangeRate * 100));
        }
        
        // 终切力
        if (sf10mChangeRate >= SF10M_EARLY_MIN) {
            abnormalParameters.put("终切力变化", formatChangeRateForJSON(sf10mChangeRate * 100));
        }
        
        // 6转
        if (rpm6ChangeRate >= RPM6_EARLY_MIN) {
            abnormalParameters.put("6转变化", formatChangeRateForJSON(rpm6ChangeRate * 100));
        }
        
        // 3转
        if (rpm3ChangeRate >= RPM3_EARLY_MIN) {
            abnormalParameters.put("3转变化", formatChangeRateForJSON(rpm3ChangeRate * 100));
        }
        
        // 钙离子
        if (caChangeRate >= CA_EARLY_MIN) {
            abnormalParameters.put("钙离子变化", formatChangeRateForJSON(caChangeRate * 100));
        }
        
        // 添加异常参数到详情Map
        detailsMap.put("abnormalParameters", abnormalParameters);

        // 使用当前记录的采样时间
        LocalDateTime samplingTime = current.getSamplingTime();
        if (samplingTime != null) {
            updateSamplingTimeInDb(samplingTime);
        }

        // 保存日志到数据库
        boolean pollutionFlag = saveLogToDbWithParams(CA_POLLUTION_LOG_KEY, "WARN",
                "钙污染预警：初期预警检测发现可能存在钙污染迹象",
                samplingTime, details, detailsMap);
        if (pollutionFlag) {
            sendPollutionAlert("钙污染迹象", (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));
        }
    }

    /**
     * 记录钙污染后期确认参数
     */
    private void logCaPollutionConfirmedParameters(ModbusData current, ModbusData previous) {
        // 专家经验阈值 - 后期确认阶段
        final double AV_CONFIRM_MIN = 0.20; // 表观黏度 +20%
        final double PV_CONFIRM_MIN = 0.10; // 塑性黏度 +10%
        final double YP_CONFIRM_MIN = 0.50; // 切力 +50%
        final double SF10S_CONFIRM_MIN = 0.20; // 初切力10s +20%
        final double SF10M_CONFIRM_MIN = 0.30; // 终切力10min +30%
        final double RPM6_CONFIRM_MIN = 0.20; // 6转 +20%
        final double RPM3_CONFIRM_MIN = 0.30; // 3转 +30%
        final double CA_CONFIRM_MIN = 0.10; // 钙离子 +10%
        final double API_LOSS_MIN = 0.20; // API滤失量 +20%
        final double API_CAKE_MIN = 0.30; // API滤饼厚度 +30%
        final double PH_MIN = -0.10; // pH值 -10%
        final double PH_MAX = -0.05; // pH值 -5%

        double avChangeRate = calculateChangeRate(current.getApparentViscosity(), previous.getApparentViscosity());
        double pvChangeRate = calculateChangeRate(current.getPlasticViscosity(), previous.getPlasticViscosity());
        
        // 计算切力变化率，处理YieldPoint可能为null的情况
        double ypChangeRate;
        if (current.getYieldPoint() != null && previous.getYieldPoint() != null) {
            ypChangeRate = calculateChangeRate(current.getYieldPoint(), previous.getYieldPoint());
        } else {
            // 如果YieldPoint为空，使用计算公式：YP = SF10m - PV
            double currentYP = current.getShearForce10m() != null && current.getPlasticViscosity() != null ? 
                    current.getShearForce10m() - current.getPlasticViscosity() : 0;
            double previousYP = previous.getShearForce10m() != null && previous.getPlasticViscosity() != null ? 
                    previous.getShearForce10m() - previous.getPlasticViscosity() : 0;
            ypChangeRate = calculateChangeRate(currentYP, previousYP);
        }
        
        double sf10sChangeRate = calculateChangeRate(current.getShearForce10s(), previous.getShearForce10s());
        double sf10mChangeRate = calculateChangeRate(current.getShearForce10m(), previous.getShearForce10m());
        double rpm6ChangeRate = calculateChangeRate(current.getRpm6(), previous.getRpm6());
        double rpm3ChangeRate = calculateChangeRate(current.getRpm3(), previous.getRpm3());
        double caChangeRate = calculateChangeRate(current.getCalciumIonContent(), previous.getCalciumIonContent());
        double apiLossChangeRate = calculateChangeRate(current.getApiFiltrationLoss(), previous.getApiFiltrationLoss());
        double apiCakeChangeRate = calculateChangeRate(current.getApiFilterCakeThickness(), previous.getApiFilterCakeThickness());
        double phChangeRate = calculateChangeRate(current.getPhValue(), previous.getPhValue());

        log.info("钙污染后期确认参数变化率分析：");
        log.info("表观黏度(AV)变化率: {}% (参考范围: 20%~50%)", formatChangeRate(avChangeRate));
        log.info("塑性黏度(PV)变化率: {}% (参考范围: 10%~25%)", formatChangeRate(pvChangeRate));
        log.info("切力(YP)变化率: {}% (参考范围: 50%~100%)", formatChangeRate(ypChangeRate));
        log.info("初切力(10s)变化率: {}% (参考范围: 20%~50%)", formatChangeRate(sf10sChangeRate));
        log.info("终切力(10min)变化率: {}% (参考范围: 30%~70%)", formatChangeRate(sf10mChangeRate));
        log.info("6转(Φ6)变化率: {}% (参考范围: 20%~50%)", formatChangeRate(rpm6ChangeRate));
        log.info("3转(Φ3)变化率: {}% (参考范围: 30%~70%)", formatChangeRate(rpm3ChangeRate));
        log.info("钙离子(Ca²⁺)变化率: {}% (参考范围: 10%~30%)", formatChangeRate(caChangeRate));
        log.info("API滤失量变化率: {}% (参考范围: 20%~50%)", formatChangeRate(apiLossChangeRate));
        log.info("API滤饼厚度变化率: {}% (参考范围: 30%~80%)", formatChangeRate(apiCakeChangeRate));
        log.info("pH值变化率: {}% (参考范围: -10%~-5%)", formatChangeRate(phChangeRate));

        String[] details = {
                String.format("表观黏度(AV)变化率: %.2f%% (参考范围: 20%%~50%%)", avChangeRate * 100),
                String.format("塑性黏度(PV)变化率: %.2f%% (参考范围: 10%%~25%%)", pvChangeRate * 100),
                String.format("切力(YP)变化率: %.2f%% (参考范围: 50%%~100%%)", ypChangeRate * 100),
                String.format("初切力(10s)变化率: %.2f%% (参考范围: 20%%~50%%)", sf10sChangeRate * 100),
                String.format("终切力(10min)变化率: %.2f%% (参考范围: 30%%~70%%)", sf10mChangeRate * 100),
                String.format("6转(Φ6)变化率: %.2f%% (参考范围: 20%%~50%%)", rpm6ChangeRate * 100),
                String.format("3转(Φ3)变化率: %.2f%% (参考范围: 30%%~70%%)", rpm3ChangeRate * 100),
                String.format("钙离子(Ca²⁺)变化率: %.2f%% (参考范围: 10%%~30%%)", caChangeRate * 100),
                String.format("API滤失量变化率: %.2f%% (参考范围: 20%%~50%%)", apiLossChangeRate * 100),
                String.format("API滤饼厚度变化率: %.2f%% (参考范围: 30%%~80%%)", apiCakeChangeRate * 100),
                String.format("pH值变化率: %.2f%% (参考范围: -10%%~-5%%)", phChangeRate * 100)
        };

        // 创建包含参数变化的HashMap
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("avChange", formatChangeRateForJSON(avChangeRate * 100));
        detailsMap.put("pvChange", formatChangeRateForJSON(pvChangeRate * 100));
        detailsMap.put("ypChange", formatChangeRateForJSON(ypChangeRate * 100));
        detailsMap.put("sf10sChange", formatChangeRateForJSON(sf10sChangeRate * 100));
        detailsMap.put("sf10mChange", formatChangeRateForJSON(sf10mChangeRate * 100));
        detailsMap.put("rpm6Change", formatChangeRateForJSON(rpm6ChangeRate * 100));
        detailsMap.put("rpm3Change", formatChangeRateForJSON(rpm3ChangeRate * 100));
        detailsMap.put("caChange", formatChangeRateForJSON(caChangeRate * 100));
        detailsMap.put("apiLossChange", formatChangeRateForJSON(apiLossChangeRate * 100));
        detailsMap.put("apiCakeChange", formatChangeRateForJSON(apiCakeChangeRate * 100));
        detailsMap.put("phChange", formatChangeRateForJSON(phChangeRate * 100));
        detailsMap.put("stage", "CONFIRMATION");

        // 评估严重程度
        String severity = "SEVERE";
        if (Math.abs(ypChangeRate) >= 0.8 || Math.abs(avChangeRate) >= 0.4 || Math.abs(caChangeRate) >= 0.25) {
            severity = "SEVERE";
        } else if (Math.abs(ypChangeRate) >= 0.6 || Math.abs(avChangeRate) >= 0.3 || Math.abs(caChangeRate) >= 0.15) {
            severity = "MEDIUM";
        } else {
            severity = "LIGHT";
        }
        detailsMap.put("severity", severity);

        // 构建异常参数JSON对象
        Map<String, Object> abnormalParameters = new HashMap<>();
        
        // 检查各参数是否达到异常阈值并记录
        // 表观黏度
        if (Math.abs(avChangeRate) >= AV_CONFIRM_MIN) {
            abnormalParameters.put("表观黏度变化", formatChangeRateForJSON(avChangeRate * 100));
        }
        
        // 塑性黏度
        if (Math.abs(pvChangeRate) >= PV_CONFIRM_MIN) {
            abnormalParameters.put("塑性黏度变化", formatChangeRateForJSON(pvChangeRate * 100));
        }
        
        // 切力
        if (Math.abs(ypChangeRate) >= YP_CONFIRM_MIN) {
            abnormalParameters.put("切力变化", formatChangeRateForJSON(ypChangeRate * 100));
        }
        
        // 初切力
        if (Math.abs(sf10sChangeRate) >= SF10S_CONFIRM_MIN) {
            abnormalParameters.put("初切力变化", formatChangeRateForJSON(sf10sChangeRate * 100));
        }
        
        // 终切力
        if (Math.abs(sf10mChangeRate) >= SF10M_CONFIRM_MIN) {
            abnormalParameters.put("终切力变化", formatChangeRateForJSON(sf10mChangeRate * 100));
        }
        
        // 6转
        if (Math.abs(rpm6ChangeRate) >= RPM6_CONFIRM_MIN) {
            abnormalParameters.put("6转变化", formatChangeRateForJSON(rpm6ChangeRate * 100));
        }
        
        // 3转
        if (Math.abs(rpm3ChangeRate) >= RPM3_CONFIRM_MIN) {
            abnormalParameters.put("3转变化", formatChangeRateForJSON(rpm3ChangeRate * 100));
        }
        
        // 钙离子
        if (Math.abs(caChangeRate) >= CA_CONFIRM_MIN) {
            abnormalParameters.put("钙离子变化", formatChangeRateForJSON(caChangeRate * 100));
        }
        
        // API滤失量
        if (Math.abs(apiLossChangeRate) >= API_LOSS_MIN) {
            abnormalParameters.put("API滤失量变化", formatChangeRateForJSON(apiLossChangeRate * 100));
        }
        
        // API滤饼厚度
        if (Math.abs(apiCakeChangeRate) >= API_CAKE_MIN) {
            abnormalParameters.put("API滤饼厚度变化", formatChangeRateForJSON(apiCakeChangeRate * 100));
        }
        
        // pH值
        if (phChangeRate <= PH_MIN && phChangeRate >= PH_MAX) {
            abnormalParameters.put("pH值变化", formatChangeRateForJSON(phChangeRate * 100));
        }
        
        // 添加异常参数到详情Map
        detailsMap.put("abnormalParameters", abnormalParameters);

        // 使用当前记录的采样时间
        LocalDateTime samplingTime = current.getSamplingTime();
        if (samplingTime != null) {
            updateSamplingTimeInDb(samplingTime);
        }

        // 确定日志级别
        String logLevel = "SEVERE".equals(severity) ? "ERROR" : "MEDIUM".equals(severity) ? "WARN" : "INFO";

        // 保存日志到数据库
        boolean pollutionFlag = saveLogToDbWithParams(CA_POLLUTION_LOG_KEY, logLevel,
                "钙污染警报：后期确认阶段已检测到钙污染",
                samplingTime, details, detailsMap);

        if (pollutionFlag) {
            sendPollutionAlert("钙污染警报", (String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));
        }
    }

    /**
     * 发送污染预警WebSocket消息
     *
     * @param pollutionType 污染类型
     * @param wellId        井ID
     */
    private void sendPollutionAlert(String pollutionType, String wellId) {
        try {
            // 构建预警消息
            Map<String, Object> alertMessage = new HashMap<>();
            alertMessage.put("type", "POLLUTION_ALERT");
            alertMessage.put("pollutionType", pollutionType);
            alertMessage.put("wellId", wellId);
            alertMessage.put("timestamp", System.currentTimeMillis());

            // 只在需要发送WebSocket消息时序列化为JSON
            String jsonMessage = objectMapper.writeValueAsString(alertMessage);
            log.info("发送污染预警WebSocket消息: {}", jsonMessage);

            // 调用WebSocket服务发送消息
            webSocketServer.sendToAllClient(jsonMessage);

        } catch (Exception e) {
            log.error("发送污染预警WebSocket消息失败: {}", e.getMessage());
        }
    }

    /**
     * 判断油基泥浆类型
     *
     * @return 是否为油基泥浆
     */
    private boolean isOilBasedMud() {
        String statusStr = (String) redisTemplate.opsForValue().get(RedisKeys.STATUS.getKey());
        Integer o = statusStr != null ? Integer.parseInt(statusStr) : null;
        return o != null && o == 0;
    }

    // 创建结果Map的通用方法
    private Map<String, List<ParameterVO>> createResultMap(
            List<ModbusData> records,
            List<String> paramNames,
            boolean isPolluted) {


        Map<String, List<ParameterVO>> resultMap = new HashMap<>();


        // 为每个参数名称初始化一个空的List
        for (String paramName : paramNames) {
            resultMap.put(paramName, new ArrayList<>());
        }


        // 遍历每条记录
        for (ModbusData drillingData : records) {
            for (String paramName : paramNames) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime());


                Double value = getParameterValue(drillingData, paramName);
                parameterVO.setValue(value != null ? value : 0);


                resultMap.get(paramName).add(parameterVO);
            }
        }


        // 添加污染标志
        resultMap.put("pollution", new ArrayList<>());
        ParameterVO pollutionFlag = new ParameterVO();
        pollutionFlag.setRed(isPolluted);
        resultMap.get("pollution").add(pollutionFlag);


        return resultMap;
    }

    /**
     * 根据参数名称获取对应的值
     *
     * @param drillingData FullPerformance对象
     * @param paramName    参数名称
     * @return 参数值
     */
    private Double getParameterValue(ModbusData drillingData, String paramName) {
        if (drillingData == null) {
            return 0.0;
        }

        switch (paramName) {
            case "drilling_fluid_density":
                return drillingData.getDrillingFluidDensity() != null ? drillingData.getDrillingFluidDensity() : 0.0;
            case "funnel_viscosity":
                return drillingData.getFunnelViscosity() != null ? drillingData.getFunnelViscosity() : 0.0;
            case "shear_force10s":
                return drillingData.getShearForce10s() != null ? drillingData.getShearForce10s() : 0.0;
            case "shear_force10m":
                return drillingData.getShearForce10m() != null ? drillingData.getShearForce10m() : 0.0;
            case "rpm600":
                return drillingData.getRpm600() != null ? Double.valueOf(drillingData.getRpm600()) : 0.0;
            case "rpm300":
                return drillingData.getRpm300() != null ? Double.valueOf(drillingData.getRpm300()) : 0.0;
            case "rpm200":
                return drillingData.getRpm200() != null ? Double.valueOf(drillingData.getRpm200()) : 0.0;
            case "rpm100":
                return drillingData.getRpm100() != null ? Double.valueOf(drillingData.getRpm100()) : 0.0;
            case "rpm6":
                return drillingData.getRpm6() != null ? Double.valueOf(drillingData.getRpm6()) : 0.0;
            case "rpm3":
                return drillingData.getRpm3() != null ? Double.valueOf(drillingData.getRpm3()) : 0.0;
            case "api_filtration_loss":
                return drillingData.getApiFiltrationLoss() != null ? drillingData.getApiFiltrationLoss() : 0.0;
            case "api_filter_cake_thickness":
                return drillingData.getApiFilterCakeThickness() != null ? drillingData.getApiFilterCakeThickness() : 0.0;
            case "hthp_filtration_loss":
                return drillingData.getHthpFiltrationLoss() != null ? drillingData.getHthpFiltrationLoss() : 0.0;
            case "hthp_filter_cake_thickness":
                return drillingData.getHthpFilterCakeThickness() != null ? drillingData.getHthpFilterCakeThickness() : 0.0;
            case "plastic_viscosity":
                return drillingData.getPlasticViscosity() != null ? drillingData.getPlasticViscosity() : 0.0;
            case "yield_point":
                return drillingData.getYieldPoint() != null ? drillingData.getYieldPoint() : 0.0;
            case "solid_content":
                return drillingData.getSolidContent() != null ? drillingData.getSolidContent() : 0.0;
            case "low_density_solid_content":
                return drillingData.getLowDensitySolidContent() != null ? drillingData.getLowDensitySolidContent() : 0.0;
            case "oil_content":
                return drillingData.getOilContent() != null ? drillingData.getOilContent() : 0.0;
            case "water_content":
                return drillingData.getWaterContent() != null ? drillingData.getWaterContent() : 0.0;
            case "chloride_ion_content":
                return drillingData.getChlorideIonContent() != null ? drillingData.getChlorideIonContent() : 0.0;
            case "calcium_ion_content":
                return drillingData.getCalciumIonContent() != null ? drillingData.getCalciumIonContent() : 0.0;
            case "potassium_ion_content":
                return drillingData.getPotassiumIonContent() != null ? drillingData.getPotassiumIonContent() : 0.0;
            case "carbonate_content":
                return drillingData.getCarbonateContent() != null ? drillingData.getCarbonateContent() : 0.0;
            case "filtrate_phenolphthalein_alkalinity":
                return drillingData.getFiltratePhenolphthaleinAlkalinity() != null ? drillingData.getFiltratePhenolphthaleinAlkalinity() : 0.0;
            case "filtrate_methyl_orange_alkalinity":
                return drillingData.getFiltrateMethylOrangeAlkalinity() != null ? drillingData.getFiltrateMethylOrangeAlkalinity() : 0.0;
            case "oil_water_ratio":
                return drillingData.getOilWaterRatio() != null ? drillingData.getOilWaterRatio() : 0.0;
            case "dynamic_plastic_ratio":
                return drillingData.getDynamicPlasticRatio() != null ? drillingData.getDynamicPlasticRatio() : 0.0;
            case "apparent_viscosity":
                return drillingData.getApparentViscosity() != null ? drillingData.getApparentViscosity() : 0.0;
            case "flow_index":
                return drillingData.getFlowIndexN() != null ? drillingData.getFlowIndexN() : 0.0;
            case "k_value":
                return drillingData.getConsistencyK() != null ? drillingData.getConsistencyK() : 0.0;
            case "emulsion_breakdown_voltage":
                return drillingData.getEmulsionBreakdownVoltage() != null ? drillingData.getEmulsionBreakdownVoltage() : 0.0;
            case "mud_cake_friction_coefficient":
                return drillingData.getMudCakeFrictionCoefficient() != null ? drillingData.getMudCakeFrictionCoefficient() : 0.0;
            case "drilling_fluid_activity":
                return drillingData.getDrillingFluidActivity() != null ? drillingData.getDrillingFluidActivity() : 0.0;
            case "filtrate_total_salinity":
                return drillingData.getFiltrateTotalSalinity() != null ? drillingData.getFiltrateTotalSalinity() : 0.0;
            case "methylene_blue_exchange_capacity":
                return drillingData.getMethyleneBlueExchangeCapacity() != null ? drillingData.getMethyleneBlueExchangeCapacity() : 0.0;
            case "yield_value":
                return drillingData.getYieldPoint() != null ? drillingData.getYieldPoint() : 0.0;
            case "ph_value":
                return drillingData.getPhValue() != null ? drillingData.getPhValue() : 0.0;
            default:
                return 0.0;
        }
    }

    /**
     * 钻井液长效稳定检测
     *
     * @return 参数变化结果Map
     */
    public Map<String, List<ParameterVO>> notTreatedForLongTimeNew() {

        boolean isTest;

        String testStr = (String) redisTemplate.opsForValue().get("isTest");
        if (testStr == null){
            isTest = false;
        }else {
            isTest = Boolean.parseBoolean(testStr);
        }
        List<ModbusData> records;
        if (isTest) {
            records = queryPerformanceRecordsTest((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 10);
        } else {
            records = queryPerformanceRecords((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()), 16);
        }

        // 处理 records 为 null 或空的情况
        if (records.isEmpty()) {
            return createResultMap(new ArrayList<>(),
                    Arrays.asList("drilling_fluid_density", "emulsion_breakdown_voltage"),
                    false);
        }

        // 密度变化阈值（专家经验）
        final double DENSITY_WARNING_THRESHOLD = 0.06; // 预警阈值
        final double DENSITY_SEVERE_THRESHOLD = 0.12; // 严重沉降阈值

        // 首先检查密度变化
        boolean densityRiskDetected = false;
        double maxDensityDiff = 0;
        ModbusData currentWithMaxDiff = null;
        ModbusData previousWithMaxDiff = null;

        for (int i = 1; i < records.size(); i++) {
            ModbusData current = records.get(i);
            ModbusData previous = records.get(i - 1);

            double currentDensity = current.getDrillingFluidDensity() != null ? current.getDrillingFluidDensity() : 0;
            double previousDensity = previous.getDrillingFluidDensity() != null ? previous.getDrillingFluidDensity() : 0;
            double densityDiff = Math.abs(currentDensity - previousDensity);

            if (densityDiff > maxDensityDiff) {
                maxDensityDiff = densityDiff;
                currentWithMaxDiff = current;
                previousWithMaxDiff = previous;
            }

            // 判断密度差值是否超过预警阈值
            if (densityDiff > DENSITY_WARNING_THRESHOLD) {
                log.warn("【钻井液稳定性预警】密度变化异常: {} g/cm³", densityDiff);
                // 不再立即记录到数据库，而是先记录日志，后续统一记录
                densityRiskDetected = true;
                break;
            }
        }

        // 如果密度有风险，进一步判断
        if (densityRiskDetected) {
            // 判断是水基还是油基泥浆
            if (!isOilBasedMud()) {
                // 水基泥浆：检查粘度、切力和失水
                boolean stabilityRiskDetected = false;
                double maxViscosityChange = 0;
                double maxPlasticViscosityChange = 0;
                double maxShearForceChange = 0;
                double maxApiLossChange = 0;
                double maxSolidChange = 0;
                
                // 记录检测到最大变化率的数据点
                ModbusData currentWithMaxChange = null;
                ModbusData previousWithMaxChange = null;

                // 水基泥浆阈值（专家经验）
                final double AV_PV_WARNING_THRESHOLD = 0.20; // 粘度增加率20%：需补充抗温处理剂
                final double AV_PV_SEVERE_THRESHOLD = 0.30; // 粘度增加率30%：体系失效风险高
                final double SHEAR_FORCE_THRESHOLD = 0.20; // 静切力增加20%：抗岩屑污染能力差
                final double SOLID_CONTENT_THRESHOLD = 0.05; // 固相含量增加5%：抗岩屑污染能力差
                final double API_LOSS_MIN_THRESHOLD = 0.15; // API滤失量增加15%
                final double API_LOSS_MAX_THRESHOLD = 0.25; // API滤失量增加25%

                for (int i = 1; i < records.size(); i++) {
                    ModbusData current = records.get(i);
                    ModbusData previous = records.get(i - 1);

                    // 计算变化率
                    double viscosityChangeRate = calculateChangeRate(current.getApparentViscosity(), previous.getApparentViscosity());
                    double plasticViscosityChangeRate = calculateChangeRate(current.getPlasticViscosity(), previous.getPlasticViscosity());
                    double shearForceChangeRate = calculateChangeRate(current.getShearForce10m(), previous.getShearForce10m());
                    double apiLossChangeRate = calculateChangeRate(current.getApiFiltrationLoss(), previous.getApiFiltrationLoss());
                    double solidChangeRate = calculateChangeRate(current.getSolidContent(), previous.getSolidContent());

                    // 更新最大变化率及对应的数据点
                    if (Math.abs(viscosityChangeRate) > Math.abs(maxViscosityChange)) {
                        maxViscosityChange = viscosityChangeRate;
                        currentWithMaxChange = current;
                        previousWithMaxChange = previous;
                    }
                    
                    if (Math.abs(plasticViscosityChangeRate) > Math.abs(maxPlasticViscosityChange)) {
                        maxPlasticViscosityChange = plasticViscosityChangeRate;
                    }
                    
                    if (Math.abs(shearForceChangeRate) > Math.abs(maxShearForceChange)) {
                        maxShearForceChange = shearForceChangeRate;
                    }
                    
                    if (Math.abs(apiLossChangeRate) > Math.abs(maxApiLossChange)) {
                        maxApiLossChange = apiLossChangeRate;
                    }
                    
                    if (Math.abs(solidChangeRate) > Math.abs(maxSolidChange)) {
                        maxSolidChange = solidChangeRate;
                    }

                    // 检查静切力变化
                    double initialCutRate = calculateChangeRate(current.getShearForce10s(), previous.getShearForce10s());
                    double finalCutRate = calculateChangeRate(current.getShearForce10m(), previous.getShearForce10m());
                    double maxCutRate = Math.max(initialCutRate, finalCutRate);

                    // 判断是否存在抗岩屑污染能力问题（AV和PV变化不大，但静切力明显增加>20%，且固相含量增加>5%）
                    if (Math.abs(viscosityChangeRate) < 0.1 &&
                            Math.abs(plasticViscosityChangeRate) < 0.1 &&
                            maxCutRate > SHEAR_FORCE_THRESHOLD &&
                            solidChangeRate > SOLID_CONTENT_THRESHOLD) {

                        log.warn("【钻井液稳定性警报】水基泥浆抗岩屑污染能力差");
                        log.warn("静切力增加率: {}%, 固相含量增加率: {}%",
                                maxCutRate * 100, solidChangeRate * 100);
                                
                        // 记录完整的异常参数到数据库
                        logWaterBasedMudStabilityWithDensity(viscosityChangeRate, plasticViscosityChangeRate, maxCutRate, 
                                apiLossChangeRate, solidChangeRate, maxDensityDiff, "抗岩屑污染能力差");
                                
                        stabilityRiskDetected = true;
                        break;
                    }

                    // 判断粘度、切力和失水增加是否超过阈值
                    if ((viscosityChangeRate > AV_PV_WARNING_THRESHOLD || plasticViscosityChangeRate > AV_PV_WARNING_THRESHOLD) &&
                            apiLossChangeRate > API_LOSS_MIN_THRESHOLD) {
                        log.warn("【钻井液稳定性警报】水基泥浆长效稳定性不足");
                        
                        // 记录完整的异常参数到数据库
                        logWaterBasedMudStabilityWithDensity(viscosityChangeRate, plasticViscosityChangeRate, shearForceChangeRate, 
                                apiLossChangeRate, solidChangeRate, maxDensityDiff, "长效稳定性不足");
                                
                        stabilityRiskDetected = true;
                        break;
                    }
                }

                if (stabilityRiskDetected) {
                    return createResultMap(records,
                            Arrays.asList("drilling_fluid_density", "apparent_viscosity", "plastic_viscosity",
                                    "shear_force10m", "shear_force10s", "api_filtration_loss", "solid_content"),
                            true);
                }
            } else {
                // 油基泥浆：检查破乳电压
                boolean oilBasedRiskDetected = false;
                double minES = Double.MAX_VALUE;
                double esChangeRate = 0;

                // 油基泥浆阈值（专家经验）
                final double ES_MIN_THRESHOLD = 300.0; // 破乳电压最小值
                final double ES_CHANGE_WARNING_THRESHOLD = 15.0; // ES下降率15%
                final double ES_CHANGE_SEVERE_THRESHOLD = 30.0; // ES下降率30%

                // 获取历史ES值作为基准
                double baselineES = 0;
                if (!records.isEmpty() && records.get(0).getEmulsionBreakdownVoltage() != null) {
                    baselineES = records.get(0).getEmulsionBreakdownVoltage();
                }

                for (int i = 0; i < records.size(); i++) {
                    ModbusData current = records.get(i);

                    if (current.getEmulsionBreakdownVoltage() != null) {
                        double currentES = current.getEmulsionBreakdownVoltage();
                        minES = Math.min(minES, currentES);
                        
                        if (baselineES > 0) {
                            esChangeRate = (baselineES - currentES) / baselineES * 100;
                        }
                        
                        // 判断破乳电压是否低于阈值或下降率过高
                        if (currentES < ES_MIN_THRESHOLD || esChangeRate >= ES_CHANGE_WARNING_THRESHOLD) {
                            log.warn("【钻井液稳定性警报】油基泥浆乳化稳定性不足");
                            
                            // 记录完整的异常参数到数据库，包含密度变化
                            logOilBasedMudStabilityWithDensity(currentES, esChangeRate, maxDensityDiff);
                            
                            oilBasedRiskDetected = true;
                            break;
                        }
                    }
                }

                if (oilBasedRiskDetected) {
                    return createResultMap(records,
                            Arrays.asList("drilling_fluid_density", "emulsion_breakdown_voltage"),
                            true);
                }
            }
        }

        // 返回结果
        if (isOilBasedMud()) {
            return createResultMap(records,
                    Arrays.asList("drilling_fluid_density", "emulsion_breakdown_voltage"),
                    false);
        } else {
            return createResultMap(records,
                    Arrays.asList("drilling_fluid_density", "apparent_viscosity", "plastic_viscosity",
                            "shear_force10m", "shear_force10s", "api_filtration_loss", "solid_content"),
                    false);
        }
    }

    /**
     * 记录水基泥浆稳定性，包含密度变化
     */
    private void logWaterBasedMudStabilityWithDensity(double viscosityChangeRate, double plasticViscosityChangeRate, 
                                             double shearForceChangeRate, double apiLossChangeRate, 
                                             double solidChangeRate, double densityDiff, String problemType) {
        log.info("水基泥浆长效稳定性分析：");

        // 高温老化后粘度变化评估
        String viscosityStatus = "抗温能力合格";
        if (viscosityChangeRate > 0.3 || plasticViscosityChangeRate > 0.3) {
            viscosityStatus = "体系失效风险高";
        } else if (viscosityChangeRate > 0.2 || plasticViscosityChangeRate > 0.2) {
            viscosityStatus = "需补充抗温处理剂";
        }
        log.info("表观粘度增加率: {}% - {}", viscosityChangeRate * 100, viscosityStatus);
        log.info("塑性粘度增加率: {}% - {}", plasticViscosityChangeRate * 100, viscosityStatus);

        // 切力变化评估
        log.info("切力变化率: {}%", shearForceChangeRate * 100);
        
        // 固相含量变化评估
        String solidStatus = "正常";
        if (solidChangeRate > 0.05) {
            solidStatus = "固相含量增加明显";
        }
        log.info("固相含量变化率: {}% - {}", solidChangeRate * 100, solidStatus);

        // API滤失量变化评估
        String apiLossStatus = "正常";
        if (apiLossChangeRate > 0.25) {
            apiLossStatus = "严重异常";
        } else if (apiLossChangeRate > 0.15) {
            apiLossStatus = "需要注意";
        }
        log.info("API滤失量变化率: {}% - {}", apiLossChangeRate * 100, apiLossStatus);
        
        // 密度变化评估
        String densityStatus = "正常";
        if (densityDiff > 0.12) {
            densityStatus = "严重沉降";
        } else if (densityDiff > 0.06) {
            densityStatus = "需要注意";
        }
        log.info("密度差值: {} g/cm³ - {}", densityDiff, densityStatus);

        // 总体评估
        String recommendation;
        if (problemType.equals("抗岩屑污染能力差")) {
            recommendation = "添加抑制剂和降滤失剂，控制固相含量";
        } else {
            recommendation = (viscosityChangeRate > 0.3 || plasticViscosityChangeRate > 0.3 || apiLossChangeRate > 0.25) ?
                    "钻井液体系需要重新调整" : "补充抗温处理剂和滤失剂";
        }
        log.info("总体建议: {}", recommendation);

        // 记录水基泥浆稳定性检测结果到数据库
        String[] details = {
                String.format("密度差值: %.3f g/cm³ - %s", densityDiff, densityStatus),
                String.format("表观粘度增加率: %.2f%% - %s", viscosityChangeRate * 100, viscosityStatus),
                String.format("塑性粘度增加率: %.2f%% - %s", plasticViscosityChangeRate * 100, viscosityStatus),
                String.format("切力变化率: %.2f%%", shearForceChangeRate * 100),
                String.format("固相含量变化率: %.2f%% - %s", solidChangeRate * 100, solidStatus),
                String.format("API滤失量变化率: %.2f%% - %s", apiLossChangeRate * 100, apiLossStatus),
                String.format("总体建议: %s", recommendation)
        };

        String logLevel = (viscosityChangeRate > 0.3 || plasticViscosityChangeRate > 0.3 || apiLossChangeRate > 0.25 || densityDiff > 0.12) ? "ERROR" :
                (viscosityChangeRate > 0.2 || plasticViscosityChangeRate > 0.2 || apiLossChangeRate > 0.15 || densityDiff > 0.06) ? "WARN" : "INFO";

        // 获取当前采样时间
        LocalDateTime samplingTime = getCurrentSamplingTime();

        // 创建包含参数变化和问题严重程度的HashMap对象
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("densityChange", densityDiff * 100);  // 转为百分比
        detailsMap.put("viscosityChange", viscosityChangeRate * 100);
        detailsMap.put("plasticViscosityChange", plasticViscosityChangeRate * 100);
        detailsMap.put("shearForceChange", shearForceChangeRate * 100);
        detailsMap.put("apiLossChange", apiLossChangeRate * 100);
        detailsMap.put("solidContentChange", solidChangeRate * 100);

        // 设置严重程度
        String severity;
        if (viscosityChangeRate > 0.3 || plasticViscosityChangeRate > 0.3 || apiLossChangeRate > 0.25 || densityDiff > 0.12) {
            severity = "SEVERE";
            detailsMap.put("severity", "SEVERE");
        } else if (viscosityChangeRate > 0.2 || plasticViscosityChangeRate > 0.2 || apiLossChangeRate > 0.15 || densityDiff > 0.06) {
            severity = "MEDIUM";
            detailsMap.put("severity", "MEDIUM");
        } else {
            severity = "LIGHT";
            detailsMap.put("severity", "LIGHT");
        }

        // 使用增强版本的方法保存到数据库
        boolean pollutionFlag = saveLogToDbWithParams(STABILITY_LOG_KEY, logLevel,
                "钻井液稳定性检测: 水基泥浆" + problemType,
                samplingTime, details, detailsMap);

        if (pollutionFlag) {
            sendPollutionAlert("水基泥浆不稳定", (String) redisTemplate.opsForValue().get(FullPerformanceServiceImpl.SAMPLING_TIME_KEY));
        }
    }

    /**
     * 记录油基泥浆稳定性，包含密度变化
     */
    private void logOilBasedMudStabilityWithDensity(double currentES, double esChangeRate, double densityDiff) {
        log.info("油基泥浆长效稳定性分析：");

        String esStatus = "正常";
        if (currentES < 300) {
            esStatus = "乳化不稳定";
        }
        log.info("破乳电压(ES): {} V - {}", currentES, esStatus);

        String changeStatus = "正常";
        if (esChangeRate > 30) {
            changeStatus = "严重降低";
        } else if (esChangeRate > 15) {
            changeStatus = "明显降低";
        }
        log.info("ES下降率: {}% - {}", esChangeRate, changeStatus);
        
        // 密度变化评估
        String densityStatus = "正常";
        if (densityDiff > 0.12) {
            densityStatus = "严重沉降";
        } else if (densityDiff > 0.06) {
            densityStatus = "需要注意";
        }
        log.info("密度差值: {} g/cm³ - {}", densityDiff, densityStatus);

        // 总体评估
        String recommendation = (currentES < 300 || esChangeRate > 30 || densityDiff > 0.12) ?
                "立即补充乳化剂，调整油水比，添加悬浮剂" :
                (esChangeRate > 15 || densityDiff > 0.06) ? "补充乳化剂和悬浮剂" : "正常监测";
        log.info("总体建议: {}", recommendation);

        // 记录油基泥浆稳定性检测结果到数据库
        String[] details = {
                String.format("密度差值: %.3f g/cm³ - %s", densityDiff, densityStatus),
                String.format("破乳电压(ES): %.2f V - %s", currentES, esStatus),
                String.format("ES下降率: %.2f%% - %s", esChangeRate, changeStatus),
                String.format("总体建议: %s", recommendation)
        };

        String logLevel = (currentES < 300 || esChangeRate > 30 || densityDiff > 0.12) ? "ERROR" :
                (esChangeRate > 15 || densityDiff > 0.06) ? "WARN" : "INFO";

        // 获取当前采样时间
        LocalDateTime samplingTime = getCurrentSamplingTime();

        // 创建包含参数变化和问题严重程度的HashMap对象
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("densityChange", densityDiff * 100);  // 转为百分比
        detailsMap.put("esValue", currentES);
        detailsMap.put("esChangeRate", esChangeRate);

        // 设置严重程度
        String severity;
        if (currentES < 300 || esChangeRate > 30 || densityDiff > 0.12) {
            severity = "SEVERE";
            detailsMap.put("severity", "SEVERE");
        } else if (esChangeRate > 15 || densityDiff > 0.06) {
            severity = "MEDIUM";
            detailsMap.put("severity", "MEDIUM");
        } else {
            severity = "LIGHT";
            detailsMap.put("severity", "LIGHT");
        }

        // 使用增强版本的方法保存到数据库
        boolean pollutionFlag = saveLogToDbWithParams(STABILITY_LOG_KEY, logLevel,
                "钻井液稳定性检测: 油基泥浆乳化稳定性不足",
                samplingTime, details, detailsMap);

        if (pollutionFlag) {
            sendPollutionAlert("油基泥浆不稳定", (String) redisTemplate.opsForValue().get(FullPerformanceServiceImpl.SAMPLING_TIME_KEY));
        }
    }

    /**
     * 获取当前采样时间，优先从Redis获取，如果Redis中不存在则查询数据库
     *
     * @return 采样时间，如果无法获取则返回当前时间
     */
    private LocalDateTime getCurrentSamplingTime() {
        try {
            // 先从Redis中获取采样时间
            Object samplingTimeStr = redisTemplate.opsForValue().get(SAMPLING_TIME_KEY);
            if (samplingTimeStr != null) {
                try {
                    // 解析Redis中存储的时间字符串
                    return LocalDateTime.parse(samplingTimeStr.toString());
                } catch (Exception e) {
                    log.error("解析采样时间失败: {}", e.getMessage());
                }
            }

            // Redis中不存在，从数据库获取
            Object wellId = redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
            if (wellId != null) {
                List<ModbusData> recentRecords = queryPerformanceRecords(wellId.toString(), 1);
                if (!recentRecords.isEmpty() && recentRecords.get(0).getSamplingTime() != null) {
                    LocalDateTime samplingTime = recentRecords.get(0).getSamplingTime();

                    // 更新采样时间缓存
                    updateSamplingTimeInDb(samplingTime);

                    return samplingTime;
                }
            }

            // 无法获取时间，返回当前时间
            return LocalDateTime.now();
        } catch (Exception e) {
            log.error("获取采样时间失败: {}", e.getMessage());
            return LocalDateTime.now();
        }
    }

    /**
     * 更新当前采样时间到Redis和数据库
     *
     * @param samplingTime 新的采样时间
     */
    private void updateSamplingTimeInDb(LocalDateTime samplingTime) {
        if (samplingTime != null) {
            try {
                // 保存到Redis作为缓存
                redisTemplate.opsForValue().set(SAMPLING_TIME_KEY, samplingTime.toString());
                redisTemplate.expire(SAMPLING_TIME_KEY, SAMPLING_TIME_EXPIRY_HOURS, TimeUnit.HOURS);
                log.info("已更新采样时间: {}", samplingTime);
            } catch (Exception e) {
                log.error("更新采样时间失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 格式化变化率为可读字符串，处理无穷大和NaN情况
     */
    private String formatChangeRate(double changeRate) {
        if (Double.isInfinite(changeRate)) {
            return changeRate > 0 ? "显著增加" : "显著减少";
        } else if (Double.isNaN(changeRate)) {
            return "无法计算";
        } else {
            return String.format("%.2f", changeRate * 100);
        }
    }
    
    /**
     * 格式化变化率为JSON安全的数值，处理无穷大和NaN情况
     */
    private double formatChangeRateForJSON(double changeRate) {
        if (Double.isInfinite(changeRate) || Double.isNaN(changeRate)) {
            return changeRate > 0 ? 1000.0 : -1000.0;
        } else {
            return Math.max(-1000.0, Math.min(1000.0, changeRate)); // 限制在±1000%范围内
        }
    }
}