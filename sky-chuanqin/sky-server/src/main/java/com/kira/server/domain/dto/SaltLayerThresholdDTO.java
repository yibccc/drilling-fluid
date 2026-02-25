package com.kira.server.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
public class SaltLayerThresholdDTO implements Serializable {
    @ApiModelProperty(value = "井号")
    private String wellId;

    @ApiModelProperty(value = "表观黏度")
    private Double apparentViscosity;

    @ApiModelProperty(value = "塑性黏度")
    private Double plasticViscosity;

    @ApiModelProperty(value = "API滤失量")
    private Double apiFiltrationLoss;

    @ApiModelProperty(value = "钻井液密度")
    private Double drillingFluidDensity;

    @ApiModelProperty(value = "切力")
    private Double shearForce;

    @ApiModelProperty(value = "固相含量")
    private Double performance;

}
