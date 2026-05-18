package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private TracingService tracingService;

    private final Map<String, ToolDescriptor> tools = new ConcurrentHashMap<>();
    
    private boolean initialized = false;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!initialized && event.getApplicationContext().equals(applicationContext)) {
            initialized = true;
            scanAndRegisterTools();
        }
    }

    private void scanAndRegisterTools() {
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = bean.getClass();

            if (beanClass.getName().contains("$EnhancerBySpringCGLIB") || beanClass.getName().contains("$$_SpringCGLIB")) {
                beanClass = beanClass.getSuperclass();
            }

            String packageName = beanClass.getPackage().getName();
            if (!packageName.contains("com.nl2sql.core.agent.tools")) {
                continue;
            }

            for (Method method : beanClass.getDeclaredMethods()) {
                dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                if (toolAnnotation == null) continue;

                // ✅ 优先使用 @Tool 注解的 name 属性，如果为空则使用方法名
                String toolName = toolAnnotation.name();
                if (toolName == null || toolName.trim().isEmpty()) {
                    toolName = method.getName();
                }
                
                String[] descriptionParts = toolAnnotation.value();
                String description = descriptionParts != null && descriptionParts.length > 0
                    ? String.join(" ", descriptionParts) : toolName;

                ToolDescriptor descriptor = new ToolDescriptor();
                descriptor.setName(toolName);
                descriptor.setDescription(description);
                descriptor.setBean(bean);
                descriptor.setMethod(method);

                Class<?>[] paramTypes = method.getParameterTypes();
                java.lang.reflect.Parameter[] params = method.getParameters();
                List<ToolParamDescriptor> paramDescriptors = new ArrayList<>();
                for (int i = 0; i < params.length; i++) {
                    ToolParamDescriptor pd = new ToolParamDescriptor();
                    String paramName = params[i].getName();

                    if (paramName == null || paramName.startsWith("arg") || paramName.matches("var\\d+")) {
                        paramName = inferParamName(method.getName(), paramTypes[i], i);
                    }

                    pd.setName(paramName);
                    pd.setType(paramTypes[i]);
                    pd.setIndex(i);

                    dev.langchain4j.agent.tool.P pAnnotation = params[i].getAnnotation(dev.langchain4j.agent.tool.P.class);
                    if (pAnnotation != null) {
                        pd.setDescription(pAnnotation.value());
                        pd.setRequired(pAnnotation.required());
                    }
                    paramDescriptors.add(pd);
                }
                descriptor.setParameters(paramDescriptors);

                tools.put(toolName, descriptor);
                log.info("[ToolRegistry] 注册 Tool: {} - {}", toolName, description);
            }
        }

        log.info("[ToolRegistry] 共注册 {} 个 Tool", tools.size());
    }

    public Object callTool(String toolName, Map<String, Object> arguments) {
        ToolDescriptor descriptor = tools.get(toolName);
        if (descriptor == null) {
            throw new IllegalArgumentException("未注册的 Tool: " + toolName);
        }

        TraceSpan run = startToolTrace(toolName, arguments);
        try {
            Object[] args = buildArguments(descriptor, arguments);
            Object result = descriptor.getMethod().invoke(descriptor.getBean(), args);
            endToolTrace(run, result, null);
            return result;
        } catch (Exception e) {
            endToolTrace(run, null, e.getMessage());
            log.error("[ToolRegistry] 调用 Tool 失败: {}", toolName, e);
            throw new RuntimeException("调用 Tool 失败: " + toolName, e);
        }
    }

    private TraceSpan startToolTrace(String toolName, Map<String, Object> arguments) {
        if (tracingService == null || !tracingService.isEnabled()) return null;
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("toolName", toolName);
            if (arguments != null) {
                arguments.forEach((k, v) -> {
                    if (v instanceof String) {
                        String s = (String) v;
                        inputs.put(k, s.length() > 500 ? s.substring(0, 500) + "..." : s);
                    } else {
                        inputs.put(k, v);
                    }
                });
            }
            return tracingService.traceTool(toolName, inputs, TracingContext.currentRunId());
        } catch (Exception e) {
            log.debug("[LangSmith] startToolTrace 失败: {}", e.getMessage());
            return null;
        }
    }

    private void endToolTrace(TraceSpan run, Object result, String error) {
        if (tracingService == null || run == null) return;
        try {
            Map<String, Object> outputs = new LinkedHashMap<>();
            if (result != null) {
                String resultStr = result instanceof String ? (String) result : String.valueOf(result);
                outputs.put("result", resultStr.length() > 1000 ? resultStr.substring(0, 1000) + "..." : resultStr);
            }
            tracingService.endRun(run, outputs, error);
        } catch (Exception e) {
            log.debug("[LangSmith] endToolTrace 失败: {}", e.getMessage());
        }
    }

    public String callToolAsString(String toolName, Map<String, Object> arguments) {
        Object result = callTool(toolName, arguments);
        if (result == null) return null;
        if (result instanceof String) return (String) result;
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    private Object[] buildArguments(ToolDescriptor descriptor, Map<String, Object> arguments) {
        if (arguments == null) arguments = Collections.emptyMap();

        List<ToolParamDescriptor> paramDescriptors = descriptor.getParameters();
        Object[] args = new Object[paramDescriptors.size()];

        for (int i = 0; i < paramDescriptors.size(); i++) {
            ToolParamDescriptor pd = paramDescriptors.get(i);
            Object value = arguments.get(pd.getName());

            if (value == null) {
                for (String alias : buildAliases(pd.getName())) {
                    value = arguments.get(alias);
                    if (value != null) break;
                }
            }

            if (value == null && pd.getDescription() != null) {
                for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                    if (pd.getDescription().contains(entry.getKey()) || entry.getKey().contains(pd.getDescription())) {
                        value = entry.getValue();
                        break;
                    }
                }
            }

            if (value == null) {
                value = matchByTypeAndPosition(arguments, pd, i, paramDescriptors);
            }

            args[i] = convertValue(value, pd.getType());
        }

        return args;
    }

    private Object matchByTypeAndPosition(Map<String, Object> arguments, ToolParamDescriptor pd,
                                           int index, List<ToolParamDescriptor> allParams) {
        List<Map.Entry<String, Object>> unmatched = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();

        for (ToolParamDescriptor other : allParams) {
            if (arguments.containsKey(other.getName())) {
                usedKeys.add(other.getName());
            }
        }

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!usedKeys.contains(entry.getKey())) {
                unmatched.add(entry);
            }
        }

        if (index < unmatched.size()) {
            return unmatched.get(index).getValue();
        }

        return null;
    }

    private List<String> buildAliases(String paramName) {
        List<String> aliases = new ArrayList<>();
        if (paramName.contains("_")) {
            aliases.add(paramName.replace("_", ""));
        }
        String camelCase = toCamelCase(paramName);
        if (!camelCase.equals(paramName)) {
            aliases.add(camelCase);
        }
        String snakeCase = toSnakeCase(paramName);
        if (!snakeCase.equals(paramName)) {
            aliases.add(snakeCase);
        }
        return aliases;
    }

    private String toCamelCase(String name) {
        if (!name.contains("_")) return name;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                sb.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            }
        }
        return sb.toString();
    }

    private String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isAssignableFrom(value.getClass())) return value;

        if (targetType == Long.class || targetType == long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(String.valueOf(value));
        }
        if (targetType == Integer.class || targetType == int.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(String.valueOf(value));
        }
        if (targetType == Double.class || targetType == double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(String.valueOf(value));
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (value instanceof Boolean) return value;
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }

        return value;
    }

    private String inferParamName(String methodName, Class<?> paramType, int index) {
        if (paramType == Long.class || paramType == long.class) {
            if (methodName.toLowerCase().contains("sql") && index == 0) return "datasourceId";
            if (index == 0) return "datasourceId";
            if (index == 1) return "userId";
            if (index == 2) return "datasourceId";
        }
        if (paramType == String.class) {
            if (methodName.toLowerCase().contains("sql") && index == 0) return "sql";
            if (methodName.toLowerCase().contains("schema") && index == 0) return "query";
            if (methodName.toLowerCase().contains("generate") && index == 0) return "question";
            if (index == 0) return "question";
            if (index == 1) return "sql";
            if (index == 2) return "dataJson";
        }
        return "param" + index;
    }

    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }

    public ToolDescriptor getToolDescriptor(String toolName) {
        return tools.get(toolName);
    }

    public Map<String, ToolDescriptor> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    @lombok.Data
    public static class ToolDescriptor {
        private String name;
        private String description;
        private Object bean;
        private Method method;
        private List<ToolParamDescriptor> parameters;
    }

    @lombok.Data
    public static class ToolParamDescriptor {
        private String name;
        private Class<?> type;
        private int index;
        private String description;
        private boolean required = true;
    }
}
