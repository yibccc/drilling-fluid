package com.kira.server.service.knowledge;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kira.server.config.OssProperties;
import com.kira.server.controller.knowledge.dto.FileRecordDTO;
import com.kira.server.domain.entity.FileRecord;
import com.kira.server.exception.DuplicateFileException;
import com.kira.server.mapper.FileRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 文件存储服务
 * 负责文件上传到 OSS 和去重检查
 *
 * @author Kira
 * @create 2026-02-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final OSS ossClient;
    private final FileRecordMapper fileRecordMapper;
    private final OssProperties ossProperties;

    /**
     * 上传文件并检查重复
     *
     * @param file        上传的文件
     * @param category    文档分类
     * @param subcategory 文档子分类（可选）
     * @return 文件记录信息
     * @throws DuplicateFileException 文件已存在
     */
    public FileRecordDTO uploadAndCheckDuplicate(
            MultipartFile file,
            String category,
            String subcategory
    ) {
        String filename = file.getOriginalFilename();

        // 1. 检查是否已存在（数据库唯一约束）
        LambdaQueryWrapper<FileRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FileRecord::getOriginalFilename, filename)
                .eq(FileRecord::getCategory, category);

        FileRecord existing = fileRecordMapper.selectOne(queryWrapper);
        if (existing != null) {
            log.info("文件已存在: filename={}, category={}", filename, category);
            throw new DuplicateFileException(
                    String.format("文件已存在: %s (分类: %s)", filename, category),
                    filename,
                    category
            );
        }

        try {
            // 2. 读取文件内容
            byte[] fileBytes = file.getBytes();

            // 3. 计算文件哈希
            String hash = calculateSHA256(fileBytes);

            // 4. 生成 OSS 路径
            String ossPath = generateOssPath(category, filename, hash);

            // 5. 上传到 OSS
            uploadToOSS(ossPath, fileBytes, file.getContentType());

            // 6. 保存记录到数据库
            FileRecord record = FileRecord.builder()
                    .fileHash(hash)
                    .originalFilename(filename)
                    .category(category)
                    .subcategory(subcategory)
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .ossPath(ossPath)
                    .bucketName(ossProperties.getBucketName())
                    .uploadedAt(LocalDateTime.now())
                    .build();

            fileRecordMapper.insert(record);

            log.info("文件上传成功: filename={}, ossPath={}", filename, ossPath);

            return toDTO(record);

        } catch (IOException e) {
            log.error("文件读取失败: filename={}", filename, e);
            throw new RuntimeException("文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算 SHA256 哈希值
     *
     * @param data 文件字节数组
     * @return SHA256 哈希值（十六进制字符串）
     */
    private String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * 生成 OSS 存储路径
     * 格式: knowledge/{category}/{hash前8位}/{filename}
     *
     * @param category  分类
     * @param filename  文件名
     * @param hash      文件哈希
     * @return OSS 路径
     */
    private String generateOssPath(String category, String filename, String hash) {
        // 处理分类中的中文和特殊字符，使用 URL 编码
        String safeCategory = category.replaceAll("[^a-zA-Z0-9_-]", "_");
        return String.format("knowledge/%s/%s/%s",
                safeCategory,
                hash.substring(0, 8),
                filename);
    }

    /**
     * 上传文件到 OSS
     *
     * @param ossPath     OSS 路径
     * @param fileBytes   文件字节数组
     * @param contentType 内容类型
     */
    private void uploadToOSS(String ossPath, byte[] fileBytes, String contentType) {
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            PutObjectRequest request = new PutObjectRequest(
                    ossProperties.getBucketName(),
                    ossPath,
                    inputStream
            );

            // 设置内容类型
            if (contentType != null && !contentType.isEmpty()) {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentType(contentType);
                request.setMetadata(metadata);
            }

            ossClient.putObject(request);
            log.debug("OSS 上传成功: path={}", ossPath);

        } catch (IOException e) {
            log.error("OSS 上传失败: path={}", ossPath, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 实体转 DTO
     *
     * @param record 文件记录实体
     * @return DTO
     */
    private FileRecordDTO toDTO(FileRecord record) {
        return FileRecordDTO.builder()
                .id(record.getId())
                .fileHash(record.getFileHash())
                .originalFilename(record.getOriginalFilename())
                .category(record.getCategory())
                .subcategory(record.getSubcategory())
                .contentType(record.getContentType())
                .fileSize(record.getFileSize())
                .ossPath(record.getOssPath())
                .bucketName(record.getBucketName())
                .uploadedAt(record.getUploadedAt())
                .build();
    }
}
