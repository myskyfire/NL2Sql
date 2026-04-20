package com.nl2sql.core.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 执行上下文
 * 
 * 为 Groovy 脚本提供访问 Spring Bean、调用 Tool 和其他 Skill 的能力
 */
@Slf4j
@Data
public class SkillContext {
    
    /**
     * 输入参数
     */
    private Map<String, Object> parameters = new HashMap<>();
    
    /**
     * Spring ApplicationContext（用于获取 Bean）
     */
    private ApplicationContext applicationContext;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 数据源 ID
     */
    private Long datasourceId;
    
    /**
     * 构造函数
     */
    public SkillContext() {
    }
    
    /**
     * 获取参数
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }
    
    /**
     * 设置参数
     */
    public void setParameter(String key, Object value) {
        this.parameters.put(key, value);
    }
    
    /**
     * 获取 Spring Bean
     */
    public <T> T getBean(Class<T> clazz) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 未初始化");
        }
        return applicationContext.getBean(clazz);
    }
    
    /**
     * 获取 Spring Bean by name
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 未初始化");
        }
        return (T) applicationContext.getBean(name);
    }
    
    /**
     * 调用 Tool（原子能力）
     * 
     * @param toolName Tool 名称
     * @param params 参数 Map
     * @return Tool 执行结果（JSON 字符串）
     */
    public String callTool(String toolName, Map<String, Object> params) {
        try {
            log.info("[SkillContext] 调用 Tool: {}, 参数: {}", toolName, params);
            
            // 从 ApplicationContext 获取 Tool Bean
            Object toolBean = applicationContext.getBean(toolName + "Tool");
            
            // 查找 execute 方法
            java.lang.reflect.Method method = null;
            for (java.lang.reflect.Method m : toolBean.getClass().getMethods()) {
                if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    method = m;
                    break;
                }
            }
            
            if (method == null) {
                throw new IllegalStateException("Tool 中未找到 @Tool 注解的方法: " + toolName);
            }
            
            // 准备参数
            Object[] args = new Object[method.getParameterCount()];
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName();
                args[i] = params.get(paramName);
            }
            
            // 执行 Tool
            Object result = method.invoke(toolBean, args);
            
            log.info("[SkillContext] Tool 调用成功: {}", toolName);
            return (String) result;
            
        } catch (Exception e) {
            log.error("[SkillContext] Tool 调用失败: {}", toolName, e);
            
            ObjectMapper mapper = new ObjectMapper();
            try {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("error", e.getMessage());
                return mapper.writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"success\":false,\"error\":\"序列化失败\"}";
            }
        }
    }
    
    /**
     * 调用其他 Skill（流程编排）
     * 
     * @param skillName Skill 名称
     * @param params 参数 Map
     * @return Skill 执行结果
     */
    public Object callSkill(String skillName, Map<String, Object> params) {
        try {
            log.info("[SkillContext] 调用 Skill: {}, 参数: {}", skillName, params);
            
            // 获取 GroovySkillExecutor
            com.nl2sql.core.agent.skills.GroovySkillExecutor executor = 
                applicationContext.getBean(com.nl2sql.core.agent.skills.GroovySkillExecutor.class);
            
            // 构建 Skill 路径
            String skillPath = "skills/" + skillName.replace("_", "-") + "/";
            
            // 创建新的上下文
            SkillContext newContext = new SkillContext();
            newContext.setApplicationContext(applicationContext);
            newContext.setSessionId(sessionId);
            newContext.setUserId(userId);
            newContext.setDatasourceId(datasourceId);
            newContext.setParameters(params);
            
            // 执行 Skill
            Object result = executor.executeSkill(skillPath, newContext);
            
            log.info("[SkillContext] Skill 调用成功: {}", skillName);
            return result;
            
        } catch (Exception e) {
            log.error("[SkillContext] Skill 调用失败: {}", skillName, e);
            throw new RuntimeException("调用 Skill 失败: " + skillName, e);
        }
    }
}
