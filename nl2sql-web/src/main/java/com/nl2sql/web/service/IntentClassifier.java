package com.nl2sql.web.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 轻量级意图识别服务
 * 
 * 职责：根据用户消息内容快速判断意图类型
 * 优势：避免每次调用 LLM 进行意图分类，减少延迟和成本
 */
@Slf4j
@Service
public class IntentClassifier {
    
    /**
     * 识别用户意图
     * 
     * @param message 用户消息
     * @return 意图类型：QUERY / SUMMARY / CHART
     */
    public String classify(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "QUERY"; // 默认意图
        }
        
        String upperMessage = message.toUpperCase();
        
        // ✅ 检测 [INTENT:XXX] 标记（最高优先级）
        if (upperMessage.contains("[INTENT:AI_SUMMARY]") || 
            upperMessage.contains("SUMMARIZE") || 
            upperMessage.contains("总结")) {
            log.debug("[IntentClassifier] 识别为 SUMMARY 意图");
            return "SUMMARY";
        }
        
        if (upperMessage.contains("[INTENT:GENERATE_CHART]") || 
            upperMessage.contains("CHART") || 
            upperMessage.contains("图表") || 
            upperMessage.contains("生成图")) {
            log.debug("[IntentClassifier] 识别为 CHART 意图");
            return "CHART";
        }
        
        // 默认为 QUERY 意图
        return "QUERY";
    }
}
