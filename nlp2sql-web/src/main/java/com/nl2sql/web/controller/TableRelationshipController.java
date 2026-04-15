package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.metadata.service.TableRelationshipService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 表关联关系管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/relationships")
public class TableRelationshipController {
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 获取指定数据源的所有表
     */
    @GetMapping("/tables")
    public Result<List<Map<String, Object>>> getTables(@RequestParam Long datasourceId) {
        try {
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT DISTINCT table_name, table_comment FROM column_metadata WHERE datasource_id = ? ORDER BY table_name",
                datasourceId
            );
            return Result.success(tables);
        } catch (Exception e) {
            log.error("获取表列表失败", e);
            return Result.error("获取表列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定表的所有字段
     */
    @GetMapping("/columns")
    public Result<List<Map<String, Object>>> getColumns(
            @RequestParam Long datasourceId, 
            @RequestParam String tableName) {
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, column_comment, is_primary_key " +
                "FROM column_metadata WHERE datasource_id = ? AND table_name = ? " +
                "ORDER BY ordinal_position",
                datasourceId, tableName
            );
            return Result.success(columns);
        } catch (Exception e) {
            log.error("获取字段列表失败", e);
            return Result.error("获取字段列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有关联关系
     */
    @GetMapping
    public Result<List<Map<String, Object>>> getAllRelationships(@RequestParam Long datasourceId) {
        try {
            List<Map<String, Object>> relationships = jdbcTemplate.queryForList(
                "SELECT * FROM table_relationships WHERE datasource_id = ? ORDER BY source_table, target_table",
                datasourceId
            );
            return Result.success(relationships);
        } catch (Exception e) {
            log.error("获取关联关系失败", e);
            return Result.error("获取关联关系失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建关联关系
     */
    @PostMapping
    public Result<Void> createRelationship(@RequestBody RelationshipRequest request) {
        try {
            // 检查是否已存在
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM table_relationships WHERE datasource_id = ? AND source_table = ? AND source_column = ? AND target_table = ? AND target_column = ?",
                Integer.class,
                request.getDatasourceId(),
                request.getSourceTable(),
                request.getSourceColumn(),
                request.getTargetTable(),
                request.getTargetColumn()
            );
            
            if (count != null && count > 0) {
                return Result.error("该关联关系已存在");
            }
            
            jdbcTemplate.update(
                "INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                request.getDatasourceId(),
                request.getSourceTable(),
                request.getSourceColumn(),
                request.getTargetTable(),
                request.getTargetColumn(),
                request.getRelationshipType() != null ? request.getRelationshipType() : "MANY_TO_ONE",
                1, // 手动创建的置信度为1
                request.getDescription() != null ? request.getDescription() : ""
            );
            
            log.info("创建关联关系成功: {}.{} -> {}.{}", 
                request.getSourceTable(), request.getSourceColumn(),
                request.getTargetTable(), request.getTargetColumn());
            
            return Result.success();
        } catch (Exception e) {
            log.error("创建关联关系失败", e);
            return Result.error("创建失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新关联关系
     */
    @PutMapping("/{id}")
    public Result<Void> updateRelationship(@PathVariable Long id, @RequestBody RelationshipRequest request) {
        try {
            jdbcTemplate.update(
                "UPDATE table_relationships SET relationship_type = ?, description = ?, is_active = ? WHERE id = ?",
                request.getRelationshipType(),
                request.getDescription(),
                request.getIsActive() != null ? request.getIsActive() : 1,
                id
            );
            
            log.info("更新关联关系成功: id={}", id);
            return Result.success();
        } catch (Exception e) {
            log.error("更新关联关系失败", e);
            return Result.error("更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除关联关系
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRelationship(@PathVariable Long id) {
        try {
            jdbcTemplate.update("DELETE FROM table_relationships WHERE id = ?", id);
            log.info("删除关联关系成功: id={}", id);
            return Result.success();
        } catch (Exception e) {
            log.error("删除关联关系失败", e);
            return Result.error("删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 使用LLM自动推断关联关系
     */
    @PostMapping("/auto-detect")
    public Result<List<Map<String, Object>>> autoDetectRelationships(@RequestParam Long datasourceId) {
        try {
            log.info("开始自动推断关联关系: datasourceId={}", datasourceId);
            
            // 获取所有表名
            List<String> tables = jdbcTemplate.queryForList(
                "SELECT DISTINCT table_name FROM column_metadata WHERE datasource_id = ?",
                String.class, datasourceId
            );
            
            if (tables.isEmpty()) {
                return Result.error("未找到任何表");
            }
            
            // 构建表结构信息
            StringBuilder schemaInfo = new StringBuilder();
            for (String tableName : tables) {
                schemaInfo.append(String.format("\n表名: %s\n", tableName));
                
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type, column_comment, is_primary_key " +
                    "FROM column_metadata WHERE datasource_id = ? AND table_name = ? " +
                    "ORDER BY ordinal_position",
                    datasourceId, tableName
                );
                
                for (Map<String, Object> col : columns) {
                    schemaInfo.append(String.format("  - %s (%s)", 
                        col.get("column_name"), col.get("data_type")));
                    
                    if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                        schemaInfo.append(" [主键]");
                    }
                    
                    if (col.get("column_comment") != null && !col.get("column_comment").toString().isEmpty()) {
                        schemaInfo.append(String.format(" - %s", col.get("column_comment")));
                    }
                    schemaInfo.append("\n");
                }
            }
            
            // TODO: 调用LLM推断关联关系
            // 暂时返回空列表，后续实现LLM推断逻辑
            
            return Result.success(new ArrayList<>());
        } catch (Exception e) {
            log.error("自动推断关联关系失败", e);
            return Result.error("推断失败: " + e.getMessage());
        }
    }
    
    /**
     * 从SQL中提取关联关系
     */
    @PostMapping("/extract-from-sql")
    public Result<List<Map<String, Object>>> extractRelationshipsFromSQL(@RequestBody ExtractSQLRequest request) {
        try {
            String sql = request.getSql();
            Long datasourceId = request.getDatasourceId();
            
            if (sql == null || sql.trim().isEmpty()) {
                return Result.error("SQL不能为空");
            }
            
            log.info("开始从SQL提取关联关系: {}", sql.substring(0, Math.min(100, sql.length())));
            
            // 使用正则提取JOIN条件
            List<Map<String, Object>> relationships = new ArrayList<>();
            
            // 匹配 JOIN ... ON 模式
            java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
                "\\bJOIN\\s+(\\w+)\\s+\\w*\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = joinPattern.matcher(sql);
            
            while (matcher.find()) {
                String targetTable = matcher.group(1);
                String sourceTable = matcher.group(2);
                String sourceColumn = matcher.group(3);
                String targetTableAlias = matcher.group(4);
                String targetColumn = matcher.group(5);
                
                // 需要解析别名到真实表名的映射
                Map<String, String> aliasMap = extractTableAliases(sql);
                String realTargetTable = aliasMap.getOrDefault(targetTableAlias, targetTableAlias);
                String realSourceTable = aliasMap.getOrDefault(sourceTable, sourceTable);
                
                Map<String, Object> rel = new HashMap<>();
                rel.put("sourceTable", realSourceTable);
                rel.put("sourceColumn", sourceColumn);
                rel.put("targetTable", realTargetTable);
                rel.put("targetColumn", targetColumn);
                rel.put("relationshipType", "MANY_TO_ONE");
                rel.put("description", String.format("%s.%s -> %s.%s", 
                    realSourceTable, sourceColumn, realTargetTable, targetColumn));
                
                relationships.add(rel);
            }
            
            // 匹配 WHERE ... IN (SELECT ...) 模式（子查询关联）
            java.util.regex.Pattern subqueryPattern = java.util.regex.Pattern.compile(
                "\\b(\\w+)\\.(\\w+)\\s+IN\\s*\\(\\s*SELECT\\s+(\\w+)\\s+FROM\\s+(\\w+)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            matcher = subqueryPattern.matcher(sql);
            
            while (matcher.find()) {
                String sourceTable = matcher.group(1);
                String sourceColumn = matcher.group(2);
                String targetColumn = matcher.group(3);
                String targetTable = matcher.group(4);
                
                Map<String, String> aliasMap = extractTableAliases(sql);
                String realSourceTable = aliasMap.getOrDefault(sourceTable, sourceTable);
                String realTargetTable = aliasMap.getOrDefault(targetTable, targetTable);
                
                Map<String, Object> rel = new HashMap<>();
                rel.put("sourceTable", realSourceTable);
                rel.put("sourceColumn", sourceColumn);
                rel.put("targetTable", realTargetTable);
                rel.put("targetColumn", targetColumn);
                rel.put("relationshipType", "MANY_TO_ONE");
                rel.put("description", String.format("%s.%s IN (SELECT %s FROM %s)", 
                    realSourceTable, sourceColumn, targetColumn, realTargetTable));
                rel.put("warning", "子查询关联，建议改为直接JOIN");
                
                relationships.add(rel);
            }
            
            log.info("从SQL中提取到 {} 条关联关系", relationships.size());
            return Result.success(relationships);
            
        } catch (Exception e) {
            log.error("从SQL提取关联关系失败", e);
            return Result.error("提取失败: " + e.getMessage());
        }
    }
    
    /**
     * 批量保存关联关系
     */
    @PostMapping("/batch-save")
    public Result<Void> batchSaveRelationships(@RequestBody BatchSaveRequest request) {
        try {
            int successCount = 0;
            int skipCount = 0;
            
            for (RelationshipRequest rel : request.getRelationships()) {
                // 检查是否已存在
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM table_relationships WHERE datasource_id = ? AND source_table = ? AND source_column = ? AND target_table = ? AND target_column = ?",
                    Integer.class,
                    rel.getDatasourceId(),
                    rel.getSourceTable(),
                    rel.getSourceColumn(),
                    rel.getTargetTable(),
                    rel.getTargetColumn()
                );
                
                if (count != null && count > 0) {
                    skipCount++;
                    continue;
                }
                
                jdbcTemplate.update(
                    "INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    rel.getDatasourceId(),
                    rel.getSourceTable(),
                    rel.getSourceColumn(),
                    rel.getTargetTable(),
                    rel.getTargetColumn(),
                    rel.getRelationshipType() != null ? rel.getRelationshipType() : "MANY_TO_ONE",
                    1,
                    rel.getDescription() != null ? rel.getDescription() : ""
                );
                
                successCount++;
            }
            
            log.info("批量保存关联关系: 成功={}, 跳过={}", successCount, skipCount);
            return Result.success();
            
        } catch (Exception e) {
            log.error("批量保存关联关系失败", e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }
    
    /**
     * 提取SQL中的表别名映射
     */
    private Map<String, String> extractTableAliases(String sql) {
        Map<String, String> aliasMap = new HashMap<>();
        
        // 匹配 FROM table alias 或 JOIN table alias
        java.util.regex.Pattern aliasPattern = java.util.regex.Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = aliasPattern.matcher(sql);
        
        while (matcher.find()) {
            String tableName = matcher.group(1);
            String alias = matcher.group(2);
            
            if (alias != null && !alias.equalsIgnoreCase("ON") && !alias.equalsIgnoreCase("WHERE") 
                && !alias.equalsIgnoreCase("LEFT") && !alias.equalsIgnoreCase("RIGHT")
                && !alias.equalsIgnoreCase("INNER") && !alias.equalsIgnoreCase("OUTER")) {
                aliasMap.put(alias, tableName);
            }
        }
        
        return aliasMap;
    }
    
    @Data
    public static class RelationshipRequest {
        private Long datasourceId;
        private String sourceTable;
        private String sourceColumn;
        private String targetTable;
        private String targetColumn;
        private String relationshipType; // MANY_TO_ONE, ONE_TO_MANY, ONE_TO_ONE
        private String description;
        private Integer isActive;
    }
    
    @Data
    public static class ExtractSQLRequest {
        private String sql;
        private Long datasourceId;
    }
    
    @Data
    public static class BatchSaveRequest {
        private List<RelationshipRequest> relationships;
    }
}
