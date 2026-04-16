package com.nl2sql.metadata.service;

import com.nl2sql.core.llm.MultiModelService;
import com.nl2sql.metadata.entity.TableRelationship;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TableRelationshipService {
    
    private final JdbcTemplate jdbcTemplate;
    private final MultiModelService multiModelService;
    
    public TableRelationshipService(JdbcTemplate jdbcTemplate, MultiModelService multiModelService) {
        this.jdbcTemplate = jdbcTemplate;
        this.multiModelService = multiModelService;
    }
    
    /**
     * 使用LLM自动发现表关联关系
     */
    public List<TableRelationship> autoDiscoverRelationships(Long datasourceId) {
        try {
            // 获取所有表的元数据
            String tableSql = "SELECT table_name, table_comment FROM table_metadata WHERE datasource_id = ?";
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql, datasourceId);
            
            if (tables.isEmpty()) {
                log.warn("数据源 {} 没有表元数据", datasourceId);
                return Collections.emptyList();
            }
            
            // ✅ 优化：批量查询所有表的字段信息（避免N次数据库查询）
            List<String> tableNames = tables.stream()
                .map(t -> (String) t.get("table_name"))
                .collect(Collectors.toList());
            
            String placeholders = tableNames.stream()
                .map(t -> "?")
                .collect(Collectors.joining(", "));
            
            String batchColSql = String.format(
                "SELECT table_name, column_name, data_type, column_comment, is_primary_key " +
                "FROM column_metadata WHERE datasource_id = ? AND table_name IN (%s) " +
                "ORDER BY table_name, ordinal_position",
                placeholders
            );
            
            Object[] params = new Object[tableNames.size() + 1];
            params[0] = datasourceId;
            for (int i = 0; i < tableNames.size(); i++) {
                params[i + 1] = tableNames.get(i);
            }
            
            List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(batchColSql, params);
            
            // 按表名分组
            Map<String, List<Map<String, Object>>> columnsByTable = allColumns.stream()
                .collect(Collectors.groupingBy(col -> (String) col.get("table_name")));
            
            // 构建表结构描述
            StringBuilder schemaDesc = new StringBuilder("数据库表结构：\n\n");
            for (Map<String, Object> table : tables) {
                String tableName = (String) table.get("table_name");
                String tableComment = (String) table.get("table_comment");
                
                schemaDesc.append(String.format("表名: %s\n", tableName));
                if (tableComment != null && !tableComment.isEmpty()) {
                    schemaDesc.append(String.format("注释: %s\n", tableComment));
                }
                
                // 从缓存的批量结果中获取字段
                List<Map<String, Object>> columns = columnsByTable.getOrDefault(tableName, Collections.emptyList());
                
                for (Map<String, Object> col : columns) {
                    schemaDesc.append(String.format("  - %s (%s)", 
                        col.get("column_name"), col.get("data_type")));
                    if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                        schemaDesc.append(" [主键]");
                    }
                    if (col.get("column_comment") != null) {
                        schemaDesc.append(String.format(" - %s", col.get("column_comment")));
                    }
                    schemaDesc.append("\n");
                }
                schemaDesc.append("\n");
            }
            
            // 调用LLM分析关联关系
            String prompt = String.format(
                "你是一个数据库专家。根据以下数据库表结构，分析表与表之间可能存在的关联关系。\n\n" +
                "%s\n" +
                "要求：\n" +
                "1. 只输出JSON数组格式，不要包含其他文字\n" +
                "2. 每个关联关系包含：source_table, source_column, target_table, target_column, relationship_type, confidence(0-1), description\n" +
                "3. relationship_type只能是：ONE_TO_ONE, MANY_TO_ONE, MANY_TO_MANY\n" +
                "4. 只返回高置信度(>0.7)的关联关系\n" +
                "5. 基于字段名、注释等推测关联，例如：user_id通常关联users表的id\n\n" +
                "示例输出：\n" +
                "[{\"source_table\":\"orders\",\"source_column\":\"user_id\",\"target_table\":\"users\",\"target_column\":\"id\",\"relationship_type\":\"MANY_TO_ONE\",\"confidence\":0.95,\"description\":\"订单表的用户ID关联用户表\"}]\n\n" +
                "请分析：",
                schemaDesc.toString()
            );
            
            String response = multiModelService.generateAnswer(prompt);
            log.info("LLM返回关联关系: {}", response);
            
            // 解析JSON响应（简化版，实际应使用JSON库）
            List<TableRelationship> relationships = parseLLMResponse(response, datasourceId);
            
            // 保存到数据库
            for (TableRelationship rel : relationships) {
                saveRelationship(rel);
            }
            
            log.info("自动发现完成，找到 {} 条关联关系", relationships.size());
            return relationships;
            
        } catch (Exception e) {
            log.error("自动发现关联关系失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取指定数据源的关联关系
     */
    public List<TableRelationship> getRelationships(Long datasourceId) {
        String sql = "SELECT * FROM table_relationships WHERE datasource_id = ? AND is_active = 1 ORDER BY confidence DESC";
        return jdbcTemplate.query(sql, new RelationshipRowMapper(), datasourceId);
    }
    
    /**
     * 获取指定表的关联关系
     */
    public List<TableRelationship> getRelationshipsByTable(Long datasourceId, String tableName) {
        String sql = "SELECT * FROM table_relationships WHERE datasource_id = ? AND (source_table = ? OR target_table = ?) AND is_active = 1 ORDER BY confidence DESC";
        return jdbcTemplate.query(sql, new RelationshipRowMapper(), datasourceId, tableName, tableName);
    }
    
    /**
     * 保存或更新关联关系
     */
    public boolean saveRelationship(TableRelationship relationship) {
        try {
            String checkSql = "SELECT COUNT(*) FROM table_relationships WHERE datasource_id = ? AND source_table = ? AND source_column = ? AND target_table = ? AND target_column = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class,
                relationship.getDatasourceId(),
                relationship.getSourceTable(),
                relationship.getSourceColumn(),
                relationship.getTargetTable(),
                relationship.getTargetColumn()
            );
            
            if (count != null && count > 0) {
                // 更新
                String updateSql = "UPDATE table_relationships SET relationship_type = ?, confidence = ?, description = ?, is_active = ? WHERE datasource_id = ? AND source_table = ? AND source_column = ? AND target_table = ? AND target_column = ?";
                jdbcTemplate.update(updateSql,
                    relationship.getRelationshipType(),
                    relationship.getConfidence(),
                    relationship.getDescription(),
                    relationship.getIsActive(),
                    relationship.getDatasourceId(),
                    relationship.getSourceTable(),
                    relationship.getSourceColumn(),
                    relationship.getTargetTable(),
                    relationship.getTargetColumn()
                );
            } else {
                // 插入
                String insertSql = "INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                jdbcTemplate.update(insertSql,
                    relationship.getDatasourceId(),
                    relationship.getSourceTable(),
                    relationship.getSourceColumn(),
                    relationship.getTargetTable(),
                    relationship.getTargetColumn(),
                    relationship.getRelationshipType(),
                    relationship.getConfidence(),
                    relationship.getDescription(),
                    relationship.getIsActive() != null ? relationship.getIsActive() : 1,
                    relationship.getCreatedBy()
                );
            }
            
            return true;
        } catch (Exception e) {
            log.error("保存关联关系失败", e);
            return false;
        }
    }
    
    /**
     * 删除关联关系
     */
    public boolean deleteRelationship(Long id) {
        try {
            jdbcTemplate.update("DELETE FROM table_relationships WHERE id = ?", id);
            return true;
        } catch (Exception e) {
            log.error("删除关联关系失败", e);
            return false;
        }
    }
    
    /**
     * 启用/禁用关联关系
     */
    public boolean toggleRelationship(Long id, boolean active) {
        try {
            jdbcTemplate.update("UPDATE table_relationships SET is_active = ? WHERE id = ?", active ? 1 : 0, id);
            return true;
        } catch (Exception e) {
            log.error("切换关联关系状态失败", e);
            return false;
        }
    }
    
    /**
     * 从 SQL中提取关联关系
     */
    public List<Map<String, Object>> extractRelationshipsFromSQL(String sql, Long datasourceId) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL不能为空");
        }
        
        if (datasourceId == null) {
            throw new IllegalArgumentException("数据源ID不能为空");
        }
        
        log.info("开始从 SQL提取关联关系: datasourceId={}, SQL={}", datasourceId, sql.substring(0, Math.min(100, sql.length())));
        
        // ✅ 验证 SQL 是否包含表关联（支持多种关联方式）
        String upperSQL = sql.toUpperCase();
        boolean hasJoin = upperSQL.contains("JOIN") || upperSQL.matches(".*FROM\\s+\\w+\\s*,\\s*\\w+.*"); // 隐式JOIN
        boolean hasSubquery = upperSQL.contains("IN (SELECT") || upperSQL.contains("EXISTS (SELECT") || 
                             upperSQL.contains("= (SELECT") || upperSQL.contains("= ANY (SELECT") || 
                             upperSQL.contains("= ALL (SELECT");
        
        if (!hasJoin && !hasSubquery) {
            throw new IllegalArgumentException("SQL必须包含JOIN、隐式关联或子查询才能提取关联关系");
        }
        
        List<Map<String, Object>> relationships = new ArrayList<>();
        
        // 匹配 JOIN ... ON 模式（支持 LEFT/RIGHT/INNER/FULL JOIN）
        java.util.regex.Pattern joinPattern = java.util.regex.Pattern.compile(
            "\\b(?:LEFT|RIGHT|INNER|OUTER|FULL|CROSS)?\\s*JOIN\\s+(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = joinPattern.matcher(sql);
        
        while (matcher.find()) {
            String tableName = matcher.group(1);
            String tableAlias = matcher.group(2);
            String leftTableRef = matcher.group(3);
            String leftColumn = matcher.group(4);
            String rightTableRef = matcher.group(5);
            String rightColumn = matcher.group(6);
            
            // 解析别名到真实表名的映射
            Map<String, String> aliasMap = extractTableAliases(sql);
            String realLeftTable = aliasMap.getOrDefault(leftTableRef, leftTableRef);
            String realRightTable = aliasMap.getOrDefault(rightTableRef, rightTableRef);
            
            // ✅ 验证表是否存在于数据源中
            if (!validateTableExists(realLeftTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realLeftTable, datasourceId);
                continue;
            }
            if (!validateTableExists(realRightTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realRightTable, datasourceId);
                continue;
            }
            
            Map<String, Object> rel = new HashMap<>();
            rel.put("sourceTable", realLeftTable);
            rel.put("sourceColumn", leftColumn);
            rel.put("targetTable", realRightTable);
            rel.put("targetColumn", rightColumn);
            rel.put("relationshipType", "MANY_TO_ONE");
            
            relationships.add(rel);
        }
        
        // 匹配 WHERE ... IN (SELECT ...) 模式（子查询关联）
        java.util.regex.Pattern subqueryPattern = java.util.regex.Pattern.compile(
            "\\b(\\w+)\\.(\\w+)\\s+IN\\s*\\(\\s*SELECT\\s+(\\w+)\\s+FROM\\s+(\\w+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        matcher = subqueryPattern.matcher(sql);
        
        while (matcher.find()) {
            String sourceTableRef = matcher.group(1);
            String sourceColumn = matcher.group(2);
            String targetColumn = matcher.group(3);
            String targetTableRef = matcher.group(4);
            
            Map<String, String> aliasMap = extractTableAliases(sql);
            String realSourceTable = aliasMap.getOrDefault(sourceTableRef, sourceTableRef);
            String realTargetTable = aliasMap.getOrDefault(targetTableRef, targetTableRef);
            
            // ✅ 验证表是否存在于数据源中
            if (!validateTableExists(realSourceTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realSourceTable, datasourceId);
                continue;
            }
            if (!validateTableExists(realTargetTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realTargetTable, datasourceId);
                continue;
            }
            
            Map<String, Object> rel = new HashMap<>();
            rel.put("sourceTable", realSourceTable);
            rel.put("sourceColumn", sourceColumn);
            rel.put("targetTable", realTargetTable);
            rel.put("targetColumn", targetColumn);
            rel.put("relationshipType", "MANY_TO_ONE");
            rel.put("warning", "子查询关联，建议改为直接JOIN");
            
            relationships.add(rel);
        }
        
        // ✅ 匹配隐式JOIN（逗号分隔 + WHERE关联条件）
        // 例如：FROM orders o, users u WHERE o.user_id = u.id
        java.util.regex.Pattern implicitJoinPattern = java.util.regex.Pattern.compile(
            "\\bFROM\\s+(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s*,\\s*(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s+WHERE.*?(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
        );
        matcher = implicitJoinPattern.matcher(sql);
        
        while (matcher.find()) {
            String table1 = matcher.group(1);
            String alias1 = matcher.group(2);
            String table2 = matcher.group(3);
            String alias2 = matcher.group(4);
            String leftRef = matcher.group(5);
            String leftCol = matcher.group(6);
            String rightRef = matcher.group(7);
            String rightCol = matcher.group(8);
            
            Map<String, String> aliasMap = extractTableAliases(sql);
            String realLeftTable = aliasMap.getOrDefault(leftRef, leftRef);
            String realRightTable = aliasMap.getOrDefault(rightRef, rightRef);
            
            // ✅ 验证表是否存在于数据源中
            if (!validateTableExists(realLeftTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realLeftTable, datasourceId);
                continue;
            }
            if (!validateTableExists(realRightTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", realRightTable, datasourceId);
                continue;
            }
            
            Map<String, Object> rel = new HashMap<>();
            rel.put("sourceTable", realLeftTable);
            rel.put("sourceColumn", leftCol);
            rel.put("targetTable", realRightTable);
            rel.put("targetColumn", rightCol);
            rel.put("relationshipType", "MANY_TO_ONE");
            rel.put("warning", "隐式JOIN，建议改为显式JOIN提高可读性");
            
            relationships.add(rel);
        }
        
        // ✅ 匹配 EXISTS/NOT EXISTS 子查询关联
        // 例如：WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = o.user_id)
        java.util.regex.Pattern existsPattern = java.util.regex.Pattern.compile(
            "\\b(?:NOT\\s+)?EXISTS\\s*\\(\\s*SELECT\\s+.*?\\bFROM\\s+(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s+WHERE.*?(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
        );
        matcher = existsPattern.matcher(sql);
        
        while (matcher.find()) {
            String subqueryTable = matcher.group(1);
            String subqueryAlias = matcher.group(2);
            String leftRef = matcher.group(3);
            String leftCol = matcher.group(4);
            String rightRef = matcher.group(5);
            String rightCol = matcher.group(6);
            
            Map<String, String> aliasMap = extractTableAliases(sql);
            String realSubqueryTable = aliasMap.getOrDefault(subqueryTable, subqueryTable);
            String realLeftTable = aliasMap.getOrDefault(leftRef, leftRef);
            String realRightTable = aliasMap.getOrDefault(rightRef, rightRef);
            
            // 确定哪一个是子查询表，哪一个是外部表
            String sourceTable, sourceColumn, targetTable, targetColumn;
            
            // 如果左边的表引用是子查询表，则子查询表是目标表
            if (realLeftTable.equals(realSubqueryTable) || leftRef.equals(subqueryAlias)) {
                sourceTable = realRightTable;
                sourceColumn = rightCol;
                targetTable = realLeftTable;
                targetColumn = leftCol;
            } else {
                sourceTable = realLeftTable;
                sourceColumn = leftCol;
                targetTable = realRightTable;
                targetColumn = rightCol;
            }
            
            // ✅ 验证表是否存在于数据源中
            if (!validateTableExists(sourceTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", sourceTable, datasourceId);
                continue;
            }
            if (!validateTableExists(targetTable, datasourceId)) {
                log.warn("表 {} 在数据源 {} 中不存在，跳过", targetTable, datasourceId);
                continue;
            }
            
            Map<String, Object> rel = new HashMap<>();
            rel.put("sourceTable", sourceTable);
            rel.put("sourceColumn", sourceColumn);
            rel.put("targetTable", targetTable);
            rel.put("targetColumn", targetColumn);
            rel.put("relationshipType", "MANY_TO_ONE");
            rel.put("warning", "EXISTS子查询关联，建议改为直接JOIN提高性能");
            
            relationships.add(rel);
        }
        
        // ✅ 去重（基于表名和字段名）
        List<Map<String, Object>> uniqueRelationships = relationships.stream()
            .filter(distinctByKey(rel -> 
                rel.get("sourceTable") + "." + rel.get("sourceColumn") + "->" + 
                rel.get("targetTable") + "." + rel.get("targetColumn")
            ))
            .collect(Collectors.toList());
        
        log.info("从 SQL中提取到 {} 条关联关系（去重后 {} 条）", relationships.size(), uniqueRelationships.size());
        return uniqueRelationships;
    }
    
    /**
     * 批量保存关联关系
     */
    public int batchSaveRelationships(List<Map<String, Object>> relationships, Long datasourceId) {
        if (relationships == null || relationships.isEmpty()) {
            return 0;
        }
        
        int successCount = 0;
        int skipCount = 0;
        
        for (Map<String, Object> rel : relationships) {
            String sourceTable = (String) rel.get("sourceTable");
            String sourceColumn = (String) rel.get("sourceColumn");
            String targetTable = (String) rel.get("targetTable");
            String targetColumn = (String) rel.get("targetColumn");
            String relationshipType = (String) rel.getOrDefault("relationshipType", "MANY_TO_ONE");
            
            // 检查是否已存在
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM table_relationships WHERE datasource_id = ? AND source_table = ? AND source_column = ? AND target_table = ? AND target_column = ?",
                Integer.class,
                datasourceId, sourceTable, sourceColumn, targetTable, targetColumn
            );
            
            if (count != null && count > 0) {
                skipCount++;
                continue;
            }
            
            // ✅ 自动生成标准格式的 description
            String autoDescription = generateStandardDescription(
                sourceTable, sourceColumn, targetTable, targetColumn, datasourceId
            );
            
            jdbcTemplate.update(
                "INSERT INTO table_relationships (datasource_id, source_table, source_column, target_table, target_column, relationship_type, confidence, description, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                datasourceId, sourceTable, sourceColumn, targetTable, targetColumn,
                relationshipType, 1, autoDescription
            );
            
            successCount++;
        }
        
        log.info("批量保存关联关系: 成功={}, 跳过={}", successCount, skipCount);
        return successCount;
    }
    
    /**
     * 验证表是否存在于数据源中
     */
    private boolean validateTableExists(String tableName, Long datasourceId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND table_name = ?",
                Integer.class, datasourceId, tableName
            );
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("验证表 {} 是否存在失败: {}", tableName, e.getMessage());
            return false;
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
    
    /**
     * 去重辅助方法
     */
    private static <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        java.util.Set<Object> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }
    
    /**
     * 自动生成标准格式的 description
     * 格式：{源表注释}通过{源字段}关联{目标表注释}
     */
    private String generateStandardDescription(String sourceTable, String sourceColumn, 
                                               String targetTable, String targetColumn, 
                                               Long datasourceId) {
        try {
            // 查询表注释
            String sourceComment = jdbcTemplate.queryForObject(
                "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
                String.class, datasourceId, sourceTable
            );
            String targetComment = jdbcTemplate.queryForObject(
                "SELECT table_comment FROM table_metadata WHERE datasource_id = ? AND table_name = ?",
                String.class, datasourceId, targetTable
            );
            
            // 使用表注释，如果没有则使用表名
            String sourceName = (sourceComment != null && !sourceComment.isEmpty()) ? sourceComment : sourceTable;
            String targetName = (targetComment != null && !targetComment.isEmpty()) ? targetComment : targetTable;
            
            return String.format("%s通过%s关联%s", sourceName, sourceColumn, targetName);
        } catch (Exception e) {
            log.warn("生成 description 失败，使用默认格式: {}", e.getMessage());
            return String.format("%s通过%s关联%s", sourceTable, sourceColumn, targetTable);
        }
    }
    
    /**
     * 获取用于Prompt的关联关系描述
     */
    public String getRelationshipsForPrompt(Long datasourceId, List<String> tables) {
        if (datasourceId == null || tables == null || tables.isEmpty()) {
            return "";
        }
        
        try {
            String tableList = tables.stream()
                .map(t -> "'" + t.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
            
            String sql = String.format(
                "SELECT source_table, source_column, target_table, target_column, relationship_type, description " +
                "FROM table_relationships " +
                "WHERE datasource_id = %d AND is_active = 1 " +
                "AND (source_table IN (%s) OR target_table IN (%s))",
                datasourceId, tableList, tableList
            );
            
            log.info("[TableRelationship] 查询关联关系: datasourceId={}, tables={}", datasourceId, tables);
            log.info("[TableRelationship] SQL: {}", sql);
            
            List<Map<String, Object>> relationships = jdbcTemplate.queryForList(sql);
            
            log.info("[TableRelationship] 找到 {} 条关联关系", relationships.size());
            
            if (relationships.isEmpty()) {
                return "";
            }
            
            // ✅ 优化：二次过滤，只保留两端都在tables列表中的关联关系
            Set<String> tableSet = new HashSet<>(tables);
            List<Map<String, Object>> filteredRelationships = relationships.stream()
                .filter(rel -> {
                    String sourceTable = (String) rel.get("source_table");
                    String targetTable = (String) rel.get("target_table");
                    return tableSet.contains(sourceTable) && tableSet.contains(targetTable);
                })
                .collect(Collectors.toList());
            
            if (filteredRelationships.isEmpty()) {
                log.info("[TableRelationship] 过滤后无有效关联关系");
                return "";
            }
            
            log.info("[TableRelationship] 过滤后剩余 {} 条关联关系", filteredRelationships.size());
            
            StringBuilder sb = new StringBuilder("\n\n表之间的关联关系：\n");
            for (Map<String, Object> rel : filteredRelationships) {
                sb.append(String.format("- %s.%s -> %s.%s (%s): %s\n",
                    rel.get("source_table"),
                    rel.get("source_column"),
                    rel.get("target_table"),
                    rel.get("target_column"),
                    rel.get("relationship_type"),
                    rel.get("description") != null ? rel.get("description") : ""
                ));
            }
            
            String result = sb.toString();
            log.info("[TableRelationship] 返回的关联关系信息:\n{}", result);
            return result;
        } catch (Exception e) {
            log.warn("获取关联关系失败: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * 解析LLM返回的JSON
     */
    private List<TableRelationship> parseLLMResponse(String response, Long datasourceId) {
        List<TableRelationship> relationships = new ArrayList<>();
        
        try {
            // 提取JSON数组部分
            int start = response.indexOf("[");
            int end = response.lastIndexOf("]");
            if (start == -1 || end == -1) {
                log.warn("LLM响应不包含有效的JSON数组");
                return relationships;
            }
            
            String jsonStr = response.substring(start, end + 1);
            
            // 简化解析：按对象分割
            String[] objects = jsonStr.split("\\},\\s*\\{");
            for (String obj : objects) {
                obj = obj.replaceAll("[\\[\\]{}]", "").trim();
                
                TableRelationship rel = new TableRelationship();
                rel.setDatasourceId(datasourceId);
                rel.setIsActive(1);
                
                // 简单字符串解析（生产环境应使用Jackson/Gson）
                String[] fields = obj.split(",");
                for (String field : fields) {
                    String[] kv = field.split(":");
                    if (kv.length == 2) {
                        String key = kv[0].trim().replaceAll("\"", "");
                        String value = kv[1].trim().replaceAll("\"", "");
                        
                        switch (key) {
                            case "source_table":
                                rel.setSourceTable(value);
                                break;
                            case "source_column":
                                rel.setSourceColumn(value);
                                break;
                            case "target_table":
                                rel.setTargetTable(value);
                                break;
                            case "target_column":
                                rel.setTargetColumn(value);
                                break;
                            case "relationship_type":
                                rel.setRelationshipType(value);
                                break;
                            case "confidence":
                                rel.setConfidence(Float.parseFloat(value));
                                break;
                            case "description":
                                rel.setDescription(value);
                                break;
                        }
                    }
                }
                
                if (rel.getSourceTable() != null && rel.getTargetTable() != null) {
                    relationships.add(rel);
                }
            }
            
        } catch (Exception e) {
            log.error("解析LLM响应失败", e);
        }
        
        return relationships;
    }
    
    /**
     * RowMapper
     */
    private static class RelationshipRowMapper implements RowMapper<TableRelationship> {
        @Override
        public TableRelationship mapRow(ResultSet rs, int rowNum) throws SQLException {
            TableRelationship rel = new TableRelationship();
            rel.setId(rs.getLong("id"));
            rel.setDatasourceId(rs.getLong("datasource_id"));
            rel.setSourceTable(rs.getString("source_table"));
            rel.setSourceColumn(rs.getString("source_column"));
            rel.setTargetTable(rs.getString("target_table"));
            rel.setTargetColumn(rs.getString("target_column"));
            rel.setRelationshipType(rs.getString("relationship_type"));
            rel.setConfidence(rs.getFloat("confidence"));
            rel.setDescription(rs.getString("description"));
            rel.setIsActive(rs.getInt("is_active"));
            rel.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            rel.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                rs.getTimestamp("created_at").toLocalDateTime() : null);
            rel.setUpdatedAt(rs.getTimestamp("updated_at") != null ? 
                rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return rel;
        }
    }
    
    /**
     * 智能提取关联关系（带优化建议）
     * 
     * @param sql SQL语句
     * @param datasourceId 数据源ID
     * @return 提取结果（包含关联关系和优化建议）
     */
    public Map<String, Object> extractRelationshipsWithSuggestions(String sql, Long datasourceId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> relationships = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        
        try {
            // 尝试提取
            relationships = extractRelationshipsFromSQL(sql, datasourceId);
            
            // 分析SQL复杂度并给出建议
            analyzeSQLComplexity(sql, suggestions);
            
            result.put("success", true);
            result.put("relationships", relationships);
            result.put("suggestions", suggestions);
            result.put("extractedCount", relationships.size());
            
        } catch (IllegalArgumentException e) {
            // SQL格式问题
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("suggestions", generateFormatSuggestions(sql));
            
        } catch (Exception e) {
            // 提取失败
            log.warn("SQL提取失败，可能过于复杂: {}", e.getMessage());
            result.put("success", false);
            result.put("error", "SQL过于复杂，无法自动提取关联关系");
            result.put("suggestions", generateSimplificationSuggestions(sql));
        }
        
        return result;
    }
    
    /**
     * 分析SQL复杂度并生成优化建议
     */
    private void analyzeSQLComplexity(String sql, List<String> suggestions) {
        String upperSQL = sql.toUpperCase();
        int complexityScore = 0; // 复杂度评分
        
        // 检测多层嵌套（SELECT数量）
        int selectCount = upperSQL.split("SELECT").length - 1;
        if (selectCount > 3) {
            complexityScore += 3;
            suggestions.add("⚠️ 检测到多层嵌套子查询（" + selectCount + "层），建议拆分为多个简单查询或使用CTE\n" +
                          "   复杂嵌套可能导致关联关系提取不完整");
        } else if (selectCount > 2) {
            complexityScore += 2;
            suggestions.add("💡 SQL包含" + selectCount + "层嵌套，如果提取结果不准确，建议简化SQL结构");
        }
        
        // 检测EXISTS子查询（较复杂）
        if (upperSQL.contains("EXISTS")) {
            complexityScore += 2;
            suggestions.add("💡 检测到EXISTS子查询，虽然可以提取关联，但如果性能不佳可考虑改为JOIN");
        }
        
        // 检测非等值关联（难以准确提取）
        if (upperSQL.matches(".*LIKE.*CONCAT.*") || upperSQL.matches(".*DATE\\(.*\\).*=") || 
            upperSQL.matches(".*UPPER\\(.*\\).*=") || upperSQL.matches(".*LOWER\\(.*\\).*=")) {
            complexityScore += 3;
            suggestions.add("⚠️ 检测到函数转换或模糊匹配关联，程序可能无法准确提取\n" +
                          "   建议：如果可能，使用标准的等值关联（a.id = b.user_id）");
        }
        
        // 检测多个JOIN（超过5个表）
        int joinCount = upperSQL.split("JOIN").length - 1;
        if (joinCount > 5) {
            complexityScore += 2;
            suggestions.add("💡 SQL涉及" + (joinCount + 1) + "个表的关联，建议检查是否真的需要这么多表");
        }
        
        // 检测CASE WHEN复杂逻辑
        if (upperSQL.contains("CASE") && upperSQL.contains("WHEN")) {
            int caseCount = upperSQL.split("CASE").length - 1;
            if (caseCount > 2) {
                complexityScore += 1;
                suggestions.add("💡 检测到复杂的CASE WHEN逻辑，可能影响关联关系识别");
            }
        }
        
        // 检测UNION（多查询合并）
        if (upperSQL.contains("UNION")) {
            complexityScore += 2;
            suggestions.add("💡 检测到UNION操作，只会提取第一个查询的关联关系");
        }
        
        // 根据复杂度评分决定是否显示建议
        if (complexityScore >= 3) {
            // 高复杂度：必须提示
            suggestions.add(0, "📊 SQL复杂度评估：较高（" + complexityScore + "分）");
        } else if (complexityScore >= 2) {
            // 中等复杂度：友好提示
            suggestions.add(0, "📊 SQL复杂度评估：中等（" + complexityScore + "分）");
        } else {
            // 低复杂度：不显示任何建议，说明SQL很规范
            suggestions.clear();
            suggestions.add("✅ SQL格式规范，关联关系清晰");
        }
    }
    
    /**
     * 为格式错误的SQL生成建议
     */
    private List<String> generateFormatSuggestions(String sql) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("❌ SQL格式不符合要求，请确保包含以下之一：");
        suggestions.add("   • 显式JOIN: SELECT * FROM orders o JOIN users u ON o.user_id = u.id");
        suggestions.add("   • 隐式JOIN: SELECT * FROM orders o, users u WHERE o.user_id = u.id");
        suggestions.add("   • IN子查询: SELECT * FROM orders WHERE user_id IN (SELECT id FROM users)");
        suggestions.add("   • EXISTS子查询: SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = o.user_id)");
        return suggestions;
    }
    
    /**
     * 为复杂SQL生成简化建议
     */
    private List<String> generateSimplificationSuggestions(String sql) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("⚠️ SQL过于复杂，建议采用以下方式简化：");
        suggestions.add("   1. 将复杂子查询拆分为多个简单查询");
        suggestions.add("   2. 使用CTE（WITH子句）提高可读性");
        suggestions.add("   3. 优先使用显式JOIN而非子查询");
        suggestions.add("   4. 避免多层嵌套（超过2层）");
        suggestions.add("");
        suggestions.add("💡 如果坚持使用当前SQL，可以尝试手动添加关联关系");
        return suggestions;
    }
}
