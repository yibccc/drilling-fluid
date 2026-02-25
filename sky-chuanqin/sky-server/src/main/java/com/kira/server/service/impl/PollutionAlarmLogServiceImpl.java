package com.kira.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kira.server.domain.entity.PollutionAlarmLog;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.query.PollutionAlarmLogQuery;
import com.kira.server.domain.vo.PollutionAlarmLogQueryVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.mapper.PollutionAlarmLogMapper;
import com.kira.server.service.IPollutionAlarmLogService;
import com.kira.common.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 钻井液污染报警日志服务实现类
 *
 * @author kira
 */
@Service
@Slf4j
public class PollutionAlarmLogServiceImpl extends ServiceImpl<PollutionAlarmLogMapper, PollutionAlarmLog> 
        implements IPollutionAlarmLogService {

    @Autowired
    private WebSocketServer webSocketServer;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 预警重复检测的时间窗口（分钟）
     * 在这个时间窗口内的相同类型预警将被视为重复预警
     */
    private static final int DUPLICATE_ALARM_WINDOW_MINUTES = 180; // 延长至3小时
    
    // Redis采样时间键 (与FullPerformanceServiceImpl中相同)
    private static final String SAMPLING_TIME_KEY = "drilling:sampling_time";
    
    @Override
    public boolean saveCaPollutionLog(String wellId, String wellLocation, boolean isPolluted, String details) {
        return saveCaPollutionLog(wellId, wellLocation, isPolluted, details, null);
    }
    
    @Override
    public boolean saveCaPollutionLog(String wellId, String wellLocation, boolean isPolluted, String details, String jsonDetails) {
        if (!isPolluted) {
            // 如果未检测到污染，则不记录
            return false;
        }
        
        try {
            // 从详细信息中提取数据采样时间
            LocalDateTime dataSamplingTime = extractSamplingTimeFromDetails(details);
            log.info("从详细信息中提取数据采样时间：{}", dataSamplingTime);
            
            // 检查是否存在重复预警
            PollutionAlarmLog existingAlarm = findRecentAlarm(wellId, PollutionAlarmLog.PollutionType.CA);
            if (existingAlarm != null) {
                // 存在重复预警，更新已有预警而不是创建新记录
                log.info("检测到重复钙污染预警，更新已有预警记录，井ID：{}, 预警ID：{}", wellId, existingAlarm.getId());
                return false;
            }
            
            // 不存在重复预警，创建新记录
            PollutionAlarmLog alarmLog = new PollutionAlarmLog();
            alarmLog.setWellId(wellId)
                    .setWellLocation(wellLocation)
                    .setPollutionType(PollutionAlarmLog.PollutionType.CA)
                    .setLogLevel(PollutionAlarmLog.LogLevel.ERROR)
                    .setSeverity(PollutionAlarmLog.Severity.MEDIUM) // 默认中度，可根据具体参数进行判断
                    .setMessage("检测到钙污染")
                    .setDetails(details)
                    .setRecommendedSolution("补充处理剂，调整pH值")
                    .setSolutionChemicals("碳酸钠、羧甲基纤维素钠(CMC)")
                    .setSolutionOperations("1. 检测钙离子含量\n2. 加入碳酸钠处理\n3. 提高pH值\n4. 补充CMC")
                    .setStatus(PollutionAlarmLog.Status.UNTREATED)
                    .setAlarmTime(dataSamplingTime != null ? dataSamplingTime : LocalDateTime.now()); // 优先使用数据采样时间
            
            // 解析详细信息JSON，提取关键参数
            if (jsonDetails != null) {
                extractParametersFromJsonString(alarmLog, jsonDetails);
            } else {
                extractParametersFromDetails(alarmLog, details);
            }
            
            // 保存到数据库
            boolean result = save(alarmLog);
            
            return result;
        } catch (Exception e) {
            log.error("保存钙污染报警日志失败", e);
            return false;
        }
    }
    
    @Override
    public boolean saveCO2PollutionLog(String wellId, String wellLocation, boolean isPolluted, String details) {
        return saveCO2PollutionLog(wellId, wellLocation, isPolluted, details, null);
    }
    
    @Override
    public boolean saveCO2PollutionLog(String wellId, String wellLocation, boolean isPolluted, String details, String jsonDetails) {
        if (!isPolluted) {
            // 如果未检测到污染，则不记录
            return false;
        }
        
        try {
            // 从详细信息中提取数据采样时间
            LocalDateTime dataSamplingTime = extractSamplingTimeFromDetails(details);
            
            // 检查是否存在重复预警
            PollutionAlarmLog existingAlarm = findRecentAlarm(wellId, PollutionAlarmLog.PollutionType.CO2);
            if (existingAlarm != null) {
                // 存在重复预警，更新已有预警而不是创建新记录
                log.info("检测到重复二氧化碳污染预警，更新已有预警记录，井ID：{}, 预警ID：{}", wellId, existingAlarm.getId());
                return false;
            }
            
            // 不存在重复预警，创建新记录
            PollutionAlarmLog alarmLog = new PollutionAlarmLog();
            alarmLog.setWellId(wellId)
                    .setWellLocation(wellLocation)
                    .setPollutionType(PollutionAlarmLog.PollutionType.CO2)
                    .setLogLevel(PollutionAlarmLog.LogLevel.ERROR)
                    .setSeverity(PollutionAlarmLog.Severity.MEDIUM) // 默认中度，可根据具体参数进行判断
                    .setMessage("检测到二氧化碳污染")
                    .setDetails(details)
                    .setRecommendedSolution("添加石灰或氢氧化钠中和")
                    .setSolutionChemicals("石灰、氢氧化钠")
                    .setSolutionOperations("1. 检测pH值\n2. 加入石灰或氢氧化钠\n3. 监测钻井液性能变化")
                    .setStatus(PollutionAlarmLog.Status.UNTREATED)
                    .setAlarmTime(dataSamplingTime != null ? dataSamplingTime : LocalDateTime.now()); // 优先使用数据采样时间
            
            // 解析详细信息JSON，提取关键参数
            if (jsonDetails != null) {
                extractParametersFromJsonString(alarmLog, jsonDetails);
            } else {
                extractParametersFromDetails(alarmLog, details);
            }
            
            // 保存到数据库
            boolean result = save(alarmLog);

            return result;
        } catch (Exception e) {
            log.error("保存CO2污染报警日志失败", e);
            return false;
        }
    }
    
    @Override
    public boolean saveStabilityPollutionLog(String wellId, String wellLocation, boolean isUnstable, String details) {
        return saveStabilityPollutionLog(wellId, wellLocation, isUnstable, details, null);
    }
    
    @Override
    public boolean saveStabilityPollutionLog(String wellId, String wellLocation, boolean isUnstable, String details, String jsonDetails) {
        if (!isUnstable) {
            // 如果未检测到稳定性问题，则不记录
            return false;
        }
        
        try {
            // 从详细信息中提取数据采样时间
            LocalDateTime dataSamplingTime = extractSamplingTimeFromDetails(details);
            
            // 检查是否存在重复预警
            PollutionAlarmLog existingAlarm = findRecentAlarm(wellId, PollutionAlarmLog.PollutionType.STABILITY);
            if (existingAlarm != null) {
                // 存在重复预警，更新已有预警而不是创建新记录
                log.info("检测到重复钻井液稳定性预警，更新已有预警记录，井ID：{}, 预警ID：{}", wellId, existingAlarm.getId());
                return false;
            }
            
            // 不存在重复预警，创建新记录
            PollutionAlarmLog alarmLog = new PollutionAlarmLog();
            alarmLog.setWellId(wellId)
                    .setWellLocation(wellLocation)
                    .setPollutionType(PollutionAlarmLog.PollutionType.STABILITY)
                    .setLogLevel(PollutionAlarmLog.LogLevel.ERROR)
                    .setSeverity(PollutionAlarmLog.Severity.MEDIUM) // 默认中度，可根据具体参数进行判断
                    .setMessage("检测到钻井液稳定性问题")
                    .setDetails(details)
                    .setRecommendedSolution("添加稳定剂，调整流变性")
                    .setSolutionChemicals("抗温抗盐处理剂、降滤失剂")
                    .setSolutionOperations("1. 测量流变参数\n2. 加入稳定剂\n3. 调整钻井液密度\n4. 监测钻井液性能")
                    .setStatus(PollutionAlarmLog.Status.UNTREATED)
                    .setAlarmTime(dataSamplingTime != null ? dataSamplingTime : LocalDateTime.now()); // 优先使用数据采样时间
            
            // 解析详细信息JSON，提取关键参数
            if (jsonDetails != null) {
                extractParametersFromJsonString(alarmLog, jsonDetails);
            } else {
                extractParametersFromDetails(alarmLog, details);
            }
            
            // 保存到数据库
            boolean result = save(alarmLog);

            return result;
        } catch (Exception e) {
            log.error("保存钻井液稳定性报警日志失败", e);
            return false;
        }
    }

    /**
     * 查询报警记录
     * @param query
     * @return
     */
    public PageDTO<PollutionAlarmLogQueryVO> queryAlarmLog(PollutionAlarmLogQuery query) {
        // 构造分页条件
        Page<PollutionAlarmLog> page = Page.of(query.getPageNo(), query.getPageSize());
        // 排序条件
        if (query.getSortBy() != null) {
            page.addOrder(new OrderItem(query.getSortBy(), query.getIsAsc()));
        }else{
            page.addOrder(new OrderItem("create_time", false));
        }
        LambdaQueryWrapper<PollutionAlarmLog> queryWrapper = new LambdaQueryWrapper<PollutionAlarmLog>()
                .ge(query.getStartTime()!= null,PollutionAlarmLog::getAlarmTime , query.getStartTime())
                .le(query.getEndTime()!= null,PollutionAlarmLog::getAlarmTime, query.getEndTime())
                .eq(query.getWellId()!=null,PollutionAlarmLog::getWellId, query.getWellId())
                .orderByDesc(PollutionAlarmLog::getAlarmTime);
        // 查询
        page(page, queryWrapper);

        //数据非空校验
        List<PollutionAlarmLog> records = page.getRecords();
        if (records == null || records.size() <= 0) {
            // 无数据，返回空结果
            return new PageDTO<>(page.getTotal(), page.getPages(), Collections.emptyList());
        }
        // 有数据,转换
        List<PollutionAlarmLogQueryVO> list = BeanUtil.copyToList(records, PollutionAlarmLogQueryVO.class);
        return new PageDTO<PollutionAlarmLogQueryVO>(page.getTotal(),page.getPages(),list);
    }

    /**
     * 查找最近的报警记录
     * 
     * @param wellId 井ID
     * @param pollutionType 污染类型
     * @return 最近的报警记录，如果没有在时间窗口内的报警记录则返回null
     */
    private PollutionAlarmLog findRecentAlarm(String wellId, String pollutionType) {
        LocalDateTime timeThreshold = null;
        // 先从Redis中获取采样时间
        Object samplingTimeStr = redisTemplate.opsForValue().get(SAMPLING_TIME_KEY);
        if (samplingTimeStr != null) {
            try {
                // 解析Redis中存储的时间字符串
                timeThreshold =  LocalDateTime.parse(samplingTimeStr.toString());
            } catch (Exception e) {
                log.error("解析采样时间失败: {}", e.getMessage());
                timeThreshold = LocalDateTime.now().minusHours(3);
            }
        }
        
        // 查询条件
        LambdaQueryWrapper<PollutionAlarmLog> queryWrapper = new LambdaQueryWrapper<PollutionAlarmLog>()
                .eq(PollutionAlarmLog::getWellId, wellId)
                .eq(PollutionAlarmLog::getPollutionType, pollutionType)
                .ge(PollutionAlarmLog::getAlarmTime, timeThreshold)
                .orderByDesc(PollutionAlarmLog::getCreateTime)
                .last("LIMIT 1");
        
        // 查询最近的记录
        List<PollutionAlarmLog> recentAlarms = list(queryWrapper);
        
        // 如果有符合条件的记录，返回最近的一条
        return recentAlarms.isEmpty() ? null : recentAlarms.get(0);
    }
    
    /**
     * 更新已有的报警记录
     * 
     * @param existingAlarm 已有的报警记录
     * @param details 新的详细信息
     */
    private void updateExistingAlarm(PollutionAlarmLog existingAlarm, String details) {
        try {
            // 只在必要时更新
            if (existingAlarm.getStatus().equals(PollutionAlarmLog.Status.UNTREATED)) {
                // 提取新参数
                extractParametersFromDetails(existingAlarm, details);
                
                // 从Redis获取采样时间，不再从details中提取
                LocalDateTime dataSamplingTime = extractSamplingTimeFromDetails(details);
                if (dataSamplingTime != null && dataSamplingTime.isAfter(existingAlarm.getAlarmTime())) {
                    log.info("更新预警时间: {} -> {}, 预警ID: {}", 
                            existingAlarm.getAlarmTime(), dataSamplingTime, existingAlarm.getId());
                    existingAlarm.setAlarmTime(dataSamplingTime);
                }
                
                // 如果是未处理状态，检查严重程度是否需要提升
                if (existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.LIGHT) || 
                    existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.MEDIUM)) {
                    
                    // 尝试从details中提取severity
                    try {
                        // 首先尝试作为JSON解析
                        if (details.trim().startsWith("{") && details.trim().endsWith("}")) {
                            JsonNode rootNode = objectMapper.readTree(details);
                            if (rootNode.has("severity")) {
                                String newSeverity = rootNode.get("severity").asText();
                                
                                // 如果新的严重程度更高，则更新
                                if ((existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.LIGHT) && 
                                     (newSeverity.equals(PollutionAlarmLog.Severity.MEDIUM) || 
                                      newSeverity.equals(PollutionAlarmLog.Severity.SEVERE))) ||
                                    (existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.MEDIUM) && 
                                     newSeverity.equals(PollutionAlarmLog.Severity.SEVERE))) {
                                    
                                    existingAlarm.setSeverity(newSeverity);
                                    log.info("预警严重程度升级: {} -> {}, 预警ID: {}", 
                                            existingAlarm.getSeverity(), newSeverity, existingAlarm.getId());
                                }
                            }
                        } else {
                            // 非JSON格式，尝试从文本中提取
                            if (details.contains("SEVERE") || details.contains("重度")) {
                                if (!existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.SEVERE)) {
                                    existingAlarm.setSeverity(PollutionAlarmLog.Severity.SEVERE);
                                    log.info("预警严重程度升级至SEVERE, 预警ID: {}", existingAlarm.getId());
                                }
                            } else if (details.contains("MEDIUM") || details.contains("中度")) {
                                if (existingAlarm.getSeverity().equals(PollutionAlarmLog.Severity.LIGHT)) {
                                    existingAlarm.setSeverity(PollutionAlarmLog.Severity.MEDIUM);
                                    log.info("预警严重程度升级至MEDIUM, 预警ID: {}", existingAlarm.getId());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析details提取severity失败: {}", e.getMessage());
                        // 解析失败不影响流程继续
                    }
                }
                
                // 附加新的详细信息
                if (existingAlarm.getDetails() != null && !existingAlarm.getDetails().isEmpty()) {
                    try {
                        // 先检查现有details是否为JSON格式
                        if (existingAlarm.getDetails().trim().startsWith("{") && 
                            existingAlarm.getDetails().trim().endsWith("}") &&
                            details.trim().startsWith("{") && 
                            details.trim().endsWith("}")) {
                            
                            // 两者都是JSON，尝试合并
                            JsonNode currentDetails = objectMapper.readTree(existingAlarm.getDetails());
                            JsonNode newDetails = objectMapper.readTree(details);
                            
                            // 创建合并后的对象
                            Map<String, Object> mergedDetails = new HashMap<>();
                            
                            // 将当前details的内容添加到合并对象
                            currentDetails.fields().forEachRemaining(entry -> {
                                mergedDetails.put(entry.getKey(), entry.getValue());
                            });
                            
                            // 将新details中不存在于当前details的内容添加到合并对象
                            newDetails.fields().forEachRemaining(entry -> {
                                if (!mergedDetails.containsKey(entry.getKey())) {
                                    mergedDetails.put(entry.getKey(), entry.getValue());
                                }
                            });
                            
                            // 转换为JSON字符串
                            String mergedDetailsJson = objectMapper.writeValueAsString(mergedDetails);
                            existingAlarm.setDetails(mergedDetailsJson);
                        } else {
                            // 如果不是JSON格式，简单地追加文本
                            existingAlarm.setDetails(existingAlarm.getDetails() + "\n---\n" + details);
                        }
                    } catch (Exception e) {
                        log.warn("合并详细信息失败: {}", e.getMessage());
                        // 合并失败，简单追加
                        existingAlarm.setDetails(existingAlarm.getDetails() + "\n---\n" + details);
                    }
                } else {
                    existingAlarm.setDetails(details);
                }
                
                // 更新数据库记录
                updateById(existingAlarm);
            }
        } catch (Exception e) {
            log.error("更新已有预警记录失败", e);
        }
    }
    
    /**
     * 从详细信息中提取参数
     * 
     * @param alarmLog 报警日志对象
     * @param details 详细信息JSON
     */
    private void extractParametersFromDetails(PollutionAlarmLog alarmLog, String details) {
        try {
            if (details == null || details.isEmpty()) {
                return;
            }
            
            Map<String, Object> abnormalParams = new HashMap<>();
            
            // 检查是否为JSON格式
            if (details.trim().startsWith("{") && details.trim().endsWith("}")) {
                try {
                    JsonNode rootNode = objectMapper.readTree(details);
                    
                    // 提取各个参数值
                    if (rootNode.has("densityChange")) {
                        double value = rootNode.get("densityChange").asDouble();
                        alarmLog.setDensityChange(BigDecimal.valueOf(value));
                        abnormalParams.put("密度变化", value);
                    }
                    
                    if (rootNode.has("viscosityChange")) {
                        double value = rootNode.get("viscosityChange").asDouble();
                        alarmLog.setViscosityChange(BigDecimal.valueOf(value));
                        abnormalParams.put("粘度变化", value);
                    }
                    
                    if (rootNode.has("shearForceChange")) {
                        double value = rootNode.get("shearForceChange").asDouble();
                        alarmLog.setShearForceChange(BigDecimal.valueOf(value));
                        abnormalParams.put("切力变化", value);
                    }
                    
                    if (rootNode.has("apiLossChange")) {
                        double value = rootNode.get("apiLossChange").asDouble();
                        alarmLog.setApiLossChange(BigDecimal.valueOf(value));
                        abnormalParams.put("API滤失量变化", value);
                    }
                    
                    if (rootNode.has("calciumContentChange")) {
                        double value = rootNode.get("calciumContentChange").asDouble();
                        alarmLog.setCalciumContentChange(BigDecimal.valueOf(value));
                        abnormalParams.put("钙离子含量变化", value);
                    }
                    
                    if (rootNode.has("phValue")) {
                        double value = rootNode.get("phValue").asDouble();
                        alarmLog.setPhValue(BigDecimal.valueOf(value));
                        abnormalParams.put("pH值", value);
                    }
                    
                    if (rootNode.has("esValue")) {
                        double value = rootNode.get("esValue").asDouble();
                        alarmLog.setEsValue(BigDecimal.valueOf(value));
                        abnormalParams.put("破乳电压值", value);
                    }
                    
                    if (rootNode.has("mudType")) {
                        int value = rootNode.get("mudType").asInt();
                        alarmLog.setMudType(value);
                    }
                    
                    if (rootNode.has("severity")) {
                        String severity = rootNode.get("severity").asText();
                        alarmLog.setSeverity(severity);
                    }
                } catch (Exception e) {
                    log.warn("解析JSON格式详细信息失败: {}", e.getMessage());
                    // JSON解析失败，继续尝试从文本提取
                }
            }
            
            // 如果不是JSON或JSON解析失败，尝试从文本中提取数字参数
            if (abnormalParams.isEmpty()) {
                try {
                    extractNumericParametersFromText(alarmLog, details, abnormalParams);
                } catch (Exception e) {
                    log.warn("从文本提取参数失败: {}", e.getMessage());
                }
            }
            
            // 提取严重程度
            if (alarmLog.getSeverity() == null) {
                if (details.contains("SEVERE") || details.contains("重度")) {
                    alarmLog.setSeverity(PollutionAlarmLog.Severity.SEVERE);
                } else if (details.contains("MEDIUM") || details.contains("中度")) {
                    alarmLog.setSeverity(PollutionAlarmLog.Severity.MEDIUM);
                } else if (details.contains("LIGHT") || details.contains("轻度")) {
                    alarmLog.setSeverity(PollutionAlarmLog.Severity.LIGHT);
                }
            }
            
            // 将异常参数列表序列化为JSON
            if (!abnormalParams.isEmpty()) {
                alarmLog.setAbnormalParameters(objectMapper.writeValueAsString(abnormalParams));
            }
            
        } catch (Exception e) {
            log.error("解析详细信息失败: {}", e.getMessage());
        }
    }
    
    /**
     * 从文本中提取数字参数
     * 
     * @param alarmLog 报警日志对象
     * @param text 文本内容
     * @param abnormalParams 异常参数Map
     */
    private void extractNumericParametersFromText(PollutionAlarmLog alarmLog, String text, Map<String, Object> abnormalParams) {
        // 使用正则表达式匹配"参数名: 数值"或"参数名变化: 数值%"的模式
        
        // 密度变化
        extractParameterWithPattern(text, "密度(变化|差值)[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*(%|g/cm³)?", value -> {
            alarmLog.setDensityChange(BigDecimal.valueOf(value));
            abnormalParams.put("密度变化", value);
        });
        
        // 粘度变化
        extractParameterWithPattern(text, "(表观黏度|粘度)(变化|增加率)[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*%", value -> {
            alarmLog.setViscosityChange(BigDecimal.valueOf(value));
            abnormalParams.put("粘度变化", value);
        });
        
        // 切力变化
        extractParameterWithPattern(text, "切力(变化|变化率)[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*%", value -> {
            alarmLog.setShearForceChange(BigDecimal.valueOf(value));
            abnormalParams.put("切力变化", value);
        });
        
        // API滤失量变化
        extractParameterWithPattern(text, "API滤失量(变化|变化率)[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*%", value -> {
            alarmLog.setApiLossChange(BigDecimal.valueOf(value));
            abnormalParams.put("API滤失量变化", value);
        });
        
        // pH值
        extractParameterWithPattern(text, "pH值[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)", value -> {
            alarmLog.setPhValue(BigDecimal.valueOf(value));
            abnormalParams.put("pH值", value);
        });
        
        // ES值
        extractParameterWithPattern(text, "破乳电压\\(ES\\)[:：]\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*V", value -> {
            alarmLog.setEsValue(BigDecimal.valueOf(value));
            abnormalParams.put("破乳电压值", value);
        });
    }
    
    /**
     * 使用正则表达式从文本中提取参数值
     * 
     * @param text 文本内容
     * @param pattern 正则表达式模式
     * @param consumer 处理提取值的函数
     */
    private void extractParameterWithPattern(String text, String pattern, Consumer<Double> consumer) {
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(text);
        if (matcher.find()) {
            try {
                // 正则表达式中的第一个捕获组是参数名，第二个捕获组是数值
                String valueStr = matcher.group(matcher.groupCount());
                double value = Double.parseDouble(valueStr);
                consumer.accept(value);
            } catch (Exception e) {
                log.debug("提取参数值失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 从详细信息中提取数据采样时间
     * 优先从Redis获取，如果Redis中不存在，则查询数据库
     * @param details 详细信息字符串（不再使用此参数解析时间）
     * @return 采样时间
     */
    private LocalDateTime extractSamplingTimeFromDetails(String details) {
        // 首先尝试从Redis获取采样时间
        try {
            Object samplingTimeStr = redisTemplate.opsForValue().get(SAMPLING_TIME_KEY);
            if (samplingTimeStr != null) {
                try {
                    return LocalDateTime.parse(samplingTimeStr.toString());
                } catch (Exception e) {
                    log.error("解析Redis中的采样时间失败: {}", e.getMessage());
                }
            }
            
            // Redis中不存在，尝试从数据库获取
            Object wellId = redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey());
            if (wellId != null) {
                // 查询最近的数据记录
                LambdaQueryWrapper<PollutionAlarmLog> queryWrapper = new LambdaQueryWrapper<PollutionAlarmLog>()
                        .eq(PollutionAlarmLog::getWellId, wellId.toString())
                        .orderByDesc(PollutionAlarmLog::getCreateTime)
                        .last("LIMIT 1");
                
                List<PollutionAlarmLog> recentLogs = list(queryWrapper);
                if (!recentLogs.isEmpty() && recentLogs.get(0).getAlarmTime() != null) {
                    return recentLogs.get(0).getAlarmTime();
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
     * 从纯文本中提取时间信息（不再使用此方法）
     */
    private LocalDateTime extractTimeFromPlainText(String text) {
        // 此方法已废弃
        return LocalDateTime.now();
    }
    
    /**
     * 通过WebSocket发送预警通知
     * 
     * @param alarmLog 报警日志对象
     */
    private void sendAlarmNotification(PollutionAlarmLog alarmLog) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "pollution_alarm");
            message.put("alarmId", alarmLog.getId());
            message.put("wellId", alarmLog.getWellId());
            message.put("wellLocation", alarmLog.getWellLocation());
            message.put("pollutionType", alarmLog.getPollutionType());
            message.put("severity", alarmLog.getSeverity());
            message.put("message", alarmLog.getMessage());
            message.put("alarmTime", alarmLog.getAlarmTime().toString());
            
            String wsMessage = objectMapper.writeValueAsString(message);
            webSocketServer.sendToAllClient(wsMessage);
            
            log.info("已通过WebSocket发送污染预警: {}", alarmLog.getMessage());
        } catch (Exception e) {
            log.error("发送WebSocket预警失败", e);
        }
    }
    
    /**
     * 直接从JSON字符串中提取参数
     * 
     * @param alarmLog 报警日志对象
     * @param jsonString 详细信息JSON字符串
     */
    private void extractParametersFromJsonString(PollutionAlarmLog alarmLog, String jsonString) {
        try {
            if (jsonString == null || jsonString.isEmpty()) {
                return;
            }
            
            JsonNode rootNode = objectMapper.readTree(jsonString);
            Map<String, Object> abnormalParams = new HashMap<>();
            
            // 检查是否已有异常参数字段
            if (rootNode.has("abnormalParameters") && rootNode.get("abnormalParameters").isObject()) {
                // 直接使用JSON中提供的异常参数字段
                JsonNode abnormalNode = rootNode.get("abnormalParameters");
                log.info("JSON中包含异常参数字段: {}", abnormalNode);
                
                // 将异常参数字段直接序列化保存
                alarmLog.setAbnormalParameters(abnormalNode.toString());
                
                // 同时根据异常参数更新具体字段
                abnormalNode.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode valueNode = entry.getValue();
                    
                    // 将异常参数添加到Map中
                    if (valueNode.isNumber()) {
                        abnormalParams.put(key, valueNode.asDouble());
                    } else {
                        abnormalParams.put(key, valueNode.asText());
                    }
                    
                    // 根据参数名称更新相应字段
                    updateFieldBasedOnParameterName(alarmLog, key, valueNode);
                });
            } else {
                // 如果没有异常参数字段，则按原来的方式提取各个参数
                // 提取各个参数值
                if (rootNode.has("densityChange")) {
                    double value = rootNode.get("densityChange").asDouble();
                    alarmLog.setDensityChange(BigDecimal.valueOf(value));
                    abnormalParams.put("密度变化", value);
                }
                
                if (rootNode.has("viscosityChange")) {
                    double value = rootNode.get("viscosityChange").asDouble();
                    alarmLog.setViscosityChange(BigDecimal.valueOf(value));
                    abnormalParams.put("粘度变化", value);
                }
                
                if (rootNode.has("shearForceChange")) {
                    double value = rootNode.get("shearForceChange").asDouble();
                    alarmLog.setShearForceChange(BigDecimal.valueOf(value));
                    abnormalParams.put("切力变化", value);
                }
                
                if (rootNode.has("apiLossChange")) {
                    double value = rootNode.get("apiLossChange").asDouble();
                    alarmLog.setApiLossChange(BigDecimal.valueOf(value));
                    abnormalParams.put("API滤失量变化", value);
                }
                
                if (rootNode.has("calciumContentChange")) {
                    double value = rootNode.get("calciumContentChange").asDouble();
                    alarmLog.setCalciumContentChange(BigDecimal.valueOf(value));
                    abnormalParams.put("钙离子含量变化", value);
                }
                
                if (rootNode.has("phValue")) {
                    double value = rootNode.get("phValue").asDouble();
                    alarmLog.setPhValue(BigDecimal.valueOf(value));
                    abnormalParams.put("pH值", value);
                }
                
                if (rootNode.has("esValue")) {
                    double value = rootNode.get("esValue").asDouble();
                    alarmLog.setEsValue(BigDecimal.valueOf(value));
                    abnormalParams.put("破乳电压值", value);
                }
                
                if (rootNode.has("mudType")) {
                    int value = rootNode.get("mudType").asInt();
                    alarmLog.setMudType(value);
                }
                
                if (rootNode.has("severity")) {
                    String severity = rootNode.get("severity").asText();
                    alarmLog.setSeverity(severity);
                }
                
                // 将异常参数列表序列化为JSON
                if (!abnormalParams.isEmpty()) {
                    alarmLog.setAbnormalParameters(objectMapper.writeValueAsString(abnormalParams));
                }
            }
            
            // 记录最终的异常参数
            log.info("最终记录的异常参数: {}", alarmLog.getAbnormalParameters());
            
        } catch (Exception e) {
            log.error("解析JSON字符串失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 根据参数名称更新相应字段
     * 
     * @param alarmLog 报警日志对象
     * @param paramName 参数名称
     * @param valueNode 参数值节点
     */
    private void updateFieldBasedOnParameterName(PollutionAlarmLog alarmLog, String paramName, JsonNode valueNode) {
        try {
            double value = valueNode.isNumber() ? valueNode.asDouble() : 
                          Double.parseDouble(valueNode.asText().replace("%", ""));
            
            if (paramName.contains("密度")) {
                alarmLog.setDensityChange(BigDecimal.valueOf(value));
            } else if (paramName.contains("黏度") || paramName.contains("粘度")) {
                alarmLog.setViscosityChange(BigDecimal.valueOf(value));
            } else if (paramName.contains("切力")) {
                alarmLog.setShearForceChange(BigDecimal.valueOf(value));
            } else if (paramName.contains("滤失")) {
                alarmLog.setApiLossChange(BigDecimal.valueOf(value));
            } else if (paramName.contains("钙离子")) {
                alarmLog.setCalciumContentChange(BigDecimal.valueOf(value));
            } else if (paramName.contains("pH")) {
                alarmLog.setPhValue(BigDecimal.valueOf(value));
            } else if (paramName.contains("破乳") || paramName.contains("ES")) {
                alarmLog.setEsValue(BigDecimal.valueOf(value));
            }
        } catch (NumberFormatException e) {
            log.warn("无法解析参数值为数字: {} - {}", paramName, valueNode);
        }
    }
} 