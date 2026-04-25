package com.nl2sql.web.service;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.LogContextUtil;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.rag.SQLFeedbackService;
import com.nl2sql.core.service.SessionContextManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.nl2sql.core.service.NL2SQLService;

import java.util.*;

/**
 * Agent 对话服务
 * 
 * 职责：处理 Agent 对话的核心业务逻辑
 */
@Slf4j
@Service
public class AgentChatService {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Autowired(required = false)
    private SQLFeedbackService feedbackService;
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private NL2SQLService nl2sqlService;
    
    @Autowired(required = false)
    private SessionContextManager sessionContextManager;
    
    @Autowired(required = false)
    private IntentClassifier intentClassifier;
    
    @Autowired
    private AgentResponseProcessor responseProcessor;
    
    @Autowired(required = false)
    private JdbcTemplate datasourceJdbcTemplate; // ✅ 用于查询数据源配置
    
    @Autowired(required = false)
    private DatasourceSessionService datasourceSessionService; // ✅ 数据源会话管理
    
    @Autowired(required = false)
    private ConversationHistoryService historyService; // ✅ 对话历史服务
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher; // ✅ 事件发布器
    
    /**
     * 处理聊天请求
     */
    public Result<Map<String, Object>> processChat(
        ChatRequest request, 
        AuthService.UserInfo userInfo
    ) {
        long startTime = System.currentTimeMillis();
        
        LogContextUtil.setUserContext(userInfo.getUserId(), userInfo.getUsername());
        
        try {
            // 1. 修复编码问题
            String fixedMessage = fixEncoding(request.getMessage());
            
            // 3. 构建完整消息（包含 context）
            String fullMessage = buildFullMessage(fixedMessage, request.getContext());
            
            // 4. 意图识别
            String intent = classifyIntent(fullMessage);
            
            // 5. 设置会话ID
            String sessionId = resolveSessionId(request, userInfo);
            setSessionId(sessionId);
            
            // ✅ 6. 检测清除命令
            if (datasourceSessionService != null && datasourceSessionService.isClearCommand(fixedMessage)) {
                log.info("[Agent对话] 检测到清除命令，清除数据源缓存");
                datasourceSessionService.clearDatasourceCache(sessionId);
                
                Map<String, Object> clearResponse = new HashMap<>();
                clearResponse.put("success", true);
                clearResponse.put("message", "✅ 已清除当前数据源选择，下次查询将重新选择");
                clearResponse.put("sessionId", sessionId);
                return Result.success(clearResponse);
            }
            
            try {
                // 6. 调用 ReAct Agent
                String agentResponse = executeAgent(fullMessage, request, userInfo);
                
                long executionTime = System.currentTimeMillis() - startTime;
                
                // 7. 处理响应
                Map<String, Object> response = responseProcessor.processResponse(
                    agentResponse,
                    buildRequestMap(request),
                    buildUserInfoMap(userInfo)
                );
                
                // ✅ 8. 只有SQL查询成功才应用默认评分
                String status = (String) response.get("status");
                if ("success".equals(status) || "sql_generated".equals(status)) {
                    applyDefaultRating(request, userInfo);
                }
                
                // 9. 添加元数据
                enrichResponse(response, sessionId, executionTime, request);
                
                // 10. ✅ 异步记录监控数据（不阻塞主流程）
                publishMonitoringEvent(request, response, userInfo);
                
                // 11. 保存对话历史
                saveConversationHistory(sessionId, userInfo.getUserId(), fullMessage, agentResponse);
                
                return Result.success(response);
                
            } finally {
                clearSessionId();
                // ✅ 清理监控上下文（防止内存泄漏）
                com.nl2sql.core.service.MonitoringContext.clear();
            }
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("[Agent对话] 处理失败 (耗时: {} ms)", executionTime, e);
            return Result.error(500, "Agent 处理失败: " + e.getMessage());
        } finally {
            LogContextUtil.clear();
        }
    }
    
