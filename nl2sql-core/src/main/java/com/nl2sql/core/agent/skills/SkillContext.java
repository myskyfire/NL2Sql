package com.nl2sql.core.agent.skills;

import lombok.Data;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 执行上下文
 * 
 * 为 Groovy 脚本提供访问 Spring Bean 和参数的能力
 */
@Data
public class SkillContext {
    
    /**
     * 输入参数
     */
    private Map<String, Object> parameters = new HashMap<>();
    
    /**
     * Spring ApplicationContext（用于获取 Bean）
     */
    private ApplicationContext applicationContext;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 数据源 ID
     */
    private Long datasourceId;
    
    /**
     * 构造函数
     */
    public SkillContext() {
    }
    
    /**
     * 获取参数
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }
    
    /**
     * 设置参数
     */
    public void setParameter(String key, Object value) {
        this.parameters.put(key, value);
    }
    
    /**
     * 获取 Spring Bean
     */
    public <T> T getBean(Class<T> clazz) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 未初始化");
        }
        return applicationContext.getBean(clazz);
    }
    
    /**
     * 获取 Spring Bean by name
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 未初始化");
        }
        return (T) applicationContext.getBean(name);
    }
}
