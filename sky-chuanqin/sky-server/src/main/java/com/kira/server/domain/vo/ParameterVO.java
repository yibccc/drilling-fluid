package com.kira.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author Kira
 * @create 2024-09-24 16:20
 */
@Data
public class ParameterVO {
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    private double value;
    private boolean isRed;
}
