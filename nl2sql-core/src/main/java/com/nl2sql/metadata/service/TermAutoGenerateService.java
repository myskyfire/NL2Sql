package com.nl2sql.metadata.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 术语自动生成服务（三层架构）
 * 
 * 1. 底层：结构化爬库（information_schema）
 * 2. 中层：LLM扩词（DDL→同义词/口语）
 * 3. 上层：前端选词约束
 */
@Slf4j
@Service
public class TermAutoGenerateService {
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private DdlExtractService ddlExtractService;
    
    @Autowired(required = false)
    private TermExpansionService termExpansionService;
    
    /**
     * 自动爬库生成术语（三层架构完整版）
     * 
     * @param datasourceId 数据源ID
     * @return 生成的术语数量
     */
    public int autoGenerateTerms(Long datasourceId) {
        if (jdbcTemplate == null) {
            log.warn("[TermAutoGenerate] JdbcTemplate未配置");
            return 0;
        }
        
        try {
            // ✅ 第1层：结构化爬库（骨架）
            log.info("[TermAutoGenerate] 开始第1层：结构化爬库...");
            int skeletonCount = generateSkeletonTerms();
            log.info("[TermAutoGenerate] 第1层完成，生成{}个骨架术语", skeletonCount);
            
            // ✅ 第2层：LLM扩词（口语/同义词）
            if (ddlExtractService != null && termExpansionService != null) {
                log.info("[TermAutoGenerate] 开始第2层：LLM扩词...");
                int expansionCount = generateExpandedTerms();
                log.info("[TermAutoGenerate] 第2层完成，生成{}个扩展术语", expansionCount);
            } else {
                log.warn("[TermAutoGenerate] LLM扩词服务未配置，跳过第2层");
            }
            
            log.info("[TermAutoGenerate] 术语自动生成完成");
            return skeletonCount;
            
        } catch (Exception e) {
            log.error("[TermAutoGenerate] 自动生成术语失败", e);
            return 0;
        }
    }
    
    /**
     * 第1层：结构化爬库生成骨架术语
     */
    private int generateSkeletonTerms() {
        // 复用之前的逻辑：从information_schema提取表名、字段注释
        List<Map<String, Object>> tables = queryAllTables(null);
        if (tables.isEmpty()) {
            return 0;
        }
        
        String schemaDescription = buildSchemaDescription(tables);
        List<TermCandidate> candidates = generateTermsByRules(schemaDescription);
        
        return batchInsertTerms(candidates, null);
    }
    
    /**
     * 第2层：LLM扩词生成口语/同义词
     */
    private int generateExpandedTerms() {
        // 1. 提取所有表的DDL
        Map<String, String> ddlMap = ddlExtractService.extractAllDdls();
        if (ddlMap.isEmpty()) {
            return 0;
        }
        
        // 2. 逐个表扩词
        int totalExpanded = 0;
        for (Map.Entry<String, String> entry : ddlMap.entrySet()) {
            String tableName = entry.getKey();
            String ddl = entry.getValue();
            
            List<TermExpansionService.TermExpansion> expansions = termExpansionService.expandTerms(ddl);
            
            // 3. 插入扩展术语（同义词）
            for (TermExpansionService.TermExpansion expansion : expansions) {
                insertExpandedTerm(expansion, tableName);
                totalExpanded++;
            }
        }
        
        return totalExpanded;
    }
    
    /**
     * 插入扩展术语（同义词）
     */
    private void insertExpandedTerm(TermExpansionService.TermExpansion expansion, String tableName) {
        if (expansion.getSynonyms() == null || expansion.getSynonyms().isEmpty()) {
            return;
        }
        
        String sql = "INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active) " +
                    "VALUES (?, 'synonym', 'ecommerce', 5, ?, 1) " +
                    "ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count)";
        
        for (String synonym : expansion.getSynonyms()) {
            try {
                String description = String.format("%s的同义词（源自：%s）", expansion.getMainTerm(), tableName);
                jdbcTemplate.update(sql, synonym, description);
            } catch (Exception e) {
                log.warn("[TermAutoGenerate] 插入同义词失败: {}", synonym, e);
            }
        }
    }
    
    /**
     * 查询所有表结构
     */
    private List<Map<String, Object>> queryAllTables(Long datasourceId) {
        // TODO: 根据datasourceId动态切换数据源
        String sql = "SELECT table_name, table_comment FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'";
        
        return jdbcTemplate.queryForList(sql);
    }
    