    /**
     * 应用默认评分
     */
    private void applyDefaultRating(ChatRequest request, AuthService.UserInfo userInfo) {
        if (feedbackService == null) return;
        
        String sessionId = resolveSessionId(request, userInfo);
        try {
            boolean applied = feedbackService.applyDefaultRating(sessionId);
            if (applied) {
                log.info("[Agent对话] 已应用默认评分: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[Agent对话] 应用默认评分失败，继续处理: {}", e.getMessage());
        }
    }
    
    /**
     * 修复编码问题
     */
    private String fixEncoding(String message) {
        if (message == null || message.trim().isEmpty()) {
            return message;
        }
        
        // 检测是否包含乱码特征
        if (message.matches("^[?\\s]+$")) {
            log.warn("[fixEncoding] 检测到纯问号文本，尝试重新解码");
            try {
                byte[] bytes = message.getBytes("ISO-8859-1");
                String fixed = new String(bytes, "UTF-8");
                
                if (fixed.matches(".*[\\u4e00-\\u9fa5].*")) {
                    log.info("[fixEncoding] 成功修复编码: {} -> {}", message, fixed);
                    return fixed;
                }
            } catch (Exception e) {
                log.error("[fixEncoding] 编码修复失败", e);
            }
        }
        
        return message;
    }
    
    /**
     * 构建完整消息
     */
    private String buildFullMessage(String message, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return message;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            
            // 如果 sessionContextManager 中有最新的 SQL，覆盖 context 中的旧 SQL
            Map<String, Object> contextMap = new HashMap<>(context);
            if (sessionContextManager != null) {
                String latestSQL = sessionContextManager.getCurrentSQL();
                String latestQuery = sessionContextManager.getCurrentQuery();
                if (latestSQL != null && !latestSQL.trim().isEmpty()) {
                    log.info("[Agent对话] 使用最新 SQL 覆盖 context: {}", latestSQL);
                    contextMap.put("generatedSQL", latestSQL);
                    if (latestQuery != null) {
                        contextMap.put("lastQuery", latestQuery);
                    }
                }
            }
            
            String contextJson = mapper.writeValueAsString(contextMap);
            String fullMessage = message + "\n\nContext: " + contextJson;
            log.info("[Agent对话] 附加 Context: {}", contextJson);
            return fullMessage;
            
        } catch (Exception e) {
            log.warn("[Agent对话] Context 序列化失败", e);
            return message;
        }
    }
    
    /**
     * 意图识别
     */
    private String classifyIntent(String message) {
        String intent = "QUERY"; // 默认意图
        if (intentClassifier != null) {
            intent = intentClassifier.classify(message);
            log.debug("[Agent对话] 意图识别结果: {}", intent);
        }
        return intent;
    }
    
    /**
     * 解析会话ID
     */
    private String resolveSessionId(ChatRequest request, AuthService.UserInfo userInfo) {
        return request.getSessionId() != null ? 
            request.getSessionId() : "default_" + userInfo.getUserId();
    }
    
    /**
     * 设置会话ID
     */
    private void setSessionId(String sessionId) {
        if (sessionContextManager != null) {
            sessionContextManager.setCurrentSessionId(sessionId);
            log.debug("[Agent对话] 已设置会话ID: {}", sessionId);
        }
    }
    
    /**
     * 清除会话ID
     */
    private void clearSessionId() {
        if (sessionContextManager != null) {
            sessionContextManager.clearCurrentSessionId();
        }
    }
    
    /**
     * 执行 Agent
     */
    private String executeAgent(
        String fullMessage, 
        ChatRequest request, 
        AuthService.UserInfo userInfo
    ) {
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "default_" + userInfo.getUserId();
        
        // ✅ 关键优化：智能预选择数据源（混合策略）
        Long resolvedDatasourceId = datasourceSessionService != null 
            ? datasourceSessionService.resolveDatasourceId(sessionId, request.getDatasourceId())
            : request.getDatasourceId();
        
        log.info("[Agent对话] 调用 ReActAgent.execute()... [datasourceId={}]", resolvedDatasourceId);
        
        // ✅ 加载对话历史
        List<Map<String, Object>> history = historyService != null 
            ? historyService.getHistory(sessionId)
            : Collections.emptyList();
        
        try {
            String result = reActAgent.execute(
                fullMessage,
                resolvedDatasourceId,
                userInfo.getUserId().longValue(),
                userInfo.getUsername(),
                history  // ✅ 传入历史消息
            );
            
            // ✅ 记录成功（自动续期）
            if (datasourceSessionService != null) {
                datasourceSessionService.recordSuccess(sessionId);
            }
            
            return result;
        } catch (Exception e) {
            // ✅ 记录失败（连续失败检测）
            if (datasourceSessionService != null) {
                boolean shouldClear = datasourceSessionService.recordFailure(sessionId);
                if (shouldClear) {
                    log.warn("[Agent对话] 连续失败，已清除数据源缓存");
                }
            }
            throw e;
        }
    }
    
