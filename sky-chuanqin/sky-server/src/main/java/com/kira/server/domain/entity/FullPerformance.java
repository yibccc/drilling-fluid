package com.kira.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 钻井液全性能参数
 * </p>
 *
 * @author kira
 * @since 2024-10-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("full_performance")
@ApiModel(value="FullPerformance对象", description="钻井液全性能参数")
public class FullPerformance implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "井编号")
    private String wellId;

    @ApiModelProperty(value = "采样时间")
    private LocalDateTime samplingTime;

    @ApiModelProperty(value = "采样深度")
    private Integer samplingDepth;

    @ApiModelProperty(value = "采样位置")
    private String samplingLocation;

    @ApiModelProperty(value = "采样量")
    private Double samplingAmount;

    @ApiModelProperty(value = "地层")
    private String formation;

    @ApiModelProperty(value = "出口温度")
    private Double outletTemperature;

    @ApiModelProperty(value = "钻井液密度")
    private Double drillingFluidDensity;

    @ApiModelProperty(value = "漏斗黏度")
    private Double funnelViscosity;

    @ApiModelProperty(value = "含砂量")
    private Double sandContent;

    @ApiModelProperty(value = "pH值")
    private Double phValue;

    @ApiModelProperty(value = "切力10秒")
    private Double shearForce10s;

    @ApiModelProperty(value = "切力10分")
    private Double shearForce10m;

    @ApiModelProperty(value = "3转速")
    private Integer rpm3;

    @ApiModelProperty(value = "6转速")
    private Integer rpm6;

    @ApiModelProperty(value = "100转速")
    private Integer rpm100;

    @ApiModelProperty(value = "200转速")
    private Integer rpm200;

    @ApiModelProperty(value = "300转速")
    private Integer rpm300;

    @ApiModelProperty(value = "600转速")
    private Integer rpm600;

    @ApiModelProperty(value = "API滤失量")
    private Double apiFiltrationLoss;

    @ApiModelProperty(value = "API滤饼厚度")
    private Double apiFilterCakeThickness;

    @ApiModelProperty(value = "高温高压失水量")
    private Double hthpFiltrationLoss;

    @ApiModelProperty(value = "高温高压滤饼厚度")
    private Double hthpFilterCakeThickness;

    @ApiModelProperty(value = "高温高压测试温度")
    private Double hthpTestTemperature;

    @ApiModelProperty(value = "塑性黏度")
    private Double plasticViscosity;

    @ApiModelProperty(value = "泥饼摩擦系数")
    private Double mudCakeFrictionCoefficient;

    @ApiModelProperty(value = "屈服点")
    private Double yieldPoint;

    @ApiModelProperty(value = "加重材料")
    private String weightingMaterial;

    @ApiModelProperty(value = "含油量")
    private Double oilContent;

    @ApiModelProperty(value = "含水量")
    private Double waterContent;

    @ApiModelProperty(value = "亚甲蓝含量")
    private Double methyleneBlueContent;

    @ApiModelProperty(value = "固相含量")
    private Double solidContent;

    @ApiModelProperty(value = "低密度固相含量")
    private Double lowDensitySolidContent;

    @ApiModelProperty(value = "膨润土含量")
    private Double bentoniteContent;

    @ApiModelProperty(value = "氯离子含量")
    private Double chlorideIonContent;

    @ApiModelProperty(value = "钙离子含量")
    private Double calciumIonContent;

    @ApiModelProperty(value = "钾离子含量")
    private Double potassiumIonContent;

    @ApiModelProperty(value = "碳酸盐含量")
    private Double carbonateContent;

    @ApiModelProperty(value = "滤液酚酞碱度")
    private Double filtratePhenolphthaleinAlkalinity;

    @ApiModelProperty(value = "滤液甲基橙碱度")
    private Double filtrateMethylOrangeAlkalinity;

    @ApiModelProperty(value = "钻井液碱度")
    private Double drillingFluidAlkalinity;

    @ApiModelProperty(value = "油水比")
    private Double oilWaterRatio;

    @ApiModelProperty(value = "钻井液活性")
    private Double drillingFluidActivity;

    @ApiModelProperty(value = "乳化击穿电压")
    private Double emulsionBreakdownVoltage;

    @ApiModelProperty(value = "钻井液体系")
    private String drillingFluidSystem;

    @ApiModelProperty(value = "流动指数")
    private Double flowIndex;

    @ApiModelProperty(value = "K值")
    private Double kValue;

    @ApiModelProperty(value = "岩性")
    private String lithology;

    @ApiModelProperty(value = "表观黏度")
    private Double apparentViscosity;

    @ApiModelProperty(value = "稠度系数")
    private Double consistencyIndex;

    @ApiModelProperty(value = "滤液总盐度")
    private Double filtrateTotalSalinity;

    @ApiModelProperty(value = "动塑比")
    private Double dynamicPlasticRatio;

    @ApiModelProperty(value = "测量原因")
    private String measurementReason;

    @ApiModelProperty(value = "屈服值")
    private Double yieldValue;

    @ApiModelProperty(value = "全性能序列号")
    private Integer fullPerformanceSerialNumber;

    @ApiModelProperty(value = "校正固相含量")
    private Double correctedSolidContent;

    @ApiModelProperty(value = "亚甲蓝交换容量")
    private Double methyleneBlueExchangeCapacity;

    @ApiModelProperty(value = "钻井液处理记录")
    private String drillingFluidTreatmentRecord;

    @ApiModelProperty(value = "设计最小密度")
    private Double designedMinDensity;

    @ApiModelProperty(value = "设计最大密度")
    private Double designedMaxDensity;

    @ApiModelProperty(value = "水基含油量")
    private Double oilContentWaterBased;

    @ApiModelProperty(value = "备注")
    private String remarks;


}
