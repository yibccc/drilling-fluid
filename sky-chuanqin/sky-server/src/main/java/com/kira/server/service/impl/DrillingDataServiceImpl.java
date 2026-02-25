package com.kira.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.server.domain.dto.DrillingOperatingConditions;
import com.kira.server.domain.dto.HandwrittenConditionsDTO;
import com.kira.server.domain.dto.ManualQuery;
import com.kira.server.domain.dto.ParametersDTO;
import com.kira.common.pojo.DrillingData;
import com.kira.server.domain.query.DrillingDataQuery;
import com.kira.server.domain.query.DrillingDataQueryVO;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.vo.DrillingDataLatestVO;
import com.kira.server.domain.vo.DrillingDataThisTripQueryVO;
import com.kira.server.domain.vo.ParameterKVO;
import com.kira.server.domain.vo.ParameterVO;
import com.kira.server.enums.RedisKeys;
import com.kira.server.mapper.DrillingDataMapper;
import com.kira.server.service.IDrillingDataService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author Kira
 * @create 2024-09-24 17:46
 */
@Service
public class DrillingDataServiceImpl extends ServiceImpl<DrillingDataMapper, DrillingData> implements IDrillingDataService {

    @Autowired
    private DrillingDataMapper mapper;

    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    @Autowired
    private Executor drillingDataExecutor;


    /**
     * 参数曲线查询（多参数）-性能参数页面
     *
     * @param parameterDTO
     * @return
     */
    public Map<String, List<ParameterVO>> queryParametersByDTO(ParametersDTO parameterDTO) {
        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));

        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, parameterDTO.getWellId())
                .eq(DrillingData::getType, 0)
                .ge(DrillingData::getSamplingTime, parameterDTO.getStartTime())
                .le(DrillingData::getSamplingTime, parameterDTO.getEndTime())
                .orderByAsc(DrillingData::getSamplingTime);

        List<DrillingData> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

        Map<String, List<ParameterVO>> resultMap = new ConcurrentHashMap<>();

        // 获取参数名称列表
        List<String> paramNames = parameterDTO.getParamNames();

        // 如果参数名称列表为空，返回空Map
        if (paramNames == null || paramNames.isEmpty() || drillingDatas.isEmpty()) {
            return resultMap;
        }

        // 为每个参数名称初始化一个空的List，以便后续存储对应的ParameterVO
        for (String paramName : paramNames) {
            resultMap.put(paramName, Collections.synchronizedList(new ArrayList<>()));
        }

        // 创建CompletableFuture列表存储异步任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 为每个参数名称创建一个异步任务，使用配置的线程池
        for (String paramName : paramNames) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                List<ParameterVO> parameterVOs = resultMap.get(paramName);
                
                // 处理该参数的所有数据
                for (DrillingData drillingData : drillingDatas) {
                    ParameterVO parameterVO = new ParameterVO();
                    parameterVO.setCreateTime(drillingData.getSamplingTime()); // 设置参数的创建时间
                    parameterVO.setRed(false);

                    // 获取参数值
                    Double value = getParameterValue(drillingData, paramName);
                    parameterVO.setValue(value != null ? value : 0); // 设置参数值，如果为null则设置为0

                    // 将ParameterVO添加到对应的List中
                    parameterVOs.add(parameterVO);
                }
            }, drillingDataExecutor);
            
            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("多线程处理参数数据时发生错误", e);
            Thread.currentThread().interrupt();
        }
        return resultMap; // 返回最终的结果Map
    }

    /**
     * 使用箱线图法(IQR)检测异常
     * @param datas 数据列表
     * @param paramName 参数名
     * @param value 待检测的值
     * @param outlierCoef 异常值系数，默认为1.5
     * @return 是否为异常值
     */
    private boolean detectAnomalyWithBoxPlot(List<DrillingData> datas, String paramName, Double value, double outlierCoef) {
        if (value == null) return false;
        
        // 提取非零参数值
        List<Double> values = new ArrayList<>();
        for (DrillingData data : datas) {
            Double val = getParameterValue(data, paramName);
            if (val != null && val != 0) {
                values.add(val);
            }
        }
        
        // 数据点过少时不做异常检测
        if (values.size() < 4) return false;
        
        // 排序
        Collections.sort(values);
        
        // 计算四分位数
        int n = values.size();
        double q1, q3;
        
        // 计算Q1(第一四分位数)
        double pos = 0.25 * (n + 1);
        int intPos = (int) pos;
        double fraction = pos - intPos;
        if (intPos < 1) {
            q1 = values.get(0);
        } else if (intPos >= n) {
            q1 = values.get(n - 1);
        } else {
            q1 = values.get(intPos - 1) + fraction * (values.get(intPos) - values.get(intPos - 1));
        }
        
        // 计算Q3(第三四分位数)
        pos = 0.75 * (n + 1);
        intPos = (int) pos;
        fraction = pos - intPos;
        if (intPos < 1) {
            q3 = values.get(0);
        } else if (intPos >= n) {
            q3 = values.get(n - 1);
        } else {
            q3 = values.get(intPos - 1) + fraction * (values.get(intPos) - values.get(intPos - 1));
        }
        
        // 计算四分位距
        double iqr = q3 - q1;
        
        // 定义异常界限
        double lowerBound = q1 - outlierCoef * iqr;
        double upperBound = q3 + outlierCoef * iqr;
        
        // 判断是否为异常值
        return value < lowerBound || value > upperBound;
    }

    /**
     * 参数曲线查询（多参数）-异常分析页面（左）
     *
     * @param parameterDTO
     * @return
     */
    public Map<String, List<ParameterVO>> queryParametersByDTOApriori(ParametersDTO parameterDTO) {

        // todo 修改回参数异常检测逻辑
        parameterDTO.setThreshold(0.4);
        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));
        Double max = 0.0;
        Double min = 0.0;

        // 构建查询条件
        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
                .eq(DrillingData::getType, 0) // 只查询类型为0的数据
