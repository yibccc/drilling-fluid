package com.kira.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT配置类
 * 统一管理JWT相关配置，从application.yml读取配置值
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    
    /**
     * JWT密钥
     */
    private String secret;
    
    /**
     * JWT过期时间（毫秒）
     */
    private Long expiration;
    
    /**
     * Token请求头名称
     */
    private String header = "Authorization";
    
    /**
     * Token前缀
     */
    private String prefix = "Bearer ";
    
    /**
     * 签名主题
     */
    private String subject = "YIBCCC";
    
    /**
     * 角色权限声明
     */
    private String roleClaims = "role";
}