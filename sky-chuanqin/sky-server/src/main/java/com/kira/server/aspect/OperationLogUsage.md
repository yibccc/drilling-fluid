# 操作日志功能使用说明

## 功能概述

操作日志功能通过AOP切面自动记录系统的所有重要操作，包括：
- 操作模块
- 操作类型（查询、新增、修改、删除、导出、导入等）
- 操作描述
- 请求方法和URL
- 请求参数和响应数据
- 操作用户
- IP地址和User-Agent
- 执行时间
- 操作状态（成功/失败）
- 错误信息（如果失败）

## 核心组件

### 1. 注解 `@OperationLog`
位置：`com.kira.server.annotation.OperationLog`

属性说明：
- `module`：业务模块名称
- `type`：操作类型（OperationType枚举）
- `description`：操作描述
- `saveRequestData`：是否保存请求参数（默认true）
- `saveResponseData`：是否保存响应数据（默认true）

### 2. 切面类 `OperationLogAspect`
位置：`com.kira.server.aspect.OperationLogAspect`

功能：
- 拦截所有带 `@OperationLog` 注解的方法
- 自动记录操作信息
- 异步保存日志（不影响业务性能）
- 自动屏蔽敏感字段（密码、身份证等）
- 自动获取真实IP地址（支持代理和负载均衡）

### 3. 实体类 `OperationLog`
位置：`com.kira.server.domain.entity.OperationLog`

数据表：`operation_log`

### 4. 服务类 `IOperationLogService`
位置：`com.kira.server.service.IOperationLogService`

提供异步保存日志的方法。

## 使用方式

### 基本使用

```java
@RestController
@RequestMapping("/api/user")
public class UserController {

    /**
     * 查询用户信息
     */
    @GetMapping("/{id}")
    @OperationLog(
        module = "用户管理",
        type = OperationType.QUERY,
        description = "查询用户详情",
        saveRequestData = true,
        saveResponseData = false  // 查询结果可能很大，不保存响应
    )
    public Result<User> getUser(@PathVariable Long id) {
        // 业务逻辑
        return Result.success(user);
    }

    /**
     * 新增用户
     */
    @PostMapping
    @OperationLog(
        module = "用户管理",
        type = OperationType.INSERT,
        description = "新增用户",
        saveRequestData = true,
        saveResponseData = true
    )
    public Result<Void> addUser(@RequestBody UserDTO dto) {
        // 业务逻辑
        return Result.success();
    }

    /**
     * 修改用户
     */
    @PutMapping("/{id}")
    @OperationLog(
        module = "用户管理",
        type = OperationType.UPDATE,
        description = "修改用户信息"
    )
    public Result<Void> updateUser(@PathVariable Long id, @RequestBody UserDTO dto) {
        // 业务逻辑
        return Result.success();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    @OperationLog(
        module = "用户管理",
        type = OperationType.DELETE,
        description = "删除用户"
    )
    public Result<Void> deleteUser(@PathVariable Long id) {
        // 业务逻辑
        return Result.success();
    }
}
```

### 操作类型说明

`OperationType` 枚举类型：
- `QUERY`：查询操作
- `INSERT`：新增操作
- `UPDATE`：修改操作
- `DELETE`：删除操作
- `EXPORT`：导出操作
- `IMPORT`：导入操作
- `OTHER`：其他操作

### 敏感字段屏蔽

系统会自动屏蔽以下敏感字段：
- password（密码）
- pwd（密码）
- secret（密钥）
- token（令牌）
- credential（凭证）
- idCard（身份证）
- identity（身份信息）

敏感字段在日志中会被替换为 `******`。

### 文件上传处理

对于文件上传的接口，建议设置 `saveRequestData = false`：

```java
@PostMapping("/upload")
@OperationLog(
    module = "文件管理",
    type = OperationType.IMPORT,
    description = "上传文件",
    saveRequestData = false,  // 不保存文件内容
    saveResponseData = true
)
public Result<String> uploadFile(@RequestParam MultipartFile file) {
    // 业务逻辑
    return Result.success(fileUrl);
}
```

### 大数据量响应处理

对于返回大量数据的接口，建议设置 `saveResponseData = false`：

```java
@GetMapping("/list")
@OperationLog(
    module = "数据管理",
    type = OperationType.QUERY,
    description = "查询数据列表",
    saveRequestData = true,
    saveResponseData = false  // 列表数据可能很大
)
public Result<List<Data>> listData() {
    // 业务逻辑
    return Result.success(dataList);
}
```

## 数据限制

为避免日志数据过大：
- 请求参数最大保存 5000 字符
- 响应数据最大保存 5000 字符
- 错误信息最大保存 2000 字符
- 超出部分会被截断并添加 `... (truncated)` 标记

## 异步处理

操作日志采用异步保存方式：
- 使用独立的线程池（`taskExecutor`）
- 核心线程数：2
- 最大线程数：5
- 队列容量：200
- 日志保存失败不会影响业务操作

## IP地址获取

系统会自动获取真实IP地址，支持以下场景：
- 直接访问
- 通过Nginx等代理服务器
- 通过负载均衡器
- IPv6地址自动转换为IPv4

优先级顺序：
1. X-Forwarded-For
2. X-Real-IP
3. Proxy-Client-IP
4. WL-Proxy-Client-IP
5. HTTP_CLIENT_IP
6. HTTP_X_FORWARDED_FOR
7. RemoteAddr

## 注意事项

1. **性能影响**：
   - 日志采用异步保存，对业务性能影响极小
   - 建议对高频接口设置 `saveResponseData = false`

2. **数据安全**：
   - 敏感字段会自动屏蔽
   - 但建议在DTO的toString()方法中也屏蔽敏感信息

3. **用户识别**：
   - 需要用户登录后才能记录操作人ID
   - 未登录用户的操作，operatorId 字段为 null

4. **日志清理**：
   - 建议定期清理历史日志
   - 可以根据业务需求保留最近1-6个月的日志

## 数据库表结构

```sql
CREATE TABLE `operation_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `module` varchar(50) DEFAULT NULL COMMENT '业务模块',
  `operation_type` varchar(20) DEFAULT NULL COMMENT '操作类型',
  `description` varchar(200) DEFAULT NULL COMMENT '操作描述',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方式',
  `request_url` varchar(500) DEFAULT NULL COMMENT '请求URL',
  `method` varchar(200) DEFAULT NULL COMMENT '调用方法',
  `request_param` text COMMENT '请求参数',
  `response_data` text COMMENT '响应数据',
  `operator_id` bigint(20) DEFAULT NULL COMMENT '操作人ID',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP地址',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `execution_time` bigint(20) DEFAULT NULL COMMENT '执行时间（毫秒）',
  `status` int(1) DEFAULT NULL COMMENT '状态（0-失败，1-成功）',
  `error_msg` varchar(2000) DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_module` (`module`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_operator_id` (`operator_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';
```

## 查询示例

```java
// 查询某个用户的操作日志
List<OperationLog> logs = operationLogService.list(
    new QueryWrapper<OperationLog>()
        .eq("operator_id", userId)
        .orderByDesc("create_time")
);

// 查询某个模块的操作日志
List<OperationLog> logs = operationLogService.list(
    new QueryWrapper<OperationLog>()
        .eq("module", "用户管理")
        .orderByDesc("create_time")
);

// 查询失败的操作
List<OperationLog> logs = operationLogService.list(
    new QueryWrapper<OperationLog>()
        .eq("status", 0)
        .orderByDesc("create_time")
);
```

## 完整示例

参考 `com.kira.server.controller.example.OperationLogExample` 类查看完整的使用示例。

