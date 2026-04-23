package com.nl2sql.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.common.util.MarkdownUtils;
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
            // ✅ P0优化：优先从QueryCache获取5分SQL模板
            if (queryCacheService != null) {
                String normalizedQuery = schemaRetrievalService.normalizeQueryForCache(query);
                com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                    queryCacheService.getFromCache(normalizedQuery);
                
                if (cached != null && cached.getUserRating() != null && cached.getUserRating() == 5) {
                    log.info("[TableSelection] ⚡⚡⚡ 5分SQL模板命中: question='{}', sql={}", 
                        query, cached.getSql());
                    sessionContextManager.saveCurrentContext(cached.getSql(), query);
                    
                    TableSelectionResult result = new TableSelectionResult();
                    result.setCachedSQL(cached.getSql());
                    return result;
                }
            }
            
            // ✅ 关键优化：尝试从缓存获取 SQL（避免 LLM 非确定性）
            if (queryCacheService != null) {
                try {
                    com.nl2sql.core.cache.QueryCacheService.CachedResult cached = 
                        queryCacheService.getFromCache(query);
                    
                    if (cached != null && cached.getData() != null && !cached.getData().isEmpty()) {
                        String cachedSQL = (String) cached.getData().get(0).get("sql");
                        if (cachedSQL != null && !cachedSQL.trim().isEmpty()) {
                            log.info("[TableSelection] SQL 缓存命中: question={}", query);
                            sessionContextManager.saveCurrentContext(cachedSQL, query);
                            
                            TableSelectionResult result = new TableSelectionResult();
                            result.setCachedSQL(cachedSQL);
                            return result;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[TableSelection] 缓存读取失败，继续生成 SQL", e);
                }
            }
            
            log.info("[TableSelection] 开始表选择流程: query={}, datasourceId={}", query, datasourceId);
            
            progressPublisher.accept("generating_sql");
            
            // 0. 同义词扩展（增强语义理解）
            String expandedQuery = synonymService.expandSynonyms(query, datasourceId);
            if (!expandedQuery.equals(query)) {
                log.info("[TableSelection] 查询扩展: {} -> {}", query, expandedQuery);
                progressPublisher.accept("synonym_expansion");
            }
            
            // 1. 初始向量检索（高召回）
            progressPublisher.accept("retrieving_tables");
            List<String> initialTables = vectorRetriever.retrieveTopTables(expandedQuery, datasourceId, 15);
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
                String relationshipInfo = ""; // TODO: 需要注入TableRelationshipService
                
                String checkPrompt = promptBuilder.apply(expandedQuery, schemaInfo);
                String llmResponse = modelRouter.smartGenerateSQL(checkPrompt, expandedQuery);
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
                                if (allTables.contains(tableName)) continue;
                                
                                Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                                if (count != null && count > 0) {
                                    allTables.add(tableName);
                                    foundNew = true;
                                    log.info("[TableSelection] 补充缺失表: {}", tableName);
                                }
                            }
                            
                            if (!foundNew) {
                                String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "缺少必要的表";
                                needsClarification = true;
                                clarificationMessage = reason;
                                progressPublisher.accept("clarification_needed");
                                break;
                            }
                            continue;
                        }
                    }
                    
                } catch (Exception e) {
                    log.warn("[TableSelection] JSON解析失败，使用传统方式: {}", e.getMessage());
                }
                
                // 传统解析方式（兼容旧格式）
                if (llmResponse.contains("表已足够") || llmResponse.contains("enough")) {
                    log.info("[TableSelection] LLM确认表已足够");
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
                    
                    Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                    if (count != null && count > 0) {
                        allTables.add(tableName);
                        foundNew = true;
                        log.info("[TableSelection] 迭代补充表: {}", tableName);
                    }
                }
                
                if (!foundNew) {
                    log.info("[TableSelection] 未找到新表，停止迭代");
                    break;
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
}
