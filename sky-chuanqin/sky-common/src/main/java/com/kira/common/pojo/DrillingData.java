package com.kira.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author kira
 * @since 2024-09-23
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("drilling_data")//本趟钻
public class DrillingData implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;  // 主键 ID

    private String wellId; // 井编号
    private LocalDateTime samplingTime; // 采样时间
    private Double samplingDepth; // 采样深度
    private String samplingLocation; // 采样位置
    private Double samplingAmount; // 采样量
    private String formation; // 地层
    private Double outletTemperature; // 出口温度 (对应寄存器 VD588 40045-40046 流变性(恒温) 温度)
    private Double drillingFluidDensity; // 钻井液密度 (对应寄存器 VD584 40043-40044 密度 密度)
    private Double funnelViscosity; // 漏斗黏度
    private Double sandContent; // 含砂量
    private Double phValue; // pH值 (对应寄存器 VD644 40073-40074 密度 pH)
    private Double shearForce10s; // 切力10秒 (对应寄存器 VD512 40007-40008 流变性(恒温) 初切)
    private Double shearForce10m; // 切力10分 (对应寄存器 VD516 40009-40010 流变性(恒温) 终切)
    private Double rpm3; // 3转速 (对应寄存器 VD548 40025-40026 流变性(恒温) 3转)
    private Double rpm6; // 6转速 (对应寄存器 VD544 40023-40024 流变性(恒温) 6转)
    private Double rpm100; // 100转速 (对应寄存器 VD540 40021-40022 流变性(恒温) 100转)
    private Double rpm200; // 200转速 (对应寄存器 VD536 40019-40020 流变性(恒温) 200转)
    private Double rpm300; // 300转速 (对应寄存器 VD532 40017-40018 流变性(恒温) 300转)
    private Double rpm600; // 600转速 (对应寄存器 VD528 40015-40016 流变性(恒温) 600转)
    private Double apiFiltrationLoss; // API滤失量 (对应寄存器 VD564 40033-40034 滤失(常温常压) 滤失量)
    private Double apiFilterCakeThickness; // API滤饼厚度 (对应寄存器 VD568 40035-40036 滤失(常温常压) 泥饼厚度)
    private Double hthpFiltrationLoss; // 高温高压失水量 (对应寄存器 VD664 40083-40084 滤失(高温高压) 滤失量)
    private Double hthpFilterCakeThickness; // 高温高压滤饼厚度 (对应寄存器 VD668 40085-40086 滤失(高温高压) 泥饼厚度)
    private Double hthpTestTemperature; // 高温高压测试温度 (对应寄存器 VD628 40065-40066 流变性(恒温) 温度)
    private Double hthpTestStresses; // 高温高压测试压力
    private Double plasticViscosity; // 塑性黏度 (对应寄存器 VD504 40003-40004 流变性(恒温) PV)
    private Double mudCakeFrictionCoefficient; // 泥饼摩擦系数
    private Double yieldPoint; // 动切力 (对应寄存器 VD508 40005-40006 流变性(恒温) YP)
    private String weightingMaterial; // 加重材料
    private Double oilContent; // 含油量 (对应寄存器 VD572 40037-40038 固相 油含量)
    private Double waterContent; // 含水量 (对应寄存器 VD576 40039-40040 固相 水含量)
    private Double solidContent; // 固相含量 (对应寄存器 VD580 40041-40042 固相 固体含量)
    private Double lowDensitySolidContent; // 低密度固相含量
    private Double bentoniteContent; // 膨润土含量
    private Double chlorideIonContent; // 氯离子含量 (对应寄存器 VD556 40029-40030 离子滴定 Cl-离子浓度)
    private Double calciumIonContent; // 钙离子含量 (对应寄存器 VD560 40031-40032 离子滴定 Ca+离子浓度)
    private Double potassiumIonContent; // 钾离子含量 (对应寄存器 VD648 40075-40076 离子滴定 K+离子浓度)
    private Double carbonateContent; // 碳酸盐含量
    private Double bromideContent; // 溴离子含量 (对应寄存器 VD656 40079-40080 离子滴定 Br-离子浓度)
    private Double strontiumContent; // 锶离子含量 (对应寄存器 VD660 40081-40082 离子滴定 Sr+离子浓度)
    private Double filtratePhenolphthaleinAlkalinity; // 滤液酚酞碱度
    private Double filtrateMethylOrangeAlkalinity; // 滤液甲基橙碱度
    private Double drillingFluidAlkalinity; // 钻井液碱度
    private Double oilWaterRatio; // 油水比
    private Double drillingFluidActivity; // 钻井液活性
    private Double emulsionBreakdownVoltage; // 电稳定性 (对应寄存器 VD636 40069-40070 密度 破乳电压)
    private String drillingFluidSystem; // 钻井液体系
    private Double flowIndexN; // 流性指数
    private Double consistencyK; // 稠度系数 (对应寄存器 VD524 40013-40014 流变性(恒温) K)
    private String lithology; // 岩性
    private Double apparentViscosity; // 表观黏度 (对应寄存器 VD500 40001-40002 流变性(恒温) AV)
    private Double filtrateTotalSalinity; // 滤液总盐度
    private Double dynamicPlasticRatio; // 动塑比
    private String measurementReason; // 测量原因
    private Double correctedSolidContent; // 校正固相含量
    private Double methyleneBlueExchangeCapacity; // 亚甲蓝交换容量
    private String drillingFluidTreatmentRecord; // 钻井液处理记录
    private Double designedMinDensity; // 设计最小密度
    private Double designedMaxDensity; // 设计最大密度
    private Double oilContentWaterBased; // 水基含油量
    private Double ypPv; //动塑比 (动塑比 对应寄存器 VD552 40027-40028 流变性(恒温) YP/PV)
    private Double conductivity; // 电导率 (对应寄存器 VD640 40071-40072 密度 电导率)

    private String remarks;

    private String operationCondition;

    private Integer type;

    private String experimentDescription;

    private Long isHandwritten;

    private Integer isOil; // 0油基/1水基


}