    /**
     * 丰富响应数据
     */
    private void enrichResponse(
        Map<String, Object> response, 
        String sessionId, 
        long executionTime,
        ChatRequest request
    ) {
        response.put("sessionId", sessionId);
        response.put("executionTime", executionTime);
        
        // 返回 datasourceId（如果不存在）
        if (request.getDatasourceId() != null && !response.containsKey("datasourceId")) {
            response.put("datasourceId", request.getDatasourceId());
        }
        
        // ✅ 新增：从 SessionContextManager 获取 selected_tables 并放入 response
        if (sessionContextManager != null) {
            java.util.List<String> selectedTables = sessionContextManager.getSelectedTables();
            if (selectedTables != null && !selectedTables.isEmpty()) {
                response.put("selectedTables", selectedTables);
                log.debug("[enrichResponse] 已添加 selectedTables: {}", selectedTables);
            }
        }
        
        // ✅ 新增：从 MonitoringContext 获取监控数据并放入 response
        try {
            com.nl2sql.core.service.MonitoringContext.MonitoringData monitoringData = 
                com.nl2sql.core.service.MonitoringContext.get();
            
            if (monitoringData != null) {
                response.put("normalizedQuery", monitoringData.getNormalizedQuery());
                response.put("hasPersonEntity", monitoringData.getHasPersonEntity());
                response.put("hasLocationEntity", monitoringData.getHasLocationEntity());
                response.put("normalizationMethod", monitoringData.getNormalizationMethod());
                response.put("cacheLevel", monitoringData.getCacheLevel());
                response.put("cacheHit", monitoringData.getCacheHit());
                response.put("ragExamplesCount", monitoringData.getRagExamplesCount());
                response.put("industryTermsMatched", monitoringData.getIndustryTermsMatched());
                
                log.debug("[enrichResponse] 已添加监控数据: cacheLevel={}, ragCount={}",
                    monitoringData.getCacheLevel(), monitoringData.getRagExamplesCount());
            }
        } catch (Exception e) {
            log.warn("[enrichResponse] 获取监控数据失败", e);
        }
    }
    
    /**
     * 构建请求 Map
     */
    private Map<String, Object> buildRequestMap(ChatRequest request) {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("message", request.getMessage());
        requestMap.put("datasourceId", request.getDatasourceId());
        requestMap.put("sessionId", request.getSessionId());
        requestMap.put("userId", 1L);
        requestMap.put("context", request.getContext());
        return requestMap;
    }
    
    /**
     * 构建用户信息 Map
     */
    private Map<String, Object> buildUserInfoMap(AuthService.UserInfo userInfo) {
        Map<String, Object> userInfoMap = new HashMap<>();
        userInfoMap.put("userId", userInfo.getUserId());
        userInfoMap.put("username", userInfo.getUsername());
        userInfoMap.put("realName", userInfo.getRealName());
        return userInfoMap;
    }
    
    /**
     * 异步发布监控事件（不阻塞主流程）
     */
    private void publishMonitoringEvent(
        ChatRequest request,
        Map<String, Object> response,
        AuthService.UserInfo userInfo
    ) {
        if (eventPublisher == null) {
            log.debug("[Monitoring] EventPublisher未注入，跳过监控记录");
            return;
        }
        
        try {
            // ✅ 从 MonitoringContext 获取监控数据
            com.nl2sql.core.service.MonitoringContext.MonitoringData monitoringData = 
                com.nl2sql.core.service.MonitoringContext.get();
            
            if (monitoringData == null) {
                log.debug("[Monitoring] MonitoringContext为空，跳过监控记录");
                return;
            }
            
            // 构建监控数据Map
            Map<String, Object> eventData = new HashMap<>();
            String sessionId = request.getSessionId() != null ? 
                request.getSessionId() : "default_" + userInfo.getUserId();
            
            eventData.put("sessionId", sessionId);
            eventData.put("userId", userInfo.getUserId());
            eventData.put("question", request.getMessage());
            
            // 归一化相关
            eventData.put("normalizedQuery", monitoringData.getNormalizedQuery());
            eventData.put("hasPersonEntity", monitoringData.getHasPersonEntity());
            eventData.put("hasLocationEntity", monitoringData.getHasLocationEntity());
            eventData.put("normalizationMethod", monitoringData.getNormalizationMethod());
            
            // 缓存相关
            eventData.put("cacheLevel", monitoringData.getCacheLevel());
            eventData.put("cacheHit", monitoringData.getCacheHit());
            
            // RAG相关
            eventData.put("ragExamplesCount", monitoringData.getRagExamplesCount());
            eventData.put("industryTermsMatched", monitoringData.getIndustryTermsMatched());
            
            // SQL执行相关
            Boolean success = (Boolean) response.get("success");
            String sql = (String) response.get("sql");
            Integer rowCount = response.get("rowCount") != null ? 
                (Integer) response.get("rowCount") : 0;
            Long executionTime = response.get("executionTime") != null ? 
                (Long) response.get("executionTime") : 0L;
            
            eventData.put("generatedSql", sql != null ? sql : "");
            eventData.put("executedSql", sql != null ? sql : "");
            eventData.put("executionSuccess", success != null ? success : false);
            eventData.put("rowCount", rowCount);
            eventData.put("executionTimeMs", executionTime);
            eventData.put("datasourceId", request.getDatasourceId());
            
            // selected_tables
            java.util.List<String> selectedTables = sessionContextManager != null ? 
                sessionContextManager.getSelectedTables() : null;
            if (selectedTables != null && !selectedTables.isEmpty()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                eventData.put("selectedTables", mapper.writeValueAsString(selectedTables));
            }
            
            // ✅ 发布异步事件
            com.nl2sql.core.event.QueryMonitoringEvent event = 
                new com.nl2sql.core.event.QueryMonitoringEvent(this, eventData);
            eventPublisher.publishEvent(event);
            
            log.debug("[Monitoring] 已发布监控事件: sessionId={}, cacheLevel={}", 
                sessionId, monitoringData.getCacheLevel());
            
        } catch (Exception e) {
            log.warn("[Monitoring] 发布监控事件失败", e);
        }
    }
    