//                .eq(DrillingData::getRemarks, "正常") // 只查询备注为"正常"的数据
                .ge(DrillingData::getSamplingTime, parameterDTO.getStartTime()) // 查询开始时间大于等于指定时间
                .le(DrillingData::getSamplingTime, parameterDTO.getEndTime()) // 查询结束时间小于等于指定时间
                .orderByAsc(DrillingData::getSamplingTime); // 按日期升序排列结果

        // 查询数据
        List<DrillingData> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

        // 初始化结果Map，用于存放参数名和对应的ParameterVO列表
        Map<String, List<ParameterVO>> resultMap = new HashMap<>();

        // 获取参数名称列表
        List<String> paramNames = parameterDTO.getParamNames();

        // 如果参数名称列表为空，返回空Map
        if (paramNames == null || paramNames.isEmpty()) {
            return resultMap; // 没有要查询的参数，直接返回空结果
        }

        // 为每个参数名称初始化一个空的List，以便后续存储对应的ParameterVO
        for (String paramName : paramNames) {
            resultMap.put(paramName, new ArrayList<>());
        }
        resultMap.put("baselineMax", new ArrayList<>());
        resultMap.put("baselineMin", new ArrayList<>());

        // 遍历每条DrillingData记录
        for (DrillingData drillingData : drillingDatas) {
            // 遍历每个参数名称
            for (String paramName : paramNames) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime()); // 设置参数的创建时间

                // 使用Map来映射参数名称到对应的getter方法，减少大量的if判断
                Double value = getParameterValue(drillingData, paramName); // 获取参数值
                parameterVO.setValue(value != null ? value : 0); // 设置参数值，如果为null则设置为0

                // 如果阈值不为null，进行值的判断
                if (parameterDTO.getThreshold() != null) {
                    // 使用箱线图法检测异常
                    double outlierCoef = 1.5;  // 默认系数
                    if (parameterDTO.getThreshold() > 0) {
                        // 可以根据业务需求调整异常值系数
                        outlierCoef = parameterDTO.getThreshold() * 3;  // 假设threshold是0.1到0.5之间，乘以3得到0.3到1.5的范围
                    }
                    
                    if (detectAnomalyWithBoxPlot(drillingDatas, paramName, value, outlierCoef)) {
                        parameterVO.setRed(true); // 设置为红色标记
                    } else {
                        parameterVO.setRed(false); // 设置为正常状态
                    }
                    
                    // 计算箱线图法的上下边界，用于前端显示
                    // 获取非零参数值列表
                    List<Double> values = new ArrayList<>();
                    for (DrillingData data : drillingDatas) {
                        Double val = getParameterValue(data, paramName);
                        if (val != null && val != 0) {
                            values.add(val);
                        }
                    }
                    
                    // 确保有足够的数据点计算四分位数
                    if (values.size() >= 4) {
                        // 排序
                        Collections.sort(values);
                        
                        // 计算四分位数
                        int n = values.size();
                        double q1, q3;
                        
                        // 计算Q1(第一四分位数)
                        double pos = 0.25 * (n + 1);
                        int intPos = (int) pos;
                        double fraction = pos - intPos;
                        if (intPos < 1) {
                            q1 = values.get(0);
                        } else if (intPos >= n) {
                            q1 = values.get(n - 1);
                        } else {
                            q1 = values.get(intPos - 1) + fraction * (values.get(intPos) - values.get(intPos - 1));
                        }
                        
                        // 计算Q3(第三四分位数)
                        pos = 0.75 * (n + 1);
                        intPos = (int) pos;
                        fraction = pos - intPos;
                        if (intPos < 1) {
                            q3 = values.get(0);
                        } else if (intPos >= n) {
                            q3 = values.get(n - 1);
                        } else {
                            q3 = values.get(intPos - 1) + fraction * (values.get(intPos) - values.get(intPos - 1));
                        }
                        
                        // 计算四分位距
                        double iqr = q3 - q1;
                        
                        // 计算箱线图的上下边界
                        min = q1 - outlierCoef * iqr;
                        max = q3 + outlierCoef * iqr;
                    } else {
                        // 数据点不足时使用平均值方法作为备选
                        double averageValue = calculateAverageValue(drillingDatas, paramName);
                        double thresholdValue = averageValue * parameterDTO.getThreshold();
                        max = averageValue + thresholdValue;
                        min = averageValue - thresholdValue;
                    }
                } else {
                    parameterVO.setRed(false); // 如果没有阈值，则默认不标记红色
                }

                // 将ParameterVO添加到对应的List中
                resultMap.get(paramName).add(parameterVO);
            }
        }

        ParameterVO vo = new ParameterVO();
        vo.setValue(max);
        resultMap.get("baselineMax").add(vo);
        ParameterVO vo1 = new ParameterVO();
        vo1.setValue(min);
        resultMap.get("baselineMin").add(vo1);
        return resultMap; // 返回最终的结果Map
    }

    /**
     * 根据参数名称获取对应的值
     *
     * @param drillingData FullPerformance对象
     * @param paramName    参数名称
     * @return 参数值
     */
    private Double getParameterValue(DrillingData drillingData, String paramName) {
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
                return drillingData.getRpm600() != null ? drillingData.getRpm600() : 0.0;
            case "rpm300":
                return drillingData.getRpm300() != null ? drillingData.getRpm300() : 0.0;
            case "rpm200":
                return drillingData.getRpm200() != null ? drillingData.getRpm200() : 0.0;
            case "rpm100":
                return drillingData.getRpm100() != null ? drillingData.getRpm100() : 0.0;
            case "rpm6":
                return drillingData.getRpm6() != null ? drillingData.getRpm6() : 0.0;
            case "rpm3":
                return drillingData.getRpm3() != null ? drillingData.getRpm3() : 0.0;
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
            default:
                return 0.0;
        }
    }

    /**
     * 计算阈值
     *
     * @param drillingDatas
     * @param paramName
     * @return
     */
    private double calculateAverageValue(List<DrillingData> drillingDatas, String paramName) {
        double sum = 0.0;
        int count = 0;

        for (DrillingData drillingData : drillingDatas) {
            Double value = getParameterValue(drillingData, paramName);
            if (value != null && value > 0) { // 只计算非零值
                sum += value;
                count++;
            }
        }

        // 计算平均值，避免除以零
        return count > 0 ? sum / count : 0.0;
    }

    /**
     * 异常识别算法
     *
     * @param parameterDTO
     * @return
     */
    public List<ParameterKVO> queryKByDTO(ParametersDTO parameterDTO) {
        // todo 修改回参数异常检测逻辑
        parameterDTO.setThreshold(0.4);

        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));


        // 构建查询条件
        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, parameterDTO.getWellId())
                .eq(DrillingData::getType, 0)
