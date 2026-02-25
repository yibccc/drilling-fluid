package com.kira.server.aspect;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.kira.common.context.BaseContext;
import com.kira.server.annotation.OperationLog;
import com.kira.server.service.IOperationLogService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志切面
 * 用于自动记录系统操作日志
 * 
 * @author kira
 * @since 2025-11-12
 */
@Aspect
@Component
@Slf4j
public class OperationLogAspect {
    
    @Autowired
    private IOperationLogService operationLogService;
    
    @Autowired
    private HttpServletRequest request;
    
    /**
     * 敏感字段列表
     */
    private static final String[] SENSITIVE_FIELDS = {
        "password", "pwd", "secret", "token", "credential", "idCard", "identity"
    };
    
    /**
     * 环绕通知，记录操作日志
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        // 构建操作日志对象
        com.kira.server.domain.entity.OperationLog logEntity = new com.kira.server.domain.entity.OperationLog();
        
        // 1. 设置基本信息
        logEntity.setModule(operationLog.module());
        logEntity.setOperationType(operationLog.type().name());
        logEntity.setDescription(operationLog.description());
        logEntity.setRequestMethod(request.getMethod());
        logEntity.setRequestUrl(request.getRequestURI());
        logEntity.setIp(getIpAddress(request));
        logEntity.setUserAgent(request.getHeader("User-Agent"));
        
        // 2. 获取当前操作用户
        try {
            Long userId = BaseContext.getCurrentId();
            logEntity.setOperatorId(userId);
        } catch (Exception e) {
            log.warn("获取当前用户ID失败: {}", e.getMessage());
            logEntity.setOperatorId(null);
        }
        
        // 3. 获取方法信息
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = joinPoint.getSignature().getName();
        logEntity.setMethod(className + "." + methodName);
        
        // 4. 保存请求参数
        if (operationLog.saveRequestData()) {
            try {
                Object[] args = joinPoint.getArgs();
                List<Object> filteredArgs = filterSensitiveData(args);
                String params = JSON.toJSONString(filteredArgs, SerializerFeature.IgnoreErrorGetter);
                // 限制参数长度，避免过大
                if (params.length() > 5000) {
                    params = params.substring(0, 5000) + "... (truncated)";
                }
                logEntity.setRequestParam(params);
            } catch (Exception e) {
                log.warn("序列化请求参数失败: {}", e.getMessage());
                logEntity.setRequestParam("参数序列化失败");
            }
        }
        
        Object result = null;
        try {
            // 执行目标方法
            result = joinPoint.proceed();
            
            // 5. 保存响应数据
            if (operationLog.saveResponseData() && result != null) {
                try {
                    String responseData = JSON.toJSONString(result, SerializerFeature.IgnoreErrorGetter);
                    // 限制响应数据长度
                    if (responseData.length() > 5000) {
                        responseData = responseData.substring(0, 5000) + "... (truncated)";
                    }
                    logEntity.setResponseData(responseData);
                } catch (Exception e) {
                    log.warn("序列化响应数据失败: {}", e.getMessage());
                    logEntity.setResponseData("响应序列化失败");
                }
            }
            
            logEntity.setStatus(1); // 成功
            
        } catch (Exception e) {
            logEntity.setStatus(0); // 失败
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000) + "... (truncated)";
            }
            logEntity.setErrorMsg(errorMsg);
            throw e; // 继续抛出异常
        } finally {
            // 6. 计算执行时间
            long endTime = System.currentTimeMillis();
            logEntity.setExecutionTime(endTime - startTime);
            logEntity.setCreateTime(LocalDateTime.now());
            
            // 7. 异步保存日志（避免影响业务性能）
            try {
                operationLogService.saveLogAsync(logEntity);
            } catch (Exception e) {
                log.error("保存操作日志失败: {}", e.getMessage(), e);
            }
        }
        
        return result;
    }
    
    /**
     * 过滤敏感数据（密码、身份证、文件上传等）
     */
    private List<Object> filterSensitiveData(Object[] args) {
        List<Object> filteredArgs = new ArrayList<>();
        
        if (args == null || args.length == 0) {
            return filteredArgs;
        }
        
        for (Object arg : args) {
            // 过滤掉不需要序列化的对象
            if (arg instanceof ServletRequest || 
                arg instanceof ServletResponse ||
                arg instanceof MultipartFile) {
                filteredArgs.add("[" + arg.getClass().getSimpleName() + "]");
            } else if (arg instanceof MultipartFile[]) {
                filteredArgs.add("[MultipartFile Array]");
            } else {
                // 对于普通对象，尝试过滤敏感字段
                filteredArgs.add(maskSensitiveFields(arg));
            }
        }
        
        return filteredArgs;
    }
    
    /**
     * 屏蔽敏感字段
     */
    private Object maskSensitiveFields(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // 转换为JSON字符串
            String jsonStr = JSON.toJSONString(obj);
            
            // 使用正则表达式屏蔽敏感字段
            for (String field : SENSITIVE_FIELDS) {
                // 匹配 "fieldName":"value" 或 "fieldName":value 格式
                jsonStr = jsonStr.replaceAll(
                    "\"" + field + "\"\\s*:\\s*\"[^\"]*\"", 
                    "\"" + field + "\":\"******\""
                );
                jsonStr = jsonStr.replaceAll(
                    "\"" + field + "\"\\s*:\\s*[^,}\\]]+", 
                    "\"" + field + "\":\"******\""
                );
            }
            
            return JSON.parse(jsonStr);
        } catch (Exception e) {
            log.warn("屏蔽敏感字段失败: {}", e.getMessage());
            return obj;
        }
    }
    
    /**
     * 获取真实IP地址
     * 考虑了代理服务器和负载均衡的情况
     */
    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            // 多次反向代理后会有多个IP值，第一个为真实IP
            int index = ip.indexOf(',');
            if (index != -1) {
                return ip.substring(0, index);
            } else {
                return ip;
            }
        }
        
        ip = request.getHeader("X-Real-IP");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        
        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        
        ip = request.getHeader("WL-Proxy-Client-IP");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        
        ip = request.getHeader("HTTP_CLIENT_IP");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        
        ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        
        ip = request.getRemoteAddr();
        
        // 对于IPv6地址，转换为IPv4
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        
        return ip;
    }
}