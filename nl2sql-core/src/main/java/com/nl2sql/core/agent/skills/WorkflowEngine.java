package com.nl2sql.core.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式 Workflow 执行引擎
 * 
 * 解析 SKILL.md 中的 workflow YAML 配置，自动执行 Tool 调用链
 * 支持：
 * - 顺序执行
 * - 条件分支（condition）
 * - 变量传递（{{variable}}）
 * - 错误处理（on_failure）
 */
@Slf4j
@Component
public class WorkflowEngine {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 执行声明式 Workflow
     * 
     * @param skillPath Skill路径
     * @param context 执行上下文
     * @return 执行结果
     */
    public Object executeWorkflow(String skillPath, SkillContext context) {
        try {
            log.info("[WorkflowEngine] 开始执行 Workflow: {}", skillPath);
            
            // 1. 解析 SKILL.md 中的 workflow YAML
            Map<String, Object> workflowConfig = parseWorkflowYaml(skillPath);
            if (workflowConfig == null || !workflowConfig.containsKey("steps")) {
                throw new IllegalStateException("SKILL.md 中未定义有效的 workflow.steps");
            }
            
            List<Map<String, Object>> steps = (List<Map<String, Object>>) workflowConfig.get("steps");
            
            // 2. 执行每个步骤
            Map<String, Object> variables = new HashMap<>();
            variables.putAll(context.getParameters());
            
            for (Map<String, Object> step : steps) {
                String stepId = (String) step.getOrDefault("id", step.get("name"));
                log.info("[WorkflowEngine] 执行步骤: {}", stepId);
                
                // 检查条件
                if (step.containsKey("condition")) {
                    String condition = (String) step.get("condition");
                    if (!evaluateCondition(condition, variables)) {
                        log.info("[WorkflowEngine] 步骤 {} 条件不满足，跳过", stepId);
                        continue;
                    }
                }
                
                // 获取 action 类型（兼容旧语法）
                String action = (String) step.get("action");
                if (action == null) {
                    // 兼容旧语法：如果有tool字段，默认为call_tool
                    if (step.containsKey("tool")) {
                        action = "call_tool";
                    } else if (step.containsKey("skill")) {
                        action = "call_skill";
                    } else if (step.containsKey("output")) {
                        action = "respond";
                    }
                }
                
                // 执行步骤
                if ("call_tool".equals(action) && step.containsKey("tool")) {
                    // Tool 调用
                    String toolName = (String) step.get("tool");
                    Map<String, Object> params = resolveParams((Map<String, Object>) step.get("input"), variables);
                    
                    String resultJson = context.callTool(toolName, params);
                    Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
                    
                    // 保存到 output_var 或 stepId
                    String outputVar = (String) step.getOrDefault("output_var", stepId);
                    variables.put(outputVar, result);
                    log.info("[WorkflowEngine] 步骤 {} 完成: tool={}, output_var={}", stepId, toolName, outputVar);
                    
                } else if ("call_skill".equals(action) && step.containsKey("skill")) {
                    // Skill 调用
                    String skillName = (String) step.get("skill");
                    Map<String, Object> params = resolveParams((Map<String, Object>) step.get("input"), variables);
                    
                    Object result = context.callSkill(skillName, params);
                    
                    // 保存到 output_var 或 stepId
                    String outputVar = (String) step.getOrDefault("output_var", stepId);
                    variables.put(outputVar, result);
                    log.info("[WorkflowEngine] 步骤 {} 完成: skill={}, output_var={}", stepId, skillName, outputVar);
                    
                } else if ("respond".equals(action) || step.containsKey("output")) {
                    // 输出步骤
                    Map<String, Object> outputTemplate = (Map<String, Object>) step.get("output");
                    Map<String, Object> output = resolveOutput(outputTemplate, variables);
                    
                    log.info("[WorkflowEngine] Workflow 执行完成");
                    return output;
                } else {
                    log.warn("[WorkflowEngine] 未知 action 类型: {}, 跳过", action);
                }
            }
            
            // 如果没有output步骤，返回所有变量
            return variables;
            
        } catch (Exception e) {
            log.error("[WorkflowEngine] Workflow 执行失败: {}", skillPath, e);
            throw new RuntimeException("Workflow 执行失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析 SKILL.md 中的 workflow YAML
     */
    private Map<String, Object> parseWorkflowYaml(String skillPath) {
        try {
            String skillMdPath = skillPath.endsWith("/") ? skillPath + "SKILL.md" : skillPath + "/SKILL.md";
            var resource = new org.springframework.core.io.ClassPathResource(
                skillMdPath, 
                getClass().getClassLoader()
            );
            
            if (!resource.exists()) {
                return null;
            }
            
            // 简单解析 YAML（只支持基本的键值对和列表）
            StringBuilder yamlContent = new StringBuilder();
            boolean inWorkflow = false;
            int indentLevel = -1;
            
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equals("---")) {
                        continue;
                    }
                    
                    if (line.startsWith("workflow:")) {
                        inWorkflow = true;
                        indentLevel = 0;
                        continue;
                    }
                    
                    if (inWorkflow) {
                        // 检测是否离开 workflow 块
                        if (!line.trim().isEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                            break;
                        }
                        yamlContent.append(line).append("\n");
                    }
                }
            }
            
            // 使用简单的 YAML 解析（实际项目中应该用 SnakeYAML）
            return parseSimpleYaml(yamlContent.toString());
            
        } catch (Exception e) {
            log.error("[WorkflowEngine] 解析 workflow YAML 失败: {}", skillPath, e);
            return null;
        }
    }
    
