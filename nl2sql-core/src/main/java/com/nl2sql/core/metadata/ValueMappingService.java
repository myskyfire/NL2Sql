package com.nl2sql.core.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字段值映射服务
 * 用于处理自然语言到数据库实际值的转换
 * 例如: "男" -> 1, "女" -> 0
 */
@Slf4j
@Service
public class ValueMappingService {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    
    public ValueMappingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        // 注册JavaTimeModule以支持LocalDateTime序列化
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    // 常见值映射规则（内置默认规则）
    private static final Map<String, Map<String, Object>> DEFAULT_MAPPINGS = new HashMap<>();
    
    static {
        // 性别映射
        Map<String, Object> genderMapping = new HashMap<>();
        genderMapping.put("男", 1);
        genderMapping.put("女", 0);
        genderMapping.put("male", 1);
        genderMapping.put("female", 0);
        genderMapping.put("M", 1);
        genderMapping.put("F", 0);
        DEFAULT_MAPPINGS.put("gender", genderMapping);
        DEFAULT_MAPPINGS.put("sex", genderMapping);
        
        // 状态映射
        Map<String, Object> statusMapping = new HashMap<>();
        statusMapping.put("启用", 1);
        statusMapping.put("禁用", 0);
        statusMapping.put("激活", 1);
        statusMapping.put("未激活", 0);
        statusMapping.put("active", 1);
        statusMapping.put("inactive", 0);
        DEFAULT_MAPPINGS.put("status", statusMapping);
        DEFAULT_MAPPINGS.put("state", statusMapping);
        
        // 是否映射
        Map<String, Object> yesNoMapping = new HashMap<>();
        yesNoMapping.put("是", 1);
        yesNoMapping.put("否", 0);
        yesNoMapping.put("yes", 1);
        yesNoMapping.put("no", 0);
        yesNoMapping.put("Y", 1);
        yesNoMapping.put("N", 0);
        DEFAULT_MAPPINGS.put("is_", yesNoMapping);  // 匹配 is_xxx 字段
    }
    
    /**
     * 转换SQL中的值
     * 例如: WHERE gender = '男' -> WHERE gender = 1
     */
    public String transformSQLValues(String sql, Long datasourceId) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        
        try {
            String transformed = sql;
            
            // 获取所有涉及表的字段映射信息
            Map<String, FieldMappingInfo> fieldMappings = loadFieldMappings(datasourceId);
            
            // 对每个字段应用值映射
            for (Map.Entry<String, FieldMappingInfo> entry : fieldMappings.entrySet()) {
                String fieldName = entry.getKey();
                FieldMappingInfo mappingInfo = entry.getValue();
                
                transformed = applyValueMapping(transformed, fieldName, mappingInfo);
            }
            
            if (!transformed.equals(sql)) {
                log.info("[值映射] SQL转换: {} -> {}", sql, transformed);
            }
            
            return transformed;
            
        } catch (Exception e) {
            log.error("[值映射] 转换失败，返回原始SQL", e);
            return sql;  // 出错时返回原始SQL，不影响执行
        }
    }
    
    /**
     * 加载字段映射信息
     */
    private Map<String, FieldMappingInfo> loadFieldMappings(Long datasourceId) {
        Map<String, FieldMappingInfo> mappings = new HashMap<>();
        
        try {
            // 从column_metadata表读取value_mapping
            String query = datasourceId != null ?
                "SELECT table_name, column_name, data_type, value_mapping FROM column_metadata WHERE datasource_id = ? AND value_mapping IS NOT NULL" :
                "SELECT table_name, column_name, data_type, value_mapping FROM column_metadata WHERE value_mapping IS NOT NULL";
            
            List<Map<String, Object>> rows = datasourceId != null ?
                jdbcTemplate.queryForList(query, datasourceId) :
                jdbcTemplate.queryForList(query);
            
            for (Map<String, Object> row : rows) {
                String tableName = (String) row.get("table_name");
                String columnName = (String) row.get("column_name");
                String dataType = (String) row.get("data_type");
                String valueMappingJson = (String) row.get("value_mapping");
                
                if (valueMappingJson != null && !valueMappingJson.isEmpty()) {
                    try {
                        Map<String, Object> valueMap = objectMapper.readValue(
                            valueMappingJson, 
                            new TypeReference<Map<String, Object>>() {}
                        );
                        
                        String fullFieldName = tableName + "." + columnName;
                        FieldMappingInfo info = new FieldMappingInfo();
                        info.setTableName(tableName);
                        info.setColumnName(columnName);
                        info.setDataType(dataType);
                        info.setValueMap(valueMap);
                        
                        mappings.put(fullFieldName, info);
                        mappings.put(columnName, info);  // 也支持只用字段名
                        
                        log.debug("[值映射] 加载字段映射: {} -> {}", fullFieldName, valueMap);
                    } catch (Exception e) {
                        log.warn("[值映射] 解析JSON失败: {}", valueMappingJson, e);
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("[值映射] 加载字段映射失败", e);
        }
        
        // 如果没有配置，使用默认映射
        if (mappings.isEmpty()) {
            applyDefaultMappings(mappings);
        }
        
        return mappings;
    }
    
    /**
     * 应用默认映射规则
     */
    private void applyDefaultMappings(Map<String, FieldMappingInfo> mappings) {
        for (Map.Entry<String, Map<String, Object>> entry : DEFAULT_MAPPINGS.entrySet()) {
            String fieldName = entry.getKey();
            FieldMappingInfo info = new FieldMappingInfo();
            info.setColumnName(fieldName);
            info.setValueMap(entry.getValue());
            
            mappings.put(fieldName, info);
        }
    }
    
    /**
     * 应用值映射到SQL
     */
    private String applyValueMapping(String sql, String fieldName, FieldMappingInfo mappingInfo) {
        String result = sql;
        
        // 匹配 WHERE field = 'value' 或 WHERE field = "value" 模式
        Pattern pattern = Pattern.compile(
            "(?i)(\\b" + Pattern.quote(fieldName) + "\\b\\s*=\\s*)['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String prefix = matcher.group(1);  // "field = "
            String stringValue = matcher.group(2);  // "男"
            
            // 查找映射值
            Object mappedValue = mappingInfo.getValueMap().get(stringValue);
            
            if (mappedValue != null) {
                // 根据数据类型决定是否需要引号
                String replacement;
                if (isNumericType(mappingInfo.getDataType())) {
                    replacement = prefix + mappedValue;  // 数字类型不加引号
                } else {
                    replacement = prefix + "'" + mappedValue + "'";  // 字符串类型加引号
                }
                
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                log.info("[值映射] {} '{}' -> {}", fieldName, stringValue, mappedValue);
            }
        }
        
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * 判断是否为数值类型
     */
    private boolean isNumericType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String upperType = dataType.toUpperCase();
        return upperType.contains("INT") || 
               upperType.contains("FLOAT") || 
               upperType.contains("DOUBLE") || 
               upperType.contains("DECIMAL") ||
               upperType.contains("NUMERIC") ||
               upperType.contains("TINYINT");
    }
    
    /**
     * 字段映射信息
     */
    @lombok.Data
    public static class FieldMappingInfo {
        private String tableName;
        private String columnName;
        private String dataType;
        private Map<String, Object> valueMap;
    }
}
