package com.nl2sql.core.rag;

import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.rag.dto.SQLFeedbackRequest;
import com.nl2sql.common.util.QueryNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL反馈服务
 * 负责收集和处理用户对生成SQL的反馈，用于持续优化
 */
@Slf4j
@Service
public class SQLFeedbackService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragKnowledgeBaseService;
    
    @Autowired(required = false)
    private FeedbackLearningService feedbackLearningService;
    
    @Autowired(required = false)
    private QueryCacheService queryCacheService;
    
    @Autowired(required = false)
    private com.nl2sql.core.cache.MetadataCacheService metadataCacheService;
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    /**
     * 提交SQL反馈
     */
    public Long submitFeedback(SQLFeedbackRequest request, String ipAddress, String userAgent) {
        try {
            log.info("[SQL反馈] 收到请求: rating={}, question={}", 
                request.getRating(), request.getQuestion());
            
            // 参数校验
            if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
                throw new IllegalArgumentException("评分必须在1-5之间");
            }
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            if (request.getGeneratedSql() == null || request.getGeneratedSql().trim().isEmpty()) {
                throw new IllegalArgumentException("生成的SQL不能为空");
            }
            
            // 保存反馈
            String sql = "INSERT INTO rag_feedback (" +
                        "knowledge_id, user_id, session_id, rating, feedback_text, " +
                        "question, generated_sql, executed_sql, execution_success, " +
                        "ip_address, user_agent, created_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
            
            jdbcTemplate.update(sql,
                request.getKnowledgeId() != null ? request.getKnowledgeId() : 0L, // knowledge_id默认为0
                null, // userId 从SecurityContext获取，暂时留空
                request.getSessionId(),
                request.getRating(),
                request.getFeedbackText(),
                request.getQuestion(),
                request.getGeneratedSql(),
                request.getExecutedSql(),
                request.getExecutionSuccess() != null ? request.getExecutionSuccess() : false,
                ipAddress,
                userAgent
            );
            
            Long feedbackId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            
            log.info("[SQL反馈] 已保存到rag_feedback: feedbackId={}", feedbackId);
            
            // 触发学习机制（包含高分同步）
            learnFromFeedback(request, feedbackId);
            
            return feedbackId;
            
        } catch (IllegalArgumentException e) {
            log.warn("[SQL反馈] 参数错误: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[SQL反馈] 提交失败: question={}", request.getQuestion(), e);
            throw new RuntimeException("提交反馈失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从反馈中学习
     */
    private void learnFromFeedback(SQLFeedbackRequest request, Long feedbackId) {
        if (ragKnowledgeBaseService == null) {
            log.debug("RAG服务未启用，跳过反馈学习");
            return;
        }
        
        try {
            // ✅ 新增：高分反馈（4-5星）自动同步到rag_knowledge_base + 注入L1/L2表缓存
            if (request.getRating() >= 4) {
                syncHighRatingFeedbackToKnowledge(request);
                injectTableSelectionToCache(request);  // ✅ 新增：注入表选择缓存
            }
            
            // 如果有关联的知识库ID，更新其质量评分
            if (request.getKnowledgeId() != null) {
                float scoreChange = calculateScoreChange(request.getRating());
                ragKnowledgeBaseService.updateQualityScore(request.getKnowledgeId(), scoreChange);
                
                log.info("根据反馈调整知识库质量评分: knowledgeId={}, change={}", 
                    request.getKnowledgeId(), scoreChange);
            }
            
            // 低分反馈自动标记，供后续分析
            if (request.getRating() <= 2 && request.getFeedbackText() != null) {
                log.warn("低分反馈 [{}星]: question={}, reason={}", 
                    request.getRating(), request.getQuestion(), request.getFeedbackText());
                
                // ✅ 关键修复：清除该问题的 SQL 缓存（使用规范化查询文本）
                if (queryCacheService != null) {
                    try {
                        String normalizedQuery = normalizeQuery(request.getQuestion());
                        // 同时清除原始问题和规范化后的key
                        queryCacheService.invalidateCache(request.getQuestion());
                        
                        // ✅ 新增：清除模板缓存（Redis key: query_cache:query:{md5}）
                        String redisKey = "query_cache:query:" + md5Hash(normalizedQuery);
                        redisTemplate.delete(redisKey);
                        
                        log.info("[反馈处理] ✅ 已清除 SQL 缓存: question={}, normalized={}", 
                            request.getQuestion(), normalizedQuery);
                    } catch (Exception e) {
                        log.warn("[反馈处理] 清除缓存失败", e);
                    }
                }
                
                // ✅ 新增：触发Agent学习修正
                if (feedbackLearningService != null) {
                    try {
                        feedbackLearningService.processLowRatingFeedback(
                            feedbackId, 
                            request.getRating().intValue(),  // Integer -> int
                            request.getQuestion(), 
                            request.getGeneratedSql(), 
                            request.getFeedbackText()
                        );
                    } catch (Exception e) {
                        log.error("[反馈学习] 处理失败: feedbackId={}", feedbackId, e);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("从反馈中学习失败: feedbackId={}", feedbackId, e);
        }
    }
    
    /**
     * 根据评分计算质量分数变化
     * 
     * @param rating 用户评分 1-5
     * @return 质量分数变化量 (-0.2 到 +0.2)
     */
    private float calculateScoreChange(int rating) {
        switch (rating) {
            case 5: return 0.2f;   // 很好：大幅提升
            case 4: return 0.1f;   // 较好：小幅提升
            case 3: return 0.0f;   // 一般：不变
            case 2: return -0.1f;  // 较差：小幅降低
            case 1: return -0.2f;  // 很差：大幅降低
            default: return 0.0f;
        }
    }
    
    /**
     * ✅ 新增：将高分反馈同步到rag_knowledge_base表
     * 
     * @param request 反馈请求
     */
    private void syncHighRatingFeedbackToKnowledge(SQLFeedbackRequest request) {
        try {
            String question = request.getQuestion();
            String sql = request.getGeneratedSql();
            int rating = request.getRating();
            
            log.info("[反馈同步] 开始处理: rating={}, question={}", rating, question);
            
            // 检查是否已存在相似问题（避免重复）
            List<RagKnowledgeBaseService.KnowledgeItem> existingItems = 
                ragKnowledgeBaseService.searchSimilarQuestions(question, 1);
            
            if (!existingItems.isEmpty()) {
                double similarity = existingItems.get(0).getRelevance() != null ? 
                    existingItems.get(0).getRelevance() : 0.0;
                
                log.info("[反馈同步] 找到相似示例: similarity={}", similarity);
                
                // 如果相似度>0.9，认为已存在，只更新质量评分
                if (similarity > 0.9) {
                    Long existingId = existingItems.get(0).getId();
                    float scoreChange = calculateScoreChange(rating);
                    ragKnowledgeBaseService.updateQualityScore(existingId, scoreChange);
                                
                    log.info("[反馈同步] ✅ 发现相似示例，更新质量评分: id={}, change={}", 
                        existingId, scoreChange);
                                
                    // ✅ 关键修复：即使RAG已存在，仍需注入表缓存和SQL模板
                    // 因为用户可能对同一问题的不同参数（如3天→7天）给出新反馈
                    return;  // 仅跳过RAG新增，继续执行后续的injectTableSelectionToCache
                }
            }
            
            // 不存在则新增
            // 根据评分计算初始质量分：5星=1.0, 4星=0.8
            float qualityScore = rating == 5 ? 1.0f : 0.8f;
            
            // 自动分类（简单规则）
            String category = categorizeQuestion(question);
            
            log.info("[反馈同步] 准备新增: category={}, qualityScore={}", category, qualityScore);
            
            // 保存到rag_knowledge_base（会自动同步到Chroma）
            Long knowledgeId = ragKnowledgeBaseService.saveQAPair(
                question,
                "",  // answer暂时为空
                sql,
                category,
                qualityScore
            );
            
            log.info("[反馈同步] ✅ 高分反馈已同步到知识库: id={}, rating={}, question={}", 
                knowledgeId, rating, question);
            
        } catch (Exception e) {
            // ⚠️ 关键：同步失败不影响主流程，只记录日志
            log.error("[反馈同步] ❌ 同步失败（不影响反馈提交）: question={}", 
                request.getQuestion(), e);
        }
    }
    
    /**
     * ✅ 新增：将高分反馈的表选择注入到L1/L2缓存
     * 核心思路：用户评分>=4分 → 提取SQL中的表 → 写入元数据缓存
     * 下次相似查询时，直接从缓存获取表，跳过L3向量检索和LLM选表
     */
    private void injectTableSelectionToCache(SQLFeedbackRequest request) {
        if (metadataCacheService == null) {
            log.debug("[表缓存注入] MetadataCacheService未注入，跳过");
            return;
        }
            
        try {
            String question = request.getQuestion();
            // ✅ 关键修复：优先使用executedSql（实际执行的SQL），降级为generatedSql
            String sql = (request.getExecutedSql() != null && !request.getExecutedSql().trim().isEmpty()) 
                ? request.getExecutedSql() 
                : request.getGeneratedSql();
            int rating = request.getRating();
                
            // 1. ✅ 获取datasourceId和usedTables（从nl2sql_query_log查询）
            Long datasourceId = null;
            List<String> usedTablesFromLog = null;
            if (request.getSessionId() != null) {
                try {
                    List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                        "SELECT datasource_id, selected_tables FROM nl2sql_query_log WHERE session_id = ? ORDER BY created_at DESC LIMIT 1",
                        request.getSessionId()
                    );
                    if (!logs.isEmpty()) {
                        Object dsIdObj = logs.get(0).get("datasource_id");
                        if (dsIdObj != null) {
                            datasourceId = ((Number) dsIdObj).longValue();
                        }
                        
                        // ✅ 关键修复：从日志中获取selected_tables
                        Object tablesObj = logs.get(0).get("selected_tables");
                        if (tablesObj != null && tablesObj instanceof String) {
                            String tablesJson = (String) tablesObj;
                            if (!tablesJson.trim().isEmpty()) {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                @SuppressWarnings("unchecked")
                                List<String> tablesList = mapper.readValue(tablesJson, List.class);
                                usedTablesFromLog = tablesList;
                                log.info("[表缓存注入] ✅ 从日志获取表列表: {}", usedTablesFromLog);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[表缓存注入] 查询datasourceId/usedTables失败", e);
                }
            }
            
            if (datasourceId == null) {
                log.warn("[表缓存注入] 无法获取datasourceId，跳过");
                return;
            }
                
            // 2. ✅ 关键修复：优先级顺序：传入的usedTables > 日志中的selected_tables > 从SQL提取
            java.util.Set<String> usedTables;
            if (request.getUsedTables() != null && !request.getUsedTables().isEmpty()) {
                usedTables = new HashSet<>(request.getUsedTables());
                log.info("[表缓存注入] ✅ 使用传入的表列表: {}", usedTables);
            } else if (usedTablesFromLog != null && !usedTablesFromLog.isEmpty()) {
                usedTables = new HashSet<>(usedTablesFromLog);
                log.info("[表缓存注入] ✅ 使用日志中的表列表: {}", usedTables);
            } else {
                // 从 SQL中提取实际使用的表
                usedTables = extractTablesFromSQL(sql);
                if (usedTables.isEmpty()) {
                    log.warn("[表缓存注入] ❌ 无法从SQL提取表: sql={}", sql);
                    return;
                }
                log.info("[表缓存注入] ⚠️ 从SQL提取表: {}", usedTables);
            }
                
            log.info("[表缓存注入] 开始处理: rating={}, question={}, tables={}", 
                rating, question, usedTables);
                
            // 3. ✅ 关键修复：注入到L2模糊向量缓存（使用归一化key）
            String normalizedQuery = normalizeQuery(question);
            List<String> tableList = new ArrayList<>(usedTables);
            metadataCacheService.putFuzzyVectorRetrieval(normalizedQuery, tableList);
                
            log.info("[表缓存注入] ✅ 已注入L2缓存: question='{}', normalized='{}', tables={}", 
                question, normalizedQuery, tableList);
                
            // 4. ✅ 关键修复：注入到L3语义索引（使用归一化查询）
            if (rating <= 2) {
                // ✅ 1-2星反馈：从L3语义索引中删除
                metadataCacheService.removeFromSemanticIndex(datasourceId, normalizedQuery);
                log.info("[表缓存注入] ⚠️ 低分反馈，已从L3语义索引删除: datasourceId={}, query='{}'", 
                    datasourceId, normalizedQuery);
            } else if (rating >= 4) {
                // ✅ 4-5星反馈：记录到L3语义索引
                metadataCacheService.recordQueryToSemanticIndex(datasourceId, normalizedQuery, tableList, rating);
                log.info("[表缓存注入] ✅ 已注入L3语义索引: datasourceId={}, tables={}", 
                    datasourceId, tableList);
            } else {
                // ✅ 3星反馈：不注入也不删除，保持中立
                log.debug("[表缓存注入] 3星反馈（中等），跳过L3语义索引注入");
            }
                
            // ✅ P0优化：5分反馈额外注入SQL模板到QueryCache
            if (rating == 5 && queryCacheService != null) {
                injectSQLTemplateToCache(question, sql, usedTables, normalizedQuery, rating);
            } else if (rating == 4) {
                log.debug("[表缓存注入] 4分反馈，跳过SQL模板注入（仅5分）");
            }
                
            // ✅ P1优化：提取列名映射并缓存
            injectColumnMappingToCache(datasourceId, sql, question);
                
            // ✅ P1优化：提取表关联关系并缓存
            injectTableRelationshipsToCache(datasourceId, usedTables, sql);
                
        } catch (Exception e) {
            // ⚠️ 关键：注入失败不影响主流程，只记录日志
            log.error("[表缓存注入] ❌ 注入失败（不影响反馈提交）: question={}", 
                request.getQuestion(), e);
        }
    }
    
    /**
     * ✅ 从SQL中提取表名（使用JSqlParser）
     */
    private java.util.Set<String> extractTablesFromSQL(String sql) {
        Set<String> tables = new HashSet<>();
        
        if (sql == null || sql.trim().isEmpty()) {
            return tables;
        }
        
        try {
            net.sf.jsqlparser.statement.Statement statement = 
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
                return tables; // 非SELECT语句
            }
            
            net.sf.jsqlparser.statement.select.Select selectStmt = 
                (net.sf.jsqlparser.statement.select.Select) statement;
            net.sf.jsqlparser.statement.select.SelectBody selectBody = selectStmt.getSelectBody();
            
            if (!(selectBody instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
                return tables;
            }
            
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect = 
                (net.sf.jsqlparser.statement.select.PlainSelect) selectBody;
            
            // FROM表
            if (plainSelect.getFromItem() != null) {
                String fromTable = plainSelect.getFromItem().toString().toLowerCase();
                // 去除别名
                if (fromTable.contains(" ")) {
                    fromTable = fromTable.split("\\s+")[0];
                }
                tables.add(fromTable);
            }
            
            // JOIN表
            if (plainSelect.getJoins() != null) {
                for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() != null) {
                        String joinTable = join.getRightItem().toString().toLowerCase();
                        if (joinTable.contains(" ")) {
                            joinTable = joinTable.split("\\s+")[0];
                        }
                        tables.add(joinTable);
                    }
                }
            }
            
            log.debug("[表缓存注入] 从SQL提取到表: {}", tables);
            
        } catch (Exception e) {
            log.warn("[表缓存注入] SQL解析失败: {}", e.getMessage());
        }
        
        return tables;
    }
    
    /**
     * ✅ P0优化：5分反馈注入SQL模板到QueryCache（使用规则引擎提取模板）
     */
    private void injectSQLTemplateToCache(String question, String sql, 
                                          java.util.Set<String> usedTables,
                                          String normalizedQuery, int rating) {
        try {
            // 1. 提取查询结构
            com.nl2sql.core.cache.QueryStructureExtractor extractor = 
                new com.nl2sql.core.cache.QueryStructureExtractor();
            com.nl2sql.core.cache.QueryStructureExtractor.QueryStructure structure = 
                extractor.extract(question);
            
            // 2. 将SQL转换为模板（替换实体值为占位符）
            String sqlTemplate = convertToTemplate(sql, structure);
            
            // 3. 缓存SQL模板
            com.nl2sql.core.cache.QueryCacheService.CachedResult cachedResult = 
                new com.nl2sql.core.cache.QueryCacheService.CachedResult();
            cachedResult.setSql(sqlTemplate);  // ✅ 存储带占位符的模板
            cachedResult.setUsedTables(usedTables);
            cachedResult.setUserRating(rating);
            cachedResult.setNormalizedQuery(normalizedQuery);
            
            queryCacheService.putTemplateToCache(normalizedQuery, cachedResult, 1440);
            
            log.info("[SQL模板缓存] ✅ 5分反馈已注入: question='{}', normalized='{}', template={}", 
                question, normalizedQuery, sqlTemplate);
            
        } catch (Exception e) {
            log.error("[SQL模板缓存] ❌ 注入失败", e);
        }
    }
    
    /**
     * ✅ 将SQL转换为模板（替换实体值为占位符）
     */
    private String convertToTemplate(String sql, com.nl2sql.core.cache.QueryStructureExtractor.QueryStructure structure) {
        if (sql == null || structure == null) {
            return sql;
        }
        
        String template = sql;
        
        // 1. 替换人名
        if (structure.getPerson() != null) {
            template = template.replace(structure.getPerson(), "{PERSON_NAME}");
        }
        
        // 2. 替换时间偏移量
        if (structure.getTime() != null && "RELATIVE".equals(structure.getTime().getType())) {
            Integer offset = convertTimeToOffset(structure.getTime());
            if (offset != null) {
                template = template.replace("INTERVAL " + offset + " DAY", "INTERVAL {OFFSET} DAY");
            }
        }
        
        // 3. 替换动态范围（最近N天）
        if (structure.getTime() != null && "RELATIVE_RANGE".equals(structure.getTime().getType())) {
            String timeValue = structure.getTime().getValue();
            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("\\{(\\d+)\\}");
            java.util.regex.Matcher numMatcher = numPattern.matcher(timeValue);
            if (numMatcher.find()) {
                String number = numMatcher.group(1);
                template = template.replace("INTERVAL " + number + " DAY", "INTERVAL {NUM} DAY");
            }
        }
        
        // 4. 替换地点
        if (structure.getLocation() != null) {
            template = template.replace(structure.getLocation(), "{LOCATION}");
        }
        
        // 5. 替换金额
        if (structure.getAmount() != null) {
            template = template.replace(structure.getAmount().getValue(), "{AMOUNT_VALUE}");
        }
        
        return template;
    }
    
    /**
     * ✅ 将时间表达式转换为SQL偏移量
     */
    private Integer convertTimeToOffset(com.nl2sql.core.cache.QueryStructureExtractor.TimeExpression time) {
        if ("RELATIVE".equals(time.getType())) {
            switch (time.getValue()) {
                case "今天": return 0;
                case "昨天": return 1;
                case "前天": return 2;
                case "明天": return -1;
                case "后天": return -2;
                default: return null;
            }
        }
        return null;
    }
    
    /**
     * ✅ P1优化：提取列名映射并缓存（英文 -> 中文）
     */
    private void injectColumnMappingToCache(Long datasourceId, String sql, String question) {
        if (metadataCacheService == null) {
            return;
        }
        
        try {
            // 从 SQL 的 AS 别名中提取列映射
            // 例如：SELECT total_amount AS '订单金额' → {"total_amount": "订单金额"}
            java.util.Map<String, String> columnMapping = extractColumnAliasMapping(sql);
            
            if (!columnMapping.isEmpty()) {
                metadataCacheService.batchPutColumnTranslations(datasourceId, columnMapping);
                log.info("[列名缓存] ✅ 已缓存 {} 个列名映射: {}", columnMapping.size(), columnMapping);
            }
            
        } catch (Exception e) {
            log.warn("[列名缓存] 提取失败", e);
        }
    }
    
    /**
     * ✅ P1优化：提取表关联关系并缓存
     */
    private void injectTableRelationshipsToCache(Long datasourceId, 
                                                  java.util.Set<String> usedTables, 
                                                  String sql) {
        if (metadataCacheService == null || usedTables.size() < 2) {
            return; // 单表无需缓存关联关系
        }
        
        try {
            // 从 SQL 的 JOIN ... ON 条件中提取关联路径
            List<String> joinPaths = extractJoinPaths(sql);
            
            if (!joinPaths.isEmpty()) {
                String relationships = String.join("\n", joinPaths);
                List<String> tableList = new ArrayList<>(usedTables);
                metadataCacheService.putRelationships(datasourceId, tableList, relationships);
                
                log.info("[表关联缓存] ✅ 已缓存 {} 个关联路径: {}", joinPaths.size(), joinPaths);
            }
            
        } catch (Exception e) {
            log.warn("[表关联缓存] 提取失败", e);
        }
    }
    
    /**
     * ✅ 从 SQL 的 AS 别名中提取列名映射
     */
    private java.util.Map<String, String> extractColumnAliasMapping(String sql) {
        Map<String, String> mapping = new HashMap<>();
        
        if (sql == null) return mapping;
        
        try {
            // 正则匹配：xxx AS '中文' 或 xxx AS "中文"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s+AS\\s+['\"]([^'\"]+)['\"]", 
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            
            java.util.regex.Matcher matcher = pattern.matcher(sql);
            while (matcher.find()) {
                String englishName = matcher.group(1).toLowerCase();
                String chineseName = matcher.group(2);
                
                // 过滤常见非业务字段
                if (!isCommonField(englishName)) {
                    mapping.put(englishName, chineseName);
                }
            }
            
        } catch (Exception e) {
            log.warn("[列名映射] 正则解析失败", e);
        }
        
        return mapping;
    }
    
    /**
     * ✅ 从 SQL 的 JOIN 条件中提取关联路径
     */
    private List<String> extractJoinPaths(String sql) {
        List<String> paths = new ArrayList<>();
        
        if (sql == null) return paths;
        
        try {
            // 正则匹配：JOIN tableB ON tableA.col1 = tableB.col2
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "JOIN\\s+(\\w+)\\s+ON\\s+(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            
            java.util.regex.Matcher matcher = pattern.matcher(sql);
            while (matcher.find()) {
                String joinedTable = matcher.group(1).toLowerCase();
                String leftTable = matcher.group(2).toLowerCase();
                String leftCol = matcher.group(3).toLowerCase();
                String rightTable = matcher.group(4).toLowerCase();
                String rightCol = matcher.group(5).toLowerCase();
                
                String path = String.format("%s.%s -> %s.%s", 
                    leftTable, leftCol, rightTable, rightCol);
                paths.add(path);
            }
            
        } catch (Exception e) {
            log.warn("[关联路径] 正则解析失败", e);
        }
        
        return paths;
    }
    
    /**
     * ✅ 判断是否为常见非业务字段
     */
    private boolean isCommonField(String fieldName) {
        return fieldName.equals("id") || 
               fieldName.equals("created_at") || 
               fieldName.equals("updated_at") ||
               fieldName.equals("deleted_at") ||
               fieldName.startsWith("row_") ||
               fieldName.startsWith("__");
    }
    
    /**
     * ✅ 归一化查询文本（用于缓存key）- 使用规则引擎
     */
    private String normalizeQuery(String query) {
        com.nl2sql.core.cache.QueryStructureExtractor extractor = 
            new com.nl2sql.core.cache.QueryStructureExtractor();
        com.nl2sql.core.cache.QueryStructureExtractor.QueryStructure structure = 
            extractor.extract(query);
        return extractor.toNormalizedJson(structure);
    }
    
    /**
     * 根据问题自动分类
     */
    private String categorizeQuestion(String question) {
        if (question == null) return "其他";
        
        String lower = question.toLowerCase();
        
        if (lower.contains("统计") || lower.contains("汇总") || lower.contains("平均") || 
            lower.contains("合计") || lower.contains("总数")) {
            return "统计查询";
        }
        
        if (lower.contains("查询") || lower.contains("查找") || lower.contains("显示")) {
            return "明细查询";
        }
        
        if (lower.contains("排序") || lower.contains("排名") || lower.contains("最")) {
            return "排序查询";
        }
        
        return "其他";
    }
    
    /**
     * 获取反馈统计
     */
    public Map<String, Object> getFeedbackStats() {
        try {
            // 总反馈数
            Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_feedback", Integer.class);
            
            // 平均评分
            Double avgRating = jdbcTemplate.queryForObject(
                "SELECT AVG(rating) FROM rag_feedback", Double.class);
            
            // 各评分分布
            List<Map<String, Object>> ratingDistribution = jdbcTemplate.queryForList(
                "SELECT rating, COUNT(*) as count FROM rag_feedback GROUP BY rating ORDER BY rating");
            
            // 最近7天反馈趋势
            List<Map<String, Object>> recentTrend = jdbcTemplate.queryForList(
                "SELECT DATE(created_at) as date, AVG(rating) as avg_rating, COUNT(*) as count " +
                "FROM rag_feedback " +
                "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) " +
                "GROUP BY DATE(created_at) " +
                "ORDER BY date");
            
            return Map.of(
                "totalCount", totalCount != null ? totalCount : 0,
                "avgRating", avgRating != null ? String.format("%.2f", avgRating) : "0.00",
                "ratingDistribution", ratingDistribution,
                "recentTrend", recentTrend
            );
            
        } catch (Exception e) {
            log.error("获取反馈统计失败", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * 获取低分反馈列表（需要改进的SQL）
     */
    public List<Map<String, Object>> getLowRatingFeedbacks(int limit) {
        try {
            String sql = "SELECT id, question, generated_sql, rating, feedback_text, created_at " +
                        "FROM rag_feedback " +
                        "WHERE rating <= 2 " +
                        "ORDER BY created_at DESC " +
                        "LIMIT ?";
            
            return jdbcTemplate.queryForList(sql, limit);
            
        } catch (Exception e) {
            log.error("获取低分反馈失败", e);
            return List.of();
        }
    }
    
    /**
     * ✅ 新增：自动给上次未评分的结果赋予默认评分（3星）
     * 当用户发起新查询时，如果上次查询没有评分，则认为用户认可，给3星
     * 
     * @param sessionId 会话ID
     * @return 是否成功应用默认评分
     */
    public boolean applyDefaultRating(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        
        try {
            // 查找该会话中最近一次生成的SQL，且没有对应的反馈记录
            String checkSql = "SELECT q.question, q.generated_sql, q.executed_sql, q.execution_success " +
                             "FROM nl2sql_query_log q " +
                             "LEFT JOIN rag_feedback f ON q.session_id = f.session_id " +
                             "AND q.generated_sql = f.generated_sql " +
                             "WHERE q.session_id = ? " +
                             "AND f.id IS NULL " +
                             "ORDER BY q.created_at DESC " +
                             "LIMIT 1";
            
            List<Map<String, Object>> queries = jdbcTemplate.queryForList(checkSql, sessionId);
            
            if (queries.isEmpty()) {
                log.debug("[默认评分] 会话 {} 没有未评分的查询", sessionId);
                return false;
            }
            
            Map<String, Object> lastQuery = queries.get(0);
            String question = (String) lastQuery.get("question");
            String generatedSql = (String) lastQuery.get("generated_sql");
            String executedSql = (String) lastQuery.get("executed_sql");
            Boolean executionSuccess = com.nl2sql.common.util.BooleanUtils.toBoolean(lastQuery.get("execution_success"));
            
            // ✅ 关键修复：先检查是否已存在相同问题的评分记录
            String checkExistingSql = "SELECT id FROM rag_feedback WHERE session_id = ? AND question = ? LIMIT 1";
            List<Map<String, Object>> existingRecords = jdbcTemplate.queryForList(
                checkExistingSql, sessionId, question
            );
            
            if (!existingRecords.isEmpty()) {
                // ✅ 存在则更新
                Long feedbackId = ((Number) existingRecords.get(0).get("id")).longValue();
                String updateSql = "UPDATE rag_feedback SET " +
                                  "rating = ?, " +
                                  "feedback_text = ?, " +
                                  "generated_sql = ?, " +
                                  "executed_sql = ?, " +
                                  "execution_success = ?, " +
                                  "ip_address = ?, " +
                                  "user_agent = ?, " +
                                  "created_at = NOW() " +
                                  "WHERE id = ?";
                
                jdbcTemplate.update(updateSql,
                    3,     // 默认3星
                    "用户未评分，默认为中等评价",
                    generatedSql,
                    executedSql,
                    executionSuccess != null ? executionSuccess : false,
                    "system",
                    "auto-rating",
                    feedbackId
                );
                
                log.info("[默认评分] 会话 {} 更新已有评分记录: feedbackId={}, question={}", 
                    sessionId, feedbackId, question);
            } else {
                // ✅ 不存在则插入
                String insertSql = "INSERT INTO rag_feedback (" +
                                  "knowledge_id, user_id, session_id, rating, feedback_text, " +
                                  "question, generated_sql, executed_sql, execution_success, " +
                                  "ip_address, user_agent, created_at" +
                                  ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
                
                jdbcTemplate.update(insertSql,
                    0L,    // ✅ knowledgeId: 0 表示无关联知识库
                    null,  // userId
                    sessionId,
                    3,     // 默认3星
                    "用户未评分，默认为中等评价",  // 自动填充的反馈文本
                    question,
                    generatedSql,
                    executedSql,
                    executionSuccess != null ? executionSuccess : false,
                    "system",  // 系统自动评分
                    "auto-rating"
                );
                
                log.info("[默认评分] 会话 {} 新增评分记录: question={}", sessionId, question);
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("[默认评分] 应用默认评分失败: sessionId={}", sessionId, e);
            return false;
        }
    }
    
    /**
     * ✅ MD5哈希（用于Redis key生成）
     */
    private String md5Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("[MD5] 哈希失败", e);
            return input.hashCode() + ""; // 降级方案
        }
    }
}
