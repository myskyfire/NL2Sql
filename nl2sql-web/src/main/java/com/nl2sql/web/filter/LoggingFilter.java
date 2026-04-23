package com.nl2sql.web.filter;

import com.nl2sql.common.util.LogContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * 日志上下文过滤器
 * 为每个请求自动生成requestId并设置到MDC中
 */
@Slf4j
@Component
@Order(1)
public class LoggingFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // 强制设置UTF-8编码
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        try {
            // ✅ 优先使用前端传递的 traceId，否则生成新的
            String requestId = httpRequest.getHeader("X-Request-ID");
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = LogContextUtil.initRequestId();
            } else {
                MDC.put(LogContextUtil.REQUEST_ID, requestId);
            }
            
            // 记录请求开始
            long startTime = System.currentTimeMillis();
            log.info("请求开始: method={}, uri={}, remoteAddr={}", 
                httpRequest.getMethod(),
                httpRequest.getRequestURI(),
                httpRequest.getRemoteAddr());
            
            // 执行请求
            chain.doFilter(request, response);
            
            // 记录请求结束
            long duration = System.currentTimeMillis() - startTime;
            log.info("请求结束: method={}, uri={}, duration={}ms", 
                httpRequest.getMethod(),
                httpRequest.getRequestURI(),
                duration);
            
        } finally {
            // 清除MDC上下文，防止内存泄漏
            LogContextUtil.clear();
        }
    }
}
