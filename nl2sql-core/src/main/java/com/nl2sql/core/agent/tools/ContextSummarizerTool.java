package com.nl2sql.core.agent.tools;

import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 上下文压缩工具 - 压缩长对话历史，保留关键信息
 */
@Slf4j
@Component
public class ContextSummarizerTool {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    /**
     * 压缩对话历史
     */
    @Tool("压缩冗长的对话历史，保留关键信息。输入对话历史JSON字符串，返回压缩后的摘要")
    public String summarizeContext(String conversationHistoryJson) {
        try {
            List<Map<String, String>> conversationHistory = parseConversationHistory(conversationHistoryJson);
            log.info("[ContextSummarizer] 开始压缩，原始消息数: {}", conversationHistory.size());
            
            if (conversationHistory == null || conversationHistory.isEmpty()) {
                return "无对话历史";
            }
            
            // 如果对话较少，不需要压缩
            if (conversationHistory.size() <= 5) {
                return "对话较短，无需压缩";
            }
            
            // 构建压缩 Prompt
            String summaryPrompt = buildSummaryPrompt(conversationHistory);
            
            // 调用 LLM 生成摘要
            String summary = modelRouter.getMultiModelService().summarizeResult(summaryPrompt);
            
            log.info("[ContextSummarizer] 压缩完成");
            
            return "📝 对话摘要:\n\n" + summary + "\n\n💡 提示: 以上是对之前对话的总结，保留了关键信息。";
            
        } catch (Exception e) {
            log.error("[ContextSummarizer] 压缩失败", e);
            return "❌ 压缩失败: " + e.getMessage();
        }
    }
    
    /**
     * 提取关键信息
     */
    @Tool("从对话中提取关键信息（数据源、表名、查询意图等）。输入对话历史JSON字符串")
    public String extractKeyInfo(String conversationHistoryJson) {
        try {
            List<Map<String, String>> conversationHistory = parseConversationHistory(conversationHistoryJson);
            Map<String, Object> keyInfo = new HashMap<>();
            
            List<String> datasources = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            List<String> queries = new ArrayList<>();
            
            for (Map<String, String> msg : conversationHistory) {
                String content = msg.getOrDefault("content", "").toLowerCase();
                
                // 简单关键词提取（生产环境应用 NLP）
                if (content.contains("数据源") || content.contains("datasource")) {
                    datasources.add(msg.get("content"));
                }
                if (content.contains("表") || content.contains("table")) {
                    tables.add(msg.get("content"));
                }
                if (msg.get("role").equals("user")) {
                    queries.add(msg.get("content"));
                }
            }
            
            keyInfo.put("datasources", datasources);
            keyInfo.put("tables", tables);
            keyInfo.put("queries", queries);
            
            return "🔑 关键信息:\n" + keyInfo.toString();
            
        } catch (Exception e) {
            log.error("[ContextSummarizer] 提取失败", e);
            return "❌ 提取失败: " + e.getMessage();
        }
    }
    
    /**
     * 构建压缩 Prompt
     */
    private String buildSummaryPrompt(List<Map<String, String>> history) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请压缩以下对话历史，保留关键信息。\n\n");
        
        prompt.append("对话历史：\n");
        int displayCount = Math.min(15, history.size());
        for (int i = 0; i < displayCount; i++) {
            Map<String, String> msg = history.get(i);
            prompt.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        
        if (history.size() > 15) {
            prompt.append("... (还有 ").append(history.size() - 15).append(" 条消息)\n");
        }
        
        prompt.append("\n要求：\n");
        prompt.append("1. 保留核心查询意图、重要数据发现、关键建议\n");
        prompt.append("2. 删除寒暄、重复内容\n");
        prompt.append("3. 简洁概括，200 字以内\n\n");
        
        prompt.append("摘要：");
        
        return prompt.toString();
    }
    
    /**
     * 解析对话历史 JSON
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseConversationHistory(String historyJson) {
        if (historyJson == null || historyJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(historyJson, List.class);
        } catch (Exception e) {
            log.error("[ContextSummarizer] 解析对话历史JSON失败", e);
            return new ArrayList<>();
        }
    }
}
