package com.nl2sql.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.llm.ModelRouterService;
import com.nl2sql.core.llm.SynonymService;
import com.nl2sql.core.mapper.MetadataMapper;
import com.nl2sql.core.retriever.VectorRetriever;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 表选择编排器
 * 
 * 职责：
 * 1. 缓存检查（5分SQL模板 + 普通缓存）
 * 2. 同义词扩展
 * 3. 向量检索初始表
 * 4. 迭代式表发现（最多3轮LLM交互）
 * 5. 澄清/多选处理
 */
@Slf4j
@Service
public class TableSelectionOrchestrator {
    
    @Autowired
    private VectorRetriever vectorRetriever;
    
    @Autowired
    private SynonymService synonymService;
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Autowired(required = false)
    private QueryCacheService queryCacheService;
    
    @Autowired(required = false)
    private com.nl2sql.core.config.QueryStructureExtractorConfig.IndustryTargetExtractorFactory extractorFactory;
    
    @Autowired(required = false)
    private MetadataCacheService metadataCacheService;
    
    @Autowired(required = false)
    private com.nl2sql.metadata.service.TableRelationshipService tableRelationshipService;
    
    // ✅ 新增：ThreadLocal用于传递已检索的表列表（避免重复L3检索）
    private static final ThreadLocal<List<String>> preRetrievedTables = new ThreadLocal<>();
    // ✅ 新增：ThreadLocal用于存储LLM最终选择的表列表（用于5星反馈缓存）
    private static final ThreadLocal<List<String>> finalSelectedTables = new ThreadLocal<>();
    
