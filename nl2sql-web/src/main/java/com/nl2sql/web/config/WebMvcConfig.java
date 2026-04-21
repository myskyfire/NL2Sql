package com.nl2sql.web.config;

import com.nl2sql.web.filter.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 
 * 职责：注册拦截器、配置静态资源等
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    private final AuthInterceptor authInterceptor;
    
    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ✅ 注册认证拦截器，对所有 /api/agent/** 路径生效
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/agent/**")  // 需要认证的路径
            .excludePathPatterns(              // 排除不需要认证的路径
                "/api/agent/test",             // 测试接口
                "/api/health/**"               // 健康检查
            );
        
        // 可以在这里添加其他拦截器
        // registry.addInterceptor(otherInterceptor).addPathPatterns("/api/other/**");
    }
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ✅ 忽略 Chrome DevTools 调试文件请求（避免日志污染）
        registry.addResourceHandler("/.well-known/**")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(0);
    }
}
