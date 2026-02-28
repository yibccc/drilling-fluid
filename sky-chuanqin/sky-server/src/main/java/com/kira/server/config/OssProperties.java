package com.kira.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置属性
 *
 * @author Kira
 * @create 2026-02-27
 */
@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    /**
     * OSS 访问域名（区域节点）
     * 例如: oss-cn-hangzhou.aliyuncs.com
     */
    private String endpoint;

    /**
     * 阿里云 AccessKey ID
     */
    private String accessKeyId;

    /**
     * 阿里云 AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * OSS Bucket 名称
     */
    private String bucketName;
}
