package com.nl2sql.core.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态提示词构建器
 * 
 * 核心思想：根据用户意图和上下文，动态组装最精简的 System Prompt
 * 
 * 优势：
 * 1. Token 减少 60-70%（从 ~2500 降至 ~800）
 * 2. 更聚焦，LLM 更容易理解当前任务
 * 3. 模块化设计，易于维护和扩展
 * 4. ✅ 内置缓存机制，避免重复构建
 */
@Slf4j
@Component
public class DynamicPromptBuilder {
    
    @Autowired(required = false)
    private BaseInstructionModule baseInstructionModule;
    
    @Autowired(required = false)
    private ToolListModule toolListModule;
    
    @Autowired(required = false)
    private SkillsModule skillsModule;
    
    @Autowired(required = false)
    private WorkflowModule workflowModule;
    
    // ✅ 细粒度 Workflow 模块
    @Autowired(required = false)
    private DataSourceWorkflowModule dataSourceWorkflowModule;
    
    @Autowired(required = false)
    private QueryWorkflowModule queryWorkflowModule;
    
    @Autowired(required = false)
    private SummaryWorkflowModule summaryWorkflowModule;
    
    @Autowired(required = false)
    private ChartWorkflowModule chartWorkflowModule;
    
    // ✅ 缓存：key=intent, value=System Prompt
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();
    
    // ✅ 缓存统计
    private int cacheHitCount = 0;
    private int cacheMissCount = 0;
    
    /**
     * 构建完整的 System Prompt
     * 
     * @param intent 用户意图类型（QUERY, SUMMARY, CHART 等）
     * @return 组装后的 System Prompt
     */
    public String build(String intent) {
        // ✅ 缓存命中检查
        if (promptCache.containsKey(intent)) {
            cacheHitCount++;
            log.debug("[DynamicPromptBuilder] 缓存命中: intent={}, hitRate={:.1f}%", 
                      intent, getHitRate());
            return promptCache.get(intent);
        }
        
        cacheMissCount++;
        List<PromptModule> modules = selectModules(intent);
        
        StringBuilder sb = new StringBuilder();
        int totalTokens = 0;
        
        for (PromptModule module : modules) {
            String content = module.build();
            sb.append(content);
            totalTokens += module.estimateTokens();
        }
        
        String prompt = sb.toString();
        
        // ✅ 写入缓存
        promptCache.put(intent, prompt);
        
        log.info("[DynamicPromptBuilder] 构建完成: 意图={}, 模块数={}, 预估Token={}, 缓存命中率={:.1f}%", 
                  intent, modules.size(), totalTokens, getHitRate());
        
        return prompt;
    }
    
    /**
     * 根据意图选择需要的模块（✅ 优化：使用细粒度 Workflow 模块）
     */
    private List<PromptModule> selectModules(String intent) {
        List<PromptModule> modules = new ArrayList<>();
        
        // 基础指令：所有请求都需要
        if (baseInstructionModule != null) {
            modules.add(baseInstructionModule);
        }
        
        // ✅ 数据源澄清流程：所有请求都需要
        if (dataSourceWorkflowModule != null) {
            modules.add(dataSourceWorkflowModule);
        }
        
        // ✅ 根据意图选择对应的 Workflow 模块
        if ("QUERY".equalsIgnoreCase(intent)) {
            if (queryWorkflowModule != null) {
                modules.add(queryWorkflowModule);
            }
            // Skills 元数据：只有 QUERY 意图需要
            if (skillsModule != null) {
                modules.add(skillsModule);
            }
        } else if ("SUMMARY".equalsIgnoreCase(intent)) {
            if (summaryWorkflowModule != null) {
                modules.add(summaryWorkflowModule);
            }
        } else if ("CHART".equalsIgnoreCase(intent)) {
            if (chartWorkflowModule != null) {
                modules.add(chartWorkflowModule);
            }
        } else {
            // 回退到通用 WorkflowModule（兼容模式）
            if (workflowModule != null) {
                modules.add(workflowModule);
            }
        }
        
        // 工具列表：所有请求都需要
        if (toolListModule != null) {
            modules.add(toolListModule);
        }
        
        return modules;
    }
    
    /**
     * 快速构建（默认意图：QUERY）
     */
    public String build() {
        return build("QUERY");
    }
    
    /**
     * ✅ 获取缓存命中率
     */
    private double getHitRate() {
        int total = cacheHitCount + cacheMissCount;
        return total == 0 ? 0.0 : (double) cacheHitCount / total * 100;
    }
    
    /**
     * ✅ 清除缓存（用于工具列表变化时）
     */
    public void clearCache() {
        promptCache.clear();
        cacheHitCount = 0;
        cacheMissCount = 0;
        log.info("[DynamicPromptBuilder] 缓存已清除");
    }
}
