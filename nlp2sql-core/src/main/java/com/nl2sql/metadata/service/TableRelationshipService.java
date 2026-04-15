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
}
