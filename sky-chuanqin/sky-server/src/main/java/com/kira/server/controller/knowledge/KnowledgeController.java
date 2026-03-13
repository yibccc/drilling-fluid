package com.kira.server.controller.knowledge;

import com.kira.common.result.Result;
import com.kira.server.controller.knowledge.dto.KnowledgeUploadResponse;
import com.kira.server.enums.ImportStatus;
import com.kira.server.service.knowledge.KnowledgeImportService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库管理控制器
 *
 * @author Kira
 * @create 2026-02-26
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Api(tags = "知识库管理接口")
public class KnowledgeController {

    private final KnowledgeImportService importService;
    private final WebClient agentWebClient;

    /**
     * 上传单个文件（异步处理）
     *
     * @param file       上传的文件
     * @param category   文档分类（可选）
     * @param subcategory 文档子分类（可选）
     * @return 上传响应，包含文档 ID 和处理状态
     */
    @PostMapping("/upload")
    @ApiOperation("上传单个文档(异步)")
    public Result<KnowledgeUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false, defaultValue = "default") String category,
            @RequestParam(value = "subcategory", required = false) String subcategory
    ) {
        log.info("收到文件异步上传请求: filename={}, category={}, subcategory={}",
                file.getOriginalFilename(), category, subcategory);

        // 验证文件
        validateFile(file);

        try {
            // 生成文档 ID
            String docId = importService.generateDocId();

            // 立即更新状态
            importService.updateStatus(docId, ImportStatus.PARSING);

            // 获取文件字节以避免异步处理时的临时文件删除问题
            byte[] fileBytes = file.getBytes();
            String filename = file.getOriginalFilename();
            String contentType = file.getContentType();
            long fileSize = file.getSize();

            // 异步处理（传递字节数组）
            importService.processFileAsync(docId, fileBytes, filename, contentType, fileSize, category, subcategory);

            KnowledgeUploadResponse response = KnowledgeUploadResponse.builder()
                    .docId(docId)
                    .title(file.getOriginalFilename())
                    .status(ImportStatus.PARSING.name())
                    .message("文件正在处理中")
                    .fileSize(file.getSize())
                    .contentType(file.getContentType())
                    .build();

            return Result.success(response);

        } catch (Exception e) {
            log.error("启动异步处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("文件处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量上传文件
     *
     * @param files     上传的文件数组
     * @param category  文档分类（可选）
     * @return 批量上传结果
     */
    @PostMapping("/upload/batch")
    @ApiOperation("批量上传文档")
    public Result<Map<String, Object>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "category", required = false, defaultValue = "default") String category
    ) {
        log.info("收到批量上传请求: count={}, category={}", files.length, category);

        Map<String, String> results = new HashMap<>();
        int successCount = 0;
        int failCount = 0;

        for (MultipartFile file : files) {
            try {
                // 验证文件
                if (file.isEmpty()) {
                    results.put(file.getOriginalFilename(), "ERROR: 文件为空");
                    failCount++;
                    continue;
                }

                if (file.getSize() > 50 * 1024 * 1024) {
                    results.put(file.getOriginalFilename(), "ERROR: 文件大小超过 50MB");
                    failCount++;
                    continue;
                }

                // 生成文档 ID
                String docId = importService.generateDocId();

                // 更新状态
                importService.updateStatus(docId, ImportStatus.PARSING);

                // 获取文件字节以避免异步处理时的临时文件删除问题
                byte[] fileBytes = file.getBytes();
                String filename = file.getOriginalFilename();
                String contentType = file.getContentType();
                long fileSize = file.getSize();

                // 异步处理（传递字节数组）
                importService.processFileAsync(docId, fileBytes, filename, contentType, fileSize, category, null);

                results.put(file.getOriginalFilename(), docId);
                successCount++;

            } catch (Exception e) {
                log.error("文件上传失败: filename={}", file.getOriginalFilename(), e);
                results.put(file.getOriginalFilename(), "ERROR: " + e.getMessage());
                failCount++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("total", files.length);
        response.put("success", successCount);
        response.put("failed", failCount);
        response.put("results", results);

        return Result.success(response);
    }

    /**
     * 获取文档状态
     *
     * @param docId 文档 ID
     * @return 文档状态信息
     */
    @GetMapping("/documents/{docId}/status")
    @ApiOperation("查询文档处理状态")
    public Result<Map<String, Object>> getDocumentStatus(@PathVariable String docId) {
        log.info("查询文档状态: docId={}", docId);
        Map<String, Object> status = importService.getDocumentStatus(docId);
        return Result.success(status);
    }

    /**
     * 知识召回测试
     *
     * @param request 检索请求
     * @return 召回结果
     */
    @PostMapping("/retrieval-test")
    @ApiOperation("知识召回测试")
    public Result<Map> retrievalTest(@RequestBody Map<String, Object> request) {
        log.info("知识召回测试: query={}, docId={}, category={}",
                request.get("query"), request.get("doc_id"), request.get("category"));

        try {
            Map response = agentWebClient.post()
                    .uri("/api/v1/diagnosis/knowledge/search")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return Result.success(response);
        } catch (Exception e) {
            log.error("知识召回测试失败: {}", e.getMessage(), e);
            return Result.error("知识召回测试失败: " + e.getMessage());
        }
    }

    /**
     * 验证上传的文件
     *
     * @param file 上传的文件
     * @throws IllegalArgumentException 文件不符合要求时抛出异常
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // 文件大小限制 50MB
        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 50MB");
        }

        // 文件类型验证（可选，根据需要扩展）
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 检查文件扩展名
        String extension = getFileExtension(filename).toLowerCase();
        if (!isSupportedFileType(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: " + extension +
                    "。支持的类型：pdf, doc, docx, xls, xlsx, ppt, pptx, txt, md"
            );
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex + 1) : "";
    }

    /**
     * 检查是否为支持的文件类型
     */
    private boolean isSupportedFileType(String extension) {
        return extension.equals("pdf") ||
                extension.equals("doc") ||
                extension.equals("docx") ||
                extension.equals("xls") ||
                extension.equals("xlsx") ||
                extension.equals("ppt") ||
                extension.equals("pptx") ||
                extension.equals("txt") ||
                extension.equals("md");
    }
}
