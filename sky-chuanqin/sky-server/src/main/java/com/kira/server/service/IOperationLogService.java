package com.kira.server.service;

import com.kira.server.domain.entity.OperationLog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 操作日志表 服务类
 * </p>
 *
 * @author kira
 * @since 2025-11-12
 */
public interface IOperationLogService extends IService<OperationLog> {

    /**
     * 异步保存操作日志
     * @param operationLog 操作日志对象
     */
    void saveLogAsync(OperationLog operationLog);

}
