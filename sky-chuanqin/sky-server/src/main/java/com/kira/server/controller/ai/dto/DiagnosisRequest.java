package com.kira.server.controller.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 诊断分析请求 DTO
 * 用于触发钻井液异常智能诊断分析
 */
@Data
public class DiagnosisRequest {

    /**
     * 井号ID
     */
    @JsonProperty("well_id")
    private String wellId;

    /**
     * 预警类型
     */
    @JsonProperty("alert_type")
    private String alertType;

    /**
     * 预警触发时间
     */
    @JsonProperty("alert_triggered_at")
    private LocalDateTime alertTriggeredAt;

    /**
     * 预警阈值信息
     */
    @JsonProperty("alert_threshold")
    private AlertThreshold alertThreshold;

    /**
     * 钻井液样本数据列表
     */
    private List<DrillingFluidSample> samples;

    /**
     * 诊断上下文信息
     */
    private DiagnosisContext context;

    /**
     * 回调URL（用于异步结果通知）
     */
    @JsonProperty("callback_url")
    private String callbackUrl;

    /**
     * 是否流式返回
     */
    @JsonProperty("stream")
    private Boolean stream = true;

    /**
     * 预警阈值信息
     */
    @Data
    public static class AlertThreshold {
        private String field;
        private String condition;
        private Double threshold;
        @JsonProperty("current_value")
        private Double currentValue;
    }

    /**
     * 钻井液样本数据
     */
    @Data
    public static class DrillingFluidSample {
        private String id;
        @JsonProperty("well_id")
        private String wellId;
        @JsonProperty("sample_time")
        private LocalDateTime sampleTime;
        private String formation;
        @JsonProperty("outlet_temp")
        private Double outletTemp;
        private Double density;
        @JsonProperty("gel_10s")
        private Double gel10s;
        @JsonProperty("gel_10m")
        private Double gel10m;
        private Double rpm3;
        private Double rpm6;
        private Double rpm100;
        private Double rpm200;
        private Double rpm300;
        private Double rpm600;
        @JsonProperty("plastic_viscosity")
        private Double plasticViscosity;
        @JsonProperty("yield_point")
        private Double yieldPoint;
        @JsonProperty("flow_behavior_index")
        private Double flowBehaviorIndex;
        @JsonProperty("consistency_coefficient")
        private Double consistencyCoefficient;
        @JsonProperty("apparent_viscosity")
        private Double apparentViscosity;
        @JsonProperty("yield_plastic_ratio")
        private Double yieldPlasticRatio;
    }

    /**
     * 诊断上下文
     */
    @Data
    public static class DiagnosisContext {
        @JsonProperty("current_depth")
        private Double currentDepth;
        @JsonProperty("formation_type")
        private String formationType;
        @JsonProperty("drilling_phase")
        private String drillingPhase;
    }
}