//                .eq(DrillingData::getRemarks, "正常")
//                .ge(DrillingData::getDate, LocalDateTime.now().minusHours(24))
                .ge(DrillingData::getSamplingTime, parameterDTO.getStartTime()) // 查询开始时间大于等于指定时间
                .le(DrillingData::getSamplingTime, parameterDTO.getEndTime()) // 查询结束时间小于等于指定时间
                .orderByDesc(DrillingData::getSamplingTime);

        // 查询数据
        List<DrillingData> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

//         创建 ParameterVO 列表
        List<ParameterVO> parameterVOS = new ArrayList<>();

        for (DrillingData drillingData : drillingDatas) {
            ParameterVO parameterVO = new ParameterVO();
            parameterVO.setCreateTime(drillingData.getSamplingTime());
            parameterVO.setValue(getParameterValue(drillingData, parameterDTO.getParamNames().get(0)));
            parameterVO.setRed(false);

            parameterVOS.add(parameterVO);
        }

        ArrayList<ParameterKVO> parameterKVOS = new ArrayList<>();
        int j = 0;
        for (ParameterVO parameterVO : parameterVOS) {
            ParameterKVO parameterKVO = new ParameterKVO();
            parameterKVO.setCreateTime(parameterVO.getCreateTime());
            parameterKVO.setValue(parameterVO.getValue());
            parameterKVO.setId(Long.valueOf(drillingDatas.get(j).getId()));
            j++;
            parameterKVOS.add(parameterKVO);
        }
        
        // 异常检测与KValue计算，但不触发告警
        for (int i = 0; i < parameterKVOS.size(); i++) {
            Double value = parameterKVOS.get(i).getValue();
            
            // 配置异常值系数
            double outlierCoef = 1.5;  // 默认系数
            if (parameterDTO.getThreshold() > 0) {
                // 可以根据业务需求调整异常值系数
                outlierCoef = parameterDTO.getThreshold() * 3;  // 假设threshold是0.1到0.5之间，乘以3得到0.3到1.5的范围
            }
            
            // 使用箱线图法检测异常
            boolean isAbnormal = false;
            if (value != null) {
                isAbnormal = detectAnomalyWithBoxPlot(drillingDatas, parameterDTO.getParamNames().get(0), value, outlierCoef);
            }
            
            parameterKVOS.get(i).setIsUnnormal(isAbnormal);
            
            // 计算KValue - 无论是否异常都计算，用于展示
            if (value != null) {
                // 计算四分位数和界限
                List<Double> values = new ArrayList<>();
                for (DrillingData data : drillingDatas) {
                    Double val = getParameterValue(data, parameterDTO.getParamNames().get(0));
                    if (val != null && val != 0) {
                        values.add(val);
                    }
                }
                
                if (values.size() >= 4) {
                    Collections.sort(values);
                    int n = values.size();
                    double q1 = values.get(n / 4);
                    double q3 = values.get(3 * n / 4);
                    double iqr = q3 - q1;
                    double median = values.get(n / 2);
                    
                    double lowerBound = q1 - outlierCoef * iqr;
                    double upperBound = q3 + outlierCoef * iqr;
                    
                    // 计算KValue
                    if (value < lowerBound) {
                        // 低于下界，计算与下界的距离比
                        parameterKVOS.get(i).setKValue(Math.abs(value - lowerBound) / iqr);
                    } else if (value > upperBound) {
                        // 高于上界，计算与上界的距离比
                        parameterKVOS.get(i).setKValue(Math.abs(value - upperBound) / iqr);
                    } else {
                        // 在正常范围内，计算到最近边界的距离比例
                        double distToLower = Math.abs(value - lowerBound);
                        double distToUpper = Math.abs(value - upperBound);
                        double minDist = Math.min(distToLower, distToUpper);
                        double normalizedDist = minDist / (upperBound - lowerBound);
                        
                        // 正常值KValue随着靠近边界而增大，不超过1
                        parameterKVOS.get(i).setKValue(Math.max(0, 1 - normalizedDist));
                    }
                } else {
                    // 数据不足时的备选方法
                    double averageValue = calculateAverageValue(drillingDatas, parameterDTO.getParamNames().get(0));
                    if (isAbnormal) {
                        parameterKVOS.get(i).setKValue(Math.abs(value - averageValue) / (averageValue * parameterDTO.getThreshold()));
                    } else {
                        parameterKVOS.get(i).setKValue(0.2); // 正常值的默认低KValue
                    }
                }
            } else {
                parameterKVOS.get(i).setKValue(0.0);
            }
        }

        return parameterKVOS; // 返回最终更新后的 ParameterVO 列表
    }

    /**
     * 分页查询
     *
     * @param query
     * @return
     */
    public PageDTO<DrillingDataQueryVO> queryDrillingData(DrillingDataQuery query) {

        query.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));


        // 1.构建条件
        // 1.1.分页条件
        Page<DrillingData> page = Page.of(query.getPageNo(), query.getPageSize());
        // 1.2.排序条件
        if (query.getSortBy() != null) {
            page.addOrder(new OrderItem(query.getSortBy(), query.getIsAsc()));
        }
