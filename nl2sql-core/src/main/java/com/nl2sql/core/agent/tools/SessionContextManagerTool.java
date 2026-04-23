package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.service.SessionContextManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话上下文管理工具 - 保存SQL和查询供后续使用
 */
@Slf4j
@Component
public class SessionContextManagerTool extends BaseToolAdapter {
    
    @Autowired
    private SessionContextManager sessionContextManager;
    
    @Override
    public String getName() { return "session_context_manager"; }
    
    @Override
    public String getDescription() { return "保存当前SQL和查询到会话上下文，供AI总结/图表生成使用。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("sessionId", Map.of("type", "string", "description", "会话ID"));
        props.put("sql", Map.of("type", "string", "description", "生成的SQL"));
        props.put("query", Map.of("type", "string", "description", "用户查询问题"));
        schema.put("properties", props);
        schema.put("required", new String[]{"sessionId"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要保存上下文供后续使用的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "不需要上下文的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("sessionId", context.getParameter("sessionId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String sessionId = context.getRequiredParameter("sessionId");
        String sql = context.getParameter("sql");
        String query = context.getParameter("query");
        
        if (sql != null && query != null) {
            sessionContextManager.saveCurrentContext(sql, query);
            log.debug("[SessionContextManager] 保存上下文: sessionId={}", sessionId);
            
            return Map.of("saved", true, "sessionId", sessionId);
        } else {
            log.warn("[SessionContextManager] SQL或Query为空，跳过保存");
            return Map.of("saved", false, "reason", "SQL或Query为空");
        }
    }
}
