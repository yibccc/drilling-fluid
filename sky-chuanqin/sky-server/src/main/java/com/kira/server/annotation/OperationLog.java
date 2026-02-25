package com.kira.server.annotation;

import com.kira.common.enumeration.OperationType;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    /**
     * 业务模块
     */
    String module() default "";
    
    /**
     * 操作类型（查询、新增、修改、删除、导出、导入等）
     */
    OperationType type() default OperationType.OTHER;
    
    /**
     * 操作描述
     */
    String description() default "";
    
    /**
     * 是否保存请求参数
     */
    boolean saveRequestData() default true;
    
    /**
     * 是否保存响应数据
     */
    boolean saveResponseData() default true;
}