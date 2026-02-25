package com.kira.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钻井液污染报警日志
 *
 * @author kira
 */
@Data
@Accessors(chain = true)
@TableName("pollution_alarm_log")
public class PollutionAlarmLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 井ID
     */
    private String wellId;

    /**
     * 井位置/层位
     */
    private String wellLocation;

    /**
     * 污染类型：CA(钙污染)、CO2(二氧化碳污染)、STABILITY(钻井液稳定性)
     */
    private String pollutionType;
    
    /**
     * 泥浆类型：0-油基，1-水基
     */
    private Integer mudType;

    /**
     * 日志级别：INFO、WARN、ERROR
     */
    private String logLevel;
    
    /**
     * 严重程度：轻度、中度、重度
     */
    private String severity;

    /**
     * 报警消息
     */
    private String message;

    /**
     * 详细信息（JSON格式）
     */
    private String details;
    
    /**
     * 异常参数清单（JSON格式）
     */
    private String abnormalParameters;
    
    /**
     * 密度变化值
     */
    private BigDecimal densityChange;
    
    /**
     * 粘度变化值/变化率
     */
    private BigDecimal viscosityChange;
    
    /**
     * 切力变化值/变化率
     */
    private BigDecimal shearForceChange;
    
    /**
     * API滤失量变化值/变化率
     */
    private BigDecimal apiLossChange;
    
    /**
     * 钙离子含量变化值/变化率
     */
    private BigDecimal calciumContentChange;
    
    /**
     * pH值
     */
    private BigDecimal phValue;
    
    /**
     * 破乳电压值
     */
    private BigDecimal esValue;
    
    /**
     * 系统推荐处理方案
     */
    private String recommendedSolution;
    
    /**
     * 建议添加药剂
     */
    private String solutionChemicals;
    
    /**
     * 建议操作步骤
     */
    private String solutionOperations;

    /**
     * 处理状态：0-未处理，1-处理中，2-已解决，3-已关闭，4-已忽略
     */
    private Integer status;

    /**
     * 处理人
     */
    private String handler;

    /**
     * 处理时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime handleTime;

    /**
     * 处理说明
     */
    private String handleDescription;
    
    /**
     * 实际处理方法
     */
    private String handleApproach;
    
    /**
     * 实际使用药剂
     */
    private String handleChemicals;

    /**
     * 处理结果
     */
    private String handleResult;

    /**
     * 报警时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime alarmTime;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 污染类型常量
     */
    public static class PollutionType {
        /**
         * 钙污染
         */
        public static final String CA = "CA";
        
        /**
         * 二氧化碳污染
         */
        public static final String CO2 = "CO2";
        
        /**
         * 钻井液稳定性
         */
        public static final String STABILITY = "STABILITY";
    }
    
    /**
     * 泥浆类型常量
     */
    public static class MudType {
        /**
         * 油基泥浆
         */
        public static final Integer OIL_BASED = 0;
        
        /**
         * 水基泥浆
         */
        public static final Integer WATER_BASED = 1;
    }
    
    /**
     * 严重程度常量
     */
    public static class Severity {
        /**
         * 轻度
         */
        public static final String LIGHT = "轻度";
        
        /**
         * 中度
         */
        public static final String MEDIUM = "中度";
        
        /**
         * 重度
         */
        public static final String SEVERE = "重度";
    }

    /**
     * 状态常量
     */
    public static class Status {
        /**
         * 未处理
         */
        public static final Integer UNTREATED = 0;
        
        /**
         * 处理中
         */
        public static final Integer PROCESSING = 1;
        
        /**
         * 已解决
         */
        public static final Integer RESOLVED = 2;
        
        /**
         * 已关闭
         */
        public static final Integer CLOSED = 3;
        
        /**
         * 已忽略
         */
        public static final Integer IGNORED = 4;
    }
    
    /**
     * 日志级别常量
     */
    public static class LogLevel {
        /**
         * 信息
         */
        public static final String INFO = "INFO";
        
        /**
         * 警告
         */
        public static final String WARN = "WARN";
        
        /**
         * 错误
         */
        public static final String ERROR = "ERROR";
    }
} 