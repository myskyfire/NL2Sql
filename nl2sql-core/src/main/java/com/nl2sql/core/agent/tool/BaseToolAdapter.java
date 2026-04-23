package com.nl2sql.core.agent.tool;

import com.nl2sql.core.error.ErrorClassifier;
import com.nl2sql.core.validation.ParameterValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool适配器基类
 * 
 * 提供通用的参数校验和错误处理逻辑，简化Tool迁移
 */
@Slf4j
public abstract class BaseToolAdapter implements BaseTool {
    
    @Autowired
    protected ParameterValidator parameterValidator;
    
    @Autowired
    protected ErrorClassifier errorClassifier;
    
    /**
     * 执行工具（带统一错误处理）
     */
    @Override
    public ToolResult execute(ToolContext context) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 参数校验
            validateParameters(context);
            
            // 2. 执行业务逻辑（由子类实现）
            Object result = doExecute(context);
            
            // 3. 构建成功结果
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            
            return ToolResult.success(result, metadata);
            
        } catch (IllegalArgumentException e) {
            // 参数校验错误
            log.warn("[{}] 参数校验失败: {}", getName(), e.getMessage());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            return ToolResult.error(e.getMessage(), metadata);
            
        } catch (Exception e) {
            // 其他异常
            log.error("[{}] 执行失败", getName(), e);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("executionTimeMs", System.currentTimeMillis() - startTime);
            metadata.put("errorType", errorClassifier.classify(e).name());
            
            return ToolResult.error(getName() + "执行失败: " + e.getMessage(), metadata);
        }
    }
    
    /**
     * 参数校验（子类可重写）
     */
    protected void validateParameters(ToolContext context) {
        // 默认不做校验，子类按需重写
    }
    
    /**
     * 执行业务逻辑（子类必须实现）
     */
    protected abstract Object doExecute(ToolContext context) throws Exception;
}
