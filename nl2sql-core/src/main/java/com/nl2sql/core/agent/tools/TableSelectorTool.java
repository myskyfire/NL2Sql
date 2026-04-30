package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.llm.IndustryConceptDictionary;
import com.nl2sql.core.llm.ModelRouterService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表选择工具 - 根据用户问题和可用表结构，智能选择需要的表
 * ✅ 从NL2SQLTool拆分出的原子能力
 */
@Slf4j
@Component
public class TableSelectorTool extends BaseToolAdapter {
    
    @Autowired
    private ModelRouterService modelRouter;
    
    @Autowired
    private IndustryConceptDictionary industryConceptDictionary;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getName() { return "table_selector"; }
    
    @Override
    public String getDescription() { return "根据用户自然语言问题和当前可用的表结构信息，智能选择需要用到的数据库表。支持表偏好检测、行业概念注入、中间表自动补充等功能。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户自然语言问题"));
        props.put("schemaInfo", Map.of("type", "string", "description", "当前可用表的结构信息"));
        props.put("relationshipInfo", Map.of("type", "string", "description", "表之间的关联关系"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        schema.put("properties", props);
        schema.put("required", Arrays.asList("query", "schemaInfo", "datasourceId"));
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要从多个候选表中选择真正需要的表时；需要检测缺失表时"; }
    
    @Override
    public String getInapplicableScenarios() { return "已知确切表名不需要选择的场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .required("schemaInfo", context.getParameter("schemaInfo"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        String schemaInfo = context.getRequiredParameter("schemaInfo");
        String relationshipInfo = context.getParameter("relationshipInfo");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        
        if (relationshipInfo == null) relationshipInfo = "";
        
        // 构建Prompt
        String prompt = buildTableCheckPrompt(query, schemaInfo, relationshipInfo, datasourceId);
        
        // 调用LLM
        String llmResponse = modelRouter.smartGenerateSQL(prompt, query);
        
        log.info("[TableSelector] LLM原始响应: {}", llmResponse);
        
        // 解析JSON响应
        Map<String, Object> result = parseLLMResponse(llmResponse);
        
        return result;
    }
    
    /**
     * 构建表选择Prompt
     */
    private String buildTableCheckPrompt(String query, String schemaInfo, String relationshipInfo, Long datasourceId) {
        String relationshipHint = relationshipInfo.isEmpty() ? "" : 
            "\n\n表之间的关联关系（重要）：\n" + relationshipInfo + 
            "\n注意：如果需要关联两张表，必须包含中间的所有表。例如：A.ref_id -> B.id -> C.ref_id，需要同时选中 A、B、C 三张表。";
        
        // 检测用户表偏好
        String tablePreferenceHint = "";
        if (query.contains("[优先使用表:")) {
            int start = query.indexOf("[优先使用表:") + "[优先使用表:".length();
            int end = query.indexOf("]", start);
            if (end > start) {
                String preferredTable = query.substring(start, end).trim();
                tablePreferenceHint = String.format(
                    "\n\n⚠️ **用户明确要求**：必须使用包含'%s'关键词的表\n" +
                    "- **强制规则**：在可用表列表中，查找表名或表注释中包含'%s'的表\n" +
                    "- 如果找到匹配的表，它**必须**出现在 selected_tables 中\n" +
                    "- 即使其他表也有相关字段，也必须优先使用用户指定的表\n" +
                    "- 只有在没有任何表匹配'%s'时，才可以根据语义选择最相关的表\n" +
                    "- 示例：用户说'使用用户表'，应选择表名为 users 或注释包含'用户'的表",
                    preferredTable, preferredTable, preferredTable
                );
                log.info("[TableSelector] ⚠️ 检测到用户表偏好（强制）: {}", preferredTable);
            }
        }
        
        // 注入行业概念
        String metricDescription = industryConceptDictionary.generateMetricDescription(datasourceId);
        String dimensionDescription = industryConceptDictionary.generateDimensionDescription(datasourceId);
        String tableRoleDescription = industryConceptDictionary.generateTableRoleDescription(datasourceId);
        
        return String.format(
            "你是数据库专家。根据用户问题和表结构，选择需要的表。\n\n" +
            "用户问题：%s\n" +
            "%s" +
            "可用表：\n%s" +
            "%s\n\n" +
            "要求：\n" +
            "1. 只选真正需要的表\n" +
            "2. 关联需包含所有中间表（A->B->C则选A,B,C）\n" +
            "3. %s\n" +
            "4. %s\n" +
            "5. 验证：SELECT/WHERE/GROUP BY字段必须在已选表中，否则返回missing_tables\n" +
            "6. 返回JSON：{\"selected_tables\": [\"表1\"]} 或 {\"missing_tables\": [\"表A\"], \"reason\": \"原因\"}\n" +
            "7. 只返回JSON",
            query, tablePreferenceHint, schemaInfo, relationshipHint,
            metricDescription, tableRoleDescription
        );
    }
    
    /**
     * 解析LLM响应
     */
    private Map<String, Object> parseLLMResponse(String llmResponse) {
        Map<String, Object> result = new HashMap<>();
        
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "LLM响应为空");
            return result;
        }
        
        try {
            // 清洗Markdown
            String cleanJson = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(cleanJson);
            
            if (jsonNode.has("selected_tables")) {
                List<String> selectedTables = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode node : jsonNode.get("selected_tables")) {
                    selectedTables.add(node.asText().toLowerCase());
                }
                
                result.put("success", true);
                result.put("action", "selected");
                result.put("tables", selectedTables);
                result.put("message", "已选择 " + selectedTables.size() + " 张表");
                
            } else if (jsonNode.has("missing_tables")) {
                List<String> missingTables = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode node : jsonNode.get("missing_tables")) {
                    missingTables.add(node.asText().toLowerCase());
                }
                
                String reason = jsonNode.has("reason") ? jsonNode.get("reason").asText() : "缺少必要的表";
                
                result.put("success", true);
                result.put("action", "missing");
                result.put("tables", missingTables);
                result.put("reason", reason);
                result.put("message", "缺少表: " + String.join(", ", missingTables));
                
            } else {
                result.put("success", false);
                result.put("error", "LLM响应格式不正确，未找到selected_tables或missing_tables");
            }
            
        } catch (Exception e) {
            log.warn("[TableSelector] JSON解析失败，尝试传统方式", e);
            
            // 降级：传统方式解析
            List<String> tables = parseMissingTables(llmResponse);
            if (!tables.isEmpty()) {
                result.put("success", true);
                result.put("action", "missing");
                result.put("tables", tables);
                result.put("reason", "从响应中提取的表");
            } else {
                result.put("success", false);
                result.put("error", "无法解析LLM响应");
            }
        }
        
        return result;
    }
    
    /**
     * 传统方式解析缺失表
     */
    private List<String> parseMissingTables(String llmResponse) {
        Set<String> tables = new HashSet<>();
        Pattern pattern = Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9_]*)\\b");
        
        for (String line : llmResponse.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.contains("表已足够")) continue;
            
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                String word = matcher.group(1).toLowerCase();
                if (!isCommonWord(word)) {
                    tables.add(word);
                }
            }
        }
        
        return new ArrayList<>(tables);
    }
    
    /**
     * 判断是否为常见词
     */
    private boolean isCommonWord(String word) {
        return Arrays.asList(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
            "table", "tables", "column", "columns", "field", "fields",
            "missing", "required", "additional"
        ).contains(word.toLowerCase());
    }
    
    @Tool("根据用户问题和可用表结构，智能选择需要的数据库表。返回选择的表列表或缺失的表列表")
    public String selectTables(String query, String schemaInfo, String relationshipInfo, Long datasourceId) {
        try {
            ToolContext context = ToolContext.builder()
                .parameters(new HashMap<String, Object>() {{
                    put("query", query);
                    put("schemaInfo", schemaInfo);
                    put("relationshipInfo", relationshipInfo != null ? relationshipInfo : "");
                    put("datasourceId", datasourceId);
                }})
                .build();
            
            com.nl2sql.core.agent.tool.ToolResult result = execute(context);
            
            if (result.isSuccess()) {
                Map<String, Object> data = (Map<String, Object>) result.getData();
                // ✅ 构建统一响应
                return ToolResponseBuilder.success("data")
                    .withData(data)
                    .addMetadata("toolName", "table_selector")
                    .build();
            } else {
                return ToolResponseBuilder.error("TABLE_SELECTION_ERROR", result.getErrorMessage())
                    .addMetadata("toolName", "table_selector")
                    .build();
            }
        } catch (Exception e) {
            log.error("[TableSelector] 执行失败", e);
            return ToolResponseBuilder.error("EXECUTION_ERROR", e.getMessage())
                .addMetadata("toolName", "table_selector")
                .build();
        }
    }
}
