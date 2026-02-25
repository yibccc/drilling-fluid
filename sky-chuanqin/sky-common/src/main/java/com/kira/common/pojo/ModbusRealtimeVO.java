package com.kira.common.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Modbus实时数据VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModbusRealtimeVO implements Serializable {
    
    private static final long serialVersionUID = 1L;

    private String type;
    private String wellId;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime samplingTime;
    
    private Long timestamp;

    // 密度（1分钟）
    private Double drillingFluidDensity;

    // 流变性（40分钟）
    private Double outletTemperature;
    private Double shearForce10s;
    private Double shearForce10m;
    private Double rpm3;
    private Double rpm6;
    private Double rpm100;
    private Double rpm200;
    private Double rpm300;
    private Double rpm600;
    private Double plasticViscosity;
    private Double yieldPoint;
    private Double apparentViscosity;
    private Double consistencyK;
    private Double ypPv;

    // 密度与电性（40分钟）
    private Double phValue;
    private Double conductivity;
    private Double emulsionBreakdownVoltage;

    // 固相（40分钟）
    private Double oilContent;
    private Double waterContent;
    private Double solidContent;

    // 离子滴定（40分钟）
    private Double chlorideIonContent;
    private Double calciumIonContent;
    private Double potassiumIonContent;
    private Double bromideContent;
    private Double strontiumContent;

    // 滤失（40分钟）
    private Double apiFiltrationLoss;
    private Double apiFilterCakeThickness;
    private Double hthpFiltrationLoss;
    private Double hthpFilterCakeThickness;
}