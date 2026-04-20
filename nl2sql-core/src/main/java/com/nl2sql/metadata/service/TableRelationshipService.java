package com.nl2sql.metadata.service;

import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.llm.MultiModelService;
import com.nl2sql.metadata.entity.TableRelationship;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    
    @Autowired(required = false)
    private MetadataCacheService metadataCacheService;
    
    @Value("${llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${llm.ollama.code-model:qwen2.5-coder:7b-instruct-q4_0}")
    private String ollamaCodeModel;
    
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
     * 使用规则引擎自动推断关联关系（基于字段名模式匹配）
     */
    public List<Map<String, Object>> autoDetectRelationships(Long datasourceId) {
        try {
            log.info("开始自动推断关联关系: datasourceId={}", datasourceId);
            
            List<Map<String, Object>> detectedRelationships = new ArrayList<>();
            
            // ✅ 优先：查询数据库真实外键约束
            List<Map<String, Object>> realForeignKeys = queryRealForeignKeys(datasourceId);
            if (!realForeignKeys.isEmpty()) {
                log.info("发现 {} 个真实外键约束", realForeignKeys.size());
                detectedRelationships.addAll(realForeignKeys);
            }
            
            // 其次：基于字段名模式匹配推断
            List<Map<String, Object>> patternBasedRels = detectByPattern(datasourceId);
            log.info("规则引擎推断完成，发现 {} 条关联关系（真实外键{} + 模式匹配{}）", 
                detectedRelationships.size() + patternBasedRels.size(),
                realForeignKeys.size(),
                patternBasedRels.size());
            
            detectedRelationships.addAll(patternBasedRels);
            return detectedRelationships;
            
        } catch (Exception e) {
            log.error("规则引擎推断失败", e);
            throw new RuntimeException("推断失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * ✅ 新增：查询数据库真实外键约束
     */
    private List<Map<String, Object>> queryRealForeignKeys(Long datasourceId) {
        List<Map<String, Object>> relationships = new ArrayList<>();
        
        try {
            // 获取数据源配置以查询information_schema
            String schemaName = jdbcTemplate.queryForObject(
                "SELECT database_name FROM datasource_config WHERE id = ?",
                String.class, datasourceId
            );
            
            if (schemaName == null || schemaName.trim().isEmpty()) {
                log.warn("无法获取数据源 {} 的schema名称", datasourceId);
                return relationships;
            }
            
            // 查询MySQL真实外键（包含字段类型）
            String fkSql = """
                SELECT 
                    kcu.TABLE_NAME AS source_table,
                    kcu.COLUMN_NAME AS source_column,
                    kcu.REFERENCED_TABLE_NAME AS target_table,
                    kcu.REFERENCED_COLUMN_NAME AS target_column,
                    c1.DATA_TYPE AS source_type,
                    c2.DATA_TYPE AS target_type
                FROM information_schema.KEY_COLUMN_USAGE kcu
                JOIN information_schema.COLUMNS c1 
                    ON kcu.TABLE_SCHEMA = c1.TABLE_SCHEMA 
                    AND kcu.TABLE_NAME = c1.TABLE_NAME 
                    AND kcu.COLUMN_NAME = c1.COLUMN_NAME
                JOIN information_schema.COLUMNS c2 
                    ON kcu.REFERENCED_TABLE_SCHEMA = c2.TABLE_SCHEMA 
                    AND kcu.REFERENCED_TABLE_NAME = c2.TABLE_NAME 
                    AND kcu.REFERENCED_COLUMN_NAME = c2.COLUMN_NAME
                WHERE kcu.TABLE_SCHEMA = ?
                  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY kcu.TABLE_NAME, kcu.COLUMN_NAME
                """;
            
            List<Map<String, Object>> fkRows = jdbcTemplate.queryForList(fkSql, schemaName);
            
            for (Map<String, Object> row : fkRows) {
                String sourceType = (String) row.get("source_type");
                String targetType = (String) row.get("target_type");
                
                // ✅ 关键校验：字段类型必须一致
                if (!isCompatibleType(sourceType, targetType)) {
                    log.warn("跳过类型不匹配的外键: {}.{}({}) -> {}.{}({})", 
                        row.get("source_table"), row.get("source_column"), sourceType,
                        row.get("target_table"), row.get("target_column"), targetType);
                    continue;
                }
                
                Map<String, Object> rel = new HashMap<>();
                rel.put("sourceTable", row.get("source_table"));
                rel.put("sourceColumn", row.get("source_column"));
                rel.put("targetTable", row.get("target_table"));
                rel.put("targetColumn", row.get("target_column"));
                rel.put("relationshipType", null); // ✅ 废弃字段，统一为NULL
                rel.put("confidence", 1.0); // 真实外键，置信度100%
                rel.put("description", String.format("数据库外键约束: %s.%s(%s) -> %s.%s(%s)",
                    row.get("source_table"), row.get("source_column"), sourceType,
                    row.get("target_table"), row.get("target_column"), targetType));
                rel.put("method", "real_foreign_key"); // 标记来源
                
                relationships.add(rel);
                log.debug("发现真实外键: {}.{} -> {}.{}", 
                    row.get("source_table"), row.get("source_column"),
                    row.get("target_table"), row.get("target_column"));
            }
            
        } catch (Exception e) {
            log.warn("查询真实外键失败（可能不支持或无权限）: {}", e.getMessage());
        }
        
        return relationships;
    }
    
    /**
     * ✅ 提取：基于字段名模式匹配推断关联关系
     */
    private List<Map<String, Object>> detectByPattern(Long datasourceId) {
        List<Map<String, Object>> detectedRelationships = new ArrayList<>();
        
        try {
            // 获取所有表名
            List<String> tables = jdbcTemplate.queryForList(
                "SELECT DISTINCT table_name FROM column_metadata WHERE datasource_id = ?",
                String.class, datasourceId
            );
            
            if (tables.isEmpty()) {
                log.warn("未找到任何表");
                return detectedRelationships;
            }
            
            log.info("共 {} 个表，开始模式匹配分析...", tables.size());
            
            // 基于字段名模式匹配推断关联关系
            for (String sourceTable : tables) {
                // 获取源表的所有字段
                List<Map<String, Object>> sourceColumns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type, is_primary_key FROM column_metadata WHERE datasource_id = ? AND table_name = ?",
                    datasourceId, sourceTable
                );
                
                for (Map<String, Object> sourceCol : sourceColumns) {
                    String sourceColumn = (String) sourceCol.get("column_name");
                    
                    // 跳过主键（除非是复合主键）
                    if ("1".equals(String.valueOf(sourceCol.get("is_primary_key")))) {
                        continue;
                    }
                    
                    // 检测外键模式：xxx_id
                    if (sourceColumn.endsWith("_id")) {
                        String potentialTargetTable = sourceColumn.substring(0, sourceColumn.length() - 3);
                        
                        // 查找匹配的表
                        for (String targetTable : tables) {
                            if (targetTable.equalsIgnoreCase(potentialTargetTable) || 
                                targetTable.toLowerCase().contains(potentialTargetTable.toLowerCase())) {
                                
                                // 验证目标表是否有对应的主键
                                Integer pkCount = jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND table_name = ? AND column_name = 'id' AND is_primary_key = 1",
                                    Integer.class, datasourceId, targetTable
                                );
                                
                                if (pkCount != null && pkCount > 0) {
                                    // ✅ 关键校验：字段类型必须一致
                                    String sourceDataType = (String) sourceCol.get("data_type");
                                    String targetDataType = getTargetColumnDataType(datasourceId, targetTable, "id");
                                    
                                    if (!isCompatibleType(sourceDataType, targetDataType)) {
                                        log.debug("跳过类型不匹配的关联: {}.{}({}) -> {}.id({})", 
                                            sourceTable, sourceColumn, sourceDataType,
                                            targetTable, targetDataType);
                                        continue;
                                    }
                                    
                                    Map<String, Object> relationship = new HashMap<>();
                                    relationship.put("sourceTable", sourceTable);
                                    relationship.put("sourceColumn", sourceColumn);
                                    relationship.put("targetTable", targetTable);
                                    relationship.put("targetColumn", "id");
                                    relationship.put("relationshipType", null); // ✅ 废弃字段，统一为NULL
                                    relationship.put("confidence", 0.8);
                                    relationship.put("description", generateStandardDescription(
                                        sourceTable, sourceColumn, targetTable, "id", datasourceId
                                    ));
                                    relationship.put("method", "rule_based"); // 标记来源
                                    
                                    detectedRelationships.add(relationship);
                                    log.info("检测到关联: {}.{} -> {}.id", sourceTable, sourceColumn, targetTable);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            
            log.info("规则引擎推断完成，发现 {} 条关联关系", detectedRelationships.size());
            return detectedRelationships;
        } catch (Exception e) {
            log.error("规则引擎推断失败", e);
            throw new RuntimeException("推断失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 智能推断：合并规则引擎和LLM的结果
     */
    public List<Map<String, Object>> smartDetectRelationships(Long datasourceId) {
        log.info("开始智能推断（规则引擎 + LLM）: datasourceId={}", datasourceId);
        
        // 1. 规则引擎快速推断
        List<Map<String, Object>> ruleBasedResults = autoDetectRelationships(datasourceId);
        log.info("规则引擎发现 {} 条关联", ruleBasedResults.size());
        
        // 2. LLM语义分析
        List<Map<String, Object>> llmResults = new ArrayList<>();
        try {
            List<TableRelationship> llmRelationships = autoDiscoverRelationshipsWithoutSave(datasourceId);
            for (TableRelationship rel : llmRelationships) {
                Map<String, Object> map = new HashMap<>();
                map.put("sourceTable", rel.getSourceTable());
                map.put("sourceColumn", rel.getSourceColumn());
                map.put("targetTable", rel.getTargetTable());
                map.put("targetColumn", rel.getTargetColumn());
                map.put("relationshipType", rel.getRelationshipType());
                map.put("confidence", rel.getConfidence());
                map.put("description", rel.getDescription());
                map.put("method", "llm_based"); // 标记来源
                llmResults.add(map);
            }
            log.info("LLM发现 {} 条关联", llmResults.size());
        } catch (Exception e) {
            log.warn("LLM推断失败，仅使用规则引擎结果: {}", e.getMessage());
        }
        
        // 3. 合并结果（检测冲突）
        Map<String, Map<String, Object>> mergedMap = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        
        // 先添加规则引擎结果
        for (Map<String, Object> rel : ruleBasedResults) {
            String key = generateRelationshipKey(rel);
            mergedMap.put(key, rel);
        }
        
        // 再检查LLM结果
        for (Map<String, Object> rel : llmResults) {
            String key = generateRelationshipKey(rel);
            if (!mergedMap.containsKey(key)) {
                // 无冲突，直接添加
                mergedMap.put(key, rel);
            } else {
                // 检测到冲突，标记为需要用户确认
                Map<String, Object> existing = mergedMap.get(key);
                Map<String, Object> conflict = new HashMap<>();
                conflict.put("key", key);
                conflict.put("ruleBased", existing);
                conflict.put("llmBased", rel);
                conflict.put("hasConflict", true);
                conflicts.add(conflict);
                
                log.warn("检测到冲突: {}", key);
            }
        }
        
        List<Map<String, Object>> finalResults = new ArrayList<>(mergedMap.values());
        
        // 如果有冲突，在返回结果中添加冲突信息
        if (!conflicts.isEmpty()) {
            Map<String, Object> resultWithConflicts = new HashMap<>();
            resultWithConflicts.put("relationships", finalResults);
            resultWithConflicts.put("conflicts", conflicts);
            resultWithConflicts.put("hasConflicts", true);
            resultWithConflicts.put("conflictCount", conflicts.size());
            
            log.info("智能推断完成，共 {} 条关联关系，{} 个冲突需用户确认", 
                finalResults.size(), conflicts.size());
            
            // 返回包含冲突信息的特殊格式
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("data", resultWithConflicts);
            wrapper.put("_hasConflicts", true);
            return Collections.singletonList(wrapper);
        }
        
        log.info("智能推断完成，共 {} 条关联关系", finalResults.size());
        return finalResults;
    }
    
    /**
     * LLM自动发现但不保存（用于合并）
     */
    private List<TableRelationship> autoDiscoverRelationshipsWithoutSave(Long datasourceId) {
        try {
            // 获取所有表的元数据
            String tableSql = "SELECT table_name, table_comment FROM table_metadata WHERE datasource_id = ?";
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(tableSql, datasourceId);
            
            if (tables.isEmpty()) {
                return Collections.emptyList();
            }
            
            log.info("[TableRelationship] 开始LLM推断关联关系，共 {} 个表", tables.size());
            
            // ✅ 关键优化：如果表太多，使用两阶段策略避免超时和遗漏
            int maxTablesForLLM = 8; // LLM最多处理8个表
            if (tables.size() > maxTablesForLLM) {
                log.info("[TableRelationship] 表数量较多({})，使用两阶段策略：规则引擎全覆盖 + LLM重点分析", tables.size());
                return twoPhaseAnalysis(datasourceId, tables, maxTablesForLLM);
            }
            
            // 表数量较少，直接分析
            return analyzeSingleBatch(datasourceId, tables);
            
        } catch (Exception e) {
            log.error("LLM自动发现关联关系失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * ✅ 新增：两阶段分析策略（避免跨批次遗漏）
     * 
     * 阶段1：规则引擎快速扫描所有表（无遗漏）
     * 阶段2：LLM深度分析高价值表组合（高质量）
     */
    private List<TableRelationship> twoPhaseAnalysis(Long datasourceId, 
                                                      List<Map<String, Object>> allTables,
                                                      int maxLLMTables) {
        List<TableRelationship> allRelationships = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>(); // 去重
        
        // ========== 阶段1：规则引擎全覆盖 ==========
        log.info("[TableRelationship] 阶段1：规则引擎扫描所有表...");
        try {
            List<Map<String, Object>> ruleResults = autoDetectRelationships(datasourceId);
            for (Map<String, Object> rel : ruleResults) {
                TableRelationship tr = convertToTableRelationship(rel, datasourceId);
                if (tr != null) {
                    String key = generateKey(tr);
                    if (!addedKeys.contains(key)) {
                        allRelationships.add(tr);
                        addedKeys.add(key);
                    }
                }
            }
            log.info("[TableRelationship] 阶段1完成，规则引擎发现 {} 条关联", ruleResults.size());
        } catch (Exception e) {
            log.warn("[TableRelationship] 阶段1规则引擎失败: {}", e.getMessage());
        }
        
        // ========== 阶段2：LLM重点分析 ==========
        log.info("[TableRelationship] 阶段2：LLM深度分析高价值表组合...");
        try {
            // 选择最有价值的表组合进行LLM分析
            List<Map<String, Object>> priorityTables = selectPriorityTables(allTables, maxLLMTables);
            
            if (!priorityTables.isEmpty()) {
                List<TableRelationship> llmRels = analyzeSingleBatch(datasourceId, priorityTables);
                
                // 只添加规则引擎未发现的关联
                int newCount = 0;
                for (TableRelationship rel : llmRels) {
                    String key = generateKey(rel);
                    if (!addedKeys.contains(key)) {
                        allRelationships.add(rel);
                        addedKeys.add(key);
                        newCount++;
                    }
                }
                
                log.info("[TableRelationship] 阶段2完成，LLM新增 {} 条关联（总计{}条）", 
                    newCount, allRelationships.size());
            }
        } catch (Exception e) {
            log.warn("[TableRelationship] 阶段2 LLM分析失败: {}", e.getMessage());
        }
        
        log.info("[TableRelationship] 两阶段分析完成，共 {} 条关联关系", allRelationships.size());
        return allRelationships;
    }
    
    /**
     * ✅ 新增：选择高优先级表进行LLM分析
     * 
     * 策略：选择规则引擎未覆盖的表（无xxx_id字段的表）
     * 原因：规则引擎已捕获所有xxx_id模式，LLM应专注语义关联
     */
    private List<Map<String, Object>> selectPriorityTables(List<Map<String, Object>> allTables, int maxCount) {
        if (allTables.size() <= maxCount) {
            return allTables; // 表数量少，全部分析
        }
        
        log.info("[TableRelationship] 开始筛选需要LLM分析的表...");
        
        // 计算每个表的"规则引擎覆盖率"
        Map<String, Boolean> hasForeignKeyPattern = new HashMap<>();
        
        for (Map<String, Object> table : allTables) {
            String tableName = (String) table.get("table_name");
            
            try {
                // 检查是否有 xxx_id 字段
                List<Map<String, Object>> fkColumns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM column_metadata WHERE table_name = ? AND column_name LIKE '%_id'",
                    tableName
                );
                hasForeignKeyPattern.put(tableName, !fkColumns.isEmpty());
            } catch (Exception e) {
                log.debug("查询表 {} 的外键字段失败", tableName);
                hasForeignKeyPattern.put(tableName, false);
            }
        }
        
        // 优先选择没有xxx_id字段的表（规则引擎无法覆盖）
        List<Map<String, Object>> noFkTables = allTables.stream()
            .filter(t -> !hasForeignKeyPattern.getOrDefault((String) t.get("table_name"), false))
            .collect(Collectors.toList());
        
        // 如果无外键字段的表不足maxCount，补充有注释的表
        if (noFkTables.size() < maxCount) {
            List<Map<String, Object>> withCommentTables = allTables.stream()
                .filter(t -> {
                    String comment = (String) t.get("table_comment");
                    return comment != null && !comment.trim().isEmpty();
                })
                .filter(t -> !noFkTables.contains(t)) // 排除已选的
                .collect(Collectors.toList());
            
            noFkTables.addAll(withCommentTables.subList(0, 
                Math.min(withCommentTables.size(), maxCount - noFkTables.size())));
        }
        
        // 仍然不足，随机补充
        if (noFkTables.size() < maxCount) {
            List<Map<String, Object>> remaining = allTables.stream()
                .filter(t -> !noFkTables.contains(t))
                .collect(Collectors.toList());
            
            noFkTables.addAll(remaining.subList(0, 
                Math.min(remaining.size(), maxCount - noFkTables.size())));
        }
        
        List<Map<String, Object>> result = noFkTables.subList(0, Math.min(maxCount, noFkTables.size()));
        
        log.info("[TableRelationship] 选择了 {} 个表进行LLM分析（规则引擎未覆盖的表）: {}", 
            result.size(), 
            result.stream().map(t -> (String) t.get("table_name")).collect(Collectors.joining(", ")));
        
        return result;
    }
    
    /**
     * ✅ 新增：将Map转换为TableRelationship
     */
    private TableRelationship convertToTableRelationship(Map<String, Object> rel, Long datasourceId) {
        try {
            TableRelationship tr = new TableRelationship();
            tr.setDatasourceId(datasourceId);
            tr.setSourceTable((String) rel.get("sourceTable"));
            tr.setSourceColumn((String) rel.get("sourceColumn"));
            tr.setTargetTable((String) rel.get("targetTable"));
            tr.setTargetColumn((String) rel.get("targetColumn"));
            tr.setRelationshipType((String) rel.get("relationshipType"));
            tr.setConfidence(((Number) rel.get("confidence")).floatValue());
            tr.setDescription((String) rel.get("description"));
            tr.setIsActive(1);
            return tr;
        } catch (Exception e) {
            log.warn("转换关联关系失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * ✅ 新增：生成关联关系的唯一Key
     */
    private String generateKey(TableRelationship rel) {
        return String.format("%s.%s->%s.%s",
            rel.getSourceTable(),
            rel.getSourceColumn(),
            rel.getTargetTable(),
            rel.getTargetColumn()
        );
    }
    
    /**
     * ✅ 新增：获取目标表指定字段的数据类型
     */
    private String getTargetColumnDataType(Long datasourceId, String tableName, String columnName) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT data_type FROM column_metadata WHERE datasource_id = ? AND table_name = ? AND column_name = ?",
                String.class, datasourceId, tableName, columnName
            );
        } catch (Exception e) {
            log.debug("查询字段类型失败: {}.{}", tableName, columnName);
            return null;
        }
    }
    
    /**
     * ✅ 新增：判断两个字段类型是否兼容
     * 
     * 兼容规则：
     * 1. 完全相同：int = int
     * 2. 数值类型互转：int ↔ bigint, decimal ↔ float
     * 3. 字符串类型互转：varchar ↔ text ↔ char
     * 4. 日期类型互转：date ↔ datetime ↔ timestamp
     */
    private boolean isCompatibleType(String type1, String type2) {
        if (type1 == null || type2 == null) {
            return false; // 类型未知，保守处理
        }
        
        String t1 = type1.toLowerCase();
        String t2 = type2.toLowerCase();
        
        // 完全相同
        if (t1.equals(t2)) {
            return true;
        }
        
        // 数值类型组
        Set<String> integerTypes = Set.of("int", "integer", "bigint", "smallint", "tinyint", "mediumint");
        Set<String> decimalTypes = Set.of("decimal", "numeric", "float", "double");
        
        if ((integerTypes.contains(t1) && integerTypes.contains(t2)) ||
            (decimalTypes.contains(t1) && decimalTypes.contains(t2))) {
            return true;
        }
        
        // 字符串类型组
        Set<String> stringTypes = Set.of("varchar", "char", "text", "tinytext", "mediumtext", "longtext");
        if (stringTypes.contains(t1) && stringTypes.contains(t2)) {
            return true;
        }
        
        // 日期时间类型组
        Set<String> dateTypes = Set.of("date", "datetime", "timestamp", "time", "year");
        if (dateTypes.contains(t1) && dateTypes.contains(t2)) {
            return true;
        }
        
        // 布尔类型
        if ((t1.equals("boolean") || t1.equals("bool") || t1.equals("tinyint")) &&
            (t2.equals("boolean") || t2.equals("bool") || t2.equals("tinyint"))) {
            return true;
        }
        
        return false;
    }
    
    /**
     * ✅ 新增：分析单批表的关联关系
     */
    private List<TableRelationship> analyzeSingleBatch(Long datasourceId, List<Map<String, Object>> tables) {
        try {
            // ✅ 优化：批量查询所有表的字段信息
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
            
            // ✅ 关键：使用带长超时的LLM调用（60秒）
            String response = callLLMWithTimeout(prompt, 60);
            log.info("LLM返回关联关系: {}", response);
            
            // 解析JSON响应
            return parseLLMResponse(response, datasourceId);
            
        } catch (Exception e) {
            log.error("LLM分析单批关联关系失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * ✅ 新增：带自定义超时的LLM调用
     */
    private String callLLMWithTimeout(String prompt, int timeoutSeconds) {
        try {
            // 直接使用 OllamaProvider 并设置更长超时
            com.nl2sql.core.llm.provider.OllamaProvider ollamaProvider = 
                new com.nl2sql.core.llm.provider.OllamaProvider(
                    ollamaBaseUrl,
                    ollamaCodeModel,
                    timeoutSeconds
                );
            
            return ollamaProvider.generate(prompt, 0.3);
            
        } catch (Exception e) {
            log.error("[TableRelationship] LLM调用超时({}s)", timeoutSeconds, e);
            throw new RuntimeException("LLM调用超时: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成关联关系的唯一键
     */
    private String generateRelationshipKey(Map<String, Object> rel) {
        return String.format("%s.%s->%s.%s",
            rel.get("sourceTable"),
            rel.get("sourceColumn"),
            rel.get("targetTable"),
            rel.get("targetColumn")
        );
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
            
            // ✅ 关键：清除关联关系缓存（因为数据已变更）
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[TableRelationship] 关联关系已变更，清除所有缓存");
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
            
            // ✅ 关键：清除关联关系缓存
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[TableRelationship] 关联关系已删除，清除所有缓存");
            }
            
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
            
            // ✅ 关键：清除关联关系缓存
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[TableRelationship] 关联关系状态已变更，清除所有缓存");
            }
            
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
        
        log.info("开始从 SQL提取关联关系: datasourceId={}, SQL={}", datasourceId, sql);
        
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
        
        // ✅ 关键优化：先查缓存
        if (metadataCacheService != null) {
            String cached = metadataCacheService.getRelationships(datasourceId, tables);
            if (cached != null) {
                log.debug("[TableRelationship] 关联关系缓存命中");
                return cached;
            }
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
            
            // ✅ 存入缓存
            if (metadataCacheService != null) {
                metadataCacheService.putRelationships(datasourceId, tables, result);
            }
            
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