//        else{
//            // 默认按照更新时间排序
//            page.addOrder(new OrderItem("update_time", false));
//        }
        // 1.3. 构建查询条件，匹配 wellId
//        QueryWrapper<DrillingData> queryWrapper = new QueryWrapper<>();
//        if (query.getWellId() != null) {
//            queryWrapper.eq("well_id", query.getWellId());
//            queryWrapper.eq("")
//        }
        LambdaQueryWrapper<DrillingData> queryWrapper = new LambdaQueryWrapper<DrillingData>()
                .like(DrillingData::getWellId, query.getWellId())
                .ge(DrillingData::getSamplingTime, query.getStartTime())
                .le(DrillingData::getSamplingTime, query.getEndTime())
                .eq(DrillingData::getType, query.getType())
                .orderByDesc(DrillingData::getSamplingTime);

        // 2. 查询
        page(page, queryWrapper);

        // 3.数据非空校验
        List<DrillingData> records = page.getRecords();
        if (records == null || records.size() <= 0) {
            // 无数据，返回空结果
            return new PageDTO<>(page.getTotal(), page.getPages(), Collections.emptyList());
        }
        // 4.有数据，转换
        List<DrillingDataQueryVO> list = BeanUtil.copyToList(records, DrillingDataQueryVO.class);
        // 5.封装返回
        return new PageDTO<DrillingDataQueryVO>(page.getTotal(), page.getPages(), list);
    }

    /**
     * 修改工况
     *
     * @param conditions
     */
    public void updateDrillingOperatingConditions(DrillingOperatingConditions conditions) {
        DrillingData drillingData = mapper.selectById(conditions.getId());
        drillingData.setOperationCondition(conditions.getOperatingConditions());
        mapper.updateById(drillingData);
    }

    /**
     * 修改手动输入数据并返回对比值
     *
     * @param dto 手动输入条件
     * @return 对比结果
     */
    @Transactional
    public DrillingDataQueryVO updateDrillingManual(HandwrittenConditionsDTO dto) {
        // 获取实测数据
        DrillingData measureddrillingData = mapper.selectById(dto.getMeasuredId());
        if (measureddrillingData == null) {
            throw new IllegalArgumentException("实测数据不存在");
        }

        // 删除对应的手动数据和实测数据
        LambdaQueryWrapper<DrillingData> wrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getIsHandwritten, dto.getMeasuredId());
        mapper.delete(wrapper);
        //新增手动数据和对比数据
        DrillingData drillingData = new DrillingData();
        drillingData.setSamplingTime(dto.getSamplingTime());
        drillingData.setRpm600(dto.getRpm600());
        drillingData.setRpm300(dto.getRpm300());
        drillingData.setRpm200(dto.getRpm200());
        drillingData.setRpm100(dto.getRpm100());
        drillingData.setRpm6(dto.getRpm6());
        drillingData.setRpm3(dto.getRpm3());
        drillingData.setShearForce10s(dto.getShearForce10s());
        drillingData.setShearForce10m(dto.getShearForce10m());
        drillingData.setApparentViscosity(dto.getApparentViscosity());
        drillingData.setPlasticViscosity(dto.getPlasticViscosity());
        drillingData.setYieldPoint(dto.getYieldPoint());
        drillingData.setOutletTemperature(dto.getOutletTemperature());
        drillingData.setDrillingFluidDensity(dto.getDrillingFluidDensity());
        drillingData.setFunnelViscosity(dto.getFunnelViscosity());
        drillingData.setSandContent(dto.getSandContent());
        drillingData.setPhValue(dto.getPhValue());
        drillingData.setApiFiltrationLoss(dto.getApiFiltrationLoss());
        drillingData.setApiFilterCakeThickness(dto.getApiFilterCakeThickness());
        drillingData.setHthpFiltrationLoss(dto.getHthpFiltrationLoss());
        drillingData.setHthpFilterCakeThickness(dto.getHthpFilterCakeThickness());
        drillingData.setHthpTestTemperature(dto.getHthpTestTemperature());
        drillingData.setHthpTestStresses(dto.getHthpTestStresses());
        drillingData.setMudCakeFrictionCoefficient(dto.getMudCakeFrictionCoefficient());
        drillingData.setOilContent(dto.getOilContent());
        drillingData.setWaterContent(dto.getWaterContent());
        drillingData.setSolidContent(dto.getSolidContent());
        drillingData.setLowDensitySolidContent(dto.getLowDensitySolidContent());
        drillingData.setBentoniteContent(dto.getBentoniteContent());
        drillingData.setChlorideIonContent(dto.getChlorideIonContent());
        drillingData.setCalciumIonContent(dto.getCalciumIonContent());
        drillingData.setPotassiumIonContent(dto.getPotassiumIonContent());
        drillingData.setCarbonateContent(dto.getCarbonateContent());
        drillingData.setBromideContent(dto.getBromideContent());
        drillingData.setStrontiumContent(dto.getStrontiumContent());
        drillingData.setFiltratePhenolphthaleinAlkalinity(dto.getFiltratePhenolphthaleinAlkalinity());
        drillingData.setFiltrateMethylOrangeAlkalinity(dto.getFiltrateMethylOrangeAlkalinity());
        drillingData.setDrillingFluidAlkalinity(dto.getDrillingFluidAlkalinity());
        drillingData.setOilWaterRatio(dto.getOilWaterRatio());
        drillingData.setDrillingFluidActivity(dto.getDrillingFluidActivity());
        drillingData.setEmulsionBreakdownVoltage(dto.getEmulsionBreakdownVoltage());
        drillingData.setFlowIndexN(dto.getFlowIndexN());
        drillingData.setConsistencyK(dto.getConsistencyK());
        drillingData.setFiltrateTotalSalinity(dto.getFiltrateTotalSalinity());
        drillingData.setDynamicPlasticRatio(dto.getDynamicPlasticRatio());
        drillingData.setCorrectedSolidContent(dto.getCorrectedSolidContent());
        drillingData.setMethyleneBlueExchangeCapacity(dto.getMethyleneBlueExchangeCapacity());
        drillingData.setDesignedMinDensity(dto.getDesignedMinDensity());
        drillingData.setDesignedMaxDensity(dto.getDesignedMaxDensity());
        drillingData.setOilContentWaterBased(dto.getOilContentWaterBased());
        drillingData.setYpPv(dto.getYpPv());
        drillingData.setConductivity(dto.getConductivity());
        drillingData.setIsHandwritten(dto.getMeasuredId());
        drillingData.setType(1); // 标记为手动数据类型

        // 对比手动和实测数据
        DrillingDataQueryVO drillingDataQueryVO = compareWithHandwritten(measureddrillingData, drillingData);

        // 保存对比数据
        DrillingData compareDrillingData = new DrillingData();
        BeanUtils.copyProperties(drillingDataQueryVO, compareDrillingData);
        compareDrillingData.setType(2); // 标记为对比数据类型

        // 更新手动数据与实测数据的关联
        compareDrillingData.setIsHandwritten(dto.getMeasuredId());
        compareDrillingData.setWellId(measureddrillingData.getWellId());
        compareDrillingData.setSamplingTime(dto.getSamplingTime());
        mapper.insert(compareDrillingData);

        drillingData.setIsHandwritten(dto.getMeasuredId());
        drillingData.setWellId(measureddrillingData.getWellId());
        if (compareDrillingData.getSamplingTime() == null) {
            compareDrillingData.setSamplingTime(LocalDateTime.now());
        } else {
            drillingData.setSamplingTime(compareDrillingData.getSamplingTime());
        }
        mapper.insert(drillingData); // 插入手动数据


        drillingDataQueryVO.setType(2);
        drillingDataQueryVO.setIsHandwritten(dto.getMeasuredId());
        return drillingDataQueryVO;
    }

