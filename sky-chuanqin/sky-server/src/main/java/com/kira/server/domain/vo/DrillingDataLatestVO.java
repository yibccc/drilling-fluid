package com.kira.server.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DrillingDataLatestVO {
    private Long id;  // 主键 ID
    private String wellId; // 井编号
    private LocalDateTime samplingTime; // 采样时间
    private String samplingLocation; //层位
    /**
     * 密度
     */
    private Double outletTemperature; // 出口温度 (对应寄存器 VD588 40045-40046 流变性(恒温) 温度)
    private Double drillingFluidDensity; // 钻井液密度 (对应寄存器 VD584 40043-40044 密度 密度)

    /**
     * 流变性
     */
    private Double shearForce10s; // 切力10秒 (对应寄存器 VD512 40007-40008 流变性(恒温) 初切)
    private Double shearForce10m; // 切力10分 (对应寄存器 VD516 40009-40010 流变性(恒温) 终切)
    private Double rpm3; // 3转速 (对应寄存器 VD548 40025-40026 流变性(恒温) 3转)
    private Double rpm6; // 6转速 (对应寄存器 VD544 40023-40024 流变性(恒温) 6转)
    private Double rpm100; // 100转速 (对应寄存器 VD540 40021-40022 流变性(恒温) 100转)
    private Double rpm200; // 200转速 (对应寄存器 VD536 40019-40020 流变性(恒温) 200转)
    private Double rpm300; // 300转速 (对应寄存器 VD532 40017-40018 流变性(恒温) 300转)
    private Double rpm600; // 600转速 (对应寄存器 VD528 40015-40016 流变性(恒温) 600转)

    /**
     * 计算参数
     */
    private Double apparentViscosity; // 表观黏度 (对应寄存器 VD500 40001-40002 流变性(恒温) AV)
    private Double ypPv; //动塑比 (动塑比 对应寄存器 VD552 40027-40028 流变性(恒温) YP/PV)
    private Double plasticViscosity; // 塑性黏度 (对应寄存器 VD504 40003-40004 流变性(恒温) PV)
    private Double yieldPoint; // 动切力 (对应寄存器 VD508 40005-40006 流变性(恒温) YP)
    private Double flowIndexN; // 流性指数
    private Double consistencyK; // 稠度系数 (对应寄存器 VD524 40013-40014 流变性(恒温) K)

}
