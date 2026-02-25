package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.server.domain.entity.PollutionAlarmLog;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.query.PollutionAlarmLogQuery;
import com.kira.server.domain.vo.PollutionAlarmLogQueryVO;

/**
 * 钻井液污染报警日志服务接口
 *
 * @author kira
 */
public interface IPollutionAlarmLogService extends IService<PollutionAlarmLog> {
    
    /**
     * 保存钙污染报警日志
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isPolluted 是否污染
     * @param details 详细信息
     * @return 保存结果
     */
    boolean saveCaPollutionLog(String wellId, String wellLocation, boolean isPolluted, String details);
    
    /**
     * 保存钙污染报警日志（支持额外的JSON格式详细参数）
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isPolluted 是否污染
     * @param details 详细信息
     * @param jsonDetails JSON格式的详细参数
     * @return 保存结果
     */
    boolean saveCaPollutionLog(String wellId, String wellLocation, boolean isPolluted, String details, String jsonDetails);
    
    /**
     * 保存CO2污染报警日志
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isPolluted 是否污染
     * @param details 详细信息
     * @return 保存结果
     */
    boolean saveCO2PollutionLog(String wellId, String wellLocation, boolean isPolluted, String details);
    
    /**
     * 保存CO2污染报警日志（支持额外的JSON格式详细参数）
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isPolluted 是否污染
     * @param details 详细信息
     * @param jsonDetails JSON格式的详细参数
     * @return 保存结果
     */
    boolean saveCO2PollutionLog(String wellId, String wellLocation, boolean isPolluted, String details, String jsonDetails);
    
    /**
     * 保存钻井液稳定性报警日志
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isUnstable 是否不稳定
     * @param details 详细信息
     * @return 保存结果
     */
    boolean saveStabilityPollutionLog(String wellId, String wellLocation, boolean isUnstable, String details);
    
    /**
     * 保存钻井液稳定性报警日志（支持额外的JSON格式详细参数）
     * 
     * @param wellId 井ID
     * @param wellLocation 井位置
     * @param isUnstable 是否不稳定
     * @param details 详细信息
     * @param jsonDetails JSON格式的详细参数
     * @return 保存结果
     */
    boolean saveStabilityPollutionLog(String wellId, String wellLocation, boolean isUnstable, String details, String jsonDetails);

    PageDTO<PollutionAlarmLogQueryVO> queryAlarmLog(PollutionAlarmLogQuery query);
} 