//    /**
//     * 查询需要的参数单个是否标红
//     *
//     * @param parameterDTO
//     * @return
//     */
//    public Map<String, ParametersSetVO> quertParametersSet(ParametersSetDTO parameterDTO) {
//        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));
//
//        if (parameterDTO.getThreshold() == null) {
//            parameterDTO.setThreshold(0.30);
//        }
//        // 构建查询条件
//        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
//                .eq(DrillingData::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
//                .eq(DrillingData::getType, 0) // 只查询类型为0的数据
// //                .eq(DrillingData::getRemarks, "正常") // 只查询备注为"正常"的数据
//                .last("LIMIT 1")
//                .orderByDesc(DrillingData::getSamplingTime); // 按日期升序排列结果
//
//        // 构建查询条件2
//        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper2 = new LambdaQueryWrapper<DrillingData>()
//                .eq(DrillingData::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
//                .eq(DrillingData::getType, 0) // 只查询类型为0的数据
// //                .eq(DrillingData::getRemarks, "正常") // 只查询备注为"正常"的数据
//                .last("LIMIT 50")
//                .orderByAsc(DrillingData::getSamplingTime); // 按日期升序排列结果
//
//        // 查询数据
//        DrillingData drillingData = mapper.selectOne(drillingDataLambdaQueryWrapper);
//
//        List<DrillingData> drillingData1 = mapper.selectList(drillingDataLambdaQueryWrapper2);
//
//        // 初始化结果Map，用于存放参数名和对应的ParameterVO列表
//        Map<String, ParametersSetVO> resultMap = new HashMap<>();
//
//        // 获取参数名称列表
//        List<String> paramNames = parameterDTO.getParameters();
//
//        // 如果参数名称列表为空，返回空Map
//        if (paramNames == null || paramNames.isEmpty()) {
//            return resultMap; // 没有要查询的参数，直接返回空结果
//        }
//
//        // 为每个参数名称初始化一个空的List，以便后续存储对应的ParameterVO
//        for (String paramName : paramNames) {
//            resultMap.put(paramName, new ParametersSetVO());
//        }
//
//        // 遍历每个参数名称
//        for (String paramName : paramNames) {
//            ParametersSetVO parameterVO = new ParametersSetVO();
// //                parameterVO.setCreateTime(drillingData.getDate()); // 设置参数的创建时间
//
//            // 使用Map来映射参数名称到对应的getter方法，减少大量的if判断
//            Double value = getParameterValue(drillingData, paramName); // 获取参数值
//            parameterVO.setValue(value != null ? value : 0); // 设置参数值，如果为null则设置为0
//
//            // 如果阈值不为null，进行值的判断
//            if (parameterDTO.getThreshold() != null) {
//                double averageValue = calculateAverageValue(drillingData1, paramName); // 计算参数的非零平均值
//                double thresholdValue = averageValue * parameterDTO.getThreshold(); // 计算阈值
//
//                // 检查当前值是否在阈值范围内
//                if (value != null && (value < (averageValue - thresholdValue) || value > (averageValue + thresholdValue))) {
//                    parameterVO.setIsRed(true); // 设置为红色标记
//                } else {
//                    parameterVO.setIsRed(false); // 设置为正常状态
//                }
//            } else {
//                parameterVO.setIsRed(false); // 如果没有阈值，则默认不标记红色
//            }
//            // 将当前参数的VO添加到对应的List中
//            resultMap.get(paramName).setValue(parameterVO.getValue());
//            resultMap.get(paramName).setIsRed(parameterVO.getIsRed()); // 设置是否红色标记
//        }
//
//
//        return resultMap; // 返回最终的结果Map
//    }

    /**
     * 本趟钻分页查询
     *
     * @param query
     * @return
     */
    public PageDTO<DrillingDataThisTripQueryVO> queryDrillingThisTripData(DrillingDataQuery query) {
        // 1.构建条件
        // 1.1.分页条件
        Page<DrillingData> page = Page.of(query.getPageNo(), query.getPageSize());
        // 1.2.排序条件
        if (query.getSortBy() != null) {
            page.addOrder(new OrderItem(query.getSortBy(), query.getIsAsc()));
        }
//        else{
//            // 默认按照更新时间排序
//            page.addOrder(new OrderItem("update_time", false));
//        }
        // 1.3. 构建查询条件，匹配 wellId
//        QueryWrapper<DrillingData> queryWrapper = new QueryWrapper<>();
//        if (query.getWellId() != null) {
//            queryWrapper.eq("well_id", query.getWellId());
//            queryWrapper.eq("")
//        }
        LambdaQueryWrapper<DrillingData> queryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, query.getWellId())
                .ge(DrillingData::getSamplingTime, query.getStartTime())
                .le(DrillingData::getSamplingTime, query.getEndTime())
                .eq(DrillingData::getType, 0);

        // 2. 查询
        page(page, queryWrapper);

        // 3.数据非空校验
        List<DrillingData> records = page.getRecords();
        if (records == null || records.size() <= 0) {
            // 无数据，返回空结果
            return new PageDTO<>(page.getTotal(), page.getPages(), Collections.emptyList());
        }
        // 4.有数据，转换
        List<DrillingDataThisTripQueryVO> list = BeanUtil.copyToList(records, DrillingDataThisTripQueryVO.class);
        // 5.封装返回
        return new PageDTO<DrillingDataThisTripQueryVO>(page.getTotal(), page.getPages(), list);
    }

    /**
     * 对比手测与测量并将值返回
     *
     * @param measureddrillingData
     * @param drillingData
     * @return
     */
    public static DrillingDataQueryVO compareWithHandwritten(DrillingData measureddrillingData, DrillingData drillingData) {
        DrillingDataQueryVO result = new DrillingDataQueryVO();

        if (ObjectUtils.isEmpty(measureddrillingData) || ObjectUtils.isEmpty(drillingData)) {
            return result;
        }

        // 计算比值
        result.setRpm600(calculateRatio(measureddrillingData.getRpm600(), drillingData.getRpm600()));
        result.setRpm300(calculateRatio(measureddrillingData.getRpm300(), drillingData.getRpm300()));
        result.setRpm200(calculateRatio(measureddrillingData.getRpm200(), drillingData.getRpm200()));
        result.setRpm100(calculateRatio(measureddrillingData.getRpm100(), drillingData.getRpm100()));
        result.setRpm6(calculateRatio(measureddrillingData.getRpm6(), drillingData.getRpm6()));
        result.setRpm3(calculateRatio(measureddrillingData.getRpm3(), drillingData.getRpm3()));
        result.setShearForce10s(calculateRatio(measureddrillingData.getShearForce10s(), drillingData.getShearForce10s()));
        result.setShearForce10m(calculateRatio(measureddrillingData.getShearForce10m(), drillingData.getShearForce10m()));
        result.setApparentViscosity(calculateRatio(measureddrillingData.getApparentViscosity(), drillingData.getApparentViscosity()));
        result.setPlasticViscosity(calculateRatio(measureddrillingData.getPlasticViscosity(), drillingData.getPlasticViscosity()));
        result.setYieldPoint(calculateRatio(measureddrillingData.getYieldPoint(), drillingData.getYieldPoint()));
        result.setOutletTemperature(calculateRatio(measureddrillingData.getOutletTemperature(), drillingData.getOutletTemperature()));
        result.setDrillingFluidDensity(calculateRatio(measureddrillingData.getDrillingFluidDensity(), drillingData.getDrillingFluidDensity()));
        result.setFunnelViscosity(calculateRatio(measureddrillingData.getFunnelViscosity(), drillingData.getFunnelViscosity()));
        result.setSandContent(calculateRatio(measureddrillingData.getSandContent(), drillingData.getSandContent()));
        result.setPhValue(calculateRatio(measureddrillingData.getPhValue(), drillingData.getPhValue()));
        result.setApiFiltrationLoss(calculateRatio(measureddrillingData.getApiFiltrationLoss(), drillingData.getApiFiltrationLoss()));
        result.setApiFilterCakeThickness(calculateRatio(measureddrillingData.getApiFilterCakeThickness(), drillingData.getApiFilterCakeThickness()));
        result.setHthpFiltrationLoss(calculateRatio(measureddrillingData.getHthpFiltrationLoss(), drillingData.getHthpFiltrationLoss()));
        result.setHthpFilterCakeThickness(calculateRatio(measureddrillingData.getHthpFilterCakeThickness(), drillingData.getHthpFilterCakeThickness()));
        result.setHthpTestTemperature(calculateRatio(measureddrillingData.getHthpTestTemperature(), drillingData.getHthpTestTemperature()));
        result.setHthpTestStresses(calculateRatio(measureddrillingData.getHthpTestStresses(), drillingData.getHthpTestStresses()));
        result.setMudCakeFrictionCoefficient(calculateRatio(measureddrillingData.getMudCakeFrictionCoefficient(), drillingData.getMudCakeFrictionCoefficient()));
        result.setOilContent(calculateRatio(measureddrillingData.getOilContent(), drillingData.getOilContent()));
        result.setWaterContent(calculateRatio(measureddrillingData.getWaterContent(), drillingData.getWaterContent()));
        result.setSolidContent(calculateRatio(measureddrillingData.getSolidContent(), drillingData.getSolidContent()));
        result.setLowDensitySolidContent(calculateRatio(measureddrillingData.getLowDensitySolidContent(), drillingData.getLowDensitySolidContent()));
        result.setBentoniteContent(calculateRatio(measureddrillingData.getBentoniteContent(), drillingData.getBentoniteContent()));
        result.setChlorideIonContent(calculateRatio(measureddrillingData.getChlorideIonContent(), drillingData.getChlorideIonContent()));
        result.setCalciumIonContent(calculateRatio(measureddrillingData.getCalciumIonContent(), drillingData.getCalciumIonContent()));
        result.setPotassiumIonContent(calculateRatio(measureddrillingData.getPotassiumIonContent(), drillingData.getPotassiumIonContent()));
        result.setCarbonateContent(calculateRatio(measureddrillingData.getCarbonateContent(), drillingData.getCarbonateContent()));
        result.setBromideContent(calculateRatio(measureddrillingData.getBromideContent(), drillingData.getBromideContent()));
        result.setStrontiumContent(calculateRatio(measureddrillingData.getStrontiumContent(), drillingData.getStrontiumContent()));
        result.setFiltratePhenolphthaleinAlkalinity(calculateRatio(measureddrillingData.getFiltratePhenolphthaleinAlkalinity(), drillingData.getFiltratePhenolphthaleinAlkalinity()));
        result.setFiltrateMethylOrangeAlkalinity(calculateRatio(measureddrillingData.getFiltrateMethylOrangeAlkalinity(), drillingData.getFiltrateMethylOrangeAlkalinity()));
        result.setDrillingFluidAlkalinity(calculateRatio(measureddrillingData.getDrillingFluidAlkalinity(), drillingData.getDrillingFluidAlkalinity()));
        result.setOilWaterRatio(calculateRatio(measureddrillingData.getOilWaterRatio(), drillingData.getOilWaterRatio()));
        result.setDrillingFluidActivity(calculateRatio(measureddrillingData.getDrillingFluidActivity(), drillingData.getDrillingFluidActivity()));
        result.setEmulsionBreakdownVoltage(calculateRatio(measureddrillingData.getEmulsionBreakdownVoltage(), drillingData.getEmulsionBreakdownVoltage()));
        result.setFlowIndexN(calculateRatio(measureddrillingData.getFlowIndexN(), drillingData.getFlowIndexN()));
        result.setConsistencyK(calculateRatio(measureddrillingData.getConsistencyK(), drillingData.getConsistencyK()));
        result.setFiltrateTotalSalinity(calculateRatio(measureddrillingData.getFiltrateTotalSalinity(), drillingData.getFiltrateTotalSalinity()));
        result.setDynamicPlasticRatio(calculateRatio(measureddrillingData.getDynamicPlasticRatio(), drillingData.getDynamicPlasticRatio()));
        result.setCorrectedSolidContent(calculateRatio(measureddrillingData.getCorrectedSolidContent(), drillingData.getCorrectedSolidContent()));
        result.setMethyleneBlueExchangeCapacity(calculateRatio(measureddrillingData.getMethyleneBlueExchangeCapacity(), drillingData.getMethyleneBlueExchangeCapacity()));
        result.setDesignedMinDensity(calculateRatio(measureddrillingData.getDesignedMinDensity(), drillingData.getDesignedMinDensity()));
        result.setDesignedMaxDensity(calculateRatio(measureddrillingData.getDesignedMaxDensity(), drillingData.getDesignedMaxDensity()));
        result.setOilContentWaterBased(calculateRatio(measureddrillingData.getOilContentWaterBased(), drillingData.getOilContentWaterBased()));
        result.setYpPv(calculateRatio(measureddrillingData.getYpPv(), drillingData.getYpPv()));
        result.setConductivity(calculateRatio(measureddrillingData.getConductivity(), drillingData.getConductivity()));


        return result;
    }

    private static Double calculateRatio(Double measuredValue, Double manualValue) {
        if (measuredValue == null) {
            return null; // 如果实测值为空，返回null
        }
        if (manualValue == null || manualValue == 0) {
            return null; // 手动值为空或者为0,不进行计算
            //throw new IllegalArgumentException("手动值不能为空且不能为0"); // 可以选择抛出异常或者返回null，这里选择返回null更符合"对比"的语义，不强制要求手动值必须有效
        }
        return measuredValue / manualValue; // 返回比值
    }

    @Override
    public List<DrillingDataQueryVO> queryDrillingDataManual(ManualQuery query) {

        //构造条件
        LambdaQueryWrapper<DrillingData> queryWrapper = new LambdaQueryWrapper<DrillingData>()
                .like(DrillingData::getWellId, query.getWellId())
                .ge(DrillingData::getSamplingTime, query.getStartTime())
                .le(DrillingData::getSamplingTime, query.getEndTime())
                .eq(DrillingData::getType, query.getType())
                .orderByDesc(DrillingData::getSamplingTime);

        // 2. 查询
        List<DrillingData> drillingData = mapper.selectList(queryWrapper);
        // 3.数据非空校验
        if (drillingData == null || drillingData.size() <= 0) {
            // 无数据，返回空结果
            return new ArrayList<DrillingDataQueryVO>();
        }
        // 4.有数据，转换
        List<DrillingDataQueryVO> list = BeanUtil.copyToList(drillingData, DrillingDataQueryVO.class);
        // 5.封装返回
        return list;
    }

    /**
     * 查询最新的一组数据
     * @return
     */
    public DrillingDataLatestVO quertEvery5min(String id) {
        LambdaQueryWrapper<DrillingData> last = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, id)
                .eq(DrillingData::getIsHandwritten,0)
                .orderByDesc(DrillingData::getSamplingTime)
                .last("limit 1");
        DrillingData drillingData = mapper.selectOne(last);
        if (drillingData != null) {
            return BeanUtil.copyProperties(drillingData, DrillingDataLatestVO.class);
        }

        return null;
    }

    @Override
    public Map<String, List<ParameterVO>> queryParametersByDTORpm(ParametersDTO parameterDTO) {
        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));

        // 构建查询条件
        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
                .eq(DrillingData::getType, 0) // 只查询类型为0的数据
                .ge(DrillingData::getSamplingTime, parameterDTO.getStartTime()) // 查询开始时间大于等于指定时间
                .le(DrillingData::getSamplingTime, parameterDTO.getEndTime()) // 查询结束时间小于等于指定时间
                .orderByAsc(DrillingData::getSamplingTime); // 按日期升序排列结果

        // 查询数据
        List<DrillingData> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

        // 初始化结果Map，使用LinkedHashMap保证顺序
        Map<String, List<ParameterVO>> resultMap = new LinkedHashMap<>();
        
        // 如果没有数据，返回空Map
        if (drillingDatas == null || drillingDatas.isEmpty()) {
            return resultMap;
        }

        // 定义RPM参数的固定顺序并初始化对应的列表
        String[] rpmParams = {"rpm600", "rpm300", "rpm200", "rpm100", "rpm6", "rpm3"};
        for (String rpmParam : rpmParams) {
            resultMap.put(rpmParam, new ArrayList<>(drillingDatas.size())); // 预分配容量
        }
        
        // 为每个RPM参数一次性处理所有数据
        for (String rpmParam : rpmParams) {
            List<ParameterVO> paramList = resultMap.get(rpmParam);
            
            for (DrillingData drillingData : drillingDatas) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime());
                
                // 根据参数名获取对应的值
                Double value;
                switch (rpmParam) {
                    case "rpm600":
                        value = drillingData.getRpm600();
                        break;
                    case "rpm300":
                        value = drillingData.getRpm300();
                        break;
                    case "rpm200":
                        value = drillingData.getRpm200();
                        break;
                    case "rpm100":
                        value = drillingData.getRpm100();
                        break;
                    case "rpm6":
                        value = drillingData.getRpm6();
                        break;
                    case "rpm3":
                        value = drillingData.getRpm3();
                        break;
                    default:
                        value = null;
                }
                
                parameterVO.setValue(value != null ? value : 0);
                parameterVO.setRed(false);
                
                // 添加到对应参数的列表中
                paramList.add(parameterVO);
            }
        }
        
        return resultMap;
    }

    @Override
    public Map<String, List<ParameterVO>> queryParametersByDTOShear(ParametersDTO parameterDTO) {
        parameterDTO.setWellId((String) redisTemplate.opsForValue().get(RedisKeys.WELL_NAME.getKey()));

        // 构建查询条件
        LambdaQueryWrapper<DrillingData> drillingDataLambdaQueryWrapper = new LambdaQueryWrapper<DrillingData>()
                .eq(DrillingData::getWellId, parameterDTO.getWellId()) // 通过井ID过滤数据
                .eq(DrillingData::getType, 0) // 只查询类型为0的数据
                .ge(DrillingData::getSamplingTime, parameterDTO.getStartTime()) // 查询开始时间大于等于指定时间
                .le(DrillingData::getSamplingTime, parameterDTO.getEndTime()) // 查询结束时间小于等于指定时间
                .orderByAsc(DrillingData::getSamplingTime); // 按日期升序排列结果

        // 查询数据
        List<DrillingData> drillingDatas = mapper.selectList(drillingDataLambdaQueryWrapper);

        // 初始化结果Map，使用LinkedHashMap保证顺序
        Map<String, List<ParameterVO>> resultMap = new LinkedHashMap<>();

        // 如果没有数据，返回空Map
        if (drillingDatas == null || drillingDatas.isEmpty()) {
            return resultMap;
        }

        // 定义剪切力参数的固定顺序并初始化对应的列表
        String[] shearParams = {"shearForce10s", "shearForce10m"};
        for (String shearParam : shearParams) {
            resultMap.put(shearParam, new ArrayList<>(drillingDatas.size())); // 预分配容量
        }

        // 为每个剪切力参数一次性处理所有数据
        for (String shearParam : shearParams) {
            List<ParameterVO> paramList = resultMap.get(shearParam);

            for (DrillingData drillingData : drillingDatas) {
                ParameterVO parameterVO = new ParameterVO();
                parameterVO.setCreateTime(drillingData.getSamplingTime());

                // 根据参数名获取对应的值
                Double value;
                switch (shearParam) {
                    case "shearForce10s":
                        value = drillingData.getShearForce10s();
                        break;
                    case "shearForce10m":
                        value = drillingData.getShearForce10m();
                        break;
                    default:
                        value = null;
                }

                parameterVO.setValue(value != null ? value : 0);
                parameterVO.setRed(false);

                // 添加到对应参数的列表中
                paramList.add(parameterVO);
            }
        }

        return resultMap;
    }
}

