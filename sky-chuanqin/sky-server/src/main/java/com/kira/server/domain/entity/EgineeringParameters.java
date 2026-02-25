package com.kira.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 工程参数
 * </p>
 *
 * @author kira
 * @since 2024-11-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("egineering_parameters")
@ApiModel(value="EgineeringParameters对象", description="工程参数")
public class EgineeringParameters implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "LONGTIME", type = IdType.AUTO)
    private Long longtime;

    @TableField("WELLDATE")  // 映射到数据库的 WELLDATE 字段
    private String welldate;

    @TableField("WELLTIME")  // 映射到数据库的 WELLTIME 字段
    private String welltime;

    @TableField("CW")
    private String cw;

    @TableField("DEP")
    private Double dep;

    @TableField("BITDEP")
    private Double bitdep;

    @TableField("HOKHEI")
    private Double hokhei;

    @TableField("BITDIST")
    private Double bitdist;

    @TableField("DRITIME")
    private Double dritime;

    @TableField("RPM")
    private Double rpm;

    @TableField("WOB")
    private Double wob;

    @TableField("HKLD")
    private Double hkld;

    @TableField("LAGTIMEMUD")
    private Double lagtimemud;

    @TableField("TOR")
    private Double tor;

    @TableField("SPP")
    private Double spp;

    @TableField("CSIP")
    private Double csip;

    @TableField("FLOWIN")
    private Double flowin;

    @TableField("FLOWOUT")
    private Double flowout;

    @TableField("MWIN")
    private Double mwin;

    @TableField("MWOUT")
    private Double mwout;

    @TableField("MTIN")
    private Double mtin;

    @TableField("MTOUT")
    private Double mtout;

    @TableField("MCONDIN")
    private Double mcondin;

    @TableField("MCONDOUT")
    private Double mcondout;

    @TableField("well_id") // 映射到数据库的 well_id 字段
    private String wellId;

}
