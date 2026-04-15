package com.nl2sql.core.agent.tools;

import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.validation.SQLValidationService;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.llm.SynonymService;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.RagLearningContext;
import com.nl2sql.core.retriever.VectorRetriever;
import com.nl2sql.metadata.service.TableRelationshipService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * NL2SQL Tool - 将自然语言转换为SQL
 */
@Slf4j
@Component
public class NL2SQLTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private SynonymService synonymService;
    
    @Autowired
    private SQLValidationService sqlValidationService;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    /**
     * 根据用户问题和数据源ID生成SQL
     * 
     * @param query 用户自然语言问题
     * @param datasourceId 数据源ID
     * @return 生成的SQL语句，或澄清信号
     */
    @Tool("根据用户的自然语言问题和数据源ID，生成对应的SQL查询语句。如果缺少必要的表信息，会返回澄清请求")
    public String generateSQL(String query, Long datasourceId) {
        try {
            log.info("[NL2SQLTool] 开始生成SQL: query={}, datasourceId={}", query, datasourceId);
            
            // 0. 同义词扩展（增强语义理解）
            String expandedQuery = synonymService.expandSynonyms(query);
            if (!expandedQuery.equals(query)) {
                log.info("[NL2SQLTool] 查询扩展: {} -> {}", query, expandedQuery);
            }
            
            // 1. 初始向量检索（高召回）
            List<String> initialTables = vectorRetriever.retrieveTopTables(expandedQuery, 15);  // 提高到15
            if (initialTables.isEmpty()) {
                return "ERROR: 未找到任何相关表，请检查元数据是否已加载";
            }
            
            log.info("[NL2SQLTool] 初始检索到 {} 个表: {}", initialTables.size(), initialTables);
            
            // 2. 迭代式表发现 + 回溯机制
            Set<String> allTables = new HashSet<>(initialTables);
            boolean needsClarification = false;
            String clarificationMessage = "";
                        
            for (int iteration = 0; iteration < 3; iteration++) {  // 最多3轮迭代
                log.info("[NL2SQLTool] 第{}轮迭代，当前表数量: {}", iteration + 1, allTables.size());
                
                // ⚠️ 获取关联关系（用于表选择阶段）
                String relationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(allTables));
                            
                // 构建当前表结构信息
                String schemaInfo = buildTableSchemaInfo(new ArrayList<>(allTables), datasourceId);
                            
                // 调用 LLM 判断并选择需要的表
                String checkPrompt = buildTableCheckPrompt(expandedQuery, schemaInfo, relationshipInfo);
                String llmResponse = modelRouter.smartGenerateSQL(checkPrompt, expandedQuery);
                            
                log.info("[NL2SQLTool] ========== LLM原始响应 ==========");
                log.info("[NL2SQLTool] {}", llmResponse);
                log.info("[NL2SQLTool] =====================================");
                            
                if (llmResponse == null || llmResponse.trim().isEmpty()) {
                    break;
                }
                            
                // 尝试解析 JSON 响应
                try {
                    // 先清洗 Markdown 代码块
                    String cleanJson = MarkdownUtils.extractFromMarkdown(llmResponse);
                    log.info("[NL2SQLTool] 清洗后的JSON: {}", cleanJson);
                    
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(cleanJson);
                                
                    // 情况1: LLM 选择了需要的表
                    if (jsonNode.has("selected_tables")) {
                        com.fasterxml.jackson.databind.JsonNode selectedTablesNode = jsonNode.get("selected_tables");
                        if (selectedTablesNode.isArray() && selectedTablesNode.size() > 0) {
                            Set<String> selectedTables = new HashSet<>();
                            log.info("[NL2SQLTool] LLM返回selected_tables: {}", selectedTablesNode.toString());
                            
                            for (com.fasterxml.jackson.databind.JsonNode tableNode : selectedTablesNode) {
                                String tableName = tableNode.asText().toLowerCase();
                                log.info("[NL2SQLTool] 处理表选择: {}", tableName);
                                
                                // 验证表是否存在
                                Integer count = jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND table_name = ?",
                                    Integer.class, datasourceId, tableName
                                );
                                if (count != null && count > 0) {
                                    selectedTables.add(tableName);
                                    log.info("[NL2SQLTool] ✅ 表{}存在，已加入选择列表", tableName);
                                } else {
                                    log.warn("[NL2SQLTool] ❌ 表{}不存在，跳过", tableName);
                                }
                            }
                                        
                            if (!selectedTables.isEmpty()) {
                                log.info("[NL2SQLTool] LLM精简表: {} -> {}", allTables.size(), selectedTables.size());
                                log.info("[NL2SQLTool] 最终选择的表: {}", selectedTables);
                                allTables = selectedTables; // 替换为精简后的表
                            }
                            break; // 已得到精简结果，退出迭代
                        }
                    }
                    // 情况2: LLM 指出缺少的表（只有当没有selected_tables时才执行）
                    else if (jsonNode.has("missing_tables")) {
                        com.fasterxml.jackson.databind.JsonNode missingTablesNode = jsonNode.get("missing_tables");
                        if (missingTablesNode.isArray() && missingTablesNode.size() > 0) {
                            List<String> missingTables = new ArrayList<>();
                            for (com.fasterxml.jackson.databind.JsonNode tableNode : missingTablesNode) {
                                missingTables.add(tableNode.asText().toLowerCase());
                            }
                                        
                            // 尝试查找缺失的表
                            boolean foundNew = false;
                            for (String tableName : missingTables) {
                                if (allTables.contains(tableName)) continue;
                                            
                                Integer count = jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND table_name = ?",
                                    Integer.class, datasourceId, tableName
                                );
                                            
                                if (count != null && count > 0) {
                                    allTables.add(tableName);
                                    foundNew = true;
                                    log.info("[NL2SQLTool] 补充缺失表: {}", tableName);
                                }
                            }
                                        
                            if (!foundNew) {
                                // 没找到缺失的表，需要澄清
                                String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "缺少必要的表";
                                needsClarification = true;
                                clarificationMessage = reason;
                                break;
                            }
                            // 找到了新表，继续下一轮迭代
                            continue;
                        }
                    }
                                
                } catch (Exception e) {
                    log.warn("[NL2SQLTool] JSON解析失败，使用传统方式: {}", e.getMessage());
                    // 降级到传统解析方式
                }
                            
                // 传统解析方式（兼容旧格式）
                if (llmResponse.contains("表已足够") || llmResponse.contains("enough")) {
                    log.info("[NL2SQLTool] LLM确认表已足够");
                    break;
                }
                            
                List<String> missingTables = parseMissingTables(llmResponse);
                if (missingTables.isEmpty()) {
                    break;
                }
                            
                boolean foundNew = false;
                for (String tableName : missingTables) {
                    tableName = tableName.toLowerCase();
                    if (allTables.contains(tableName)) continue;
                                
                    Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM column_metadata WHERE datasource_id = ? AND table_name = ?",
                        Integer.class, datasourceId, tableName
                    );
                                
                    if (count != null && count > 0) {
                        allTables.add(tableName);
                        foundNew = true;
                        log.info("[NL2SQLTool] 迭代补充表: {}", tableName);
                    }
                }
                            
                if (!foundNew) {
                    log.info("[NL2SQLTool] 未找到新表，停止迭代");
                    break;
                }
            }
            
            log.info("[NL2SQLTool] 最终确定 {} 个表: {}", allTables.size(), allTables);
            
            // 3. 如果仍然需要澄清，返回澄清信号
            if (needsClarification) {
                return "CLARIFICATION_NEEDED: " + clarificationMessage;
            }
            
            // 4. 如果表数量过多（>10），让用户选择
            if (allTables.size() > 10) {
                StringBuilder tableList = new StringBuilder();
                tableList.append("检测到较多相关表（" + allTables.size() + "个），请确认需要使用哪些表：\n");
                
                int index = 1;
                for (String tableName : allTables) {
                    String comment = getTableComment(tableName, datasourceId);
                    tableList.append(String.format("%d. %s (%s)\n", index++, tableName, 
                        comment != null && !comment.isEmpty() ? comment : "无注释"));
                }
                
                return "TABLE_SELECTION_NEEDED: " + tableList.toString();
            }
            
            // 5. ⚠️ P0优化：RAG 检索相似问答对（中等复杂度查询）
            String ragEnhancement = "";
            if (ragService != null) {
                try {
                    List<RagKnowledgeBaseService.KnowledgeItem> similarItems = 
                        ragService.searchSimilarQuestions(expandedQuery, 3);
                    
                    if (!similarItems.isEmpty()) {
                        log.info("[NL2SQLTool] RAG检索到 {} 个参考示例", similarItems.size());
                        StringBuilder ragBuilder = new StringBuilder();
                        ragBuilder.append("\n\n参考示例（历史成功案例，请借鉴其JOIN方式和字段选择）:\n");
                        
                        for (int i = 0; i < Math.min(similarItems.size(), 3); i++) {
                            RagKnowledgeBaseService.KnowledgeItem item = similarItems.get(i);
                            ragBuilder.append(String.format("\n示例%d:\n", i + 1));
                            ragBuilder.append("问题: ").append(item.getQuestion()).append("\n");
                            if (item.getSqlExample() != null && !item.getSqlExample().isEmpty()) {
                                ragBuilder.append("SQL: ").append(item.getSqlExample()).append("\n");
                            }
                        }
                        ragEnhancement = ragBuilder.toString();
                    } else {
                        log.debug("[NL2SQLTool] RAG未找到相似示例");
                    }
                } catch (Exception e) {
                    log.warn("[NL2SQLTool] RAG检索失败: {}", e.getMessage());
                }
            }
            // 6. ✅ 基于关联关系智能扩展表（补充必要的JOIN表）
            Set<String> expandedTables = new HashSet<>(allTables);
            
            // 第一次：基于LLM选的表获取关联关系
            String relationshipInfo = relationshipService.getRelationshipsForPrompt(
                datasourceId, new ArrayList<>(allTables));
            
            // 从关联关系中提取所有涉及的表名，并添加到expandedTables
            if (!relationshipInfo.isEmpty()) {
                java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile("\\b(\\w+)\\.\\w+\\s*->\\s*(\\w+)\\.\\w+");
                java.util.regex.Matcher matcher = tablePattern.matcher(relationshipInfo);
                while (matcher.find()) {
                    String sourceTable = matcher.group(1).toLowerCase();
                    String targetTable = matcher.group(2).toLowerCase();
                    expandedTables.add(sourceTable);
                    expandedTables.add(targetTable);
                }
                
                if (expandedTables.size() > allTables.size()) {
                    Set<String> newTables = new HashSet<>(expandedTables);
                    newTables.removeAll(allTables);
                    log.info("[NL2SQLTool] 基于关联关系扩展表: {} -> {}", allTables.size(), expandedTables.size());
                    log.info("[NL2SQLTool] 新增表: {}", newTables);
                }
            }
            
            // ⚠️ 关键：基于扩展后的表重新获取完整的关联关系
            String fullRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                datasourceId, new ArrayList<>(expandedTables));
            
            log.info("[NL2SQLTool] 最终用于SQL生成的表: {}", expandedTables);
            log.info("[NL2SQLTool] 最终关联关系数量: {}", 
                fullRelationshipInfo.isEmpty() ? 0 : fullRelationshipInfo.split("\n").length);
            
            String schemaInfo = buildTableSchemaInfo(new ArrayList<>(expandedTables), datasourceId);
            
            log.info("[NL2SQLTool] ========== SchemaInfo(前500字符) ==========");
            log.info("[NL2SQLTool] {}", schemaInfo.length() > 500 ? schemaInfo.substring(0, 500) + "..." : schemaInfo);
            log.info("[NL2SQLTool] ================================================");
            
            String joinHint = fullRelationshipInfo.isEmpty() ? "" : 
                fullRelationshipInfo + "\n重要：以上关联关系是数据库中已定义的，请直接用于JOIN语句，不要再次询问或澄清。";
            
            // ✅ 关键：明确列出可用表清单，强化约束
            String availableTablesList = String.join(", ", expandedTables);
            
            String sqlPrompt = String.format(
                "你是一个MySQL SQL专家。根据以下数据库结构和用户问题，生成一条MySQL查询SQL。\n\n" +
                "⚠️ **重要约束：你只能使用以下 %d 张表**：%s\n" +
                "**严禁使用未列出的任何表！**如果需要的表不在上述列表中，说明表选择阶段有误，请基于现有表生成SQL。\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "%s" +
                "用户问题：%s\n\n" +
                "要求：\n" +
                "1. 只输出SQL语句，不要包含```sql或其他标记\n" +
                "2. **严格限制：只能使用上面'数据库表结构'中列出的表和字段**\n" +
                "   - ❌ 错误：SELECT u.province ... FROM orders o JOIN user_addresses ua ... （u表不在FROM/JOIN中）\n" +
                "   - ✅ 正确：SELECT ua.province ... FROM orders o JOIN user_addresses ua ... （ua在JOIN中）\n" +
                "   - **所有SELECT中的字段必须属于FROM或JOIN中的表**\n" +
                "3. 添加LIMIT限制返回行数（但如果是GROUP BY统计查询，可以不设LIMIT或设为较大值）\n" +
                "4. **别名规范（重要）**：\n" +
                "   - **所有SELECT字段都必须使用 AS 指定中文别名**，这样前端表格会直接显示中文表头\n" +
                "   - 例如：SELECT category_name AS '分类名称', SUM(amount) AS '订单总金额', COUNT(*) AS '订单数量'\n" +
                "   - 聚合函数必须加别名：SUM(xxx) AS '总和', COUNT(*) AS '数量', AVG(xxx) AS '平均值'\n" +
                "   - 分组字段也必须加别名：GROUP BY 的字段也要 AS '中文名'\n" +
                "5. **重要：识别统计类问题并使用聚合函数**\n" +
                "   - 当用户问'统计每个X的Y'、'按X分组的Y'、'各X的Y总和/平均值/数量'时，必须使用 GROUP BY\n" +
                "   - 常用聚合函数：SUM()求和、COUNT()计数、AVG()平均、MAX()最大、MIN()最小\n" +
                "   - 例如：'统计每个地区的销售额' -> SELECT region, SUM(amount) FROM orders GROUP BY region\n" +
                "   - 例如：'每个城市的订单数' -> SELECT city, COUNT(*) FROM orders GROUP BY city\n" +
                "6. **SELECT字段规则**：\n" +
                "   - GROUP BY查询：SELECT中只能包含GROUP BY字段和聚合函数，不能直接选择非分组字段\n" +
                "   - 错误示例：SELECT user_id, province, SUM(amount) ... GROUP BY province （user_id不在GROUP BY中）\n" +
                "   - 正确示例：SELECT province, SUM(amount) ... GROUP BY province\n" +
                "7. **时间格式化规范**：\n" +
                "   - 如果用户要求按天/月/年统计（如'最近10天每天的订单金额'），必须使用 DATE_FORMAT() 函数格式化时间\n" +
                "   - 按天统计：DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS order_date\n" +
                "   - 按月统计：DATE_FORMAT(created_at, '%%Y-%%m') AS order_month\n" +
                "   - 按年统计：DATE_FORMAT(created_at, '%%Y') AS order_year\n" +
                "   - ❌ 错误：DATE(created_at) 会返回带时分秒的格式\n" +
                "   - ✅ 正确：DATE_FORMAT(created_at, '%%Y-%%m-%%d') 只返回日期部分\n" +
                "8. **表关联规则**：\n" +
                "   - 如果上面提供了'表之间的关联关系'，直接使用这些关系进行JOIN\n" +
                "   - **必须使用直接JOIN，禁止使用子查询或IN子句进行表关联**\n" +
                "   - 错误示例：JOIN tableB ON colA IN (SELECT id FROM tableB WHERE ...)\n" +
                "   - 正确示例：JOIN tableB ON tableA.ref_id = tableB.id\n" +
                "   - **重要：关联字段必须是外键或ID字段，不能是文本字段**\n" +
                "   - 错误示例：JOIN user_addresses ua ON orders.shipping_address = ua.id （shipping_address是文本，不是ID）\n" +
                "   - 正确示例：JOIN users u ON orders.user_id = u.id （通过用户ID关联）\n" +
                "   - **一对多关联时必须添加过滤条件避免笛卡尔积**\n" +
                "   - 错误示例：JOIN user_addresses ua ON orders.user_id = ua.user_id （一个用户可能有多个地址，导致订单金额重复计算）\n" +
                "   - 正确示例：JOIN user_addresses ua ON orders.user_id = ua.user_id AND ua.is_default = 1 （只取默认地址）\n" +
                "   - 或者优先使用主表的字段：直接使用users表的地区字段，而非user_addresses\n" +
                "   - 只有在完全没有关联关系信息且确实需要多表JOIN时，才返回'CLARIFY_RELATIONSHIP:表A,表B'\n" +
                "   - 当前场景已有明确的关联关系，请不要返回CLARIFY_RELATIONSHIP\n" +
                "\nSQL：",
                expandedTables.size(), availableTablesList,
                schemaInfo, fullRelationshipInfo.isEmpty() ? "" : fullRelationshipInfo + "\n\n", ragEnhancement, expandedQuery
            );
            
            String sql = modelRouter.smartGenerateSQL(sqlPrompt, expandedQuery);
            
            // 清理SQL
            sql = cleanSQL(sql);
            
            log.info("[NL2SQLTool] 生成的SQL: {}", sql);
            
            // ⚠️ P0优化：SQL 验证与 Self-Correction
            sql = validateAndCorrectSQL(sql, expandedQuery, datasourceId, schemaInfo, relationshipInfo, 3);
            
            // ⚠️ RAG优化：设置学习上下文（供后续 SQL 执行后自动学习）
            RagLearningContext.setCurrentQuestion(expandedQuery);
            RagLearningContext.setCurrentSql(sql);
            
            return sql;
            
        } catch (Exception e) {
            log.error("[NL2SQLTool] 生成SQL失败", e);
            return "错误：" + e.getMessage();
        }
    }
    
    /**
     * 获取表的中文注释
     */
    private String getTableComment(String tableName, Long datasourceId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT DISTINCT table_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ? LIMIT 1",
                String.class, datasourceId, tableName
            );
        } catch (Exception e) {
            return "";
        }
    }
    
    private String buildTableSchemaInfo(List<String> tables, Long datasourceId) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        
        // ✅ 优化：批量查询所有表的字段信息（避免N次数据库查询）
        String placeholders = tables.stream()
            .map(t -> "?")
            .collect(java.util.stream.Collectors.joining(", "));
        
        String batchColSql = String.format(
            "SELECT table_name, column_name, data_type, column_comment, is_primary_key " +
            "FROM column_metadata WHERE datasource_id = ? AND table_name IN (%s) " +
            "ORDER BY table_name, ordinal_position",
            placeholders
        );
        
        Object[] params = new Object[tables.size() + 1];
        params[0] = datasourceId;
        for (int i = 0; i < tables.size(); i++) {
            params[i + 1] = tables.get(i);
        }
        
        List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(batchColSql, params);
        
        // 按表名分组
        Map<String, List<Map<String, Object>>> columnsByTable = allColumns.stream()
            .collect(java.util.stream.Collectors.groupingBy(col -> (String) col.get("table_name")));
        
        StringBuilder sb = new StringBuilder();
        for (String tableName : tables) {
            sb.append(String.format("\n表名: %s\n", tableName));
            
            List<Map<String, Object>> columns = columnsByTable.getOrDefault(tableName, java.util.Collections.emptyList());
            
            if (columns.isEmpty()) {
                sb.append("  [警告] 该表没有字段元数据\n");
            } else {
                for (Map<String, Object> col : columns) {
                    sb.append(String.format("  - %s (%s)", 
                        col.get("column_name"), col.get("data_type")));
                    
                    if ("1".equals(String.valueOf(col.get("is_primary_key")))) {
                        sb.append(" [主键]");
                    }
                    
                    if (col.get("column_comment") != null && !col.get("column_comment").toString().isEmpty()) {
                        sb.append(String.format(" - %s", col.get("column_comment")));
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }
    
    private String buildTableCheckPrompt(String query, String schemaInfo, String relationshipInfo) {
        String relationshipHint = relationshipInfo.isEmpty() ? "" : 
            "\n\n表之间的关联关系（重要）：\n" + relationshipInfo + 
            "\n注意：如果需要关联两张表，必须包含中间的所有表。例如：order_items -> products -> product_categories，需要同时选中这三张表。";
        
        // ✅ 关键修复：检测用户是否指定了表名偏好
        String tablePreferenceHint = "";
        if (query.contains("[优先使用表:")) {
            int start = query.indexOf("[优先使用表:") + "[优先使用表:".length();
            int end = query.indexOf("]", start);
            if (end > start) {
                String preferredTable = query.substring(start, end).trim();
                tablePreferenceHint = String.format(
                    "\n\n⚠️ **用户明确要求**：优先使用 `%s` 表\n" +
                    "- 如果 `%s` 表在可用表列表中，**必须选择它**\n" +
                    "- 除非该表完全无法满足用户需求，否则不要忽略用户的表选择\n" +
                    "- 在选择表时，优先考虑用户指定的表，再补充必要的关联表",
                    preferredTable, preferredTable
                );
                log.info("[NL2SQLTool] 检测到用户表偏好: {}", preferredTable);
            }
        }
        
        return String.format(
            "你是一个数据库专家。根据用户问题和当前可用的表结构，请选出需要用到的表。\n\n" +
            "用户问题：%s\n" +
            "%s" +
            "当前可用的表：\n%s" +
            "%s\n\n" +
            "要求：\n" +
            "1. 仔细分析用户问题，只选择真正需要用到的表\n" +
            "2. **如果需要通过中间表关联，必须包含所有中间表**（如 order_items.product_id -> products.id -> products.category_id -> product_categories.id，需要选中 order_items、products、product_categories 三张表）\n" +
            "3. **关键指标识别规则**：\n" +
            "   - 涉及'销售额'、'金额'、'订单数'、'交易量'等业务指标时，必须选择包含这些字段的业务主表（通常是交易/订单类表）\n" +
            "   - 不能仅根据维度字段（如地区、城市）选择表，必须确保所选表包含用户询问的指标字段\n" +
            "   - 例如：'统计每个地区的销售额' → 必须选择有金额字段的订单表，而非仅有地区字段的用户表\n" +
            "4. **表选择验证规则（重要）**：\n" +
            "   - 在返回selected_tables之前，必须验证：基于已选表能否生成满足用户问题的SQL？\n" +
            "   - 检查清单：\n" +
            "     a) SELECT中的每个字段是否都能在已选表中找到？\n" +
            "     b) WHERE/GROUP BY中的字段是否都在已选表中？\n" +
            "     c) 如果需要JOIN，关联字段是否在已选表中？\n" +
            "   - 如果任何一个检查失败，必须返回missing_tables，而不是selected_tables\n" +
            "   - 示例：用户问'统计每个地区的销售额'，如果只选了orders和user_addresses，但想使用users.province，则必须补充users表\n" +
            "5. 如果某些表完全用不到，不要包含在结果中\n" +
            "6. 返回JSON格式：{\"selected_tables\": [\"表1\", \"表2\"]}\n" +
            "7. 如果确实缺少必要的表，返回：{\"missing_tables\": [\"表A\", \"表B\"], \"reason\": \"缺少的表用途说明\"}\n" +
            "8. 只返回JSON，不要其他内容",
            query, tablePreferenceHint, schemaInfo, relationshipHint
        );
    }
    
    private List<String> parseMissingTables(String llmResponse) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            return Collections.emptyList();
        }
        
        Set<String> tables = new HashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9_]*)\\b");
        
        for (String line : llmResponse.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.contains("表已足够")) continue;
            
            java.util.regex.Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String word = matcher.group(1).toLowerCase();
                if (!isCommonWord(word)) {
                    tables.add(word);
                }
            }
        }
        
        return new ArrayList<>(tables);
    }
    
    private boolean isCommonWord(String word) {
        return Arrays.asList(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
            "table", "tables", "column", "columns", "field", "fields",
            "missing", "required", "additional"
        ).contains(word.toLowerCase());
    }
    
    private String cleanSQL(String sql) {
        return MarkdownUtils.cleanSQL(sql);
    }
    
    /**
     * SQL 验证与 Self-Correction（P0优化）
     * 
     * @param sql 原始 SQL
     * @param question 用户问题
     * @param datasourceId 数据源ID
     * @param schemaInfo 表结构信息
     * @param relationshipInfo 关联关系
     * @param maxRetries 最大重试次数
     * @return 修正后的 SQL
     */
    private String validateAndCorrectSQL(String sql, String question, Long datasourceId, 
                                         String schemaInfo, String relationshipInfo, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 1. 综合验证
            SQLValidationService.ValidationReport report = sqlValidationService.comprehensiveValidate(sql);
            
            if (report.isOverallValid()) {
                log.info("[NL2SQLTool] SQL验证通过 (attempt={})", attempt);
                return sql;
            }
            
            log.warn("[NL2SQLTool] SQL验证失败 (attempt={}): syntaxValid={}, issues={}", 
                attempt, report.isSyntaxValid(), 
                report.getAggregationIssues().size() + report.getJoinIssues().size());
            
            // 2. 如果是语法错误，尝试修正
            if (!report.isSyntaxValid()) {
                log.info("[NL2SQLTool] 尝试修正语法错误: {}", report.getSyntaxError());
                sql = attemptSyntaxCorrection(sql, report.getSyntaxError(), question, schemaInfo, relationshipInfo);
                continue;
            }
            
            // 3. 如果是聚合或JOIN问题，生成警告并返回（不阻断执行）
            if (!report.getAggregationIssues().isEmpty() || !report.getJoinIssues().isEmpty()) {
                StringBuilder warning = new StringBuilder();
                warning.append("⚠️ SQL潜在问题:\n");
                
                for (String issue : report.getAggregationIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                for (String issue : report.getJoinIssues()) {
                    warning.append("- ").append(issue).append("\n");
                }
                
                log.warn("[NL2SQLTool] {}", warning.toString());
                // 不阻断执行，但记录警告
                break;
            }
        }
        
        return sql;
    }
    
    /**
     * 尝试修正语法错误
     */
    private String attemptSyntaxCorrection(String failedSql, String errorMessage, 
                                           String question, String schemaInfo, String relationshipInfo) {
        try {
            String correctionPrompt = String.format(
                "你是一个MySQL SQL专家。以下SQL语句存在语法错误，请修正。\n\n" +
                "用户问题：%s\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "失败的SQL:\n%s\n\n" +
                "错误信息:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. 保持原有查询意图不变\n" +
                "4. 仔细检查括号、关键字、字段名是否正确",
                question,
                schemaInfo,
                relationshipInfo.isEmpty() ? "" : relationshipInfo + "\n\n",
                failedSql,
                errorMessage
            );
            
            String correctedSql = modelRouter.smartGenerateSQL(correctionPrompt, question);
            correctedSql = cleanSQL(correctedSql);
            
            log.info("[NL2SQLTool] 修正后SQL: {}", correctedSql);
            return correctedSql;
            
        } catch (Exception e) {
            log.error("[NL2SQLTool] 语法修正失败", e);
            return failedSql; // 返回原SQL
        }
    }
    
    /**
     * 检索表结构信息（供Skill使用）
     */
    public String retrieveSchema(String query, Long datasourceId) {
        try {
            // 向量检索相关表
            List<String> tables = vectorRetriever.retrieveTopTables(query, 10);
            if (tables.isEmpty()) {
                return "ERROR: 未找到任何相关表";
            }
            
            // 构建schema信息
            return buildTableSchemaInfo(tables, datasourceId);
        } catch (Exception e) {
            log.error("[NL2SQLTool] 检索schema失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    /**
     * 自动修正SQL（供Skill使用）
     */
    public String autoFixSQL(String failedSql, String errorMessage) {
        try {
            log.info("[NL2SQLTool] 开始修正SQL: error={}", errorMessage);
            
            String fixPrompt = String.format(
                "SQL执行失败，请修正。\n\n" +
                "失败的SQL:\n%s\n\n" +
                "错误信息:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. 保持原有查询意图不变\n" +
                "4. **重要：如果ON条件中使用了IN子查询，必须改为直接JOIN**\n" +
                "   - 错误：JOIN tableB ON colA IN (SELECT id FROM tableB WHERE ...)\n" +
                "   - 正确：JOIN tableB ON tableA.ref_id = tableB.id",
                failedSql, errorMessage
            );
            
            String fixedSql = modelRouter.smartGenerateSQL(fixPrompt, "");
            return cleanSQL(fixedSql);
        } catch (Exception e) {
            log.error("[NL2SQLTool] SQL修正失败", e);
            return failedSql; // 返回原SQL
        }
    }
}
