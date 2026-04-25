package com.nl2sql.core.service;

import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.agent.validation.SQLValidationService;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.llm.IndustryConceptDictionary;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.llm.SynonymService;
import com.nl2sql.core.rag.LowRatingExampleService;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.RagLearningContext;
import com.nl2sql.core.retriever.VectorRetriever;
import com.nl2sql.metadata.service.TableRelationshipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * NL2SQL服务类 - 将自然语言转换为SQL
 * ⚠️ 注意：这不是Tool，是内部服务类，供StandardQuerySkill调用
 */
@Slf4j
@Service
public class NL2SQLService {
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired
    private TableRelationshipService relationshipService;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private SynonymService synonymService;
    
    @Autowired
    private com.nl2sql.core.mapper.MetadataMapper metadataMapper;
    
    @Autowired
    private SQLValidationService sqlValidationService;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    @Autowired
    private IndustryConceptDictionary industryConceptDictionary;
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired(required = false)
    private com.nl2sql.core.cache.QueryCacheService queryCacheService;
    
    @Autowired
    private MetadataCacheService metadataCacheService;
    
    @Autowired(required = false)
    private LowRatingExampleService lowRatingExampleService;
    
    @Autowired
    private SQLCorrectionService sqlCorrectionService;
    
    @Autowired
    private SessionContextManager sessionContextManager;
    
    @Autowired
    private SchemaRetrievalService schemaRetrievalService;
    
