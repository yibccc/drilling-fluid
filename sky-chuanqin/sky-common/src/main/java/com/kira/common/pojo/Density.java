package com.kira.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author kira
 * @since 2025-06-27
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("density")
public class Density implements Serializable {

    private static final long serialVersionUID = 1L;

    private Double drillingFluidDensity;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private LocalDateTime samplingTime;

    private String wellId;

    private Integer isOil;

    private String samplingLocation;


}
