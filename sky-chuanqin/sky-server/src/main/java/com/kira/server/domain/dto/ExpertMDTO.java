package com.kira.server.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class ExpertMDTO {
    private Long id;
    @ApiModelProperty(value = "预警名", required = true, example = "co2污染")
    private String message;
    @ApiModelProperty(value = "备注", required = true, example = "无")
    private String tips;
    @ApiModelProperty(value = "预警内容", required = true, example = "co2污染，造成酸性污染")
    private String expertContext; // 预警内容
}
