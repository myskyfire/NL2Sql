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
 * 表关联关系管理Controller（轻量级，只做参数验证和转发）
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
            
            // ✅ 自动生成标准格式的 description
            String autoDescription = generateStandardDescription(
                request.getSourceTable(),
                request.getSourceColumn(),
                request.getTargetTable(),
                request.getTargetColumn(),
                request.getDatasourceId()
            );
            
            jdbcTemplate.update(
                "INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                request.getDatasourceId(),
                request.getSourceTable(),
                request.getSourceColumn(),
                request.getTargetTable(),
                request.getTargetColumn(),
                request.getRelationshipType() != null ? request.getRelationshipType() : "MANY_TO_ONE",
                1,
                autoDescription
            );
            
            log.info("创建关联关系成功: {}.{} -> {}.{}, description={}", 
                request.getSourceTable(), request.getSourceColumn(),
                request.getTargetTable(), request.getTargetColumn(), autoDescription);
            
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
     * 使用规则引擎自动推断关联关系
     */
    @PostMapping("/auto-detect")
    public Result<List<Map<String, Object>>> autoDetectRelationships(@RequestParam Long datasourceId) {
        try {
            List<Map<String, Object>> relationships = relationshipService.smartDetectRelationships(datasourceId);
            return Result.success(relationships);
        } catch (Exception e) {
            log.error("自动推断关联关系失败", e);
            return Result.error("推断失败: " + e.getMessage());
        }
    }
    
    /**
     * 从SQL中提取关联关系（委托给Service）
     */
    @PostMapping("/extract-from-sql")
    public Result<List<Map<String, Object>>> extractRelationshipsFromSQL(@RequestBody ExtractSQLRequest request) {
        try {
            List<Map<String, Object>> relationships = relationshipService.extractRelationshipsFromSQL(
                request.getSql(), 
                request.getDatasourceId()
            );
            return Result.success(relationships);
        } catch (IllegalArgumentException e) {
            log.warn("SQL提取参数错误: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("从SQL提取关联关系失败", e);
            return Result.error("提取失败: " + e.getMessage());
        }
    }
    
    /**
     * 智能提取关联关系（带优化建议）
     */
    @PostMapping("/extract-with-suggestions")
    public Result<Map<String, Object>> extractRelationshipsWithSuggestions(@RequestBody ExtractSQLRequest request) {
        try {
            Map<String, Object> result = relationshipService.extractRelationshipsWithSuggestions(
                request.getSql(), 
                request.getDatasourceId()
            );
            return Result.success(result);
        } catch (Exception e) {
            log.error("智能提取关联关系失败", e);
            return Result.error("提取失败: " + e.getMessage());
        }
    }
    
    /**
     * 批量保存关联关系（委托给Service）
     */
    @PostMapping("/batch-save")
    public Result<Void> batchSaveRelationships(@RequestBody BatchSaveRequest request) {
        try {
            if (request.getRelationships() == null || request.getRelationships().isEmpty()) {
                return Result.error("关联关系列表不能为空");
            }
            
            // 转换为Map格式供Service处理
            List<Map<String, Object>> relList = new ArrayList<>();
            for (RelationshipRequest rel : request.getRelationships()) {
                Map<String, Object> map = new HashMap<>();
                map.put("sourceTable", rel.getSourceTable());
                map.put("sourceColumn", rel.getSourceColumn());
                map.put("targetTable", rel.getTargetTable());
                map.put("targetColumn", rel.getTargetColumn());
                map.put("relationshipType", rel.getRelationshipType());
                relList.add(map);
            }
            
            int successCount = relationshipService.batchSaveRelationships(
                relList, 
                request.getRelationships().get(0).getDatasourceId()
            );
            
            log.info("批量保存成功: {} 条", successCount);
            return Result.success();
        } catch (Exception e) {
            log.error("批量保存关联关系失败", e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }
    
    /**
     * 自动生成标准格式的description
     */
    private String generateStandardDescription(String sourceTable, String sourceColumn, 
                                               String targetTable, String targetColumn, 
                                               Long datasourceId) {
        try {
            String sourceComment = jdbcTemplate.queryForObject(
                "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
                String.class, datasourceId, sourceTable
            );
            String targetComment = jdbcTemplate.queryForObject(
                "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
                String.class, datasourceId, targetTable
            );
            
            String sourceName = (sourceComment != null && !sourceComment.isEmpty()) ? sourceComment : sourceTable;
            String targetName = (targetComment != null && !targetComment.isEmpty()) ? targetComment : targetTable;
            
            return String.format("%s通过%s关联%s", sourceName, sourceColumn, targetName);
        } catch (Exception e) {
            log.warn("生成description失败，使用默认格式: {}", e.getMessage());
            return String.format("%s通过%s关联%s", sourceTable, sourceColumn, targetTable);
        }
    }
    
    @Data
    public static class RelationshipRequest {
        private Long datasourceId;
        private String sourceTable;
        private String sourceColumn;
        private String targetTable;
        private String targetColumn;
        private String relationshipType;
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
