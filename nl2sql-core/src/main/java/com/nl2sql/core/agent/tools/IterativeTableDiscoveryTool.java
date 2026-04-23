package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.common.util.MarkdownUtils;
import com.nl2sql.core.llm.MultiModelService;
import com.nl2sql.core.mapper.MetadataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 迭代式表发现工具
 * 
 * 通过最多3轮LLM交互，智能发现用户查询所需的数据库表：
 * 1. 第一轮：基于初始表集合，让LLM判断是否缺少必要的表
 * 2. 第二轮：如果LLM指出缺失表，尝试查找并补充
 * 3. 第三轮：再次验证表集合是否完整
 * 
 * 支持JSON响应解析和传统文本降级解析
 */
@Slf4j
@Component
public class IterativeTableDiscoveryTool extends BaseToolAdapter {
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Autowired
    private MultiModelService multiModelService;
    
    @Override
    public String getName() {
        return "iterative_table_discovery";
    }
    
    @Override
    public String getDescription() {
        return "通过多轮LLM交互迭代发现用户查询所需的数据库表。" +
               "支持表选择回溯机制和缺失表查找，最多进行3轮迭代优化。" +
               "返回最终确定的表列表，或需要澄清的信号。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "用户原始查询问题"));
        props.put("initialTables", Map.of("type", "array", "items", Map.of("type", "string"), "description", "初始候选表列表"));
        props.put("datasourceId", Map.of("type", "integer", "description", "数据源ID"));
        props.put("schemaInfo", Map.of("type", "string", "description", "当前表的Schema信息"));
        props.put("relationshipInfo", Map.of("type", "string", "description", "表关联关系信息"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query", "initialTables", "datasourceId"});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() {
        return "复杂查询需要精确表选择、初始表集合可能不完整时";
    }
    
    @Override
    public String getInapplicableScenarios() {
        return "简单查询、单表查询或已有明确表列表时";
    }
    
    @Override
    protected void validateParameters(ToolContext context) {
        parameterValidator
            .required("query", context.getParameter("query"))
            .required("initialTables", context.getParameter("initialTables"))
            .required("datasourceId", context.getParameter("datasourceId"))
            .throwIfHasErrors();
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        String query = context.getRequiredParameter("query");
        List<String> initialTables = (List<String>) context.getParameter("initialTables");
        Long datasourceId = context.getRequiredParameter("datasourceId");
        String schemaInfo = context.getParameter("schemaInfo");
        String relationshipInfo = context.getParameter("relationshipInfo");
        
        if (schemaInfo == null) schemaInfo = "";
        if (relationshipInfo == null) relationshipInfo = "";
        
        log.info("[IterativeTableDiscovery] 开始迭代式表发现: query={}, initialTables={}", query, initialTables);
        
        // 执行迭代式表发现
        DiscoveryResult result = iterativeDiscovery(query, initialTables, datasourceId, schemaInfo, relationshipInfo);
        
        log.info("[IterativeTableDiscovery] 发现完成: finalTables={}, needsClarification={}", 
                 result.getFinalTables(), result.isNeedsClarification());
        
        // 构建返回结果
        Map<String, Object> response = new HashMap<>();
        response.put("finalTables", new ArrayList<>(result.getFinalTables()));
        response.put("tableCount", result.getFinalTables().size());
        response.put("iterations", result.getIterations());
        response.put("needsClarification", result.isNeedsClarification());
        response.put("clarificationMessage", result.getClarificationMessage());
        response.put("success", !result.isNeedsClarification());
        
        return response;
    }
    
    /**
     * 迭代式表发现核心逻辑（最多3轮）
     */
    private DiscoveryResult iterativeDiscovery(String query, List<String> initialTables, 
                                               Long datasourceId, String schemaInfo, 
                                               String relationshipInfo) {
        Set<String> allTables = new HashSet<>(initialTables);
        boolean needsClarification = false;
        String clarificationMessage = "";
        int iterations = 0;
        
        for (int iteration = 0; iteration < 3; iteration++) {
            iterations = iteration + 1;
            log.info("[IterativeTableDiscovery] 第{}轮迭代，当前表数量: {}", iteration + 1, allTables.size());
            
            // 构建检查Prompt
            String checkPrompt = buildTableCheckPrompt(query, schemaInfo, relationshipInfo, datasourceId);
            
            // 调用LLM判断表是否完整
            String llmResponse = multiModelService.generateAnswer(checkPrompt);
            log.info("[IterativeTableDiscovery] LLM响应: {}", llmResponse);
            
            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                log.warn("[IterativeTableDiscovery] LLM响应为空，停止迭代");
                break;
            }
            
            // 尝试解析JSON响应
            ParseResult parseResult = parseLLMResponse(llmResponse, datasourceId, allTables);
            
            if (parseResult.isSuccess()) {
                // 情况1: LLM选择了需要的表
                if (parseResult.getSelectedTables() != null && !parseResult.getSelectedTables().isEmpty()) {
                    log.info("[IterativeTableDiscovery] LLM精简表: {} -> {}", 
                             allTables.size(), parseResult.getSelectedTables().size());
                    allTables = parseResult.getSelectedTables();
                    break; // 已得到精简结果，退出迭代
                }
                
                // 情况2: LLM指出缺少的表
                if (parseResult.getMissingTables() != null && !parseResult.getMissingTables().isEmpty()) {
                    log.info("[IterativeTableDiscovery] LLM指出缺失表: {}", parseResult.getMissingTables());
                    
                    boolean foundNew = false;
                    for (String tableName : parseResult.getMissingTables()) {
                        if (allTables.contains(tableName)) continue;
                        
                        Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                        if (count != null && count > 0) {
                            allTables.add(tableName);
                            foundNew = true;
                            log.info("[IterativeTableDiscovery] 补充缺失表: {}", tableName);
                        }
                    }
                    
                    if (!foundNew) {
                        // 没找到缺失的表，需要澄清
                        needsClarification = true;
                        clarificationMessage = parseResult.getReason() != null ? 
                                              parseResult.getReason() : "缺少必要的表";
                        log.warn("[IterativeTableDiscovery] 未找到缺失表，需要澄清: {}", clarificationMessage);
                        break;
                    }
                    
                    // 找到了新表，继续下一轮迭代
                    continue;
                }
            } else {
                // JSON解析失败，使用传统方式解析
                log.warn("[IterativeTableDiscovery] JSON解析失败，使用传统方式");
                
                if (llmResponse.contains("表已足够") || llmResponse.contains("enough")) {
                    log.info("[IterativeTableDiscovery] LLM确认表已足够");
                    break;
                }
                
                List<String> missingTables = parseMissingTablesTraditional(llmResponse);
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
                        log.info("[IterativeTableDiscovery] 迭代补充表: {}", tableName);
                    }
                }
                
                if (!foundNew) {
                    log.info("[IterativeTableDiscovery] 未找到新表，停止迭代");
                    break;
                }
            }
        }
        
