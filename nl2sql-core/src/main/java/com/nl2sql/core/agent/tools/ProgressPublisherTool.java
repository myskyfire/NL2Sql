package com.nl2sql.core.agent.tools;

import com.nl2sql.common.event.StreamProgressEvent;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 进度事件发布工具 - 向前端发送流式进度事件
 */
@Slf4j
@Component
public class ProgressPublisherTool extends BaseToolAdapter {
    
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    
    @Override
    public String getName() { return "progress_publisher"; }
    
    @Override
    public String getDescription() { return "向前端发布流式进度事件，用于显示执行进度。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("sessionId", Map.of("type", "string", "description", "会话ID"));
        props.put("step", Map.of("type", "string", "description", "步骤名称"));
        props.put("message", Map.of("type", "string", "description", "进度消息"));
        schema.put("properties", props);
        schema.put("required", new String[]{"sessionId", "step", "message"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要前端流式反馈的场景"; }
    
    @Override
    public String getInapplicableScenarios() { return "不需要进度反馈的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("sessionId", context.getParameter("sessionId"))
            .required("step", context.getParameter("step"))
            .required("message", context.getParameter("message"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String sessionId = context.getRequiredParameter("sessionId");
        String step = context.getRequiredParameter("step");
        String message = context.getRequiredParameter("message");
        
        if (eventPublisher == null) {
            log.debug("[ProgressPublisher] 事件发布器未启用");
            return Map.of("published", false, "reason", "事件发布器未启用");
        }
        
        try {
            eventPublisher.publishEvent(new StreamProgressEvent(this, sessionId, step, message, null));
            log.debug("[ProgressPublisher] 发布事件: step={}, message={}", step, message);
            
            return Map.of("published", true, "step", step, "message", message);
        } catch (Exception e) {
            log.warn("[ProgressPublisher] 发布事件失败: {}", e.getMessage());
            return Map.of("published", false, "error", e.getMessage());
        }
    }
}
