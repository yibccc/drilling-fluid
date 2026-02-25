package com.kira.server.domain.dto;

import lombok.Data;

/**
 * @author Kira
 * @create 2024-10-15 19:35
 */
@Data
public class ExpertDTO {
    private Long id;

    private Boolean isData;

    private String expertContext;

    private Boolean isInstrument;
}
