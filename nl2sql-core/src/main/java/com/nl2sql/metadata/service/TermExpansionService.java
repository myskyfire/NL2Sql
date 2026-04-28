package com.nl2sql.metadata.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * LLM术语扩词服务
 * 
 * 基于DDL，调用LLM自动生成同义词、业务术语、口语表达
 */
@Slf4j
@Service
public class TermExpansionService {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 基于DDL扩词（当前使用规则生成，LLM集成待实现）
     * 
     * @param ddl DDL建表语句
     * @return 扩展后的术语列表
     */
    public List<TermExpansion> expandTerms(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        // TODO: 集成LLM服务
        log.info("[TermExpansion] LLM集成待实现，使用规则生成");
        return expandByRules(ddl);
    }
    
    /**
     * 规则生成术语（降级方案）
     */
    private List<TermExpansion> expandByRules(String ddl) {
        List<TermExpansion> expansions = new ArrayList<>();
        
        // 简单规则：提取COMMENT作为标准词
        String[] lines = ddl.split("\n");
        for (String line : lines) {
            if (line.contains("COMMENT") || line.contains("comment")) {
                String comment = extractComment(line);
                if (!comment.isEmpty()) {
                    TermExpansion expansion = new TermExpansion();
                    expansion.setMainTerm(comment);
                    expansion.setSynonyms(generateSynonyms(comment));
                    expansion.setIntentWords(Arrays.asList("列表", "详情", "统计"));
                    expansions.add(expansion);
                }
            }
        }
        
        return expansions;
    }
    
    /**
     * 提取注释内容
     */
    private String extractComment(String line) {
        int start = line.indexOf("'");
        int end = line.lastIndexOf("'");
        
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        
        return "";
    }
    
    /**
     * 简单同义词生成（规则）
     */
    private List<String> generateSynonyms(String term) {
        List<String> synonyms = new ArrayList<>();
        
        // 简单映射规则
        if (term.contains("订单")) {
            synonyms.add("单子");
            synonyms.add("定单");
        }
        if (term.contains("金额")) {
            synonyms.add("钱");
            synonyms.add("费用");
        }
        if (term.contains("用户")) {
            synonyms.add("客户");
            synonyms.add("买家");
        }
        
        return synonyms;
    }
    
    /**
     * 构建LLM Prompt（待实现）
     */
    private String buildLLMPrompt(String ddl) {
        return """
            你是垂直行业术语挖掘专家。
            给定一段 MySQL 建表语句、字段注释，帮我输出：
            1. 标准业务名词
            2. 日常口语同义词
            3. 部门业务别名
            4. 常用查询意图（列表/统计/详情/排行）
            
            输出格式为JSON数组，严格收敛，不要编造无关业务词汇：
            [
              {
                "mainTerm": "主标准词",
                "synonyms": ["近义词1","近义词2"],
                "intentWords": ["查看列表","查看详情","统计汇总"]
              }
            ]
            
            ---
            DDL：
            %s
            
            请直接输出JSON数组，不要包含其他文字。
            """.formatted(ddl);
    }
    
    /**
     * 解析LLM响应（待实现）
     */
    private List<TermExpansion> parseLLMResponse(String response) {
        try {
            return Arrays.asList(objectMapper.readValue(response, TermExpansion[].class));
        } catch (JsonProcessingException e) {
            log.error("[TermExpansion] 解析LLM响应失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 术语扩展数据结构
     */
    @Data
    public static class TermExpansion {
        /** 标准业务名词 */
        private String mainTerm;
        
        /** 同义词/口语 */
        private List<String> synonyms;
        
        /** 常用查询意图 */
        private List<String> intentWords;
    }
}