    /**
     * 简单 YAML 解析器（支持 OpenClaw 风格语法）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSimpleYaml(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // 提取 steps 列表
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> currentStep = null;
        Map<String, Object> currentInput = null;
        Map<String, Object> currentOutput = null;
        String currentKey = null;
        boolean inParams = false; // 兼容旧语法
        
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            
            // 检测缩进级别
            int indent = getIndentLevel(line);
            String trimmed = line.trim();
            
            if (trimmed.equals("steps:")) {
                continue;
            }
            
            // 新的步骤项 (- id: xxx 或 - name: xxx)
            if (trimmed.startsWith("- id:") || trimmed.startsWith("- name:")) {
                if (currentStep != null) {
                    steps.add(currentStep);
                }
                currentStep = new LinkedHashMap<>();
                currentInput = null;
                currentOutput = null;
                currentKey = null;
                inParams = false;
                
                // 提取 ID 或 name
                String idOrName;
                if (trimmed.startsWith("- id:")) {
                    idOrName = trimmed.substring("- id:".length()).trim();
                    currentStep.put("id", idOrName);
                } else {
                    idOrName = trimmed.substring("- name:".length()).trim();
                    currentStep.put("name", idOrName);
                }
                // 如果没有id，用name作为id
                if (!currentStep.containsKey("id")) {
                    currentStep.put("id", idOrName);
                }
                continue;
            }
            
            if (currentStep == null) {
                continue;
            }
            
            // 步骤属性（indent=4）
            if (indent == 4) {
                if (trimmed.startsWith("id:") || trimmed.startsWith("name:")) {
                    // 已经在上面处理了
                } else if (trimmed.startsWith("action:")) {
                    String action = trimmed.substring("action:".length()).trim();
                    currentStep.put("action", action);
                    currentKey = null;
                    inParams = false;
                } else if (trimmed.startsWith("tool:")) {
                    // 兼容旧语法：tool -> action: call_tool
                    String tool = trimmed.substring("tool:".length()).trim();
                    currentStep.put("action", "call_tool");
                    currentStep.put("tool", tool);
                    currentKey = null;
                    inParams = false;
                } else if (trimmed.startsWith("skill:")) {
                    String skill = trimmed.substring("skill:".length()).trim();
                    currentStep.put("action", "call_skill");
                    currentStep.put("skill", skill);
                    currentKey = null;
                    inParams = false;
                } else if (trimmed.startsWith("condition:")) {
                    String condition = trimmed.substring("condition:".length()).trim();
                    currentStep.put("condition", condition);
                    currentKey = null;
                    inParams = false;
                } else if (trimmed.startsWith("output_var:")) {
                    String outputVar = trimmed.substring("output_var:".length()).trim();
                    currentStep.put("output_var", outputVar);
                    currentKey = null;
                    inParams = false;
                } else if (trimmed.equals("input:")) {
                    currentInput = new LinkedHashMap<>();
                    currentStep.put("input", currentInput);
                    currentKey = "input";
                    inParams = false;
                } else if (trimmed.equals("params:")) {
                    // 兼容旧语法：params -> input
                    currentInput = new LinkedHashMap<>();
                    currentStep.put("input", currentInput);
                    currentKey = "input";
                    inParams = true;
                } else if (trimmed.equals("output:")) {
                    currentOutput = new LinkedHashMap<>();
                    currentStep.put("output", currentOutput);
                    currentKey = "output";
                    inParams = false;
                }
            } else if (indent == 6 && currentKey != null) {
                // input/params/output 的子项
                if (trimmed.contains(":")) {
                    String[] parts = trimmed.split(":", 2);
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    
                    Map<String, Object> target = currentInput != null ? currentInput : currentOutput;
                    if (target != null) {
                        target.put(key, value);
                    }
                }
            }
        }
        
        // 添加最后一个步骤
        if (currentStep != null) {
            steps.add(currentStep);
        }
        
        result.put("steps", steps);
        return result;
    }
    
    /**
     * 获取行的缩进级别
     */
    private int getIndentLevel(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }
    
