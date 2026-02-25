package com.kira.server.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author Kira
 * @create 2025-05-06 14:32
 */
@Data
public class SolutionDTO {
    @ApiModelProperty(value = "id")
    private Long id;
    /**
     * 实际处理方法
     */
    @ApiModelProperty(value = "实际处理方法")
    private String handleApproach;

    /**
     * 实际使用药剂
     */
    @ApiModelProperty(value = "实际使用药剂")
    private String handleChemicals;
}
