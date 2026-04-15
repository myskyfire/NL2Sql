package com.nl2sql.core.llm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ModelRouterService {
    
    private final MultiModelService multiModelService;
    
    public ModelRouterService(MultiModelService multiModelService) {
        this.multiModelService = multiModelService;
    }
    
    /**
     * 评估查询复杂度
     */
    public QueryComplexity assessComplexity(String query) {
        QueryComplexity complexity = new QueryComplexity();
        String lowerQuery = query.toLowerCase();
        
        int score = 0;
        
        // 1. 关键词数量
        String[] words = query.split("\\s+");
        if (words.length > 10) score += 2;
        else if (words.length > 5) score += 1;
        
        // 2. 是否包含聚合函数
        if (lowerQuery.contains("统计") || lowerQuery.contains("平均") || 
            lowerQuery.contains("总和") || lowerQuery.contains("最大") || 
            lowerQuery.contains("最小") || lowerQuery.contains("count") ||
            lowerQuery.contains("avg") || lowerQuery.contains("sum")) {
            score += 2;
        }
        
        // 3. 是否包含多表关联
        if (lowerQuery.contains("和") || lowerQuery.contains("以及") || 
            lowerQuery.contains("join") || lowerQuery.contains("关联")) {
            score += 3;
        }
        
        // 4. 是否包含时间范围
        if (lowerQuery.contains("最近") || lowerQuery.contains("过去") || 
            lowerQuery.contains("以来") || lowerQuery.contains("between")) {
            score += 1;
        }
        
        // 5. 是否包含子查询或复杂条件
        if (lowerQuery.contains("哪些") || lowerQuery.contains("为什么") || 
            lowerQuery.contains("如何") || lowerQuery.contains("嵌套")) {
            score += 3;
        }
        
        // 6. 是否包含排序和分组
        if (lowerQuery.contains("排序") || lowerQuery.contains("分组") || 
            lowerQuery.contains("order by") || lowerQuery.contains("group by")) {
            score += 1;
        }
        
        complexity.setScore(score);
        
        // 根据分数确定复杂度等级
        if (score >= 8) {
            complexity.setLevel("COMPLEX");
            complexity.setModel("nlp"); // 复杂查询使用推理能力强的模型
            complexity.setDescription("复杂查询，需要深度理解");
        } else if (score >= 4) {
            complexity.setLevel("MEDIUM");
            complexity.setModel("code"); // 中等复杂度使用代码模型
            complexity.setDescription("中等复杂度查询");
        } else {
            complexity.setLevel("SIMPLE");
            complexity.setModel("code"); // 简单查询使用代码模型（更快）
            complexity.setDescription("简单查询");
        }
        
        log.debug("查询复杂度评估: query={}, score={}, level={}", query, score, complexity.getLevel());
        
        return complexity;
    }
    
    /**
     * 智能生成SQL(根据复杂度选择模型)
     */
    public String smartGenerateSQL(String prompt, String userQuery) {
        QueryComplexity complexity = assessComplexity(userQuery);
            
        log.info("模型路由: level={}, model={}, reason={}", 
            complexity.getLevel(), complexity.getModel(), complexity.getDescription());
            
        // 根据复杂度选择不同的模型和策略
        switch (complexity.getLevel()) {
            case "COMPLEX":
                // 复杂查询：使用NLP模型（更强的推理能力）+ RAG增强
                log.info("使用NLP模型处理复杂查询，启用RAG增强");
                return multiModelService.generateSQLWithRAGUsingNLPModel(prompt, userQuery);
                    
            case "MEDIUM":
                // 中等复杂度：使用代码模型 + RAG增强
                log.info("使用代码模型处理中等复杂度查询，启用RAG增强");
                return multiModelService.generateSQLWithRAG(prompt, userQuery);
                    
            case "SIMPLE":
            default:
                // 简单查询：使用代码模型（更快更准确），不使用RAG
                log.info("使用代码模型处理简单查询，普通模式");
                return multiModelService.generateSQL(prompt);
        }
    }
    
    /**
     * 获取MultiModelService实例
     */
    public MultiModelService getMultiModelService() {
        return multiModelService;
    }
    
    @Data
    public static class QueryComplexity {
        private String level; // SIMPLE, MEDIUM, COMPLEX
        private int score;
        private String model; // code, nlp
        private String description;
    }
}