    /**
     * 评估条件表达式
     */
    private boolean evaluateCondition(String condition, Map<String, Object> variables) {
        try {
            // 简单实现：替换变量后执行 Groovy 表达式
            String resolved = resolveTemplate(condition, variables);
            
            // 使用 Groovy 引擎评估表达式
            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell();
            Object result = shell.evaluate(resolved);
            
            return result instanceof Boolean ? (Boolean) result : false;
            
        } catch (Exception e) {
            log.warn("[WorkflowEngine] 条件评估失败: {}, 默认false", condition, e);
            return false;
        }
    }
    
    /**
     * 解析参数模板
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveParams(Map<String, Object> params, Map<String, Object> variables) {
        if (params == null) {
            return new HashMap<>();
        }
        
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof String) {
                resolved.put(key, resolveTemplate((String) value, variables));
            } else {
                resolved.put(key, value);
            }
        }
        
        return resolved;
    }
    
    /**
     * 解析输出模板
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveOutput(Map<String, Object> template, Map<String, Object> variables) {
        Map<String, Object> output = new LinkedHashMap<>();
        
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof String) {
                String strValue = (String) value;
                if (strValue.startsWith("{{") && strValue.endsWith("}}")) {
                    // 引用变量
                    String varName = strValue.substring(2, strValue.length() - 2).trim();
                    output.put(key, getNestedVariable(varName, variables));
                } else {
                    // 字符串模板
                    output.put(key, resolveTemplate(strValue, variables));
                }
            } else if (value instanceof Map) {
                // 嵌套对象
                output.put(key, resolveOutput((Map<String, Object>) value, variables));
            } else {
                output.put(key, value);
            }
        }
        
        return output;
    }
    
    /**
     * 解析字符串模板 {{variable}}
     */
    private String resolveTemplate(String template, Map<String, Object> variables) {
        Pattern pattern = Pattern.compile("\\{\\{(.*?)\\}\\}");
        Matcher matcher = pattern.matcher(template);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object value = getNestedVariable(varName, variables);
            matcher.appendReplacement(result, value != null ? value.toString() : "");
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * 获取嵌套变量（支持 validate.valid 这样的点号访问）
     */
    @SuppressWarnings("unchecked")
    private Object getNestedVariable(String varName, Map<String, Object> variables) {
        String[] parts = varName.split("\\.");
        Object current = variables;
        
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        
        return current;
    }
}
