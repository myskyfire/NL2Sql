package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 意图分类工具 - 精准识别用户意图
 */
@Slf4j
@Component
public class IntentClassifierTool {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    /**
     * 分类用户意图
     */
    @Tool("分析用户问题，识别其意图类型。输入用户问题，返回意图分类结果")
    public String classifyIntent(String userQuery) {
        try {
            log.info("[IntentClassifier] 分类意图: {}", userQuery);
            
            // 使用 LLM 进行分类
            String classificationPrompt = buildClassificationPrompt(userQuery);
            String result = modelRouter.getMultiModelService().summarizeResult(classificationPrompt);
            
            // 解析结果
            IntentClassification classification = parseClassification(result);
            
            log.info("[IntentClassifier] 分类结果: {}", classification.getIntentType());
            
            return formatClassificationResult(classification);
            
        } catch (Exception e) {
            log.error("[IntentClassifier] 分类失败", e);
            // 降级：基于关键词的简单分类
            return fallbackClassification(userQuery);
        }
    }
    
    /**
     * 构建分类 Prompt
     */
    private String buildClassificationPrompt(String query) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个意图分类专家。请分析用户问题，判断其意图类型。\n\n");
        
        prompt.append("## 可选意图类型\n");
        prompt.append("1. QUERY - 数据查询（查某类数据、查指标等）\n");
        prompt.append("2. ANALYSIS - 数据分析（趋势、对比、占比等）\n");
        prompt.append("3. CHART - 图表生成（柱状图、折线图等）\n");
        prompt.append("4. REPORT - 报告生成（生成分析报告）\n");
        prompt.append("5. OPTIMIZE - SQL优化（优化SQL性能）\n");
        prompt.append("6. DOWNLOAD - 数据下载（导出Excel/CSV）\n");
        prompt.append("7. CLARIFY - 需要澄清（信息不足，需追问）\n");
        prompt.append("8. OTHER - 其他（闲聊、无关问题）\n\n");
        
        prompt.append("## 用户问题\n");
        prompt.append(query).append("\n\n");
        
        prompt.append("## 输出格式\n");
        prompt.append("请以 JSON 格式输出：\n");
        prompt.append("{\n");
        prompt.append("  \"intent_type\": \"QUERY\",\n");
        prompt.append("  \"confidence\": 0.95,\n");
        prompt.append("  \"entities\": {\n");
        prompt.append("    \"datasource\": \"数据源名称或null\",\n");
        prompt.append("    \"tables\": [\"表名列表\"],\n");
        prompt.append("    \"metrics\": [\"指标列表\"]\n");
        prompt.append("  },\n");
        prompt.append("  \"reasoning\": \"分类理由\"\n");
        prompt.append("}\n\n");
        
        prompt.append("请输出分类结果：");
        
        return prompt.toString();
    }
    
    /**
     * 解析分类结果
     */
    private IntentClassification parseClassification(String result) {
        IntentClassification classification = new IntentClassification();
        
        // 简化解析（生产环境应用 JSON Parser）
        if (result.contains("QUERY")) {
            classification.setIntentType("QUERY");
        } else if (result.contains("ANALYSIS")) {
            classification.setIntentType("ANALYSIS");
        } else if (result.contains("CHART")) {
            classification.setIntentType("CHART");
        } else if (result.contains("REPORT")) {
            classification.setIntentType("REPORT");
        } else if (result.contains("OPTIMIZE")) {
            classification.setIntentType("OPTIMIZE");
        } else if (result.contains("DOWNLOAD")) {
            classification.setIntentType("DOWNLOAD");
        } else if (result.contains("CLARIFY")) {
            classification.setIntentType("CLARIFY");
        } else {
            classification.setIntentType("OTHER");
        }
        
        classification.setConfidence(0.85);
        classification.setReasoning("基于关键词匹配");
        
        return classification;
    }
    
    /**
     * 格式化分类结果
     */
    private String formatClassificationResult(IntentClassification classification) {
        StringBuilder result = new StringBuilder();
        result.append("🎯 意图分类结果:\n\n");
        result.append("- 类型: ").append(classification.getIntentType()).append("\n");
        result.append("- 置信度: ").append((int)(classification.getConfidence() * 100)).append("%\n");
        result.append("- 理由: ").append(classification.getReasoning()).append("\n");
        
        if (classification.getEntities() != null) {
            result.append("\n📦 提取实体:\n");
            if (classification.getEntities().getDatasource() != null) {
                result.append("- 数据源: ").append(classification.getEntities().getDatasource()).append("\n");
            }
            if (classification.getEntities().getTables() != null && !classification.getEntities().getTables().isEmpty()) {
                result.append("- 表: ").append(classification.getEntities().getTables()).append("\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * 降级方案：基于关键词的分类
     */
    private String fallbackClassification(String query) {
        String lowerQuery = query.toLowerCase();
        String intentType;
        
        if (lowerQuery.contains("图表") || lowerQuery.contains("图")) {
            intentType = "CHART";
        } else if (lowerQuery.contains("报告") || lowerQuery.contains("分析")) {
            intentType = "REPORT";
        } else if (lowerQuery.contains("优化") || lowerQuery.contains("性能")) {
            intentType = "OPTIMIZE";
        } else if (lowerQuery.contains("下载") || lowerQuery.contains("导出")) {
            intentType = "DOWNLOAD";
        } else if (lowerQuery.contains("趋势") || lowerQuery.contains("对比")) {
            intentType = "ANALYSIS";
        } else {
            intentType = "QUERY";
        }
        
        return "🎯 意图分类（降级模式）:\n- 类型: " + intentType + "\n- 置信度: 70%";
    }
    
    /**
     * 意图分类结果
     */
    @Data
    static class IntentClassification {
        private String intentType;
        private double confidence;
        private IntentEntities entities;
        private String reasoning;
        
        public IntentClassification() {
            this.entities = new IntentEntities();
        }
    }
    
    @Data
    static class IntentEntities {
        private String datasource;
        private List<String> tables = new ArrayList<>();
        private List<String> metrics = new ArrayList<>();
    }
}