    // ✅ 新增：表组合缓存（question -> selected_tables）
    private static final Map<String, List<String>> tableCombinationCache = Collections.synchronizedMap(new LinkedHashMap<String, List<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 1000; // 最多缓存1000个查询
        }
    });
    
    /**
     * ✅ 新增：设置预检索的表列表（供StandardQuerySkill使用）
     * @param tables 已检索的表列表
     */
    public static void setPreRetrievedTables(List<String> tables) {
        if (tables != null && !tables.isEmpty()) {
            preRetrievedTables.set(tables);
            log.debug("[TableSelection] 设置预检索表列表: {}", tables);
        }
    }
    
    /**
     * ✅ 新增：获取LLM最终选择的表列表（供AgentChatService使用）
     * @return 最终选择的表列表
     */
    public static List<String> getFinalSelectedTables() {
        return finalSelectedTables.get();
    }
    
    /**
     * ✅ 新增：设置LLM最终选择的表列表（供NL2SQLService更新为SQL实际使用的表）
     * @param tables 最终选择的表列表
     */
    public static void setFinalSelectedTables(List<String> tables) {
        if (tables != null && !tables.isEmpty()) {
            finalSelectedTables.set(tables);
            log.debug("[TableSelection] 设置最终表列表: {}", tables);
        }
    }
    
    /**
     * ✅ 新增：清除预检索的表列表（防止内存泄漏）
     */
    public static void clearPreRetrievedTables() {
        preRetrievedTables.remove();
        finalSelectedTables.remove();
    }
    
    /**
     * 执行表选择流程
     * 
     * @return TableSelectionResult 包含最终表列表、澄清信号等
     */
    public TableSelectionResult execute(String query, Long datasourceId, 
                                        SchemaRetrievalService schemaRetrievalService,
                                        SessionContextManager sessionContextManager,
                                        java.util.function.BiFunction<String, String, String> promptBuilder,
                                        java.util.function.Consumer<String> progressPublisher) {
        try {
            // ✅ 初始化监控上下文
            MonitoringContext.init();
            
            // ✅ P0优化：优先从QueryCache获取5分SQL模板
            if (queryCacheService != null) {
                String normalizedQuery = schemaRetrievalService.normalizeQueryForCache(query);
                com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                    queryCacheService.getFromNormalizedQuery(normalizedQuery);  // ✅ 修复：使用规范化查询文本检索
                
                if (cached != null && cached.getUserRating() != null && cached.getUserRating() == 5) {
                    // ✅ 关键修复：使用SQLTemplateFiller填充模板（注入行业提取器）
                    com.nl2sql.core.cache.QueryStructureExtractor extractor = 
                        new com.nl2sql.core.cache.QueryStructureExtractor();
                    
                    // ✅ 设置数据源信息（用于查询行业表）
                    if (schemaRetrievalService != null) {
                        extractor.setDataSource(
                            schemaRetrievalService.getJdbcTemplate(), 
                            datasourceId
                        );
                    }
                    
                    // ✅ 根据数据源注入行业提取器（仅用于特殊逻辑）
                    if (extractorFactory != null) {
                        com.nl2sql.core.cache.IndustryTargetExtractor industryExtractor = 
                            extractorFactory.getExtractor(datasourceId);
                        if (industryExtractor != null) {
                            extractor.setIndustryTargetExtractor(industryExtractor);
                        }
                    }
                    
                    com.nl2sql.core.cache.QueryStructureExtractor.QueryStructure structure = 
                        extractor.extract(query);
                    com.nl2sql.core.cache.SQLTemplateFiller filler = 
                        new com.nl2sql.core.cache.SQLTemplateFiller();
                    String finalSQL = filler.fill(cached.getSql(), structure);
                    
                    log.info("[TableSelection] ⚡⚡⚡ 5分SQL模板命中: question='{}', sql={}", 
                        query, finalSQL);
                    sessionContextManager.saveCurrentContext(finalSQL, query);
                    
                    // ✅ 记录监控数据：L1缓存命中
                    MonitoringContext.setCacheInfo("L1", true);
                    
                    TableSelectionResult result = new TableSelectionResult();
                    result.setCachedSQL(finalSQL);
                    return result;
                }
            }
            
            // ✅ 关键优化：尝试从缓存获取 SQL（避免 LLM 非确定性）
            if (queryCacheService != null) {
                try {
                    // 1. 先尝试L1精确匹配
                    com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                        queryCacheService.getFromCache(query);
                    
                    if (cached != null && cached.getData() != null && !cached.getData().isEmpty()) {
                        String cachedSQL = (String) cached.getData().get(0).get("sql");
                        if (cachedSQL != null && !cachedSQL.trim().isEmpty()) {
                            log.info("[TableSelection] SQL 缓存命中(L1): question={}", query);
                            sessionContextManager.saveCurrentContext(cachedSQL, query);
                            
                            // ✅ 记录监控数据：L1缓存命中
                            MonitoringContext.setCacheInfo("L1", true);
                            
                            TableSelectionResult result = new TableSelectionResult();
                            result.setCachedSQL(cachedSQL);
                            return result;
                        }
                    }
                    
                    // 2. ✅ 新增：L2归一化模板匹配
                    String normalizedQuery = schemaRetrievalService.normalizeQueryForCache(query);
                    com.nl2sql.core.cache.QueryCacheService.CachedResult templateCached = 
                        queryCacheService.getFromNormalizedQuery(normalizedQuery);
                    
                    if (templateCached != null && templateCached.getUserRating() != null && templateCached.getUserRating() >= 4) {
                        // ✅ 使用SQLTemplateFiller填充模板（注入行业提取器）
                        com.nl2sql.core.cache.QueryStructureExtractor extractor = 
                            new com.nl2sql.core.cache.QueryStructureExtractor();
                        
                        // ✅ 设置数据源信息（用于查询行业表）
                        if (schemaRetrievalService != null) {
                            extractor.setDataSource(
                                schemaRetrievalService.getJdbcTemplate(), 
                                datasourceId
                            );
                        }
                        
                        // ✅ 根据数据源注入行业提取器（仅用于特殊逻辑）
                        if (extractorFactory != null) {
                            com.nl2sql.core.cache.IndustryTargetExtractor industryExtractor = 
                                extractorFactory.getExtractor(datasourceId);
                            if (industryExtractor != null) {
                                extractor.setIndustryTargetExtractor(industryExtractor);
                            }
                        }
                        
                        com.nl2sql.core.cache.QueryStructureExtractor.QueryStructure structure = 
                            extractor.extract(query);
                        com.nl2sql.core.cache.SQLTemplateFiller filler = 
                            new com.nl2sql.core.cache.SQLTemplateFiller();
                        String filledSQL = filler.fill(templateCached.getSql(), structure);
                        
                        log.info("[TableSelection] ⚡⚡ L2模板命中: original='{}', normalized='{}', rating={}", 
                            query, normalizedQuery, templateCached.getUserRating());
                        sessionContextManager.saveCurrentContext(filledSQL, query);
                        
                        // ✅ 记录监控数据：L2缓存命中
                        MonitoringContext.setCacheInfo("L2", true);
                        
                        TableSelectionResult result = new TableSelectionResult();
                        result.setCachedSQL(filledSQL);
                        return result;
                    }
                    
                } catch (Exception e) {
                    log.debug("[TableSelection] 缓存读取失败，继续生成 SQL", e);
                }
            }
            
            log.info("[TableSelection] 开始表选择流程: query={}, datasourceId={}", query, datasourceId);
            
            // ✅ 新增：检查表组合缓存
            String cacheKey = datasourceId + ":" + query.trim().toLowerCase();
            List<String> cachedTables = tableCombinationCache.get(cacheKey);
            if (cachedTables != null && !cachedTables.isEmpty()) {
                log.info("[TableSelection] ⚡⚡⚡ 表组合缓存命中: query='{}', tables={}", query, cachedTables);
                
                // ✅ 记录监控数据：L3缓存命中
                MonitoringContext.setCacheInfo("L3", true);
                
                TableSelectionResult result = new TableSelectionResult();
                result.setExpandedQuery(query);
                result.setSelectedTables(new HashSet<>(cachedTables));
                result.setFromCache(true); // 标记来自缓存
                return result;
            }
            
            progressPublisher.accept("generating_sql");
            
            // 0. ✅ P0优化：删除空的同义词扩展调用，直接使用原始query
            String expandedQuery = query;
            
            // ✅ 记录归一化信息
            String normalizedQuery = schemaRetrievalService.normalizeQueryForCache(query);
            java.util.List<String> persons = com.nl2sql.common.util.EntityExtractor.extractPersons(query);
            java.util.List<String> locations = com.nl2sql.common.util.EntityExtractor.extractLocations(query);
            
            boolean hasPerson = !persons.isEmpty();
            boolean hasLocation = !locations.isEmpty();
            String normMethod = hasPerson || hasLocation ? "hanlp" : "regex";
            
            MonitoringContext.setNormalizationInfo(normalizedQuery, hasPerson, hasLocation, normMethod);
            log.debug("[MonitoringContext] 归一化信息: normalized={}, person={}, location={}, method={}",
                normalizedQuery, hasPerson, hasLocation, normMethod);
            
            // 1. ✅ P0优化：优先从L3语义缓存获取表列表（避免重复检索）
            progressPublisher.accept("retrieving_tables");
            List<String> initialTables = null;
            
            // ✅ 关键优化：检查是否有预检索的表列表（来自StandardQuerySkill）
            List<String> preRetrieved = preRetrievedTables.get();
            if (preRetrieved != null && !preRetrieved.isEmpty()) {
                initialTables = preRetrieved;
                log.info("[TableSelection] ⚡ 复用预检索的表列表: {}", initialTables);
                preRetrievedTables.remove(); // 清除ThreadLocal，避免内存泄漏
            }
            
            if (initialTables == null || initialTables.isEmpty()) {
                if (metadataCacheService != null) {
                    // ✅ 关键修复：使用expandedQuery检查L3缓存，与后续向量检索保持一致
                    initialTables = metadataCacheService.findSimilarQueryBySemantic(expandedQuery, datasourceId, 0.85);
                    if (initialTables != null && !initialTables.isEmpty()) {
                        log.info("[TableSelection] ⚡ L3语义缓存命中: query='{}', tables={}", expandedQuery, initialTables);
                        // ✅ 记录监控数据：L3缓存命中
                        MonitoringContext.setCacheInfo("L3", true);
                    }
                }
                
                // L3未命中，执行向量检索
                if (initialTables == null || initialTables.isEmpty()) {
                    initialTables = vectorRetriever.retrieveTopTables(expandedQuery, datasourceId, 20);
                    // ✅ 记录监控数据：缓存未命中
                    MonitoringContext.MonitoringData data = MonitoringContext.get();
                    if (data.getCacheHit() == null || !data.getCacheHit()) {
                        MonitoringContext.setCacheInfo("MISS", false);
                    }
                }
            }
            
            if (initialTables.isEmpty()) {
                TableSelectionResult result = new TableSelectionResult();
                result.setError("ERROR: 未找到任何相关表，请检查元数据是否已加载");
                return result;
            }
            
            log.info("[TableSelection] 初始检索到 {} 个表: {}", initialTables.size(), initialTables);
            progressPublisher.accept("tables_retrieved");
            
            // ✅ 关键优化：如果只有一个数据源且检索到的表 <= 5，跳过 LLM 表选择
            Integer totalDatasourceCount = metadataMapper.countDistinctDatasources();
            
            if (totalDatasourceCount != null && totalDatasourceCount == 1 && initialTables.size() <= 5) {
                log.info("[TableSelection] ⚡ 单数据源模式，直接使用检索结果，跳过 LLM 表选择");
                
                TableSelectionResult result = new TableSelectionResult();
                result.setExpandedQuery(expandedQuery);
                result.setSelectedTables(new HashSet<>(initialTables));
                result.setSkipLLMSelection(true);
                return result;
            }
            
            // 2. 迭代式表发现 + 回溯机制
            Set<String> allTables = new HashSet<>(initialTables);
            boolean needsClarification = false;
            String clarificationMessage = "";
            String lastLlmResponse = null;
                        
            for (int iteration = 0; iteration < 3; iteration++) {
                log.info("[TableSelection] 第{}轮迭代，当前表数量: {}", iteration + 1, allTables.size());
                progressPublisher.accept("table_iteration");
                
                // 构建Prompt并调用LLM
                String schemaInfo = schemaRetrievalService.buildTableSchemaInfo(new ArrayList<>(allTables), datasourceId);
                
                // ✅ 修复：获取表关联关系
                String relationshipInfo = "";
                if (tableRelationshipService != null) {
                    try {
                        relationshipInfo = tableRelationshipService.getRelationshipsForPrompt(
                            datasourceId, new ArrayList<>(allTables));
                    } catch (Exception e) {
                        log.warn("[TableSelection] 获取关联关系失败: {}", e.getMessage());
                    }
                }
                
                String checkPrompt = promptBuilder.apply(expandedQuery, schemaInfo);
                String llmResponse = modelRouter.getMultiModelService().generateSQL(checkPrompt);
                lastLlmResponse = llmResponse;
                
                log.info("[TableSelection] ========== LLM原始响应 ==========");
                log.info("[TableSelection] {}", llmResponse);
                log.info("[TableSelection] =====================================");
                
                if (llmResponse == null || llmResponse.trim().isEmpty()) {
                    break;
                }
                
                // 尝试解析 JSON 响应
                try {
                    String cleanJson = MarkdownUtils.extractFromMarkdown(llmResponse);
                    log.info("[TableSelection] 清洗后的JSON: {}", cleanJson);
                    
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode jsonNode = mapper.readTree(cleanJson);
                    
                    boolean handled = false;
                    
                    // 情况1: LLM 选择了需要的表
                    if (jsonNode.has("selected_tables")) {
                        JsonNode selectedTablesNode = jsonNode.get("selected_tables");
                        if (selectedTablesNode.isArray() && selectedTablesNode.size() > 0) {
                            Set<String> selectedTables = new HashSet<>();
                            log.info("[TableSelection] LLM返回selected_tables: {}", selectedTablesNode.toString());
                            
                            for (JsonNode tableNode : selectedTablesNode) {
                                String tableName = tableNode.asText().toLowerCase();
                                log.info("[TableSelection] 处理表选择: {}", tableName);
                                
                                Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                                if (count != null && count > 0) {
                                    selectedTables.add(tableName);
                                    log.info("[TableSelection] ✅ 表{}存在，已加入选择列表", tableName);
                                } else {
                                    log.warn("[TableSelection] ❌ 表{}不存在，跳过", tableName);
                                }
                            }
                            
                            if (!selectedTables.isEmpty()) {
                                log.info("[TableSelection] LLM精简表: {} -> {}", allTables.size(), selectedTables.size());
                                log.info("[TableSelection] 最终选择的表: {}", selectedTables);
                                allTables = selectedTables;
                                progressPublisher.accept("tables_optimized");
                            }
                            handled = true;
                            break;
                        }
                    }
                    // 情况2: LLM 指出缺少的表
                    else if (jsonNode.has("missing_tables")) {
                        JsonNode missingTablesNode = jsonNode.get("missing_tables");
                        if (missingTablesNode.isArray() && missingTablesNode.size() > 0) {
                            List<String> missingTables = new ArrayList<>();
                            for (JsonNode tableNode : missingTablesNode) {
                                missingTables.add(tableNode.asText().toLowerCase());
                            }
                            
                            progressPublisher.accept("finding_missing_tables");
                            
                            boolean foundNew = false;
                            for (String tableName : missingTables) {
                                if (allTables.contains(tableName)) {
                                    log.debug("[TableSelection] 表{}已在列表中，跳过", tableName);
                                    continue;
                                }
                                
                                // ✅ 关键修复：只查本地元数据表，不连远端库
                                Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                                log.info("[TableSelection] 检查表{}元数据: count={}", tableName, count);
                                
                                if (count != null && count > 0) {
                                    // 元数据存在，加入表列表
                                    allTables.add(tableName);
                                    foundNew = true;
                                    log.info("[TableSelection] ✅ 补充缺失表(元数据存在): {}", tableName);
                                } else {
                                    // 元数据不存在，触发澄清
                                    String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "缺少必要的表";
                                    clarificationMessage = String.format("系统元数据中未找到表'%s'，%s", tableName, reason);
                                    progressPublisher.accept("clarification_needed");
                                    log.warn("[TableSelection] ❌ 元数据中不存在{}，触发澄清: {}", tableName, reason);
                                    
                                    // ✅ 关键修复：立即返回澄清信号，不再继续后续流程
                                    TableSelectionResult result = new TableSelectionResult();
                                    result.setNeedsClarification(true);
                                    result.setClarificationMessage(clarificationMessage);
                                    return result;
                                }
                            }
                            
                            // ✅ 关键修复：LLM响应格式正确，设置handled=true
                            handled = true;
                            
                            // 如果找到了新表，重新验证；否则结束迭代
                            if (foundNew) {
                                log.info("[TableSelection] 已补充{}个新表，重新验证表完整性", 
                                    missingTables.size());
                                continue; // 继续下一轮验证
                            } else {
                                // 所有建议的表都已存在，结束表选择
                                log.info("[TableSelection] 所有建议的表都已在列表中，结束表选择");
                                break;
                            }
                        }
                    }
                    
                    // ✅ 关键修复：JSON解析成功但未匹配预期字段，说明LLM响应格式错误
                    if (!handled) {
                        log.warn("[TableSelection] JSON格式不符合预期，缺少selected_tables或missing_tables字段");
                        log.warn("[TableSelection] LLM原始响应: {}", llmResponse);
                        // 不执行传统解析，直接中断迭代
                        break;
                    }
                    
                } catch (Exception e) {
                    log.warn("[TableSelection] JSON解析失败，使用传统方式: {}", e.getMessage());
                    // JSON解析失败才执行传统解析
                }
            }
            
            log.info("[TableSelection] 最终确定 {} 个表: {}", allTables.size(), allTables);
            
            // 3. 如果仍然需要澄清，返回澄清信号
            if (needsClarification) {
                TableSelectionResult result = new TableSelectionResult();
                result.setNeedsClarification(true);
                result.setClarificationMessage(clarificationMessage);
                return result;
            }
            
            // 4. 如果表数量过多（>10），让用户选择
            if (allTables.size() > 10) {
                StringBuilder tableList = new StringBuilder();
                tableList.append("检测到较多相关表（" + allTables.size() + "个），请确认需要使用哪些表：\n");
                
                int index = 1;
                for (String tableName : allTables) {
                    String comment = schemaRetrievalService.getTableComment(tableName, datasourceId);
                    tableList.append(String.format("%d. %s (%s)\n", index++, tableName, 
                        comment != null && !comment.isEmpty() ? comment : "无注释"));
                }
                
                TableSelectionResult result = new TableSelectionResult();
                result.setNeedsTableSelection(true);
                result.setTableSelectionList(tableList.toString());
                return result;
            }
            
            // 5. 返回最终结果
            TableSelectionResult result = new TableSelectionResult();
            result.setExpandedQuery(expandedQuery);
            result.setSelectedTables(allTables);
            result.setLastLlmResponse(lastLlmResponse);
            
            // ✅ 新增：保存最终选择的表列表到ThreadLocal（用于5星反馈缓存）
            if (allTables != null && !allTables.isEmpty()) {
                List<String> tablesList = new ArrayList<>(allTables);
                finalSelectedTables.set(tablesList);
                log.info("[TableSelection] 已保存最终表列表: {}", tablesList);
                
                // ✅ 新增：缓存表组合（question -> selected_tables）
                tableCombinationCache.put(cacheKey, tablesList);
                log.debug("[TableSelection] 已缓存表组合: key={}, tables={}", cacheKey, tablesList);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("[TableSelection] 表选择流程失败", e);
            TableSelectionResult result = new TableSelectionResult();
            result.setError("错误：" + e.getMessage());
            return result;
        }
    }
    
    /**
     * 传统方式解析缺失表
     */
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
     * 表选择结果
     */
    @Data
    public static class TableSelectionResult {
        private String cachedSQL;              // 缓存的SQL（如果命中）
        private String expandedQuery;          // 扩展后的查询
        private Set<String> selectedTables;    // 选定的表
        private String lastLlmResponse;        // 最后一次LLM响应
        private boolean skipLLMSelection;      // 是否跳过LLM选择（单数据源）
        private boolean fromCache;             // ✅ 新增：是否来自表组合缓存
        private boolean needsClarification;    // 是否需要澄清
        private String clarificationMessage;   // 澄清消息
        private boolean needsTableSelection;   // 是否需要用户选择表
        private String tableSelectionList;     // 表选择列表
        private String error;                  // 错误信息
        
        public boolean hasCachedSQL() {
            return cachedSQL != null && !cachedSQL.trim().isEmpty();
        }
        
        public boolean hasError() {
            return error != null && !error.trim().isEmpty();
        }
    }
    
    /**
     * ✅ 替换SQL模板中的参数占位符
     * 从用户查询中提取数字，替换到SQL模板中
     * 
     * @param templateSQL SQL模板（可能包含INTERVAL N DAY等）
     * @param userQuery 用户原始查询
     * @return 替换后的SQL
     */
    private String replaceTemplateParameters(String templateSQL, String userQuery) {
        if (templateSQL == null || userQuery == null) {
            return templateSQL;
        }
        
        try {
            // 1. 提取用户查询中的所有数字
            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("\\d+");
            java.util.regex.Matcher numMatcher = numPattern.matcher(userQuery);
            
            List<Integer> numbers = new ArrayList<>();
            while (numMatcher.find()) {
                numbers.add(Integer.parseInt(numMatcher.group()));
            }
            
            if (numbers.isEmpty()) {
                log.debug("[TableSelection] 用户查询中无数字，直接返回模板");
                return templateSQL;
            }
            
            // 2. 提取SQL模板中的INTERVAL占位符模式：INTERVAL \d+ DAY/HOUR/MONTH/YEAR
            java.util.regex.Pattern intervalPattern = java.util.regex.Pattern.compile(
                "INTERVAL\\s+(\\d+)\\s+(DAY|HOUR|MONTH|YEAR|WEEK)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher intervalMatcher = intervalPattern.matcher(templateSQL);
            
            StringBuffer result = new StringBuffer();
            int numberIndex = 0;
            
            while (intervalMatcher.find()) {
                String oldNumber = intervalMatcher.group(1);
                String timeUnit = intervalMatcher.group(2);
                
                // 使用用户查询中的第一个数字替换
                if (numberIndex < numbers.size()) {
                    int newNumber = numbers.get(numberIndex);
                    String replacement = String.format("INTERVAL %d %s", newNumber, timeUnit);
                    intervalMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
                    log.info("[TableSelection] 替换参数: INTERVAL {} {} -> INTERVAL {} {}",
                        oldNumber, timeUnit, newNumber, timeUnit);
                    numberIndex++;
                } else {
                    // 没有更多数字，保持原样
                    intervalMatcher.appendReplacement(result, Matcher.quoteReplacement(intervalMatcher.group(0)));
                }
            }
            intervalMatcher.appendTail(result);
            
            String finalSQL = result.toString();
            log.info("[TableSelection] 参数替换完成: {} -> {}", templateSQL, finalSQL);
            return finalSQL;
            
        } catch (Exception e) {
            log.warn("[TableSelection] 参数替换失败，使用原始模板: {}", e.getMessage());
            return templateSQL;
        }
    }
}
