package com.kira.server.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class HandwrittenConditionsDTO {

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime samplingTime; // 采样时间

    private Double rpm600;
    private Double rpm300;
    private Double rpm200;
    private Double rpm100;
    private Double rpm6;
    private Double rpm3;
    private Double shearForce10s;
    private Double shearForce10m;
    private Double apparentViscosity;
    private Double plasticViscosity;
    private Double yieldPoint;
    private Double outletTemperature;
    private Double drillingFluidDensity;
    private Double funnelViscosity;
    private Double sandContent;
    private Double phValue;
    private Double apiFiltrationLoss;
    private Double apiFilterCakeThickness;
    private Double hthpFiltrationLoss;
    private Double hthpFilterCakeThickness;
    private Double hthpTestTemperature;
    private Double hthpTestStresses;
    private Double mudCakeFrictionCoefficient;
    private Double oilContent;
    private Double waterContent;
    private Double solidContent;
    private Double lowDensitySolidContent;
    private Double bentoniteContent;
    private Double chlorideIonContent;
    private Double calciumIonContent;
    private Double potassiumIonContent;
    private Double carbonateContent;
    private Double bromideContent;
    private Double strontiumContent;
    private Double filtratePhenolphthaleinAlkalinity;
    private Double filtrateMethylOrangeAlkalinity;
    private Double drillingFluidAlkalinity;
    private Double oilWaterRatio;
    private Double drillingFluidActivity;
    private Double emulsionBreakdownVoltage;
    private Double flowIndexN;
    private Double consistencyK;
    private Double filtrateTotalSalinity;
    private Double dynamicPlasticRatio;
    private Double correctedSolidContent;
    private Double methyleneBlueExchangeCapacity;
    private Double designedMinDensity;
    private Double designedMaxDensity;
    private Double oilContentWaterBased;
    private Double ypPv;
    private Double conductivity;

    private Long measuredId;
}
