package com.kira.server.utils;

import com.kira.server.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 * 统一的JWT token生成和验证工具
 * @author Kira
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenUtil {

    private final JwtConfig jwtConfig;

    /**
     * 缓存SecretKey对象，避免重复生成
     */
    private SecretKey secretKey;

    /**
     * 获取签名密钥
     */
    private SecretKey getSecretKey() {
        if (secretKey == null) {
            secretKey = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
        }
        return secretKey;
    }

    /**
     * 生成Token
     */
    public String createToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(jwtConfig.getRoleClaims(), role);

        String token = Jwts.builder()
                .setSubject(jwtConfig.getSubject())
                .setClaims(claims)
                .claim("username", username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtConfig.getExpiration()))
                .signWith(getSecretKey())
                .compact();

        log.debug("生成JWT token 成功，用户: {}, 角色: {}", username, role);
        return token;
    }

    /**
     * 解析和校验Token，返回Claims
     * @param token JWT token
     * @return Claims a collection of key-value pairs
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验Token
     * @return 成功则返回 Claims，失败（如过期、签名错误）则返回 null
     */
    public Claims checkJWT(String token) {
        try {
            return parseClaims(token);
        } catch (Exception e) {
            log.warn("JWT验证失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsername(String token) {
        return parseClaims(token).get("username", String.class);
    }

    /**
     * 从Token中获取用户角色
     */
    public String getUserRole(String token) {
        return parseClaims(token).get(jwtConfig.getRoleClaims(), String.class);
    }

    /**
     * 校验Token是否过期
     */
    public boolean isExpiration(String token) {
        try {
            return parseClaims(token).getExpiration().before(new Date());
        } catch (Exception e) {
            // 如果解析时就因为过期而抛出异常，那么它就是过期的
            return true;
        }
    }

    /**
     * 获取Token请求头名称
     */
    public String getTokenHeader() {
        return jwtConfig.getHeader();
    }

    /**
     * 获取Token前缀
     */
    public String getTokenPrefix() {
        return jwtConfig.getPrefix();
    }

    /**
     * 从请求头中提取JWT token
     */
    public String extractTokenFromHeader(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith(getTokenPrefix())) {
            return authorizationHeader.substring(getTokenPrefix().length());
        }
        return null;
    }
}