    /**
     * 保存对话历史（优化版：只保存摘要，避免上下文爆炸）
     */
    private void saveConversationHistory(String sessionId, Number userId, String userMessage, String agentResponse) {
        if (historyService == null) {
            return;
        }
        
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            
            // User message
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);
            
            // ✅ 关键优化：解析 agentResponse，只保存摘要信息
            String assistantContent = extractSummaryFromResponse(agentResponse);
            
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantContent);
            messages.add(assistantMsg);
            
            historyService.saveHistory(sessionId, userId.longValue(), messages);
            
        } catch (Exception e) {
            log.warn("[对话历史] 保存失败", e);
        }
    }
    
    /**
     * 从 Agent 响应中提取摘要信息（避免保存完整结果数据）
     * 
     * @param agentResponse 完整的 Agent 响应 JSON
     * @return 摘要文本
     */
    private String extractSummaryFromResponse(String agentResponse) {
        // ✅ 先检查是否为 JSON 格式
        if (agentResponse == null || !agentResponse.trim().startsWith("{")) {
            log.debug("[对话历史] 响应非 JSON 格式，直接截断保存");
            if (agentResponse != null && agentResponse.length() > 1000) {
                return agentResponse.substring(0, 1000) + "... [已截断]";
            }
            return agentResponse;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> response = mapper.readValue(agentResponse, Map.class);
            
            Boolean success = (Boolean) response.get("success");
            if (success != null && success) {
                // 成功查询：保存 SQL + 结果摘要
                String sql = (String) response.get("sql");
                Integer rowCount = (Integer) response.get("rowCount");
                
                StringBuilder summary = new StringBuilder();
                summary.append("✅ 查询成功\n");
                if (sql != null) {
                    summary.append("SQL: ").append(sql).append("\n");
                }
                if (rowCount != null) {
                    summary.append("结果: ").append(rowCount).append(" 行");
                }
                
                // ✅ 可选：添加前3行数据样本（限制字段数）
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data != null && !data.isEmpty()) {
                    summary.append("\n\n数据样本（前3行）:\n");
                    int sampleSize = Math.min(3, data.size());
                    for (int i = 0; i < sampleSize; i++) {
                        Map<String, Object> row = data.get(i);
                        // 只取前5个字段
                        int fieldCount = 0;
                        for (Map.Entry<String, Object> entry : row.entrySet()) {
                            if (fieldCount >= 5) break;
                            summary.append(entry.getKey()).append(": ").append(entry.getValue()).append(", ");
                            fieldCount++;
                        }
                        summary.append("\n");
                    }
                }
                
                return summary.toString();
            } else {
                // 失败：保存错误信息
                String error = (String) response.get("error");
                return "❌ 查询失败: " + (error != null ? error : "未知错误");
            }
            
        } catch (Exception e) {
            log.warn("[对话历史] 解析响应失败，保存原始响应", e);
            // 降级：如果解析失败，截断原始响应
            if (agentResponse != null && agentResponse.length() > 1000) {
                return agentResponse.substring(0, 1000) + "... [已截断]";
            }
            return agentResponse;
        }
    }
    
    /**
     * 聊天请求 DTO
     */
    public static class ChatRequest {
        @jakarta.validation.constraints.NotBlank(message = "消息不能为空")
        private String message;
        private Long datasourceId;
        private String sessionId;
        private Map<String, Object> context;
        
        // Getters and Setters
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public Map<String, Object> getContext() { return context; }
        public void setContext(Map<String, Object> context) { this.context = context; }
    }
}
