package com.kira.server.domain.query;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel(description = "分页结果")
public class PageDTO<T> {
    @ApiModelProperty("总条数")
    private Long total;
    @ApiModelProperty("总页数")
    private Long pages;
    @ApiModelProperty("集合")
    private List<T> list;

    public PageDTO() {
    }

    public PageDTO(Long total, Long pages, List<T> list) {
        this.total = total;
        this.pages = pages;
        this.list = list;
    }
}