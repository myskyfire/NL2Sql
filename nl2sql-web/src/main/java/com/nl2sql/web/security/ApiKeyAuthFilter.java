package com.nl2sql.web.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * API Key 认证过滤器
 * 
 * 为管理端点提供简单的 API Key 认证
 */
@Slf4j
@Component
@Order(1)
public class ApiKeyAuthFilter implements Filter {
    
    @Value("${admin.api.key:}")
    private String apiKey;
    
    private static final String API_KEY_HEADER = "X-API-Key";
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String uri = httpRequest.getRequestURI();
        
        // 仅对管理端点进行认证
        if (uri.startsWith("/api/admin/")) {
            String providedKey = httpRequest.getHeader(API_KEY_HEADER);
            
            // 如果未配置 API Key，则允许访问（开发环境）
            if (apiKey == null || apiKey.trim().isEmpty()) {
                log.warn("[ApiKeyAuth] 未配置 admin.api.key，允许访问管理端点（开发模式）");
                chain.doFilter(request, response);
                return;
            }
            
            // 验证 API Key
            if (providedKey == null || !providedKey.equals(apiKey)) {
                log.warn("[ApiKeyAuth] API Key 验证失败: uri={}, providedKey={}", uri, providedKey);
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.setContentType("application/json;charset=UTF-8");
                httpResponse.getWriter().write("{\"success\":false,\"message\":\"无效的 API Key\"}");
                return;
            }
            
            log.debug("[ApiKeyAuth] API Key 验证成功: uri={}", uri);
        }
        
        chain.doFilter(request, response);
    }
}
