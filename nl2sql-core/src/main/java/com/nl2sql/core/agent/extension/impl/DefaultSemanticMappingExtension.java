package com.nl2sql.core.agent.extension.impl;

import com.nl2sql.core.agent.extension.SemanticMappingExtension;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 默认语义映射扩展点（空实现）
 * 
 * 标准产品不提供任何行业特定的语义映射
 * 行业插件可通过实现SemanticMappingExtension接口注入领域知识
 */
@Component
public class DefaultSemanticMappingExtension implements SemanticMappingExtension {
    
    @Override
    public Map<String, String> getSemanticMappings() {
        // 标准产品不预设任何行业语义
        return Collections.emptyMap();
    }
    
    @Override
    public int getPriority() {
        return 100; // 最低优先级
    }
    
    @Override
    public String getName() {
        return "DefaultSemanticMapping";
    }
}
