package com.nl2sql.core.agent.extension;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义映射服务
 * 
 * 聚合所有SemanticMappingExtension实现，按优先级排序
 * 在构建LLM Prompt时注入语义映射规则
 */
@Slf4j
@Component
public class SemanticMappingService implements IndustryConceptExtension {
    
    @Autowired(required = false)
    private List<SemanticMappingExtension> extensions;
    
    private Map<String, String> mergedMappings;
    
    @PostConstruct
    public void init() {
        if (extensions == null || extensions.isEmpty()) {
            log.warn("[语义映射] 未找到任何SemanticMappingExtension实现");
            this.mergedMappings = Collections.emptyMap();
            return;
        }
        
        // 按优先级排序（数字越小优先级越高）
        List<SemanticMappingExtension> sortedExtensions = extensions.stream()
            .sorted(Comparator.comparingInt(SemanticMappingExtension::getPriority))
            .collect(Collectors.toList());
        
        // 合并所有映射（高优先级覆盖低优先级）
        Map<String, String> merged = new LinkedHashMap<>();
        for (SemanticMappingExtension ext : sortedExtensions) {
            Map<String, String> mappings = ext.getSemanticMappings();
            if (mappings != null && !mappings.isEmpty()) {
                log.info("[语义映射] 加载扩展: {} (priority={}, rules={})", 
                    ext.getName(), ext.getPriority(), mappings.size());
                merged.putAll(mappings);
            }
        }
        
        this.mergedMappings = Collections.unmodifiableMap(merged);
        log.info("[语义映射] 初始化完成，共{}条映射规则", mergedMappings.size());
    }
    
    /**
     * 获取所有语义映射规则（用于构建Prompt）
     */
    public Map<String, String> getAllMappings() {
        return mergedMappings;
    }
    
    /**
     * 检查是否有自定义语义映射
     */
    public boolean hasCustomMappings() {
        return !mergedMappings.isEmpty();
    }
    
    /**
     * 生成Prompt片段（用于注入到System Prompt）
     */
    public String generatePromptSnippet() {
        if (mergedMappings.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【行业特定语义映射规则】\n");
        sb.append("以下词汇在数据库查询中有特定含义，请严格遵守：\n\n");
        
        for (Map.Entry<String, String> entry : mergedMappings.entrySet()) {
            sb.append("- \"").append(entry.getKey()).append("\" → ").append(entry.getValue()).append("\n");
        }
        
        sb.append("\n⚠️ 重要：当用户问题包含上述词汇时，必须按照映射规则理解其数据库语义。\n");
        
        return sb.toString();
    }
    
    /**
     * ✅ 实现IndustryConceptExtension接口
     * 在LLM生成SQL前注入语义映射规则
     * 
     * @param originalPrompt 原始Prompt（可能为null）
     * @param question 用户自然语言问题
     * @param datasourceId 数据源ID
     * @return 增强后的Prompt片段，如果无自定义映射则返回null
     */
    @Override
    public String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId) {
        if (mergedMappings.isEmpty()) {
            log.debug("[语义映射] 无自定义映射，不干预Prompt");
            return null; // 无自定义映射，不修改Prompt
        }
        
        // 检查问题中是否包含需要映射的词汇
        boolean hasMatchedTerm = false;
        StringBuilder sb = new StringBuilder();
        
        for (Map.Entry<String, String> entry : mergedMappings.entrySet()) {
            if (question.contains(entry.getKey())) {
                if (!hasMatchedTerm) {
                    sb.append("\n\n【行业特定语义映射规则】\n");
                    sb.append("以下词汇在数据库查询中有特定含义，请严格遵守：\n\n");
                    hasMatchedTerm = true;
                }
                sb.append("- \"").append(entry.getKey()).append("\" → ").append(entry.getValue()).append("\n");
            }
        }
        
        if (!hasMatchedTerm) {
            log.debug("[语义映射] 问题中未匹配到需要映射的词汇: {}", question);
            return null;
        }
        
        sb.append("\n⚠️ 重要：当用户问题包含上述词汇时，必须按照映射规则理解其数据库语义。\n");
        
        String enhancement = sb.toString();
        log.info("[语义映射] 注入{}条映射规则到Prompt", mergedMappings.size());
        
        return enhancement;
    }
}
