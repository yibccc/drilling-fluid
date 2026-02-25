package com.kira.mqtt.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author Kira
 * @create 2025-02-21 16:15
 */
@ApiModel(description = "")
@Data
public class ModbusDataDTO {
    @ApiModelProperty(value = "出口温度 (对应寄存器 VD588 40045-40046 密度 温度)")
    private Double outletTemperature;

    @ApiModelProperty(value = "钻井液密度 (对应寄存器 VD584 40043-40044 密度 密度)")
    private Double drillingFluidFensity;//此处有错误但硬件不好改，只好将错就错

    @ApiModelProperty(value = "pH值 (对应寄存器 VD644 40073-40074 密度 pH)")
    private Double phValue;

    @ApiModelProperty(value = "切力10秒 (对应寄存器 VD512 40007-40008 流变性(恒温) 初切)")
    private Double shearForce10s;

    @ApiModelProperty(value = "切力10分 (对应寄存器 VD516 40009-40010 流变性(恒温) 终切)")
    private Double shearForce10m;

    @ApiModelProperty(value = "3转速 (对应寄存器 VD548 40025-40026 流变性(恒温) 3转)")
    private Double rpm3;

    @ApiModelProperty(value = "6转速 (对应寄存器 VD544 40023-40024 流变性(恒温) 6转)")
    private Double rpm6;

    @ApiModelProperty(value = "100转速 (对应寄存器 VD540 40021-40022 流变性(恒温) 100转)")
    private Double rpm100;

    @ApiModelProperty(value = "200转速 (对应寄存器 VD536 40019-40020 流变性(恒温) 200转)")
    private Double rpm200;

    @ApiModelProperty(value = "300转速 (对应寄存器 VD532 40017-40018 流变性(恒温) 300转)")
    private Double rpm300;

    @ApiModelProperty(value = "600转速 (对应寄存器 VD528 40015-40016 流变性(恒温) 600转)")
    private Double rpm600;

    @ApiModelProperty(value = "API滤失量 (对应寄存器 VD564 40033-40034 滤失(常温常压) 滤失量)")
    private Double apiFiltrationLoss;

    @ApiModelProperty(value = "API滤饼厚度 (对应寄存器 VD568 40035-40036 滤失(常温常压) 泥饼厚度)")
    private Double apiFilterCakeThickness;

    @ApiModelProperty(value = "高温高压失水量 (对应寄存器 VD664 40083-40084 滤失(高温高压) 滤失量)")
    private Double hthpFiltrationLoss;

    @ApiModelProperty(value = "高温高压滤饼厚度 (对应寄存器 VD668 40085-40086 滤失(高温高压) 泥饼厚度)")
    private Double hthpFilterCakeThickness;

    @ApiModelProperty(value = "高温高压测试温度 (对应寄存器 VD628 40065-40066 流变性(恒温) 温度)")
    private Double hthpTestTemperature;

    @ApiModelProperty(value = "塑性黏度 (对应寄存器 VD504 40003-40004 流变性(恒温) PV)")
    private Double plasticViscosity;

    @ApiModelProperty(value = "动切力 (对应寄存器 VD508 40005-40006 流变性(恒温) YP)")
    private Double yieldPoint;

    @ApiModelProperty(value = "含油量 (对应寄存器 VD572 40037-40038 固相 油含量)")
    private Double oilContent;

    @ApiModelProperty(value = "含水量 (对应寄存器 VD576 40039-40040 固相 水含量)")
    private Double waterContent;

    @ApiModelProperty(value = "固相含量 (对应寄存器 VD580 40041-40042 固相 固体含量)")
    private Double solidContent;

    @ApiModelProperty(value = "氯离子含量 (对应寄存器 VD556 40029-40030 离子滴定 Cl-离子浓度)")
    private Double chlorideIonContent;

    @ApiModelProperty(value = "钙离子含量 (对应寄存器 VD560 40031-40032 离子滴定 Ca+离子浓度)")
    private Double calciumIonContent;

    @ApiModelProperty(value = "钾离子含量 (对应寄存器 VD648 40075-40076 离子滴定 K+离子浓度)")
    private Double potassiumIonContent;

    @ApiModelProperty(value = "溴离子含量 (对应寄存器 VD656 40079-40080 离子滴定 Br-离子浓度)")
    private Double bromideContent;

    @ApiModelProperty(value = "锶离子含量 (对应寄存器 VD660 40081-40082 离子滴定 Sr+离子浓度)")
    private Double strontiumContent;

    @ApiModelProperty(value = "电稳定性 (对应寄存器 VD636 40069-40070 密度 破乳电压)")
    private Double emulsionBreakdownVoltage;

    @ApiModelProperty(value = "稠度系数 (对应寄存器 VD524 40013-40014 流变性(恒温) K)")
    private Double consistencyK;

    @ApiModelProperty(value = "表观黏度 (对应寄存器 VD500 40001-40002 流变性(恒温) AV)")
    private Double apparentViscosity;

    @ApiModelProperty(value = " (动塑比 对应寄存器 VD552 40027-40028 流变性(恒温) YP/PV)")
    private Double ypPv;

    @ApiModelProperty(value = "电导率 (对应寄存器 VD640 40071-40072 密度 电导率)")
    private Double conductivity;

//    @ApiModelProperty(value = "含砂量")
//    private Double sandContent; //暂借为PN码

    @ApiModelProperty(value = "低密度固相含量")
    private Double drillingFluidFensity3; //新增密度： 暂借为drillingFluidFensity3

    @ApiModelProperty(value = "膨润土含量")
    private Double outletTemperature3;// 新增密度单元温度 暂借为outletTemperature3

    @ApiModelProperty(value = "流性指数")
    private Double flowIndexN;

    @ApiModelProperty(value = "采样深度")
    private Double samplingDepth;

    @ApiModelProperty(value = "采样量")
    private Double samplingAmount;

    @ApiModelProperty(value = "漏斗黏度")
    private Double funnelViscosity;

    @ApiModelProperty(value = "高温高压测试压力")
    private Double hthpTestStresses;

    @ApiModelProperty(value = "泥饼摩擦系数")
    private Double mudCakeFrictionCoefficient;

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

    @ApiModelProperty(value = "滤液总盐度")
    private Double filtrateTotalSalinity;

    @ApiModelProperty(value = "动塑比")
    private Double dynamicPlasticRatio;

    @ApiModelProperty(value = "校正固相含量")
    private Double correctedSolidContent;

    @ApiModelProperty(value = "亚甲蓝交换容量")
    private Double methyleneBlueExchangeCapacity;

    @ApiModelProperty(value = "设计最小密度")
    private Double designedMinDensity;

    @ApiModelProperty(value = "设计最大密度")
    private Double designedMaxDensity;

    @ApiModelProperty(value = "水基含油量")
    private Double oilContentWaterBased;

    @ApiModelProperty(value = "0油基/1水基")
    private Integer isOil;

}
