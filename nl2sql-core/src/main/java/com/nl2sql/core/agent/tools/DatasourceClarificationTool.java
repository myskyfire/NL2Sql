package com.nl2sql.core.agent.tools;

import com.nl2sql.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.config.TableSelectionConfig;
import com.nl2sql.core.llm.LLMService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据源澄清 Tool - 使用LLM智能选择或列出可用数据源
 */
@Slf4j
@Component
public class DatasourceClarificationTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private TableSelectionConfig tableSelectionConfig;
    
    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public DatasourceClarificationTool(LLMService llmService) {
        this.llmService = llmService;
    }
    
    /**
     * 智能选择数据源或列出选项
     * 
     * @param userQuery 用户原始问题
     * @return JSON格式的澄清响应
     */
    @Tool("根据用户问题智能选择数据源。如果意图明确则推荐对应数据源并请求确认；如果无法判断则列出所有数据源供选择。输入用户问题")
    /**
     * ✅ 重构：统一返回JSON结构，便于LLM和前端统一处理
     */
    public String clarifyDatasource(String userQuery) {
        try {
            log.info("[DatasourceClarification] 分析用户问题: {}", userQuery);
                
            List<Map<String, Object>> datasources = getActiveDatasources();
                
            if (datasources.isEmpty()) {
                return buildErrorResponse("没有可用的数据源，请联系管理员配置");
            }
                
            // 情况1：只有一个数据源，自动选择
            if (datasources.size() == 1) {
                Map<String, Object> ds = datasources.get(0);
                Long dsId = ((Number) ds.get("id")).longValue();
                String dsName = (String) ds.get("name");
                
                return buildAutoSelectedResponse(dsId, dsName, formatDatasourceInfo(ds), true);
            }
                
            // 情况2：使用LLM智能匹配
            Map<String, Object> matchResult = llmIntelligentMatch(userQuery, datasources);
                        
            if (matchResult != null && matchResult.containsKey("datasource")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> matchedDs = (Map<String, Object>) matchResult.get("datasource");
                Long dsId = ((Number) matchedDs.get("id")).longValue();
                String dsName = (String) matchedDs.get("name");
                double confidence = matchResult.containsKey("confidence") ? 
                    ((Number) matchResult.get("confidence")).doubleValue() : 0.0;
                
                // ✅ 三层分级策略
                if (confidence > 0.8) {
                    // 高置信度：根据配置决定自动执行或推荐确认
                    return buildAutoSelectedResponse(dsId, dsName, formatDatasourceInfo(matchedDs), false);
                } else if (confidence >= 0.6) {
                    // 中置信度：推荐+确认，提供"拒绝后选择其他"选项
                    return buildMediumConfidenceRecommendation(matchedDs, confidence, 
                        String.valueOf(matchResult.get("reason")), datasources);
                } else {
                    // 低置信度（<0.6）：直接列出所有数据源供选择
                    log.info("[DatasourceClarification] 低置信度({})，返回所有数据源", confidence);
                    return buildDatasourceSelectionResponse(datasources);
                }
            }
            
            // ✅ 情况3：LLM无法确定或置信度不足，返回所有候选让用户选择
            log.info("[DatasourceClarification] LLM未能高置信度匹配，返回所有数据源供用户选择");
            return buildDatasourceSelectionResponse(datasources);
                
        } catch (Exception e) {
            log.error("[DatasourceClarification] 处理失败", e);
            return buildErrorResponse("无法获取数据源列表 - " + e.getMessage());
        }
    }
    
    /**
     * 获取所有激活的数据源（包含business_category字段）
     */
    private List<Map<String, Object>> getActiveDatasources() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, db_type, host, port, database_name, description, business_category FROM datasource_config WHERE is_active = 1 ORDER BY name"
        );
    }
    
    /**
     * 使用LLM智能匹配数据源（动态分层策略）
     */
    private Map<String, Object> llmIntelligentMatch(String userQuery, List<Map<String, Object>> datasources) {
        try {
            // ✅ 动态分层：数据源>5个时先筛选候选集
            if (datasources.size() > 5) {
                log.info("[DatasourceClarification] 数据源数量={}，启用双层策略", datasources.size());
                return twoLayerMatch(userQuery, datasources);
            } else {
                log.info("[DatasourceClarification] 数据源数量={}，使用单层策略", datasources.size());
                return singleLayerMatch(userQuery, datasources);
            }
        } catch (Exception e) {
            log.error("[DatasourceClarification] LLM匹配失败", e);
            return null;
        }
    }
    
    /**
     * 单层策略：直接展示所有表（适用于≤5个数据源）
     * @return Map包含: datasource(匹配的数据源), confidence(置信度), reason(推荐理由)
     */
    private Map<String, Object> singleLayerMatch(String userQuery, List<Map<String, Object>> datasources) {
        try {
            // ✅ P1优化：合并两层为单层，qwen3.5-plus可直接从精简表结构做出准确判断
            StringBuilder datasourceInfo = new StringBuilder();
            
            for (Map<String, Object> ds : datasources) {
                Long dsId = ((Number) ds.get("id")).longValue();
                String dbName = String.valueOf(ds.get("database_name"));
                
                datasourceInfo.append(String.format(
                    "\n### 数据源 [%d] %s\n",
                    ds.get("id"),
                    ds.get("name")
                ));
                datasourceInfo.append(String.format("- 数据库: %s\n", dbName));
                if (ds.get("description") != null) {
                    datasourceInfo.append(String.format("- 说明: %s\n", ds.get("description")));
                }
                if (ds.get("business_category") != null) {
                    datasourceInfo.append(String.format("- 业务类别: %s\n", ds.get("business_category")));
                }
                
                // ✅ 从本地元数据表查询表列表（避免连接远程业务数据库）
                try {
                    List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                        "SELECT table_name, table_comment FROM table_metadata " +
                        "WHERE datasource_id = ? " +
                        "ORDER BY table_name",
                        dsId
                    );
                    
                    if (!tables.isEmpty()) {
                        datasourceInfo.append("- 表列表:\n");
                        for (Map<String, Object> table : tables) {
                            String tableName = String.valueOf(table.get("table_name"));
                            String tableComment = table.get("table_comment") != null ? 
                                String.valueOf(table.get("table_comment")) : "";
                            // 仅展示表名+简短注释，控制Token
                            String shortComment = tableComment.length() > 20 ? 
                                tableComment.substring(0, 20) + "..." : tableComment;
                            datasourceInfo.append(String.format("  - %s: %s\n", tableName, shortComment));
                        }
                    } else {
                        datasourceInfo.append("- 表列表: 无表或无法访问\n");
                    }
                } catch (Exception e) {
                    log.warn("[DatasourceClarification] 查询数据源{}的表结构失败: {}", dsId, e.getMessage());
                    datasourceInfo.append("- 表列表: 查询失败\n");
                }
            }
            
            // ✅ 单层Prompt：直接提供精简表结构进行匹配
            String prompt = String.format(
                "你是数据源选择专家。根据用户问题和表结构，匹配最相关的数据源。\n\n" +
                "用户问题：%s\n\n" +
                "可用数据源（含核心表）：\n%s\n\n" +
                "任务：分析用户问题涉及的表，找到最匹配的数据源。无法确定则返回null。\n\n" +
                "输出标准JSON格式，包含字段：matched_datasource_id, confidence, reason",
                userQuery,
                datasourceInfo.toString()
            );
            
            log.info("[DatasourceClarification] 调用LLM进行数据源匹配");
            String response = llmService.generateSQL(prompt);
            log.info("[DatasourceClarification] LLM响应: {}", response);
            
            // 解析响应
            Map<String, Object> result = parseLlmResponse(response);
            
            if (result != null && result.containsKey("matched_datasource_id")) {
                Integer matchedId = result.get("matched_datasource_id") instanceof Number ?
                    ((Number) result.get("matched_datasource_id")).intValue() : null;
                
                // ✅ 修复：confidence可能是Double或String，统一处理
                double confidence = 0.0;
                Object confObj = result.get("confidence");
                if (confObj instanceof Number) {
                    confidence = ((Number) confObj).doubleValue();
                } else if (confObj instanceof String) {
                    try {
                        confidence = Double.parseDouble((String) confObj);
                    } catch (NumberFormatException e) {
                        confidence = 0.0;
                    }
                }
                
                // 置信度 > 0.6 认为匹配成功（平衡准确率与召回率）
                if (matchedId != null && confidence > 0.6) {
                    for (Map<String, Object> ds : datasources) {
                        if (((Number) ds.get("id")).intValue() == matchedId) {
                            log.info("[DatasourceClarification] 匹配成功: {}, confidence={}", ds.get("name"), confidence);
                            // ✅ 返回完整结果，包含置信度和理由
                            Map<String, Object> matchResult = new HashMap<>();
                            matchResult.put("datasource", ds);
                            matchResult.put("confidence", confidence);
                            matchResult.put("reason", result.get("reason"));
                            return matchResult;
                        }
                    }
                } else if (matchedId != null) {
                    // ✅ 低置信度匹配，仍返回结果供前端展示
                    log.info("[DatasourceClarification] 低置信度匹配: {}, confidence={}", matchedId, confidence);
                    for (Map<String, Object> ds : datasources) {
                        if (((Number) ds.get("id")).intValue() == matchedId) {
                            Map<String, Object> matchResult = new HashMap<>();
                            matchResult.put("datasource", ds);
                            matchResult.put("confidence", confidence);
                            matchResult.put("reason", result.get("reason"));
                            matchResult.put("lowConfidence", true);  // 标记为低置信度
                            return matchResult;
                        }
                    }
                }
            }
            
            log.info("[DatasourceClarification] 未能确定数据源");
            return null;
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 单层匹配失败", e);
            return null;
        }
    }
    
    /**
     * 双层策略：第一层筛选候选集，第二层精确匹配（适用于>5个数据源）
     */
    private Map<String, Object> twoLayerMatch(String userQuery, List<Map<String, Object>> datasources) {
        try {
            // 第一层：基于数据源基本信息筛选候选集（~2-3个）
            StringBuilder basicInfo = new StringBuilder();
            for (Map<String, Object> ds : datasources) {
                basicInfo.append(String.format(
                    "- ID: %d, 名称: %s, 数据库: %s, 说明: %s, 业务类别: %s\n",
                    ds.get("id"),
                    ds.get("name"),
                    ds.get("database_name"),
                    ds.get("description") != null ? ds.get("description") : "无",
                    ds.get("business_category") != null ? ds.get("business_category") : "无"
                ));
            }
            
            String firstPrompt = String.format(
                "你是数据源选择助手。根据用户问题和数据源列表，筛选最相关的2-3个候选数据源。\n\n" +
                "用户问题：%s\n\n" +
                "可用数据源：\n%s\n\n" +
                "任务：分析意图和业务领域，返回候选数据源ID列表。\n\n" +
                "输出标准JSON格式，包含字段：candidate_ids（整数数组）, reason",
                userQuery,
                basicInfo.toString()
            );
            
            log.info("[DatasourceClarification] 第一层：筛选候选集");
            String firstResponse = llmService.generateSQL(firstPrompt);
            log.info("[DatasourceClarification] 第一层LLM响应: {}", firstResponse);
            
            Map<String, Object> firstResult = parseLlmResponse(firstResponse);
            if (firstResult == null || !firstResult.containsKey("candidate_ids")) {
                log.warn("[DatasourceClarification] 第一层解析失败，降级为单层策略");
                return singleLayerMatch(userQuery, datasources);
            }
            
            @SuppressWarnings("unchecked")
            List<Integer> candidateIds = (List<Integer>) firstResult.get("candidate_ids");
            if (candidateIds == null || candidateIds.isEmpty()) {
                log.warn("[DatasourceClarification] 第一层未找到候选集，降级为单层策略");
                return singleLayerMatch(userQuery, datasources);
            }
            
            // 过滤出候选数据源
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Map<String, Object> ds : datasources) {
                if (candidateIds.contains(((Number) ds.get("id")).intValue())) {
                    candidates.add(ds);
                }
            }
            
            log.info("[DatasourceClarification] 第一层筛选结果: {} 个候选数据源", candidates.size());
            
            // 第二层：展示候选集的完整表列表，精确匹配
            return singleLayerMatch(userQuery, candidates);
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 双层匹配失败，降级为单层策略", e);
            return singleLayerMatch(userQuery, datasources);
        }
    }

    
    /**
     * 解析LLM返回的JSON（使用公共工具类）
     */
    private Map<String, Object> parseLlmResponse(String response) {
        return JsonUtils.parseToJsonMap(response);
    }
    
    /**
     * ✅ 新增：构建中置信度推荐响应（推荐+确认+拒绝后选择其他）
     */
    private String buildMediumConfidenceRecommendation(Map<String, Object> recommendedDs, 
                                                        double confidence, 
                                                        String reason,
                                                        List<Map<String, Object>> allDatasources) {
        try {
            Long dsId = ((Number) recommendedDs.get("id")).longValue();
            String dsName = (String) recommendedDs.get("name");
            
            // 构建推荐理由
            String recommendationMsg = String.format(
                "💡 根据您的问句，**推荐使用数据源：%s**\n\n" +
                "**匹配理由：** %s\n" +
                "**置信度：** %.0f%%\n\n" +
                "您可以：\n" +
                "1️⃣ **使用此数据源** - 直接执行查询\n" +
                "2️⃣ **选择其他数据源** - 查看所有可用数据源",
                escapeJson(dsName),
                escapeJson(reason != null ? reason : "无"),
                confidence * 100
            );
            
            // 构建所有数据源列表（供用户选择其他）
            List<Map<String, Object>> datasourceList = new ArrayList<>();
            for (Map<String, Object> ds : allDatasources) {
                Map<String, Object> dsInfo = new HashMap<>();
                dsInfo.put("id", ds.get("id"));
                dsInfo.put("name", ds.get("name"));
                dsInfo.put("db_type", ds.get("db_type"));
                dsInfo.put("database_name", ds.get("database_name"));
                if (ds.get("description") != null && !String.valueOf(ds.get("description")).isEmpty()) {
                    dsInfo.put("description", ds.get("description"));
                }
                datasourceList.add(dsInfo);
            }
            
            // ✅ 构建统一响应
            return ToolResponseBuilder.clarification("datasource_medium_confidence")
                .withMessage(recommendationMsg)
                .withRecommendedDatasourceId(dsId)
                .withContext(Map.of(
                    "recommendedDatasource", recommendedDs,
                    "availableDatasources", datasourceList,
                    "confidence", confidence,
                    "reason", reason
                ))
                .addMetadata("toolName", "clarify_datasource")
                .addMetadata("confidenceLevel", "medium")
                .build();
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 构建中置信度推荐响应失败", e);
            return ToolResponseBuilder.error("BUILD_ERROR", "构建响应失败: " + e.getMessage())
                .addMetadata("toolName", "clarify_datasource")
                .build();
        }
    }
    
    /**
     * ✅ 重构：构建数据源选择响应 - 返回JSON供前端渲染按钮
     */
    private String buildDatasourceSelectionResponse(List<Map<String, Object>> datasources) {
        // ✅ 构建数据源列表
        List<Map<String, Object>> datasourceList = new ArrayList<>();
        for (Map<String, Object> ds : datasources) {
            Map<String, Object> dsInfo = new HashMap<>();
            dsInfo.put("id", ds.get("id"));
            dsInfo.put("name", ds.get("name"));
            dsInfo.put("db_type", ds.get("db_type"));
            dsInfo.put("database_name", ds.get("database_name"));
            if (ds.get("description") != null && !String.valueOf(ds.get("description")).isEmpty()) {
                dsInfo.put("description", ds.get("description"));
            }
            datasourceList.add(dsInfo);
        }
        
        // ✅ 构建统一响应
        return ToolResponseBuilder.clarification("datasource_selection")
            .withMessage("📋 请选择数据源：")
            .withContext(Map.of("availableDatasources", datasourceList))
            .addMetadata("toolName", "clarify_datasource")
            .addMetadata("datasourceCount", datasources.size())
            .build();
    }
    
    /**
     * ✅ 新增：构建自动选择响应（统一JSON格式）
     */
    private String buildAutoSelectedResponse(Long dsId, String dsName, String datasourceInfo, boolean isOnlyOne) {
        try {
            // ✅ 关键逻辑：检查是否满足自动选择条件
            boolean shouldAutoSelect = tableSelectionConfig.isAutoSelectDatasource() 
                && !isOnlyOne;
            
            String message;
            boolean autoExecuted;
            
            if (isOnlyOne) {
                // 唯一数据源：始终自动执行
                message = String.format(
                    "✅ 系统只有唯一数据源：%s\n\n%s\n\n请直接使用此数据源执行查询，无需再次确认。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = true;
            } else if (shouldAutoSelect) {
                // ✅ 配置开启 + LLM匹配成功：自动选择，不返回前端确认
                message = String.format(
                    "🎯 自动选择数据源：%s\n\n%s\n\n已自动使用该数据源执行查询。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = true;
                log.info("[DatasourceClarification] ✅ 自动选择数据源: {}", dsName);
            } else {
                // 需要前端确认
                message = String.format(
                    "🎯 根据您的问句，推荐使用数据源：%s\n\n%s\n\n请使用此数据源执行查询。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = false;
            }
            
            // ✅ 构建统一响应
            return ToolResponseBuilder.clarification("datasource_recommendation")
                .withMessage(message)
                .withAutoExecuted(autoExecuted)
                .withRecommendedDatasourceId(dsId)
                .withContext(Map.of("datasourceInfo", datasourceInfo))
                .addMetadata("toolName", "clarify_datasource")
                .addMetadata("isOnlyOne", isOnlyOne)
                .build();
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 构建自动选择响应失败", e);
            return ToolResponseBuilder.error("BUILD_ERROR", "构建响应失败: " + e.getMessage())
                .addMetadata("toolName", "clarify_datasource")
                .build();
        }
    }
    
    /**
     * ✅ 新增：构建错误响应（统一JSON格式）
     */
    private String buildErrorResponse(String errorMessage) {
        return ToolResponseBuilder.error("DATASOURCE_ERROR", escapeJson(errorMessage))
            .addMetadata("toolName", "clarify_datasource")
            .build();
    }
    
    /**
     * 格式化数据源信息
     */
    private String formatDatasourceInfo(Map<String, Object> ds) {
        StringBuilder info = new StringBuilder();
        info.append(String.format("- 名称: %s\n", ds.get("name")));
        info.append(String.format("- 类型: %s\n", ds.get("db_type")));
        info.append(String.format("- 数据库: %s\n", ds.get("database_name")));
        if (ds.get("description") != null && !String.valueOf(ds.get("description")).isEmpty()) {
            info.append(String.format("- 说明: %s", ds.get("description")));
        }
        return info.toString();
    }
    
    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
