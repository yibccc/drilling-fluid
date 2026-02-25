package com.kira.server.domain.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class NotTreatedForLongTimeNewThresholdDTO implements Serializable {
    private double densityIncrease;            // 密度差值阈值（必要条件）
    private double viscosityIncrease;          // 粘度增长阈值
    private double shearForceIncrease;         // 切力增长阈值
    private double filtrationLossIncrease;     // 失水增长阈值
    private double demulsificationVoltage;     // 破乳电压阈值
    private String wellId;               // 井号

}