    @Autowired
    private TableSelectionOrchestrator tableSelectionOrchestrator;
    

    
    /**
     * 根据用户问题和数据源ID生成SQL
     * 
     * @param query 用户自然语言问题
     * @param datasourceId 数据源ID
     * @return 生成的SQL语句，或澄清信号
     */
    public String generateSQL(String query, Long datasourceId) {
        try {
            // 执行表选择流程
            TableSelectionOrchestrator.TableSelectionResult selectionResult = 
                tableSelectionOrchestrator.execute(
                    query, 
                    datasourceId,
                    schemaRetrievalService,
                    sessionContextManager,
                    (q, s) -> buildTableCheckPrompt(q, s, "", datasourceId),
                    step -> sessionContextManager.publishProgress(this, step, "")
                );
            
            // 检查缓存命中
            if (selectionResult.hasCachedSQL()) {
                return selectionResult.getCachedSQL();
            }
            
            // 检查错误
            if (selectionResult.hasError()) {
                return selectionResult.getError();
            }
            
            // 检查是否需要澄清
            if (selectionResult.isNeedsClarification()) {
                return "CLARIFICATION_NEEDED: " + selectionResult.getClarificationMessage();
            }
            
            // 检查是否需要用户选择表
            if (selectionResult.isNeedsTableSelection()) {
                return "TABLE_SELECTION_NEEDED: " + selectionResult.getTableSelectionList();
            }
            
            // 继续SQL生成流程
            String expandedQuery = selectionResult.getExpandedQuery();
            Set<String> allTables = selectionResult.getSelectedTables();
            String lastLlmResponse = selectionResult.getLastLlmResponse();
            
            // 如果是单数据源快速路径，需要构建schema和relationship
            if (selectionResult.isSkipLLMSelection()) {
                String schemaInfo = buildTableSchemaInfo(new ArrayList<>(allTables), datasourceId);
                String relationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(allTables));
                return generateSQLWithTables(expandedQuery, allTables, schemaInfo, relationshipInfo, datasourceId, query, lastLlmResponse);
            }
            
            // 正常流程
            return generateSQLWithTables(expandedQuery, allTables, "", "", datasourceId, query, lastLlmResponse);
            
        } catch (Exception e) {
            log.error("[NL2SQLService] 生成SQL失败", e);
            return "错误：" + e.getMessage();
        }
    }
    
    /**
     * ✅ 关键方法：基于已选定的表生成 SQL（支持单数据源快速路径和多数据源完整流程）
     */
    private String generateSQLWithTables(String expandedQuery, Set<String> allTables, 
                                        String schemaInfo, String relationshipInfo,
                                        Long datasourceId, String originalQuery, String llmResponse) {
        try {
            // 1. RAG 检索相似问答对（中等复杂度查询）
            String ragEnhancement = "";
            if (ragService != null) {
                try {
                    sessionContextManager.publishProgress(this, "rag_search", "📚 检索历史相似案例...");
                    List<RagKnowledgeBaseService.KnowledgeItem> similarItems = 
                        ragService.searchSimilarQuestions(expandedQuery, 1); // ✅ 限制为1个，避免Prompt过长
                    
                    if (!similarItems.isEmpty()) {
                        log.info("[NL2SQLService] RAG检索到 {} 个参考示例", similarItems.size());
                        StringBuilder ragBuilder = new StringBuilder();
                        ragBuilder.append("\n\n参考示例（历史成功案例，请借鉴其JOIN方式和字段选择）:\n");
                        
                        int validCount = 0;
                        for (int i = 0; i < Math.min(similarItems.size(), 1); i++) { // ✅ 最多使用1个
                            RagKnowledgeBaseService.KnowledgeItem item = similarItems.get(i);
                            
                            // ✅ 关键过滤：跳过包含GROUP BY但问题未要求统计的示例
                            if (item.getSqlExample() != null && !item.getSqlExample().isEmpty()) {
                                String sql = item.getSqlExample().toUpperCase();
                                boolean hasGroupBy = sql.contains("GROUP BY");
                                boolean isStatQuestion = item.getQuestion().contains("统计") || 
                                                       item.getQuestion().contains("汇总") ||
                                                       item.getQuestion().contains("平均") ||
                                                       item.getQuestion().contains("合计");
                                
                                // 如果SQL有GROUP BY但问题不是统计类，跳过此示例
                                if (hasGroupBy && !isStatQuestion) {
                                    log.warn("[NL2SQLService] 跳过错误的RAG示例: question={}, reason=非统计问题但包含GROUP BY", 
                                        item.getQuestion());
                                    continue;
                                }
                            }
                            
                            ragBuilder.append(String.format("\n示例%d:\n", ++validCount));
                            ragBuilder.append("问题: ").append(item.getQuestion()).append("\n");
                            if (item.getSqlExample() != null && !item.getSqlExample().isEmpty()) {
                                ragBuilder.append("SQL: ").append(item.getSqlExample()).append("\n");
                            }
                        }
                        
                        if (validCount > 0) {
                            ragEnhancement = ragBuilder.toString();
                            log.info("[NL2SQLService] 有效RAG示例数量: {}", validCount);
                            sessionContextManager.publishProgress(this, "rag_completed", "✅ 找到 " + validCount + " 个参考案例");
                        } else {
                            log.info("[NL2SQLService] 所有RAG示例均被过滤，不使用RAG增强");
                        }
                    } else {
                        log.debug("[NL2SQLService] RAG未找到相似示例");
                    }
                } catch (Exception e) {
                    log.warn("[NL2SQLService] RAG检索失败: {}", e.getMessage());
                }
            }
            
            // 2. ✅ 基于关联关系智能扩展表（补充必要的JOIN表）
            Set<String> expandedTables = new HashSet<>(allTables);
            
            sessionContextManager.publishProgress(this, "expanding_relationships", "🔗 分析表关联关系...");
            
            // ⚠️ 关键：只在LLM明确请求缺失表时才进行智能扩展
            // 如果LLM在追问阶段已确认"表已足够"，则不强制扩展，尊重LLM判断
            boolean shouldExpand = false;
            
            // 检查是否有missing_tables信号（说明LLM知道自己缺表）
            if (llmResponse != null && llmResponse.contains("missing_tables")) {
                shouldExpand = true;
                log.info("[NL2SQLService] 检测到LLM请求缺失表，启用智能扩展");
            }
            
            // 第一次：基于LLM选的表获取关联关系
            String fullRelationshipInfo = relationshipInfo;
            if (fullRelationshipInfo.isEmpty() && shouldExpand) {
                fullRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(allTables));
            }
            
            // 从关联关系中提取所有涉及的表名，并添加到expandedTables
            if (shouldExpand && !fullRelationshipInfo.isEmpty()) {
                java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile("\\b(\\w+)\\.\\w+\\s*->\\s*(\\w+)\\.\\w+");
                java.util.regex.Matcher matcher = tablePattern.matcher(fullRelationshipInfo);
                
                int expandedCount = 0;
                int maxExpansion = 2; // 最多扩展2个表，避免过度扩展
                
                while (matcher.find() && expandedCount < maxExpansion) {
                    String sourceTable = matcher.group(1).toLowerCase();
                    String targetTable = matcher.group(2).toLowerCase();
                    
                    // 只扩展不在原表列表中的表
                    if (!allTables.contains(targetTable)) {
                        expandedTables.add(targetTable);
                        expandedCount++;
                        log.info("[NL2SQLService] 智能扩展表: {} -> {}", sourceTable, targetTable);
                    }
                }
                
                if (expandedTables.size() > allTables.size()) {
                    Set<String> newTables = new HashSet<>(expandedTables);
                    newTables.removeAll(allTables);
                    log.info("[NL2SQLService] 基于关联关系扩展表: {} -> {}", allTables.size(), expandedTables.size());
                    log.info("[NL2SQLService] 新增表: {}", newTables);
                    sessionContextManager.publishProgress(this, "relationships_expanded", "🔗 基于关联关系扩展至 " + expandedTables.size() + " 张表");
                }
            } else if (!shouldExpand) {
                log.info("[NL2SQLService] LLM已确认表足够，跳过自动扩展，使用原始表列表: {}", allTables);
            }
            
            // ⚠️ 关键：基于扩展后的表重新获取完整的关联关系
            if (expandedTables.size() > allTables.size()) {
                fullRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(expandedTables));
            }
            
            log.info("[NL2SQLService] 最终用于SQL生成的表: {}", expandedTables);
            log.info("[NL2SQLService] 最终关联关系数量: {}", 
                fullRelationshipInfo.isEmpty() ? 0 : fullRelationshipInfo.split("\n").length);
            
            String finalSchemaInfo = buildTableSchemaInfo(new ArrayList<>(expandedTables), datasourceId);
            
            log.info("[NL2SQLService] ========== SchemaInfo(前500字符) ==========");
            log.info("[NL2SQLService] {}", finalSchemaInfo);
            log.info("[NL2SQLService] ================================================");
            
            String joinHint = fullRelationshipInfo.isEmpty() ? "" : 
                fullRelationshipInfo + "\n重要：以上关联关系是数据库中已定义的，请直接用于JOIN语句，不要再次询问或澄清。";
            
            // ✅ Layer 1: 检索低分示例并注入负面Prompt
            String negativeExamples = "";
            if (lowRatingExampleService != null) {
                try {
                    List<LowRatingExampleService.LowRatingExample> badExamples = 
                        lowRatingExampleService.findSimilarLowRatingExamples(originalQuery, 0.85, 2);
                    
                    if (!badExamples.isEmpty()) {
                        log.info("[NL2SQLService] ⚠️ 找到 {} 个低分示例，注入负面Prompt", badExamples.size());
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
                                i + 1,
                                ex.getQuestion(),
                                ex.getGeneratedSql(),
                                ex.getFeedbackText() != null ? ex.getFeedbackText() : "未提供原因",
                                ex.getRating()
                            ));
                        }
                        
                        negBuilder.append("**请确保生成的SQL与上述错误示例完全不同！**\n\n");
                        negativeExamples = negBuilder.toString();
                        sessionContextManager.publishProgress(this, "negative_examples_loaded", "⚠️ 已加载 " + badExamples.size() + " 个负面示例");
                    }
                } catch (Exception e) {
                    log.warn("[NL2SQLService] 检索低分示例失败", e);
                }
            }
            
            // ✅ 关键：明确列出可用表清单，分级提示（不再硬性限制）
            String availableTablesList = String.join(", ", expandedTables);
            
            sessionContextManager.publishProgress(this, "generating_final_sql", "🤖 生成最终SQL...");
            
            String sqlPrompt = String.format(
                "你是一个MySQL SQL专家。根据以下数据库结构和用户问题，生成一条MySQL查询SQL。\n\n" +
                "📋 **可用表清单（共 %d 张）**：%s\n" +
                "💡 **建议**：优先使用与用户问题最相关的表。如果单表无法满足需求，可以根据'表之间的关联关系'进行JOIN。\n\n" +
                "数据库表结构：\n%s\n\n" +
                "%s" +
                "%s" +
                "%s" +  // ✅ Layer 1: 负面示例
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
                "2. **表使用规范**：\n" +
                "   - 优先使用'可用表清单'中的表\n" +
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
                "   - ⚠️ **关键判断规则**：只有当用户明确要求'统计'、'汇总'、'合计'、'平均'、'分组'时，才使用 GROUP BY\n" +
                "   - ❌ 错误场景：用户问'查最近7天的订单'、'显示订单列表'、'查看所有订单' → 这是查询详情，不要加 GROUP BY\n" +
                "   - ✅ 正确场景：用户问'统计每天的订单数'、'按地区汇总销售额'、'各城市的平均金额' → 这是统计汇总，需要 GROUP BY\n" +
                "   - **判断依据**：如果用户想看'每条记录'，就不要 GROUP BY；如果想看'汇总数据'，才用 GROUP BY\n" +
                "   - 常用聚合函数：SUM()求和、COUNT()计数、AVG()平均、MAX()最大、MIN()最小\n" +
                "   - 例如（统计）：'统计每个地区的销售额' -> SELECT region, SUM(amount) FROM orders GROUP BY region\n" +
                "   - 例如（详情）：'查最近7天的订单' -> SELECT * FROM orders WHERE created_at >= NOW() - INTERVAL 7 DAY\n" +
                "6. **SELECT字段规则**：\n" +
                "   - GROUP BY查询：SELECT中只能包含GROUP BY字段和聚合函数，不能直接选择非分组字段\n" +
                "   - 错误示例：SELECT user_id, province, SUM(amount) ... GROUP BY province （user_id不在GROUP BY中）\n" +
                "   - 正确示例：SELECT province, SUM(amount) ... GROUP BY province\n" +
                "7. **时间格式化规范**：\n" +
                "   - 如果用户要求按天/月/年统计（如'最近10天每天的订单金额'），必须使用 DATE_FORMAT() 函数格式化时间\n" +
                "   - 按天统计：DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期'\n" +
                "   - 按月统计：DATE_FORMAT(created_at, '%%Y-%%m') AS '订单月份'\n" +
                "   - 按年统计：DATE_FORMAT(created_at, '%%Y') AS '订单年份'\n" +
                "   - ❌ 错误：DATE(created_at) 会返回带时分秒的格式\n" +
                "   - ✅ 正确：DATE_FORMAT(created_at, '%%Y-%%m-%%d') 只返回日期部分\n" +
                "   - ⚠️ **重要区分**：\n" +
                "     * **指定具体日期**：'查询2026年4月10号的订单' → WHERE DATE(created_at) = '2026-04-10' （不要GROUP BY）\n" +
                "     * **按天分组统计**：'统计最近7天每天的订单数' → GROUP BY DATE_FORMAT(created_at, '%%Y-%%m-%%d') （需要GROUP BY）\n" +
                "     * **关键判断**：用户说'X月X号'是查那一天的数据，不是按天分组！\n" +
                "8. **ORDER BY 别名一致性规则（重要）**：\n" +
                "   - ⚠️ **强制规则**：ORDER BY 中使用的字段名或别名，必须与 SELECT 中定义的完全一致\n" +
                "   - 错误示例：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期' ... ORDER BY order_date\n" +
                "     （SELECT 中是 '订单日期'，但 ORDER BY 用了 order_date）\n" +
                "   - 正确示例1：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期' ... ORDER BY '订单日期'\n" +
                "   - 正确示例2：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS order_date ... ORDER BY order_date\n" +
                "   - **关键**：SELECT 和 ORDER BY 必须使用相同的别名，不能混用\n" +
                "9. **表关联规则**：\n" +
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
                "10. **⚠️ 语义一致性强制规则（重要）**：\n" +
                "    - **对于相同语义的查询（如'查询用户X的订单'），必须保持SQL结构完全一致**\n" +
                "    - 例如：'查询用户张三的订单'和'查询用户李四的订单'应该生成相同的SQL结构，只是WHERE条件不同\n" +
                "    - **表选择一致性**：如果第一次选择了orders JOIN users，第二次也必须使用相同的表组合\n" +
                "    - **字段映射一致性**：同一概念必须映射到相同字段（如用户名始终用u.username，不用o.receiver_name）\n" +
                "    - **JOIN顺序一致性**：FROM orders o JOIN users u ON ... 的顺序必须保持一致\n" +
                "    - ❌ 错误：第一次用 JOIN users，第二次用 JOIN order_items\n" +
                "    - ✅ 正确：两次都用 JOIN users u ON o.user_id = u.id\n" +
                "    - **关键原则**：优先使用业务主键关联（user_id），而非文本字段匹配（receiver_name）\n" +
                "\nSQL：",
                expandedTables.size(), availableTablesList,
                finalSchemaInfo, fullRelationshipInfo.isEmpty() ? "" : fullRelationshipInfo + "\n\n", ragEnhancement, negativeExamples, expandedQuery
            );
            
            String sql = modelRouter.smartGenerateSQL(sqlPrompt, expandedQuery);
            
            // 清理SQL
            sql = MarkdownUtils.cleanSQL(sql);
            
            log.info("[NL2SQLService] 生成的SQL: {}", sql);
            sessionContextManager.publishProgress(this, "sql_generated", "✅ SQL生成完成");
            
            // ✅ 关键修复：从SQL中提取实际使用的表，更新ThreadLocal（用于5星反馈缓存）
            Set<String> actualTablesInSQL = extractTablesFromSQL(sql);
            if (!actualTablesInSQL.isEmpty()) {
                List<String> actualTablesList = new ArrayList<>(actualTablesInSQL);
                com.nl2sql.core.service.TableSelectionOrchestrator.setFinalSelectedTables(actualTablesList);
                log.info("[NL2SQLService] ✅ 已更新ThreadLocal为SQL实际使用的表: {}", actualTablesList);
            }
            
            // ✅ 检测SQL中使用的表是否都在expandedTables中，如果有新表则补充schema并重新生成
            Set<String> tablesInSQL = actualTablesInSQL;
            Set<String> missingTables = new HashSet<>(tablesInSQL);
            missingTables.removeAll(expandedTables);
            
            if (!missingTables.isEmpty()) {
                log.warn("[NL2SQLService] ⚠️ SQL中使用了未提供schema的表: {}", missingTables);
                log.warn("[NL2SQLService] 原始expandedTables: {}", expandedTables);
                
                // 补充缺失表的schema
                expandedTables.addAll(missingTables);
                String updatedSchemaInfo = buildTableSchemaInfo(new ArrayList<>(expandedTables), datasourceId);
                String updatedRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(expandedTables));
                
                log.info("[NL2SQLService] 已补充表schema，重新生成SQL");
                sessionContextManager.publishProgress(this, "regenerating_sql_with_full_schema", "⚠️ 检测到缺失表，补充schema后重新生成...");
                
                // 重新构建Prompt
                String updatedAvailableTablesList = String.join(", ", expandedTables);
                String updatedSqlPrompt = String.format(
                    "你是一个MySQL SQL专家。根据以下数据库结构和用户问题，生成一条MySQL查询SQL。\n\n" +
                    "📋 **可用表清单（共 %d 张）**：%s\n" +
                    "💡 **建议**：优先使用与用户问题最相关的表。如果单表无法满足需求，可以根据'表之间的关联关系'进行JOIN。\n\n" +
                    "数据库表结构：\n%s\n\n" +
                    "%s" +
                    "%s" +
                    "%s" +  // ✅ Layer 1: 负面示例
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
                    "2. **表使用规范**：\n" +
                    "   - 优先使用'可用表清单'中的表\n" +
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
                    "   - ⚠️ **关键判断规则**：只有当用户明确要求'统计'、'汇总'、'合计'、'平均'、'分组'时，才使用 GROUP BY\n" +
                    "   - ❌ 错误场景：用户问'查最近7天的订单'、'显示订单列表'、'查看所有订单' → 这是查询详情，不要加 GROUP BY\n" +
                    "   - ✅ 正确场景：用户问'统计每天的订单数'、'按地区汇总销售额'、'各城市的平均金额' → 这是统计汇总，需要 GROUP BY\n" +
                    "   - **判断依据**：如果用户想看'每条记录'，就不要 GROUP BY；如果想看'汇总数据'，才用 GROUP BY\n" +
                    "   - 常用聚合函数：SUM()求和、COUNT()计数、AVG()平均、MAX()最大、MIN()最小\n" +
                    "   - 例如（统计）：'统计每个地区的销售额' -> SELECT region, SUM(amount) FROM orders GROUP BY region\n" +
                    "   - 例如（详情）：'查最近7天的订单' -> SELECT * FROM orders WHERE created_at >= NOW() - INTERVAL 7 DAY\n" +
                    "6. **SELECT字段规则**：\n" +
                    "   - GROUP BY查询：SELECT中只能包含GROUP BY字段和聚合函数，不能直接选择非分组字段\n" +
                    "   - 错误示例：SELECT user_id, province, SUM(amount) ... GROUP BY province （user_id不在GROUP BY中）\n" +
                    "   - 正确示例：SELECT province, SUM(amount) ... GROUP BY province\n" +
                    "7. **时间格式化规范**：\n" +
                    "   - 如果用户要求按天/月/年统计（如'最近10天每天的订单金额'），必须使用 DATE_FORMAT() 函数格式化时间\n" +
                    "   - 按天统计：DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期'\n" +
                    "   - 按月统计：DATE_FORMAT(created_at, '%%Y-%%m') AS '订单月份'\n" +
                    "   - 按年统计：DATE_FORMAT(created_at, '%%Y') AS '订单年份'\n" +
                    "   - ❌ 错误：DATE(created_at) 会返回带时分秒的格式\n" +
                    "   - ✅ 正确：DATE_FORMAT(created_at, '%%Y-%%m-%%d') 只返回日期部分\n" +
                    "   - ⚠️ **重要区分**：\n" +
                    "     * **指定具体日期**：'查询2026年4月10号的订单' → WHERE DATE(created_at) = '2026-04-10' （不要GROUP BY）\n" +
                    "     * **按天分组统计**：'统计最近7天每天的订单数' → GROUP BY DATE_FORMAT(created_at, '%%Y-%%m-%%d') （需要GROUP BY）\n" +
                    "     * **关键判断**：用户说'X月X号'是查那一天的数据，不是按天分组！\n" +
                    "8. **ORDER BY 别名一致性规则（重要）**：\n" +
                    "   - ⚠️ **强制规则**：ORDER BY 中使用的字段名或别名，必须与 SELECT 中定义的完全一致\n" +
                    "   - 错误示例：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期' ... ORDER BY order_date\n" +
                    "     （SELECT 中是 '订单日期'，但 ORDER BY 用了 order_date）\n" +
                    "   - 正确示例1：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS '订单日期' ... ORDER BY '订单日期'\n" +
                    "   - 正确示例2：SELECT DATE_FORMAT(created_at, '%%Y-%%m-%%d') AS order_date ... ORDER BY order_date\n" +
                    "   - **关键**：SELECT 和 ORDER BY 必须使用相同的别名，不能混用\n" +
                    "9. **表关联规则**：\n" +
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
                    "10. **⚠️ 语义一致性强制规则（重要）**：\n" +
                    "    - **对于相同语义的查询（如'查询用户X的订单'），必须保持SQL结构完全一致**\n" +
                    "    - 例如：'查询用户张三的订单'和'查询用户李四的订单'应该生成相同的SQL结构，只是WHERE条件不同\n" +
                    "    - **表选择一致性**：如果第一次选择了orders JOIN users，第二次也必须使用相同的表组合\n" +
                    "    - **字段映射一致性**：同一概念必须映射到相同字段（如用户名始终用u.username，不用o.receiver_name）\n" +
                    "    - **JOIN顺序一致性**：FROM orders o JOIN users u ON ... 的顺序必须保持一致\n" +
                    "    - ❌ 错误：第一次用 JOIN users，第二次用 JOIN order_items\n" +
                    "    - ✅ 正确：两次都用 JOIN users u ON o.user_id = u.id\n" +
                    "    - **关键原则**：优先使用业务主键关联（user_id），而非文本字段匹配（receiver_name）\n" +
                    "\nSQL：",
                    expandedTables.size(), updatedAvailableTablesList,
                    updatedSchemaInfo, updatedRelationshipInfo.isEmpty() ? "" : updatedRelationshipInfo + "\n\n", ragEnhancement, negativeExamples, expandedQuery
                );
                
                sql = modelRouter.smartGenerateSQL(updatedSqlPrompt, expandedQuery);
                sql = MarkdownUtils.cleanSQL(sql);
                
                log.info("[NL2SQLService] ✅ 重新生成后的SQL: {}", sql);
                sessionContextManager.publishProgress(this, "sql_regenerated", "✅ 已重新生成SQL");
            }
            
            // ✅ Layer 2: 检查是否与历史低分SQL高度相似，是则重新生成
            if (lowRatingExampleService != null) {
                try {
                    LowRatingExampleService.LowRatingExample badMatch = 
                        lowRatingExampleService.checkIfSimilarToLowRating(originalQuery, sql);
                    
                    if (badMatch != null) {
                        log.warn("[NL2SQLService] ⚠️ 生成的SQL与低分示例完全匹配，触发重新生成");
                        log.warn("[NL2SQLService] 低分原因: {}", badMatch.getFeedbackText());
                        
                        // 构造修正Prompt
                        String correctionPrompt = String.format(
                            "⚠️ **重要：刚才生成的SQL曾被用户评为%d星（低分）**\n" +
                            "错误SQL: %s\n" +
                            "用户反馈: %s\n\n" +
                            "请重新生成一个完全不同的SQL，避免上述错误。\n" +
                            "用户问题：%s\n\nSQL：",
                            badMatch.getRating(),
                            badMatch.getGeneratedSql(),
                            badMatch.getFeedbackText() != null ? badMatch.getFeedbackText() : "未提供原因",
                            expandedQuery
                        );
                        
                        sessionContextManager.publishProgress(this, "regenerating_sql", "⚠️ 检测到低分风险，重新生成...");
                        sql = modelRouter.smartGenerateSQL(correctionPrompt, expandedQuery);
                        sql = MarkdownUtils.cleanSQL(sql);
                        
                        log.info("[NL2SQLService] ✅ 重新生成后的SQL: {}", sql);
                        sessionContextManager.publishProgress(this, "sql_regenerated", "✅ 已重新生成SQL");
                    }
                } catch (Exception e) {
                    log.warn("[NL2SQLService] Layer 2检查失败，继续执行", e);
                }
            }
            
            // ⚠️ P0优化：SQL 验证与 Self-Correction
            sessionContextManager.publishProgress(this, "validating_sql", "🔍 验证SQL正确性...");
            
            // ✅ 关键修复：从 SQL 中提取实际使用的表，补充缺失表的 schema（用于纠错）
            Set<String> tablesInSQLForCorrection = extractTablesFromSQL(sql);
            Set<String> missingTablesForCorrection = new HashSet<>(tablesInSQLForCorrection);
            missingTablesForCorrection.removeAll(expandedTables);
            
            String correctionSchemaInfo = finalSchemaInfo;
            String correctionRelationshipInfo = fullRelationshipInfo;
            
            if (!missingTablesForCorrection.isEmpty()) {
                log.warn("[NL2SQLService] ⚠️ SQL验证阶段检测到缺失表: {}", missingTablesForCorrection);
                
                // 补充缺失表的 schema
                Set<String> allTablesForCorrection = new HashSet<>(expandedTables);
                allTablesForCorrection.addAll(missingTablesForCorrection);
                correctionSchemaInfo = buildTableSchemaInfo(new ArrayList<>(allTablesForCorrection), datasourceId);
                correctionRelationshipInfo = relationshipService.getRelationshipsForPrompt(
                    datasourceId, new ArrayList<>(allTablesForCorrection));
                
                log.info("[NL2SQLService] 已补充纠错用 schema，包含 {} 张表", allTablesForCorrection.size());
            }
            
            sql = sqlCorrectionService.validateAndCorrectSQL(sql, expandedQuery, datasourceId, 
                correctionSchemaInfo, correctionRelationshipInfo, 3);
            sessionContextManager.publishProgress(this, "validation_completed", "✅ SQL验证通过");
            
            // ⚠️ RAG优化：设置学习上下文（供后续 SQL 执行后自动学习）
            RagLearningContext.setCurrentQuestion(expandedQuery);
            RagLearningContext.setCurrentSql(sql);
            
            // ✅ 关键修复：保存当前 SQL 和查询问题到 ThreadLocal（用于 AI 总结/图表生成）
            sessionContextManager.saveCurrentContext(sql, expandedQuery);
            
            // ✅ 新增：保存 selected_tables 到 SessionContext（用于5星反馈缓存）
            if (allTables != null && !allTables.isEmpty()) {
                java.util.List<String> tablesList = new java.util.ArrayList<>(allTables);
                sessionContextManager.saveSelectedTables(tablesList);
                log.info("[NL2SQLService] 已保存表列表: sessionId={}, tables={}", 
                    sessionContextManager.getCurrentSessionId(), tablesList);
            }
            
            return sql;
            
        } catch (Exception e) {
            log.error("[NL2SQLService] 生成SQL失败", e);
            return "错误：" + e.getMessage();
        }
    }
    
    /**
     * 获取表的中文注释
     */
    private String getTableComment(String tableName, Long datasourceId) {
        return schemaRetrievalService.getTableComment(tableName, datasourceId);
    }
    
    private String buildTableSchemaInfo(List<String> tables, Long datasourceId) {
        return schemaRetrievalService.buildTableSchemaInfo(tables, datasourceId);
    }
    
    private String buildTableCheckPrompt(String query, String schemaInfo, String relationshipInfo, Long datasourceId) {
        String relationshipHint = relationshipInfo.isEmpty() ? "" : 
            "\n\n表之间的关联关系（重要）：\n" + relationshipInfo + 
            "\n注意：如果需要关联两张表，必须包含中间的所有表。例如：A.ref_id -> B.id -> C.ref_id，需要同时选中 A、B、C 三张表。";
        
        // ✅ 关键修复：检测用户是否指定了表名偏好
        String tablePreferenceHint = "";
        if (query.contains("[优先使用表:")) {
            int start = query.indexOf("[优先使用表:") + "[优先使用表:".length();
            int end = query.indexOf("]", start);
            if (end > start) {
                String preferredTable = query.substring(start, end).trim();
                tablePreferenceHint = String.format(
                    "\n\n⚠️ **用户明确要求**：必须使用包含'%s'关键词的表\n" +
                    "- **强制规则**：在可用表列表中，查找表名或表注释中包含'%s'的表\n" +
                    "- 如果找到匹配的表，它**必须**出现在 selected_tables 中\n" +
                    "- 即使其他表也有相关字段，也必须优先使用用户指定的表\n" +
                    "- 只有在没有任何表匹配'%s'时，才可以根据语义选择最相关的表\n" +
                    "- 示例：用户说'使用用户表'，应选择表名为 users 或注释包含'用户'的表",
                    preferredTable, preferredTable, preferredTable
                );
                log.info("[NL2SQLService] ⚠️ 检测到用户表偏好（强制）: {}", preferredTable);
            }
        }
        
        // ✅ 新增：告知LLM查询文本已做归一化处理，帮助理解意图
        String normalizationHint = "";
        if (query.contains("{DATE_RELATIVE}") || query.contains("{PERSON}") || 
            query.contains("{LOCATION}") || query.contains("{AMOUNT}")) {
            normalizationHint = "\n\n💡 **提示**：以下查询文本已做归一化处理：\n" +
                "- {DATE_RELATIVE} = 相对时间词（昨天/前天/上周等）\n" +
                "- {PERSON} = 人名\n" +
                "- {LOCATION} = 地名\n" +
                "- {AMOUNT} = 金额\n" +
                "请根据这些占位符理解用户意图，生成正确的SQL。";
        }
        
        // ✅ 注入行业概念（动态增强提示词）
        String metricDescription = industryConceptDictionary.generateMetricDescription(datasourceId);
        String dimensionDescription = industryConceptDictionary.generateDimensionDescription(datasourceId);
        String tableRoleDescription = industryConceptDictionary.generateTableRoleDescription(datasourceId);
        
        // ✅ 新增：注入表选择特殊规则（从行业配置中读取）
        String tableSelectionRules = industryConceptDictionary.generateTableSelectionRules(datasourceId);
        
        // ✅ 新增：注入使用场景区分规则（如users vs user_addresses）
        String usageRulesDescription = industryConceptDictionary.generateUsageRulesDescription(datasourceId);
        
        return String.format(
            "你是一个数据库专家。根据用户问题和当前可用的表结构，请选出需要用到的表。\n\n" +
            "用户问题：%s" +
            "%s" +
            "当前可用的表：\n%s" +
            "%s\n\n" +
            "要求：\n" +
            "1. 仔细分析用户问题，只选择真正需要用到的表\n" +
            "2. **如果需要通过中间表关联，必须包含所有中间表**（如 A.ref_id -> B.id -> C.ref_id，需要选中 A、B、C 三张表）\n" +
            "3. **关键指标识别规则**：\n" +
            "   - 涉及%s时，必须选择包含这些字段的业务主表\n" +
            "   - 不能仅根据维度字段选择表，必须确保所选表包含用户询问的指标字段\n" +
            "   - 例如：'统计每个%s的数值指标' → 必须选择有数值字段的主表，同时选择有%s字段的维度表\n" +
            "   - ⚠️ 重要：仔细区分不同维度的概念，根据实际表结构判断\n" +
            "4. **表角色理解**：%s\n" +
            "5. **表选择验证规则（重要）**：\n" +
            "   - 在返回selected_tables之前，必须模拟生成SQL并验证：\n" +
            "     a) SELECT中的每个字段是否都能在已选表中找到？\n" +
            "     b) WHERE/GROUP BY/ORDER BY中的字段是否都在已选表中？\n" +
            "     c) 如果需要JOIN，关联字段是否在已选表中？\n" +
            "     d) **严禁臆造字段**：如果不确定某个表是否有某字段，必须返回missing_tables请求补充该表的schema\n" +
            "   - 如果任何一个检查失败，必须返回missing_tables，而不是selected_tables\n" +
            "   - ✅ **关联表推理**：如果已选表的字段不足以满足需求，查看关联关系图，推测可能包含所需字段的表，返回missing_tables请求补充其schema\n" +
            "   - 例如：已选orders和user_addresses，但需要username字段 → 看到orders.user_id → users.id关联 → 返回missing_tables: [\"users\"]\n" +
            "%s" +
            "6. **宁可多选，不可漏选**：如果不确定是否需要某表，优先包含进来\n" +
            "7. 返回JSON格式：{\"selected_tables\": [\"表1\", \"表2\"]}\n" +
            "8. 如果确实缺少必要的表，返回：{\"missing_tables\": [\"表A\", \"表B\"], \"reason\": \"缺少的表用途说明\"}\n" +
            "9. 只返回JSON，不要其他内容",
            query, normalizationHint, tablePreferenceHint, schemaInfo, relationshipHint,
            metricDescription, dimensionDescription.split("，")[0], dimensionDescription.split("，")[0],
            tableRoleDescription, tableSelectionRules, usageRulesDescription
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
                if (!Arrays.asList(
                    "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
                    "table", "tables", "column", "columns", "field", "fields",
                    "missing", "required", "additional"
                ).contains(word.toLowerCase())) {
                    tables.add(word);
                }
            }
        }
        
        return new ArrayList<>(tables);
    }
    
    /**
     * 自动修正SQL（供Skill使用）
     */
    public String autoFixSQL(String failedSql, String errorMessage, Long datasourceId) {
        try {
            log.info("[NL2SQLService] 开始修正SQL: error={}", errorMessage);
            
            // ✅ 关键修复：从 SQL 中提取表名，获取 schema
            Set<String> tablesInSQL = extractTablesFromSQL(failedSql);
            String schemaInfo = "";
            
            if (!tablesInSQL.isEmpty() && datasourceId != null) {
                schemaInfo = buildTableSchemaInfo(new ArrayList<>(tablesInSQL), datasourceId);
                log.info("[NL2SQLService] 已加载 {} 张表的 schema: {}", tablesInSQL.size(), tablesInSQL);
            }
            
            String fixPrompt = String.format(
                "你是一个MySQL SQL专家。以下SQL执行失败，请根据表结构修正。\n\n" +
                "%s" +
                "失败的SQL:\n%s\n\n" +
                "错误信息:\n%s\n\n" +
                "要求：\n" +
                "1. 只输出修正后的SQL语句\n" +
                "2. 不要包含```sql或其他标记\n" +
                "3. 保持原有查询意图不变\n" +
                "4. **重要：如果ON条件中使用了IN子查询，必须改为直接JOIN**\n" +
                "   - 错误：JOIN tableB ON colA IN (SELECT id FROM tableB WHERE ...)\n" +
                "   - 正确：JOIN tableB ON tableA.ref_id = tableB.id",
                schemaInfo.isEmpty() ? "" : "数据库表结构：\n" + schemaInfo + "\n\n",
                failedSql, errorMessage
            );
            
            String fixedSql = modelRouter.smartGenerateSQL(fixPrompt, "");
            return MarkdownUtils.cleanSQL(fixedSql);
        } catch (Exception e) {
            log.error("[NL2SQLService] SQL修正失败", e);
            return failedSql; // 返回原SQL
        }
    }
    
    /**
     * ✅ 新增：基于外部传入的 schema 生成 SQL（用于 Tool 调用）
     * 
     * @param query 用户问题
     * @param schema 表结构信息（JSON格式）
     * @param datasourceId 数据源ID
     * @return 生成的 SQL 语句
     */
    public String generateSQLWithSchema(String query, String schema, Long datasourceId) {
        try {
            log.info("[NL2SQLService] 使用外部 schema 生成SQL: query={}, schema长度={}", 
                query, schema != null ? schema.length() : 0);
            
            if (schema == null || schema.trim().isEmpty()) {
                return "错误：表结构信息不能为空";
            }
            
            // 构建 Prompt
            String prompt = String.format(
                "请根据以下表结构和用户问题生成 SQL 语句。\n\n" +
                "表结构信息：\n%s\n\n" +
                "用户问题：%s\n\n" +
                "要求：\n" +
                "1. 只输出 SQL 语句，不要包含```sql或其他标记\n" +
                "2. 使用正确的 JOIN 语法\n" +
                "3. 字段名和表名必须与 schema 中定义的一致\n" +
                "4. 如果无法生成 SQL，请说明原因",
                schema, query
            );
            
            String sql = modelRouter.smartGenerateSQL(prompt, "");
            String cleanedSql = MarkdownUtils.cleanSQL(sql);
            
            log.info("[NL2SQLService] 生成成功: {}", cleanedSql);
            return cleanedSql;
            
        } catch (Exception e) {
            log.error("[NL2SQLService] 使用外部 schema 生成SQL失败", e);
            return "错误：" + e.getMessage();
        }
    }
    
    /**
     * ✅ 新增：从 SQL 中提取使用的表名
     * 
     * @param sql SQL语句
     * @return 表名集合
     */
    private Set<String> extractTablesFromSQL(String sql) {
        Set<String> tables = new HashSet<>();
        if (sql == null || sql.trim().isEmpty()) {
            return tables;
        }
        
        try {
            // 匹配 FROM 和 JOIN 后面的表名
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_]*)", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pattern.matcher(sql);
            
            while (matcher.find()) {
                String tableName = matcher.group(1).toLowerCase();
                // 过滤SQL关键字
                if (!Arrays.asList(
                    "select", "where", "group", "order", "by", "having", "limit",
                    "on", "and", "or", "not", "in", "is", "null", "as", "desc", "asc"
                ).contains(tableName)) {
                    tables.add(tableName);
                }
            }
            
            log.debug("[NL2SQLService] 从 SQL 中提取到表: {}", tables);
        } catch (Exception e) {
            log.warn("[NL2SQLService] 提取表名失败", e);
        }
        
        return tables;
    }
}
