package com.kira.server.security;

import com.kira.common.context.BaseContext;
import com.kira.server.utils.JwtTokenUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;

/**
 * @author Kira
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {


        try {
            // 从请求头中提取JWT令牌
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                // 验证令牌
                Claims claims = jwtTokenUtil.checkJWT(jwt);

                if (claims != null && !jwtTokenUtil.isExpiration(jwt)) {
                    // 从令牌中提取用户信息
                    String email = jwtTokenUtil.getUsername(jwt);
                    String role = jwtTokenUtil.getUserRole(jwt);

                    log.info("JWT过滤器 - 有效令牌, 用户: {}, 角色: {}", email, role);

                    // 构建权限列表
                    List<GrantedAuthority> authorities = AuthorityUtils.commaSeparatedStringToAuthorityList(role);

                    // 创建认证对象
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(email, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // 在安全上下文中设置认证信息
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // 在ThreadLocal中设置userId以便业务逻辑访问
                    if (jwtTokenUtil.getUsername(jwt) != null) {
                        Long userId = Long.valueOf(claims.get("userId").toString());
                        BaseContext.setCurrentId(userId);
                        log.debug("JWT过滤器 - 设置BaseContext用户ID: {}", userId);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("JWT过滤器 - 令牌处理错误: {}", ex.getMessage(), ex);
        }

        try {
            // 继续过滤器链
            filterChain.doFilter(request, response);
        } finally {
            // 始终清理ThreadLocal以防止内存泄漏
            BaseContext.removeCurrentId();
        }
    }

    /**
     * 从请求头中提取JWT令牌
     */
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(jwtTokenUtil.getTokenHeader());
        return jwtTokenUtil.extractTokenFromHeader(bearerToken);
    }
}
