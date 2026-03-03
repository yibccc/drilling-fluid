package com.kira.server.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端配置
 *
 * @author Kira
 * @create 2026-02-27
 */
@Configuration
@EnableConfigurationProperties(OssProperties.class)
@RequiredArgsConstructor
public class OssConfig {

    private final OssProperties ossProperties;

    /**
     * 创建 OSS 客户端 Bean
     */
    @Bean
    public OSS ossClient() {
        return new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
    }
}
