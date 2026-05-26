package com.nl2sql.web.service;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.context.UserContext;
import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.BooleanUtils;
import com.nl2sql.common.util.LogContextUtil;
import com.nl2sql.conversation.ConversationHistoryService;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.agent.SupervisorAgent;
import com.nl2sql.core.agent.tools.AISummaryTool;
import com.nl2sql.core.agent.tools.ChartDetectionTool;
import com.nl2sql.core.agent.tools.ToolRegistry;
import com.nl2sql.core.rag.SQLFeedbackService;
import com.nl2sql.core.service.MonitoringContext;
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
    private SupervisorAgent supervisorAgent;

    @Autowired(required = false)
    private ReActAgent reActAgent; // ⚠️ 保留供降级使用
    
    @Autowired(required = false)
    private SQLFeedbackService feedbackService;
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private NL2SQLService nl2sqlService;
    
    @Autowired(required = false)
    private SessionContextManager sessionContextManager;
    
    // ✅ 已移除：意图分类由 SkillRouter 处理
    // @Autowired(required = false)
    // private IntentClassifier intentClassifier;
    
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
    
    @Autowired(required = false)
    private ToolRegistry toolRegistry; // ✅ Tool 注册中心（追问执行用）
    
    /**
     * 处理聊天请求
     */
    public Result<Map<String, Object>> processChat(
        ChatRequest request, 
        AuthService.UserInfo userInfo
    ) {
        long startTime = System.currentTimeMillis();
        
        // ✅ 关键优化：设置用户上下文到ThreadLocal，避免层层传参
        String sessionId = resolveSessionId(request, userInfo);
        UserContext.set(new UserContext.UserInfo(
            userInfo.getUserId(), 
            userInfo.getUsername(), 
            sessionId
        ));
        
        LogContextUtil.setUserContext(userInfo.getUserId(), userInfo.getUsername());
        LogContextUtil.setSessionId(sessionId);
        
        try {
            // 1. 修复编码问题
            String fixedMessage = fixEncoding(request.getMessage());
            
            // 3. 构建完整消息（包含 context）
            String fullMessage = buildFullMessage(fixedMessage, request.getContext());
            
            /*// 4. 意图识别
            String intent = classifyIntent(fullMessage);*/
            
            // 5. 设置会话ID到 SessionContextManager
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
            
            // ✅ 7. 追问意图识别（过渡期：仅自然语言追问触发，按钮追问走原有流程）
            String followUpResult = detectAndExecuteFollowUp(request, userInfo, fixedMessage);
            if (followUpResult != null) {
                // 追问已处理，直接返回结果
                long executionTime = System.currentTimeMillis() - startTime;
                Map<String, Object> response = responseProcessor.processResponse(
                    followUpResult,
                    buildRequestMap(request),
                    buildUserInfoMap(userInfo)
                );
                enrichResponse(response, sessionId, executionTime, request);
                publishMonitoringEvent(request, response, userInfo);
                saveConversationHistory(sessionId, userInfo.getUserId(), fullMessage, response);
                
                return Result.success(response);
            }
            
            try {
                // 6. 调用 Agent
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
                
                // 11. 保存对话历史（使用已解析的Map，避免重复解析）
                saveConversationHistory(sessionId, userInfo.getUserId(), fullMessage, response);
                
                return Result.success(response);
                
            } finally {
                clearSessionId();
                // ✅ 清理监控上下文（防止内存泄漏）
                MonitoringContext.clear();
                // ✅ 清理用户上下文
                UserContext.clear();
            }
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("[Agent对话] 处理失败 (耗时: {} ms)", executionTime, e);
            return Result.error(500, "Agent 处理失败: " + e.getMessage());
        } finally {
            LogContextUtil.clear();
            UserContext.clear(); // 双重保障
        }
    }
    
    /**
     * 应用默认评分
     */
    private void applyDefaultRating(ChatRequest request, AuthService.UserInfo userInfo) {
        if (feedbackService == null) return;
        
        // ✅ 关键优化：从 UserContext 直接获取 sessionId
        String sessionId = UserContext.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            // 降级方案：从 request 解析
            sessionId = resolveSessionId(request, userInfo);
        }
        
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
     * 意图识别（已由 SkillRouter 接管）
     */
    private String classifyIntent(String message) {
        // ✅ 简化：直接返回默认意图，实际路由由 ReActAgent 内部的 SkillRouter 处理
        return "QUERY";
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
        
        log.info("[Agent对话] 调用 SupervisorAgent.execute()... [datasourceId={}]", resolvedDatasourceId);

        // ✅ 加载对话历史
        List<Map<String, Object>> history = historyService != null
            ? historyService.getHistory(sessionId)
            : Collections.emptyList();

        try {
            String result = supervisorAgent.execute(
                fullMessage,
                resolvedDatasourceId,
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
            Boolean success = com.nl2sql.common.util.BooleanUtils.toBoolean(response.get("success"));
            String sql = (String) response.get("sql");
            Integer rowCount = parseInteger(response.get("rowCount"));
            Long executionTime = parseLong(response.get("executionTime"));
            
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
    private void saveConversationHistory(String sessionId, Number userId, String userMessage, Map<String, Object> response) {
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
            
            // ✅ 关键优化：解析 response，只保存摘要信息
            String assistantContent = extractSummaryFromResponse(response);
            
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
     * @param response 已解析的 Agent 响应 Map
     * @return 摘要文本
     */
    private String extractSummaryFromResponse(Map<String, Object> response) {
        if (response == null) {
            return "无响应";
        }
        
        try {
            Boolean success = com.nl2sql.common.util.BooleanUtils.toBoolean(response.get("success"));
            if (BooleanUtils.isTrue(success)) {
                // 成功查询：保存 SQL + 结果摘要
                String sql = (String) response.get("sql");
                Integer rowCount = parseInteger(response.get("rowCount"));
                
                StringBuilder summary = new StringBuilder();
                summary.append("✅ 查询成功\n");
                if (sql != null) {
                    summary.append("SQL: ").append(sql).append("\n");
                }
                if (rowCount != null) {
                    summary.append("结果: ").append(rowCount).append(" 行");
                }
                
                // ✅ 可选：添加前3行数据样本（限制字段数）
                Object dataObj = response.get("data");
                List<Map<String, Object>> data = null;
                if (dataObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tempList = (List<Map<String, Object>>) dataObj;
                    data = tempList;
                }
                
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
            log.warn("[对话历史] 提取摘要失败", e);
            return "响应处理异常";
        }
    }
    
    /**
     * ✅ 人机协同：处理SQL确认请求
     * 
     * @param approvalId 确认ID
     * @param approved 是否批准
     * @param userInfo 用户信息
     * @return 执行结果
     */
    public Result<Map<String, Object>> handleSqlApproval(
        String approvalId, 
        Boolean approved,
        AuthService.UserInfo userInfo
    ) {
        log.info("[人机协同] 处理SQL确认: approvalId={}, approved={}, userId={}", 
            approvalId, approved, userInfo.getUserId());
        
        if (!approved) {
            // 用户取消执行
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "❌ 已取消执行高风险SQL");
            response.put("approvalId", approvalId);
            return Result.success(response);
        }
        
        // ✅ 用户批准，需要重新执行原始SQL
        // TODO: 从缓存中获取原始请求参数，重新调用Agent执行
        // 当前简化实现：返回提示信息，前端需要重新发送完整请求
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "✅ 已批准执行，请重新发送查询请求");
        response.put("approvalId", approvalId);
        response.put("note", "由于会话状态已过期，请重新发送原始问题以执行SQL");
        
        log.info("[人机协同] SQL已批准，但需重新发送请求: approvalId={}", approvalId);
        
        return Result.success(response);
    }
    
    /**
     * 安全解析 Integer（兼容 String/Number/null）
     */
    private Integer parseInteger(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * 安全解析 Long（兼容 String/Number/null）
     */
    private Long parseLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
    
    /**
     * 聊天请求 DTO
     */
    /**
     * 检测并执行追问（业界标准 2 层架构）
     * 
     * @return 如果处理了追问，返回 Tool 执行结果；否则返回 null（走正常流程）
     */
    private String detectAndExecuteFollowUp(ChatRequest request, AuthService.UserInfo userInfo, String message) {
        // ✅ L1: 前端显式信号验证
        if (!Boolean.TRUE.equals(request.getHasData()) || request.getContextId() == null) {
            return null;  // 前端未标识为追问，走正常 Agent 流程
        }
        
        log.info("[L1] 前端已标识为追问: contextId={}, hasData=true", request.getContextId());
        
        // ✅ L2: 后端二次验证（滑动窗口衰减 + 意图识别）
        String followUpSkill = detectFollowUpIntent(message, request.getSessionId());
        if (followUpSkill == null) {
            log.info("[L2] 后端判定非追问，走正常流程");
            return null;  // 后端判定不是追问，走正常流程
        }
        
        log.info("[追问识别] 确认追问意图: skill={}, message={}", followUpSkill, message);
        
        // 执行追问 Tool
        return executeFollowUpTool(followUpSkill, request, userInfo);
    }
    
    /**
     * 检测追问意图（业界标准 2 层架构）
     * L1: 关键词匹配（快速过滤）
     * L2: 滑动窗口衰减 + 实体重叠度（精细判断）
     */
    private String detectFollowUpIntent(String message, String sessionId) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        
        String lowerMsg = message.toLowerCase().trim();
        
        // ========== L1: 显式信号检测 ==========
        
        // ❌ 新话题信号词（confidence=1.0 -> 新查询）
        if (lowerMsg.matches(".*(统计|查询|查看|获取|列出|显示|找出|检索).*") && message.length() > 8) {
            log.debug("[L1] 检测到新话题信号，非追问");
            return null;
        }
        
        if (lowerMsg.matches(".*(最近|上周|上月|今年|去年|本周|本月|前\\d+天).*") && message.length() > 8) {
            log.debug("[L1] 检测到时间范围信号，非追问");
            return null;
        }
        
        if (lowerMsg.matches(".*(每个|所有|全部|整个|总共).*") && message.length() > 8) {
            log.debug("[L1] 检测到全局信号，非追问");
            return null;
        }
        
        // ✅ 延续信号词（confidence=0.95 -> 追问）
        if (lowerMsg.matches(".*(总结|概括|概述|分析一下|帮我看看|解读).*")) {
            log.debug("[L1] 检测到总结类信号，是追问");
            return "summarize_result";
        }
        
        if (lowerMsg.matches(".*(图表|画图|可视化|柱状图|折线图|饼图|生成图).*")) {
            log.debug("[L1] 检测到图表类信号，是追问");
            return "generate_chart";
        }
        
        if (lowerMsg.matches(".*(下载|导出|excel|csv).*")) {
            log.debug("[L1] 检测到下载类信号，是追问");
            return "download_excel";
        }
        
        // ✅ 指代模式（confidence=0.95 -> 追问）
        if (lowerMsg.matches("^(按|按照|根据|以).*")) {
            log.debug("[L1] 检测到介词开头，是追问");
            return determineFollowUpSkill(message);
        }
        
        if (lowerMsg.matches("^(只|仅|只要|只要看).*")) {
            log.debug("[L1] 检测到限制词开头，是追问");
            return determineFollowUpSkill(message);
        }
        
        if (lowerMsg.matches("^(对比|比较|和.*比).*")) {
            log.debug("[L1] 检测到对比词开头，是追问");
            return determineFollowUpSkill(message);
        }
        
        // ========== L2: 边界区域处理 ==========
        
        // 滑动窗口衰减：计算与历史消息的相关性
        double contextRelevance = calculateContextRelevance(message, sessionId);
        
        if (contextRelevance > 0.15) {
            log.info("[L2] 上下文相关性 {:.2f} > 0.15，是追问", contextRelevance);
            return determineFollowUpSkill(message);
        }
        
        // 极短消息且无主语，默认视为追问
        if (message.length() < 10 && !lowerMsg.matches(".*(我|你|他|她|它|我们|你们|他们).*")) {
            log.info("[L2] 极短消息且无主语，视为追问");
            return determineFollowUpSkill(message);
        }
        
        log.debug("[L2] 默认视为新查询");
        return null;  // 不是追问
    }
    
    /**
     * 根据消息内容确定追问技能类型
     */
    private String determineFollowUpSkill(String message) {
        String lowerMsg = message.toLowerCase().trim();
        
        if (lowerMsg.matches(".*(总结|概括|概述|分析|解读).*")) {
            return "summarize_result";
        }
        
        if (lowerMsg.matches(".*(图表|画图|可视化|柱状|折线|饼图).*")) {
            return "generate_chart";
        }
        
        if (lowerMsg.matches(".*(下载|导出|excel|csv).*")) {
            return "download_excel";
        }
        
        // 默认返回总结
        return "summarize_result";
    }
    
    /**
     * 计算上下文相关性（滑动窗口衰减）
     */
    private double calculateContextRelevance(String currentMessage, String sessionId) {
        if (historyService == null || sessionId == null) {
            return 0;
        }
        
        try {
            // 获取最近历史消息（ConversationHistoryService 内部已限制为 5 轮）
            List<Map<String, Object>> history = historyService.getHistory(sessionId);
            
            if (history == null || history.isEmpty()) {
                return 0;
            }
            
            double maxRelevance = 0;
            int historySize = history.size();
            
            // 遍历历史消息，计算加权相关性
            for (int i = 0; i < historySize; i++) {
                Map<String, Object> msg = history.get(i);
                String historicalMessage = (String) msg.get("userMessage");
                
                if (historicalMessage == null || historicalMessage.trim().isEmpty()) {
                    continue;
                }
                
                // 1. 时间衰减因子（越近的消息权重越高）
                double timeWeight = Math.exp(-0.3 * (historySize - 1 - i));
                
                // 2. 实体重叠度（Jaccard 相似度）
                double overlap = calculateEntityOverlap(currentMessage, historicalMessage);
                
                // 3. 综合得分
                double relevance = timeWeight * overlap;
                maxRelevance = Math.max(maxRelevance, relevance);
            }
            
            return maxRelevance;
            
        } catch (Exception e) {
            log.warn("[滑动窗口] 计算相关性失败", e);
            return 0;
        }
    }
    
    /**
     * 计算两个消息的实体重叠度（Jaccard 相似度）
     */
    private double calculateEntityOverlap(String msg1, String msg2) {
        if (msg1 == null || msg2 == null || msg1.trim().isEmpty() || msg2.trim().isEmpty()) {
            return 0;
        }
        
        // 简单分词：按字符分割（中文场景）
        Set<Character> words1 = new HashSet<>();
        Set<Character> words2 = new HashSet<>();
        
        for (char c : msg1.toCharArray()) {
            if (Character.isLetterOrDigit(c) || (c >= '\u4e00' && c <= '\u9fa5')) {
                words1.add(c);
            }
        }
        
        for (char c : msg2.toCharArray()) {
            if (Character.isLetterOrDigit(c) || (c >= '\u4e00' && c <= '\u9fa5')) {
                words2.add(c);
            }
        }
        
        if (words1.isEmpty() || words2.isEmpty()) {
            return 0;
        }
        
        // 计算交集
        Set<Character> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);
        
        // Jaccard 相似度 = 交集 / 并集
        Set<Character> union = new HashSet<>(words1);
        union.addAll(words2);
        
        return (double) intersection.size() / union.size();
    }
    
    /**
     * 执行追问 Tool（直接使用前端传递的数据）
     * TODO: 后期按钮追问也迁移到此逻辑，统一追问入口
     */
    private String executeFollowUpTool(String skillName, ChatRequest request, AuthService.UserInfo userInfo) {
        try {
            // ✅ 从前端请求中获取上次查询的数据
            List<Map<String, Object>> data = null;
            if (request.getContext() != null && request.getContext().containsKey("followUpData")) {
                data = (List<Map<String, Object>>) request.getContext().get("followUpData");
            }
            
            if (data == null || data.isEmpty()) {
                log.warn("[追问执行] 没有可操作的数据: skill={}", skillName);
                return "{\"success\":false,\"error\":\"没有可操作的数据，请先执行查询\"}";
            }
            
            log.info("[追问执行] 开始执行: skill={}, dataRows={}", skillName, data.size());
            
            switch (skillName) {
                case "summarize_result":
                    return executeSummarizeTool(data);
                    
                case "generate_chart":
                    return executeChartTool(data, request);
                    
                case "download_excel":
                    // 下载由前端处理，返回标识
                    return "{\"success\":true,\"type\":\"download_ready\",\"message\":\"准备下载Excel\"}";
                    
                default:
                    log.warn("[追问执行] 不支持的追问类型: {}", skillName);
                    return "{\"success\":false,\"error\":\"不支持的追问类型: " + skillName + "\"}";
            }
            
        } catch (Exception e) {
            log.error("[追问执行] 失败: skill={}", skillName, e);
            return "{\"success\":false,\"error\":\"追问执行失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 执行总结 Tool
     */
    private String executeSummarizeTool(List<Map<String, Object>> data) {
        try {
            // 通过 ToolRegistry 调用（保持与按钮追问一致）
            if (toolRegistry == null) {
                log.warn("[追问执行] ToolRegistry 未注入，降级到直接调用");
                return executeSummarizeDirectly(data);
            }
            
            Map<String, Object> args = new HashMap<>();
            args.put("data", data);  // Tool 参数名必须与 @Tool 注解一致
            
            String result = (String) toolRegistry.callToolAsString("summarize_result", args);
            log.info("[追问执行] summarize_result 完成");
            return result;
            
        } catch (Exception e) {
            log.error("[追问执行] summarize_result 失败", e);
            return "{\"success\":false,\"error\":\"总结失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 执行图表 Tool
     */
    private String executeChartTool(List<Map<String, Object>> data, ChatRequest request) {
        try {
            // 解析图表类型（如果有指定）
            String chartType = "bar";  // 默认柱状图
            if (request.getContext() != null && request.getContext().containsKey("chartType")) {
                chartType = (String) request.getContext().get("chartType");
            }
            
            // 通过 ToolRegistry 调用
            if (toolRegistry == null) {
                log.warn("[追问执行] ToolRegistry 未注入，降级到直接调用");
                return executeChartDirectly(data, chartType);
            }
            
            Map<String, Object> args = new HashMap<>();
            args.put("data", data);
            args.put("chartType", chartType);
            
            String result = (String) toolRegistry.callToolAsString("generate_chart", args);
            log.info("[追问执行] generate_chart 完成: chartType={}", chartType);
            return result;
            
        } catch (Exception e) {
            log.error("[追问执行] generate_chart 失败", e);
            return "{\"success\":false,\"error\":\"图表生成失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 降级方案：直接调用 AISummaryTool（当 ToolRegistry 不可用时）
     */
    private String executeSummarizeDirectly(List<Map<String, Object>> data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String dataJson = mapper.writeValueAsString(data);
            
            // 反射调用 AISummaryTool
            Class<?> toolClass = AISummaryTool.class;
            Object toolInstance = toolClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method method = toolClass.getMethod("summarize", String.class);
            
            String result = (String) method.invoke(toolInstance, dataJson);
            log.info("[追问执行-降级] summarize_result 完成");
            return result;
            
        } catch (Exception e) {
            log.error("[追问执行-降级] summarize_result 失败", e);
            return "{\"success\":false,\"error\":\"总结失败: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 降级方案：直接调用 ChartDetectionTool（当 ToolRegistry 不可用时）
     */
    private String executeChartDirectly(List<Map<String, Object>> data, String chartType) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String dataJson = mapper.writeValueAsString(data);
            
            // 反射调用 ChartDetectionTool
            Class<?> toolClass = ChartDetectionTool.class;
            Object toolInstance = toolClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method method = toolClass.getMethod("detectAndGenerateChart", String.class, List.class);
            
            String result = (String) method.invoke(toolInstance, "生成" + chartType + "图表", data);
            log.info("[追问执行-降级] generate_chart 完成");
            return result;
            
        } catch (Exception e) {
            log.error("[追问执行-降级] generate_chart 失败", e);
            return "{\"success\":false,\"error\":\"图表生成失败: " + e.getMessage() + "\"}";
        }
    }
    
    public static class ChatRequest {
        @jakarta.validation.constraints.NotBlank(message = "消息不能为空")
        private String message;
        private Long datasourceId;
        private String sessionId;
        private Map<String, Object> context;
        
        // ✅ 追问上下文标识（过渡期：按钮追问暂不传，自然语言追问会传）
        private String contextId;      // 上次查询的唯一ID
        private Boolean hasData;       // 是否有可追问的数据
        
        // Getters and Setters
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public Map<String, Object> getContext() { return context; }
        public void setContext(Map<String, Object> context) { this.context = context; }
        
        // ✅ 追问上下文 Getter/Setter
        public String getContextId() { return contextId; }
        public void setContextId(String contextId) { this.contextId = contextId; }
        public Boolean getHasData() { return hasData; }
        public void setHasData(Boolean hasData) { this.hasData = hasData; }
    }
}