    /**
     * 构建表结构描述（用于LLM Prompt）
     */
    private String buildSchemaDescription(List<Map<String, Object>> tables) {
        StringBuilder sb = new StringBuilder();
        
        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("table_name");
            String tableComment = (String) table.get("table_comment");
            
            sb.append("表名: ").append(tableName).append("\n");
            if (tableComment != null && !tableComment.isEmpty()) {
                sb.append("表注释: ").append(tableComment).append("\n");
            }
            
            // 查询字段信息
            List<Map<String, Object>> columns = queryColumns(tableName);
            for (Map<String, Object> column : columns) {
                String columnName = (String) column.get("column_name");
                String columnType = (String) column.get("data_type");
                String columnComment = (String) column.get("column_comment");
                
                sb.append("  - 字段: ").append(columnName)
                  .append(" (").append(columnType).append(")");
                if (columnComment != null && !columnComment.isEmpty()) {
                    sb.append(", 注释: ").append(columnComment);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 查询表的字段信息
     */
    private List<Map<String, Object>> queryColumns(String tableName) {
        String sql = "SELECT column_name, data_type, column_comment " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = ? " +
                    "ORDER BY ordinal_position";
        
        return jdbcTemplate.queryForList(sql, tableName);
    }
    
    /**
     * 调用LLM生成术语（当前使用规则生成，LLM集成待实现）
     */
    private List<TermCandidate> callLLMToGenerateTerms(String schemaDescription) {
        // TODO: 集成LLM服务
        log.info("[TermAutoGenerate] LLM集成待实现，使用规则生成");
        return generateTermsByRules(schemaDescription);
    }
    
    /**
     * 构建LLM Prompt
     */
    private String buildLLMPrompt(String schemaDescription) {
        return """
            你是一个电商领域的业务专家。请根据以下数据库表结构，生成业务术语词典。
            
            【任务要求】
            1. 从表名、字段名、字段注释中提取业务术语
            2. 为每个术语生成2-5个同义词（包括口语化表达、行业黑话）
            3. 识别关键指标（数值型字段）和分析维度（日期/字符串型字段）
            4. 输出JSON格式
            
            【表结构】
            %s
            
            【输出格式】
            [
              {
                "term": "订单状态",
                "type": "entity",
                "synonyms": ["订单进度", "处理状态", "当前状态"],
                "description": "订单的当前处理状态"
              },
              {
                "term": "订单金额",
                "type": "metric",
                "synonyms": ["GMV", "交易额", "流水"],
                "description": "订单的交易金额"
              }
            ]
            
            请直接输出JSON数组，不要包含其他文字。
            """.formatted(schemaDescription);
    }
    
    /**
     * 解析LLM响应
     */
    private List<TermCandidate> parseLLMResponse(String response) {
        // TODO: 实现JSON解析
        log.warn("[TermAutoGenerate] JSON解析待实现，返回空列表");
        return Collections.emptyList();
    }
    
    /**
     * 规则生成术语（降级方案）
     */
    private List<TermCandidate> generateTermsByRules(String schemaDescription) {
        List<TermCandidate> candidates = new ArrayList<>();
        
        // 简单规则：提取中文注释作为术语
        String[] lines = schemaDescription.split("\n");
        for (String line : lines) {
            if (line.contains("注释:")) {
                String comment = line.substring(line.indexOf("注释:") + 3).trim();
                if (!comment.isEmpty() && comment.length() <= 20) {
                    TermCandidate candidate = new TermCandidate();
                    candidate.setTerm(comment);
                    candidate.setType(inferTermType(line));
                    candidate.setDescription(comment);
                    candidates.add(candidate);
                }
            }
        }
        
        return candidates;
    }
    
    /**
     * 推断术语类型
     */
    private String inferTermType(String line) {
        if (line.contains("int") || line.contains("decimal") || line.contains("double")) {
            return "metric";
        } else if (line.contains("date") || line.contains("time")) {
            return "dimension";
        } else {
            return "entity";
        }
    }
    
    /**
     * 批量插入术语
     */
    private int batchInsertTerms(List<TermCandidate> candidates, Long datasourceId) {
        if (candidates.isEmpty()) {
            return 0;
        }
        
        String sql = "INSERT INTO term_suggestion (term, term_type, industry_code, usage_count, description, is_active) " +
                    "VALUES (?, ?, 'ecommerce', ?, ?, 1) " +
                    "ON DUPLICATE KEY UPDATE usage_count = usage_count + VALUES(usage_count)";
        
        int count = 0;
        for (TermCandidate candidate : candidates) {
            try {
                jdbcTemplate.update(sql, 
                    candidate.getTerm(),
                    candidate.getType(),
                    candidate.getUsageCount() != null ? candidate.getUsageCount() : 10,
                    candidate.getDescription()
                );
                count++;
            } catch (Exception e) {
                log.warn("[TermAutoGenerate] 插入术语失败: {}", candidate.getTerm(), e);
            }
        }
        
        return count;
    }
    
    /**
     * 术语候选数据结构
     */
    public static class TermCandidate {
        private String term;
        private String type;
        private List<String> synonyms;
        private String description;
        private Integer usageCount;
        
        // Getters and Setters
        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public Integer getUsageCount() { return usageCount; }
        public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }
    }
}
