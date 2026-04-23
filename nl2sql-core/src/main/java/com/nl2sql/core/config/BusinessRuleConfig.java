package com.nl2sql.core.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务规则配置
 * 
 * 支持通过配置表动态调整业务逻辑，无需修改代码
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessRuleConfig {
    
    /**
     * 规则ID
     */
    private String ruleId;
    
    /**
     * 规则名称
     */
    private String ruleName;
    
    /**
     * 规则类型（table_mapping/time_parsing/synonym等）
     */
    private String ruleType;
    
    /**
     * 规则内容（JSON格式）
     */
    private Map<String, Object> ruleContent;
    
    /**
     * 适用数据源ID（null表示全局）
     */
    private Long datasourceId;
    
    /**
     * 优先级（数字越大优先级越高）
     */
    @Builder.Default
    private Integer priority = 0;
    
    /**
     * 是否启用
     */
    @Builder.Default
    private Boolean enabled = true;
    
    /**
     * 描述
     */
    private String description;
    
    // ==================== 便捷工厂方法 ====================
    
    /**
     * 创建表映射规则
     */
    public static BusinessRuleConfig tableMapping(String naturalName, String tableName, Long datasourceId) {
        Map<String, Object> content = new HashMap<>();
        content.put("naturalName", naturalName);
        content.put("tableName", tableName);
        
        return BusinessRuleConfig.builder()
            .ruleId("table_mapping_" + naturalName)
            .ruleName("表映射: " + naturalName)
            .ruleType("table_mapping")
            .ruleContent(content)
            .datasourceId(datasourceId)
            .description(String.format("将'%s'映射到表'%s'", naturalName, tableName))
            .build();
    }
    
    /**
     * 创建时间解析规则
     */
    public static BusinessRuleConfig timeParsing(String pattern, String sqlFormat, Long datasourceId) {
        Map<String, Object> content = new HashMap<>();
        content.put("pattern", pattern);
        content.put("sqlFormat", sqlFormat);
        
        return BusinessRuleConfig.builder()
            .ruleId("time_parsing_" + pattern)
            .ruleName("时间解析: " + pattern)
            .ruleType("time_parsing")
            .ruleContent(content)
            .datasourceId(datasourceId)
            .description(String.format("将'%s'解析为SQL格式'%s'", pattern, sqlFormat))
            .build();
    }
    
    /**
     * 创建同义词规则
     */
    public static BusinessRuleConfig synonym(String word, String standardWord, Long datasourceId) {
        Map<String, Object> content = new HashMap<>();
        content.put("word", word);
        content.put("standardWord", standardWord);
        
        return BusinessRuleConfig.builder()
            .ruleId("synonym_" + word)
            .ruleName("同义词: " + word)
            .ruleType("synonym")
            .ruleContent(content)
            .datasourceId(datasourceId)
            .description(String.format("将'%s'标准化为'%s'", word, standardWord))
            .build();
    }
    
    /**
     * 创建字段映射规则
     */
    public static BusinessRuleConfig fieldMapping(String tableName, String naturalName, String fieldName, Long datasourceId) {
        Map<String, Object> content = new HashMap<>();
        content.put("tableName", tableName);
        content.put("naturalName", naturalName);
        content.put("fieldName", fieldName);
        
        return BusinessRuleConfig.builder()
            .ruleId("field_mapping_" + tableName + "_" + naturalName)
            .ruleName("字段映射: " + tableName + "." + naturalName)
            .ruleType("field_mapping")
            .ruleContent(content)
            .datasourceId(datasourceId)
            .description(String.format("将表'%s'的'%s'映射到字段'%s'", tableName, naturalName, fieldName))
            .build();
    }
}
