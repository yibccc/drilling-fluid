package com.kira.common.enumeration;

/**
 * 操作类型:（查询、新增、修改、删除、导出、导入等）
 */
public enum OperationType {

    /**
     * 更新操作
     */
    UPDATE,

    /**
     * 插入操作
     */
    INSERT,

    /**
     * 删除操作
     */
    DELETE,

    /**
     * 查询操作
     */
    QUERY,

    /**
     * 导出操作
     */
    EXPORT,

    /**
     * 导入操作
     */
    IMPORT,

    /**
     * 其它操作
     */
    OTHER;

}
