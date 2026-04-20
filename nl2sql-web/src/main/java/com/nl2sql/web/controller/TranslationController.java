package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.StringUtils;
import com.nl2sql.core.agent.tools.NL2SQLTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 智能翻译Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/translate")
public class TranslationController {
    
    @Autowired
    private NL2SQLTool nl2sqlTool;
    
    /**
     * 批量翻译列名
     */
    @PostMapping("/column-names")
    public Result<Map<String, String>> translateColumnNames(@RequestBody List<String> columnNames) {
        try {
            if (columnNames == null || columnNames.isEmpty()) {
                return Result.success(new HashMap<>());
            }
            
            // 过滤掉已经是中文的列名
            List<String> needTranslate = new ArrayList<>();
            for (String col : columnNames) {
                if (col != null && !col.matches(".*[\\u4e00-\\u9fa5].*")) {
                    needTranslate.add(col);
                }
            }
            
            if (needTranslate.isEmpty()) {
                return Result.success(new HashMap<>());
            }
            
            // 构建翻译Prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append("请将以下数据库字段名翻译成简洁的中文，保持原顺序返回JSON格式。\n\n");
            prompt.append("字段列表：\n");
            for (String col : needTranslate) {
                prompt.append("- ").append(col).append("\n");
            }
            prompt.append("\n要求：\n");
            prompt.append("1. 只返回JSON格式：{\"字段名\": \"中文翻译\"}\n");
            prompt.append("2. 翻译要简洁准确，例如：total_amount -> 总金额, created_at -> 创建时间\n");
            prompt.append("3. 不要添加任何解释或其他内容\n");
            
            String response = nl2sqlTool.generateSQL(prompt.toString(), 1L);
            
            // 解析JSON响应
            Map<String, String> translations = parseTranslationResponse(response, needTranslate);
            
            log.info("翻译结果: {}", translations);
            return Result.success(translations);
            
        } catch (Exception e) {
            log.error("翻译失败", e);
            return Result.error("翻译失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析LLM返回的翻译结果
     */
    private Map<String, String> parseTranslationResponse(String response, List<String> originalNames) {
        Map<String, String> result = new HashMap<>();
        
        if (response == null || response.trim().isEmpty()) {
            return result;
        }
        
        try {
            // 清理Markdown代码块
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();
            
            // 解析JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, String> translations = mapper.readValue(cleanJson, Map.class);
            
            // 验证并填充结果
            for (String original : originalNames) {
                if (translations.containsKey(original)) {
                    result.put(original, translations.get(original));
                } else {
                    // 未翻译的保持原样
                    result.put(original, StringUtils.formatReadable(original));
                }
            }
            
        } catch (Exception e) {
            log.warn("解析翻译结果失败，使用默认格式化: {}", e.getMessage());
            // 降级：使用通用格式化
            for (String original : originalNames) {
                result.put(original, StringUtils.formatReadable(original));
            }
        }
        
        return result;
    }
}
