package com.kira.server.controller.example;

import com.kira.common.enumeration.OperationType;
import com.kira.common.result.Result;
import com.kira.server.annotation.OperationLog;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志使用示例
 * 演示如何在Controller中使用@OperationLog注解
 * 
 * @author kira
 * @since 2025-11-12
 */
@RestController
@RequestMapping("/api/example")
@Api(tags = "操作日志示例接口")
@Slf4j
public class OperationLogExample {

    /**
     * 查询操作示例
     * 保存请求参数，但不保存响应数据（查询结果可能很大）
     */
    @GetMapping("/query/{id}")
    @ApiOperation("查询数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.QUERY,
        description = "查询数据详情",
        saveRequestData = true,
        saveResponseData = false
    )
    public Result<String> queryExample(@PathVariable String id) {
        log.info("查询数据: {}", id);
        return Result.success("查询结果: " + id);
    }

    /**
     * 新增操作示例
     * 同时保存请求参数和响应数据
     */
    @PostMapping("/add")
    @ApiOperation("新增数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.INSERT,
        description = "新增数据",
        saveRequestData = true,
        saveResponseData = true
    )
    public Result<String> addExample(@RequestBody ExampleDTO dto) {
        log.info("新增数据: {}", dto);
        return Result.success("新增成功");
    }

    /**
     * 修改操作示例
     */
    @PutMapping("/update/{id}")
    @ApiOperation("修改数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.UPDATE,
        description = "修改数据",
        saveRequestData = true,
        saveResponseData = true
    )
    public Result<String> updateExample(@PathVariable String id, @RequestBody ExampleDTO dto) {
        log.info("修改数据: id={}, data={}", id, dto);
        return Result.success("修改成功");
    }

    /**
     * 删除操作示例
     */
    @DeleteMapping("/delete/{id}")
    @ApiOperation("删除数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.DELETE,
        description = "删除数据",
        saveRequestData = true,
        saveResponseData = false
    )
    public Result<String> deleteExample(@PathVariable String id) {
        log.info("删除数据: {}", id);
        return Result.success("删除成功");
    }

    /**
     * 导出操作示例
     */
    @GetMapping("/export")
    @ApiOperation("导出数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.EXPORT,
        description = "导出数据",
        saveRequestData = true,
        saveResponseData = false
    )
    public Result<String> exportExample(@RequestParam String condition) {
        log.info("导出数据: {}", condition);
        return Result.success("导出成功");
    }

    /**
     * 导入操作示例
     */
    @PostMapping("/import")
    @ApiOperation("导入数据示例")
    @OperationLog(
        module = "示例模块",
        type = OperationType.IMPORT,
        description = "导入数据",
        saveRequestData = false, // 文件上传不保存请求数据
        saveResponseData = true
    )
    public Result<String> importExample() {
        log.info("导入数据");
        return Result.success("导入成功");
    }

    /**
     * 示例DTO
     */
    public static class ExampleDTO {
        private String name;
        private String password; // 敏感字段，会被自动屏蔽
        private String email;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        @Override
        public String toString() {
            return "ExampleDTO{" +
                    "name='" + name + '\'' +
                    ", password='******'" + // 手动屏蔽密码
                    ", email='" + email + '\'' +
                    '}';
        }
    }
}
