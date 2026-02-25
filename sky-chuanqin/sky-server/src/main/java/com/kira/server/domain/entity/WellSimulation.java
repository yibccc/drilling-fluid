package com.kira.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author kira
 * @since 2024-10-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("well_simulation")
@ApiModel(value="WellSimulation对象", description="")
public class WellSimulation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Double deep;

    private Double q;

    private Double densM;

    private Double influT;

    private Double surfT;

    private Double g;

    private Double deep4;

    private Double deep3;

    private Double deep2;

    private Double deep1;

    private Double r1;

    private Double r2;

    private Double r3;

    private Double r4;

    private Double r5;

    private Double r6;

    private Double r7;

    private Double r8;

    private Double r9;

    private Double r10;

    private Double r11;


}
