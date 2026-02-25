package com.kira.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PollutionAlarmLogQueryVO {
    private Long id;
    private String wellId;
    private String pollutionType;
    /**
     * 系统推荐处理方案
     */
    private String recommendedSolution;

    /**
     * 建议添加药剂
     */
    private String solutionChemicals;

    /**
     * 建议操作步骤
     */
    private String solutionOperations;

    /**
     * 实际处理方法
     */
    private String handleApproach;

    /**
     * 实际使用药剂
     */
    private String handleChemicals;
    /**
     * 报警时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime alarmTime;
}