        return new DiscoveryResult(allTables, iterations, needsClarification, clarificationMessage);
    }
    
    /**
     * 构建表检查Prompt
     */
    private String buildTableCheckPrompt(String query, String schemaInfo, 
                                         String relationshipInfo, Long datasourceId) {
        return String.format(
            "你是一个数据库专家。根据以下信息，判断当前表集合是否足以生成SQL。\n\n" +
            "## 用户问题\n%s\n\n" +
            "## 当前可用表结构\n%s\n\n" +
            "## 表关联关系\n%s\n\n" +
            "## 任务\n" +
            "1. 分析用户问题需要哪些表和字段\n" +
            "2. 检查当前表集合是否包含所有必需的表\n" +
            "3. 如果表足够，返回选中的表列表\n" +
            "4. 如果缺少表，返回缺失的表名和原因\n\n" +
            "## 输出格式\n" +
            "只返回JSON格式：\n" +
            "- 如果表足够：{\"selected_tables\": [\"表1\", \"表2\"]}\n" +
            "- 如果缺少表：{\"missing_tables\": [\"表A\", \"表B\"], \"reason\": \"说明缺少的表用途\"}",
            query, schemaInfo, relationshipInfo
        );
    }
    
    /**
     * 解析LLM的JSON响应
     */
    private ParseResult parseLLMResponse(String llmResponse, Long datasourceId, Set<String> currentTables) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String cleanJson = MarkdownUtils.extractFromMarkdown(llmResponse);
            JsonNode jsonNode = mapper.readTree(cleanJson);
            
            ParseResult result = new ParseResult();
            result.setSuccess(true);
            
            // 情况1: selected_tables
            if (jsonNode.has("selected_tables")) {
                JsonNode selectedTablesNode = jsonNode.get("selected_tables");
                if (selectedTablesNode.isArray() && selectedTablesNode.size() > 0) {
                    Set<String> selectedTables = new HashSet<>();
                    for (JsonNode tableNode : selectedTablesNode) {
                        String tableName = tableNode.asText().toLowerCase();
                        Integer count = metadataMapper.countTableByDatasource(datasourceId, tableName);
                        if (count != null && count > 0) {
                            selectedTables.add(tableName);
                        }
                    }
                    result.setSelectedTables(selectedTables);
                }
            }
            // 情况2: missing_tables
            else if (jsonNode.has("missing_tables")) {
                JsonNode missingTablesNode = jsonNode.get("missing_tables");
                if (missingTablesNode.isArray() && missingTablesNode.size() > 0) {
                    List<String> missingTables = new ArrayList<>();
                    for (JsonNode tableNode : missingTablesNode) {
                        missingTables.add(tableNode.asText().toLowerCase());
                    }
                    result.setMissingTables(missingTables);
                    
                    if (jsonNode.has("reason")) {
                        result.setReason(jsonNode.get("reason").asText());
                    }
                }
            }
            
            return result;
            
        } catch (Exception e) {
            log.warn("[IterativeTableDiscovery] JSON解析失败: {}", e.getMessage());
            ParseResult result = new ParseResult();
            result.setSuccess(false);
            return result;
        }
    }
    
    /**
     * 传统方式解析缺失表（降级方案）
     */
    private List<String> parseMissingTablesTraditional(String llmResponse) {
        Set<String> tables = new HashSet<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9_]*)\\b");
        
        for (String line : llmResponse.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.contains("表已足够")) continue;
            
            java.util.regex.Matcher matcher = pattern.matcher(line);
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
    
    /**
     * 发现结果封装类
     */
    private static class DiscoveryResult {
        private final Set<String> finalTables;
        private final int iterations;
        private final boolean needsClarification;
        private final String clarificationMessage;
        
        public DiscoveryResult(Set<String> finalTables, int iterations, 
                              boolean needsClarification, String clarificationMessage) {
            this.finalTables = finalTables;
            this.iterations = iterations;
            this.needsClarification = needsClarification;
            this.clarificationMessage = clarificationMessage;
        }
        
        public Set<String> getFinalTables() { return finalTables; }
        public int getIterations() { return iterations; }
        public boolean isNeedsClarification() { return needsClarification; }
        public String getClarificationMessage() { return clarificationMessage; }
    }
    
    /**
     * 解析结果封装类
     */
    private static class ParseResult {
        private boolean success;
        private Set<String> selectedTables;
        private List<String> missingTables;
        private String reason;
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Set<String> getSelectedTables() { return selectedTables; }
        public void setSelectedTables(Set<String> selectedTables) { this.selectedTables = selectedTables; }
        public List<String> getMissingTables() { return missingTables; }
        public void setMissingTables(List<String> missingTables) { this.missingTables = missingTables; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
