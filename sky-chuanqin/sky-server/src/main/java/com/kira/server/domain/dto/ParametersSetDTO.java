package com.kira.server.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class ParametersSetDTO {
    private String wellId;
    private Double threshold;
    private List<String> parameters;
}
