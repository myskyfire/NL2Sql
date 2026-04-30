package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.StringUtils;
import com.nl2sql.core.service.NL2SQLService;
import com.nl2sql.core.cache.MetadataCacheService;
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
    private NL2SQLService nl2sqlService;
    
    @Autowired(required = false)
    private MetadataCacheService metadataCacheService;
    
    /**
     * 批量翻译列名
     */
    @PostMapping("/column-names")
    public Result<Map<String, String>> translateColumnNames(
            @RequestBody List<String> columnNames,
            @RequestParam(required = false) Long datasourceId) {
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
            
            log.info("[TranslationController] 开始翻译: {} 个列, datasourceId={}", needTranslate.size(), datasourceId);
            
            // ✅ 步骤1：从缓存中查找已翻译的列
            Map<String, String> cachedTranslations = new HashMap<>();
            List<String> needLLMTranslation = new ArrayList<>();
            
            if (metadataCacheService != null && datasourceId != null) {
                cachedTranslations = metadataCacheService.batchGetColumnTranslations(datasourceId, needTranslate);
                
                // 过滤出需要调用LLM翻译的列
                for (String col : needTranslate) {
                    if (!cachedTranslations.containsKey(col)) {
                        needLLMTranslation.add(col);
                    }
                }
                
                log.info("[TranslationController] 缓存命中: {}/{} 个", cachedTranslations.size(), needTranslate.size());
            } else {
                needLLMTranslation.addAll(needTranslate);
            }
            
            // ✅ 步骤2：对未命中的列调用LLM翻译
            Map<String, String> llmTranslations = new HashMap<>();
            if (!needLLMTranslation.isEmpty()) {
                // 构建翻译Prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("请将以下数据库字段名翻译成简洁中文，保持原顺序。\n\n");
                prompt.append("字段列表：\n");
                for (String col : needLLMTranslation) {
                    prompt.append("- ").append(col).append("\n");
                }
                prompt.append("\n要求：\n");
                prompt.append("1. 只返回 JSON：{\"字段名\":\"中文翻译\"}\n");
                prompt.append("2. 简洁准确，如：total_amount->总金额，created_at->创建时间\n");
                prompt.append("3. 不要其他内容\n");
                
                String response = nl2sqlService.generateSQL(prompt.toString(), 1L);
                
                // 解析JSON响应
                llmTranslations = parseTranslationResponse(response, needLLMTranslation);
                
                // ✅ 步骤3：将LLM翻译结果写入缓存
                if (metadataCacheService != null && datasourceId != null && !llmTranslations.isEmpty()) {
                    metadataCacheService.batchPutColumnTranslations(datasourceId, llmTranslations);
                    log.info("[TranslationController] LLM翻译完成并缓存: {} 个", llmTranslations.size());
                }
            }
            
            // ✅ 步骤4：合并缓存和LLM翻译结果
            Map<String, String> allTranslations = new HashMap<>();
            allTranslations.putAll(cachedTranslations);
            allTranslations.putAll(llmTranslations);
            
            log.info("[TranslationController] 翻译完成: {} 个列", allTranslations.size());
            return Result.success(allTranslations);
            
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
