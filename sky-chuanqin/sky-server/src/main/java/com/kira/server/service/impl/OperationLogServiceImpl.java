package com.kira.server.service.impl;

import com.kira.server.domain.entity.OperationLog;
import com.kira.server.mapper.OperationLogMapper;
import com.kira.server.service.IOperationLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 操作日志表 服务实现类
 * </p>
 *
 * @author kira
 * @since 2025-11-12
 */
@Service
@Slf4j
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog> implements IOperationLogService {

    /**
     * 异步保存操作日志
     * 使用@Async注解实现异步保存，避免影响主业务性能
     * 
     * @param operationLog 操作日志对象
     */
    @Async
    @Override
    public void saveLogAsync(OperationLog operationLog) {
        try {
            this.save(operationLog);
            log.debug("操作日志保存成功: 模块={}, 操作={}, 用户ID={}", 
                operationLog.getModule(), 
                operationLog.getOperationType(), 
                operationLog.getOperatorId());
        } catch (Exception e) {
            log.error("操作日志保存失败: {}", e.getMessage(), e);
            // 日志保存失败不影响业务，只记录错误
        }
    }

}
