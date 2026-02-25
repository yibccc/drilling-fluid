package com.kira.server.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
public class CaThresholdDTO implements Serializable {

    @ApiModelProperty(value = "井号")
    private String wellId;

    @ApiModelProperty(value = "表观黏度",example = "0.01")
    private Double apparentViscosity;

    @ApiModelProperty(value = "塑性黏度",example = "0.01")
    private Double plasticViscosity;

    @ApiModelProperty(value = "切力",example = "0.01")
    private Double shearForce;

    @ApiModelProperty(value = "6转",example = "0.01")
    private Double rpm6;

    @ApiModelProperty(value = "3转",example = "0.01")
    private Double rpm3;

    @ApiModelProperty(value = "钙离子",example = "0.01")
    private Double ca;

    @ApiModelProperty(value = "表观黏度2",example = "0.01")
    private Double ConfirmedApparentViscosity;

    @ApiModelProperty(value = "塑性黏度2",example = "0.01")
    private Double ConfirmedPlasticViscosity;

    @ApiModelProperty(value = "切力2",example = "0.01")
    private Double ConfirmedShearForce;

    @ApiModelProperty(value = "6转2",example = "0.01")
    private Double ConfirmedRpm6;

    @ApiModelProperty(value = "3转2",example = "0.01")
    private Double ConfirmedRpm3;

    @ApiModelProperty(value = "钙离子2",example = "0.01")
    private Double ConfirmedCa;

    @ApiModelProperty(value = "API滤失量2",example = "0.01")
    private Double ConfirmedApiFiltrationLoss;

    @ApiModelProperty(value = "API滤饼厚度2",example = "0.01")
    private Double ConfirmedApiFilterCakeThickness;

    @ApiModelProperty(value = "pH值2",example = "0.01")
    private Double ConfirmedPhValue;

}
