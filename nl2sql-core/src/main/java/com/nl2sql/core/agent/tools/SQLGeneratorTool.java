package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.event.StreamProgressEvent;
import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.rag.LowRatingExampleService;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.RagLearningContext;
import com.nl2sql.metadata.service.TableRelationshipService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL生成工具 - 基于选定的表和用户问题生成SQL语句
 * ✅ 从NL2SQLTool拆分出的原子能力
 */
@Slf4j
@Component
public class SQLGeneratorTool extends BaseToolAdapter {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    @Autowired(required = false)
    private LowRatingExampleService lowRatingExampleService;
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired(required = false)
    private com.nl2sql.core.cache.QueryCacheService queryCacheService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // ThreadLocal存储sessionId用于发布进度事件
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();
    
    public void setCurrentSessionId(String sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }
    
    @Override
    public String getName() { return "sql_generator"; }
    
    @Override
    public String getDescription() { return "基于已选定的数据库表和用户问题，生成MySQL查询SQL。支持RAG增强、低分示例过滤、表关联扩展等功能。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户自然语言问题"));
        props.put("tables", Map.of("type", "array", "description", "已选定的表名列表"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        props.put("schemaInfo", Map.of("type", "string", "description", "表结构信息"));
        props.put("relationshipInfo", Map.of("type", "string", "description", "表关联关系"));
        props.put("llmResponse", Map.of("type", "string", "description", "LLM表选择响应（可选）"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query", "tables", "datasourceId", "schemaInfo"));
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "已完成表选择，需要生成SQL时"; }
    
    @Override
    public String getInapplicableScenarios() { return "表尚未选定或不需要生成SQL的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .required("tables", context.getParameter("tables"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .required("schemaInfo", context.getParameter("schemaInfo"))
            .throwIfHasErrors();
    }
    
    @Override
    @SuppressWarnings("unchecked")
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        List<String> tablesList = (List<String>) context.getRequiredParameter("tables");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        String schemaInfo = context.getRequiredParameter("schemaInfo");
        String relationshipInfo = context.getParameter("relationshipInfo");
        String llmResponse = context.getParameter("llmResponse");
        
        if (relationshipInfo == null) relationshipInfo = "";
        
        Set<String> allTables = new HashSet<>(tablesList);
        
        // 执行SQL生成
        String sql = generateSQLInternal(query, allTables, schemaInfo, relationshipInfo, datasourceId, llmResponse);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", !sql.startsWith("错误："));
        result.put("sql", sql);
        
        return result;
    }
    
    /**
     * 内部方法：生成SQL
     */
    private String generateSQLInternal(String expandedQuery, Set<String> allTables, 
                                      String schemaInfo, String relationshipInfo,
                                      Long datasourceId, String llmResponse) {
        try {
            publishProgress("generating_sql", "🤖 生成SQL...");
            
            // 1. RAG检索相似问答对
            String ragEnhancement = buildRAGEnhancement(expandedQuery);
            
            // 2. 基于关联关系智能扩展表
            Set<String> expandedTables = expandTablesByRelationship(allTables, relationshipInfo, datasourceId, llmResponse);
            
            // 3. 构建最终Schema和关联关系
            String fullRelationshipInfo = relationshipInfo;
            if (expandedTables.size() > allTables.size()) {
                fullRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(expandedTables));
            }
            
            String finalSchemaInfo = schemaInfo;
            if (expandedTables.size() > allTables.size()) {
                // 需要重新构建schema（简化处理，实际应调用buildTableSchemaInfo）
                finalSchemaInfo = schemaInfo; // TODO: 重构后需注入SchemaRetrieverTool
            }
            
            // 4. 检索低分示例
            String negativeExamples = buildNegativeExamples(expandedQuery);
            
            // 5. 构建SQL生成Prompt
            String availableTablesList = String.join(", ", expandedTables);
            String joinHint = fullRelationshipInfo.isEmpty() ? "" : 
                fullRelationshipInfo + "\n重要：以上关联关系是数据库中已定义的，请直接用于JOIN语句，不要再次询问或澄清。";
            
            String sqlPrompt = buildSQLGenerationPrompt(expandedQuery, expandedTables.size(), 
                availableTablesList, finalSchemaInfo, joinHint, ragEnhancement, negativeExamples);
            
            // 6. 调用LLM生成SQL
            String sql = modelRouter.smartGenerateSQL(sqlPrompt, expandedQuery);
            sql = MarkdownUtils.cleanSQL(sql);
            
            log.info("[SQLGenerator] 生成的SQL: {}", sql);
            publishProgress("sql_generated", "✅ SQL生成完成");
            
            // 7. Layer 2: 检查是否与历史低分SQL高度相似
            sql = checkAndRegenerateIfLowRating(expandedQuery, sql);
            
            // 8. 设置RAG学习上下文
            RagLearningContext.setCurrentQuestion(expandedQuery);
            RagLearningContext.setCurrentSql(sql);
            
            // 9. 缓存SQL
            cacheSQL(expandedQuery, sql, datasourceId);
            
            return sql;
            
        } catch (Exception e) {
            log.error("[SQLGenerator] 生成SQL失败", e);
            return "错误：" + e.getMessage();
        }
    }
    
    /**
     * 构建RAG增强内容
     */
    private String buildRAGEnhancement(String query) {
        if (ragService == null) return "";
        
        try {
            publishProgress("rag_search", "📚 检索历史相似案例...");
            String enhancement = ragService.buildRAGEnhancement(query);
            
            // ✅ 记录监控数据：RAG示例数量
            int ragCount = 0;
            if (enhancement != null && !enhancement.isEmpty()) {
                // 统计示例数量（每个示例以"示例"开头）
                ragCount = enhancement.split("示例").length - 1;
                publishProgress("rag_completed", "✅ 找到参考案例");
                log.debug("[MonitoringContext] RAG检索到 {} 个示例", ragCount);
            }
            
            com.nl2sql.core.service.MonitoringContext.setRagInfo(ragCount, null);
            
            return enhancement;
            
        } catch (Exception e) {
            log.warn("[SQLGenerator] RAG检索失败: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * 基于关联关系扩展表
     */
    @SuppressWarnings("unchecked")
    private Set<String> expandTablesByRelationship(Set<String> allTables, String relationshipInfo, 
                                                   Long datasourceId, String llmResponse) {
        Set<String> expandedTables = new HashSet<>(allTables);
        
        publishProgress("expanding_relationships", "🔗 分析表关联关系...");
        
        boolean shouldExpand = false;
        if (llmResponse != null && llmResponse.contains("missing_tables")) {
            shouldExpand = true;
            log.info("[SQLGenerator] 检测到LLM请求缺失表，启用智能扩展");
        }
        
        String fullRelationshipInfo = relationshipInfo;
        if (fullRelationshipInfo.isEmpty() && shouldExpand) {
            fullRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                datasourceId, new ArrayList<>(allTables));
        }
        
        if (shouldExpand && !fullRelationshipInfo.isEmpty()) {
            java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile("\\b(\\w+)\\.\\w+\\s*->\\s*(\\w+)\\.\\w+");
            java.util.regex.Matcher matcher = tablePattern.matcher(fullRelationshipInfo);
            
            int expandedCount = 0;
            int maxExpansion = 2;
            
            while (matcher.find() && expandedCount < maxExpansion) {
                String sourceTable = matcher.group(1).toLowerCase();
                String targetTable = matcher.group(2).toLowerCase();
                
                if (!allTables.contains(targetTable)) {
                    expandedTables.add(targetTable);
                    expandedCount++;
                    log.info("[SQLGenerator] 智能扩展表: {} -> {}", sourceTable, targetTable);
                }
            }
            
            if (expandedTables.size() > allTables.size()) {
                Set<String> newTables = new HashSet<>(expandedTables);
                newTables.removeAll(allTables);
                log.info("[SQLGenerator] 基于关联关系扩展表: {} -> {}", allTables.size(), expandedTables.size());
                log.info("[SQLGenerator] 新增表: {}", newTables);
                publishProgress("relationships_expanded", "🔗 基于关联关系扩展至 " + expandedTables.size() + " 张表");
            }
        } else if (!shouldExpand) {
            log.info("[SQLGenerator] LLM已确认表足够，跳过自动扩展");
        }
        
        return expandedTables;
    }
    
    /**
     * 构建负面示例
     */
    private String buildNegativeExamples(String query) {
        if (lowRatingExampleService == null) return "";
        
        try {
            List<LowRatingExampleService.LowRatingExample> badExamples = 
                lowRatingExampleService.findSimilarLowRatingExamples(query, 0.85, 2);
            
            if (badExamples.isEmpty()) return "";
            
            log.info("[SQLGenerator] ⚠️ 找到 {} 个低分示例，注入负面Prompt", badExamples.size());
            StringBuilder negBuilder = new StringBuilder();
            negBuilder.append("\n⚠️ **以下SQL曾被用户评为低分，请避免类似错误：**\n\n");
            
            for (int i = 0; i < badExamples.size(); i++) {
                LowRatingExampleService.LowRatingExample ex = badExamples.get(i);
                negBuilder.append(String.format(
                    "**反例 %d:**\n" +
                    "问题: %s\n" +
                    "错误SQL: %s\n" +
                    "用户反馈: %s\n" +
                    "评分: %d星\n\n",
                    i + 1, ex.getQuestion(), ex.getGeneratedSql(),
                    ex.getFeedbackText() != null ? ex.getFeedbackText() : "未提供原因",
                    ex.getRating()
                ));
            }
            
            negBuilder.append("**请确保生成的SQL与上述错误示例完全不同！**\n\n");
            publishProgress("negative_examples_loaded", "⚠️ 已加载 " + badExamples.size() + " 个负面示例");
            return negBuilder.toString();
            
        } catch (Exception e) {
            log.warn("[SQLGenerator] 检索低分示例失败", e);
            return "";
        }
    }
    
    /**
     * 构建SQL生成Prompt
     */
    private String buildSQLGenerationPrompt(String query, int tableCount, String availableTablesList,
                                           String schemaInfo, String joinHint, String ragEnhancement,
                                           String negativeExamples) {
        return String.format(
            "你是一个MySQL SQL专家。根据以下数据库结构和用户问题，生成一条MySQL查询SQL。\n\n" +
            "📋 **可用表清单（共 %d 张）**：%s\n" +
            "💡 **建议**：优先使用与用户问题最相关的表。如果单表无法满足需求，可以根据'表之间的关联关系'进行JOIN。\n" +
            "⚠️ **注意**：严禁使用上述列表之外的任何表！\n\n" +
            "数据库表结构：\n%s\n\n" +
            "%s" +
            "%s" +
            "%s" +
            "⏰ **时间查询关键区分（重要）**：\n" +
            "- ❌ 错误理解：'查询2026年4月10号的订单' → WHERE created_at >= NOW() - INTERVAL 7 DAY GROUP BY ...\n" +
            "- ✅ 正确理解：'查询2026年4月10号的订单' → WHERE DATE(created_at) = '2026-04-10' （单表查询，不要GROUP BY）\n" +
            "- **判断规则**：用户说'X月X号'或'X年X月X日'是查具体某一天的数据，不是按天统计！\n\n" +
            "🚫 **严禁同义词替换（极其重要）**：\n" +
            "- 永远不要对用户原句做字面同义词替换改写，不要把词语强行换成近义词\n" +
            "- 若问句中已经出现具体日期、具体数字、具体名称、具体对象等明确实体：\n" +
            "  所有代词：当天、当日、该月、这家、此项、该商品、其上、对应等\n" +
            "  一律就近绑定前面已出现的具体实体\n" +
            "- 严禁私自泛化替换成全局默认值：今天、当前本月、全部、本店、系统当前时间\n" +
            "- 生成 SQL 禁止同时出现固定指定值 + 系统动态当前值，避免逻辑冲突\n\n" +
            "用户问题：%s\n\n" +
            "要求：\n" +
            "1. 只输出SQL语句，不要包含```sql或其他标记\n" +
            "2. **表使用规范**：所有SELECT中的字段必须属于FROM或JOIN中的表\n" +
            "3. 添加LIMIT限制返回行数（但如果是GROUP BY统计查询，可以不设LIMIT或设为较大值）\n" +
            "4. **别名规范**：所有SELECT字段都必须使用 AS 指定中文别名\n" +
            "5. **重要：识别统计类问题并使用聚合函数**\n" +
            "   - 只有当用户明确要求'统计'、'汇总'、'合计'、'平均'、'分组'时，才使用 GROUP BY\n" +
            "   - 如果用户想看'每条记录'，就不要 GROUP BY；如果想看'汇总数据'，才用 GROUP BY\n" +
            "6. **SELECT字段规则**：GROUP BY查询中SELECT只能包含GROUP BY字段和聚合函数\n" +
            "7. **时间格式化规范**：按天/月/年统计必须使用 DATE_FORMAT() 函数\n" +
            "8. **ORDER BY 别名一致性规则**：ORDER BY 中使用的字段名或别名，必须与 SELECT 中定义的完全一致\n" +
            "9. **表关联规则**：必须使用直接JOIN，禁止使用子查询或IN子句进行表关联\n" +
            "10. **语义一致性强制规则**：对于相同语义的查询，必须保持SQL结构完全一致\n" +
            "\nSQL：",
            tableCount, availableTablesList, schemaInfo,
            joinHint.isEmpty() ? "" : joinHint + "\n\n",
            ragEnhancement, negativeExamples, query
        );
    }
    
    /**
     * 检查并重新生成低分SQL
     */
    private String checkAndRegenerateIfLowRating(String query, String sql) {
        if (lowRatingExampleService == null) return sql;
        
        try {
            LowRatingExampleService.LowRatingExample badMatch = 
                lowRatingExampleService.checkIfSimilarToLowRating(query, sql);
            
            if (badMatch != null) {
                log.warn("[SQLGenerator] ⚠️ 生成的SQL与低分示例完全匹配，触发重新生成");
                log.warn("[SQLGenerator] 低分原因: {}", badMatch.getFeedbackText());
                
                String correctionPrompt = String.format(
                    "⚠️ **重要：刚才生成的SQL曾被用户评为%d星（低分）**\n" +
                    "错误SQL: %s\n" +
                    "用户反馈: %s\n\n" +
                    "请重新生成一个完全不同的SQL，避免上述错误。\n" +
                    "用户问题：%s\n\nSQL：",
                    badMatch.getRating(), badMatch.getGeneratedSql(),
                    badMatch.getFeedbackText() != null ? badMatch.getFeedbackText() : "未提供原因",
                    query
                );
                
                publishProgress("regenerating_sql", "⚠️ 检测到低分风险，重新生成...");
                sql = modelRouter.smartGenerateSQL(correctionPrompt, query);
                sql = MarkdownUtils.cleanSQL(sql);
                
                log.info("[SQLGenerator] ✅ 重新生成后的SQL: {}", sql);
                publishProgress("sql_regenerated", "✅ 已重新生成SQL");
            }
        } catch (Exception e) {
            log.warn("[SQLGenerator] Layer 2检查失败，继续执行", e);
        }
        
        return sql;
    }
    
    /**
     * 缓存SQL
     */
    private void cacheSQL(String query, String sql, Long datasourceId) {
        if (queryCacheService == null) return;
        
        try {
            Map<String, Object> cacheData = new HashMap<>();
            cacheData.put("sql", sql);
            cacheData.put("question", query);
            cacheData.put("datasourceId", datasourceId);
            
            com.nl2sql.core.cache.QueryCacheService.CachedResult cacheResult = 
                new com.nl2sql.core.cache.QueryCacheService.CachedResult();
            cacheResult.setData(Collections.singletonList(cacheData));
            cacheResult.setRowCount(1);
            cacheResult.setExecutionTime(0);
            
            queryCacheService.putToCache(query, cacheResult, 60);
            log.info("[SQLGenerator] SQL 已缓存: question={}", query);
        } catch (Exception e) {
            log.warn("[SQLGenerator] SQL 缓存失败", e);
        }
    }
    
    /**
     * 发布进度事件
     */
    private void publishProgress(String step, String message) {
        if (eventPublisher != null) {
            String sessionId = CURRENT_SESSION_ID.get();
            if (sessionId != null) {
                try {
                    eventPublisher.publishEvent(new StreamProgressEvent(this, sessionId, step, message, null));
                    log.debug("[StreamProgress] 发布事件: step={}, message={}", step, message);
                } catch (Exception e) {
                    log.warn("[StreamProgress] 发布事件失败: {}", e.getMessage());
                }
            }
        }
    }
    
    @Tool("基于已选定的表和用户问题生成SQL语句。输入查询问题、表列表、数据源ID等信息，返回生成的SQL")
    public String generateSQL(String query, List<String> tables, Long datasourceId, 
                             String schemaInfo, String relationshipInfo, String llmResponse) {
        try {
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("query", query);
                    put("tables", tables);
                    put("datasourceId", datasourceId);
                    put("schemaInfo", schemaInfo);
                    put("relationshipInfo", relationshipInfo != null ? relationshipInfo : "");
                    put("llmResponse", llmResponse != null ? llmResponse : "");
                }})
                .build();
            
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            if (result.isSuccess()) {
                return objectMapper.writeValueAsString(result.getData());
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", result.getErrorMessage());
                return objectMapper.writeValueAsString(error);
            }
        } catch (Exception e) {
            log.error("[SQLGenerator] 执行失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            try {
                return objectMapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"序列化失败\"}";
            }
        }
    }
}
