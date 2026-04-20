package com.nl2sql.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 
 * 职责：
 * 1. 从请求头提取 Token
 * 2. 验证 Token 有效性
 * 3. 检查用户白名单
 * 4. 将用户信息存入 request attribute，供 Controller 使用
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {
    
    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) throws Exception {
        
        // 获取 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            log.warn("[AuthInterceptor] 缺少 Authorization 头: {}", request.getRequestURI());
            sendErrorResponse(response, 401, "请先登录");
            return false;
        }
        
        // 提取 Token（去除 Bearer 前缀）
        String token = authHeader.startsWith("Bearer ") ? 
            authHeader.substring(7) : authHeader;
        
        try {
            // 验证 Token
            AuthService.UserInfo userInfo = authService.validateToken(token);
            if (userInfo == null) {
                log.warn("[AuthInterceptor] Token 无效: {}", request.getRequestURI());
                sendErrorResponse(response, 401, "Token 已过期或无效");
                return false;
            }
            
            // 检查白名单
            if (!authService.isInWhitelist(userInfo.getUserId())) {
                log.warn("[AuthInterceptor] 用户 {} 不在白名单中", userInfo.getUserId());
                sendErrorResponse(response, 403, "您没有访问权限");
                return false;
            }
            
            // ✅ 将用户信息存入 request，供 Controller 使用
            request.setAttribute("userInfo", userInfo);
            log.debug("[AuthInterceptor] 用户认证成功: userId={}, username={}", 
                userInfo.getUserId(), userInfo.getUsername());
            
            return true;
            
        } catch (Exception e) {
            log.error("[AuthInterceptor] Token 验证异常", e);
            sendErrorResponse(response, 500, "认证服务异常");
            return false;
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpServletResponse response, 
                                   int status, 
                                   String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        
        Result<Void> errorResult = Result.error(message);
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }
}
