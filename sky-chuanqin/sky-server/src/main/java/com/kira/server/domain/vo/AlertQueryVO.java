package com.kira.server.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class  AlertQueryVO {
    private Long id;

    private Double value;

    private LocalDateTime alertTime; // 结束时间

    private String message;

    private Integer time;

    private String wellId;

    private String expertContext; // 预警内容

    private LocalDateTime createTime; //开始时间

    private String updateUser;

    private String tips;
}
