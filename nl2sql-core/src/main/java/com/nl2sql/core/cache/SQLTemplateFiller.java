package com.nl2sql.core.cache;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL模板填充引擎
 * 将占位符替换为实际值，生成可执行SQL
 */
@Slf4j
public class SQLTemplateFiller {
    
    /**
     * 填充SQL模板
     * 
     * @param template SQL模板（含占位符）
     * @param structure 当前查询结构
     * @return 填充后的SQL
     */
    public String fill(String template, QueryStructureExtractor.QueryStructure structure) {
        if (template == null || structure == null) {
            return null;
        }
        
        String filled = template;
        
        // 1. 填充人名
        if (structure.getPerson() != null) {
            filled = filled.replace("{PERSON_NAME}", escapeSql(structure.getPerson()));
        }
        
        // 2. 填充时间偏移量
        if (structure.getTime() != null) {
            Integer offset = convertTimeToOffset(structure.getTime());
            if (offset != null) {
                filled = filled.replace("{OFFSET}", String.valueOf(offset));
            }
            
            // 绝对日期
            if ("ABSOLUTE".equals(structure.getTime().getType())) {
                filled = filled.replace("{DATE_VALUE}", structure.getTime().getValue());
            }
            
            // ✅ 动态范围（最近N天）
            if ("RELATIVE_RANGE".equals(structure.getTime().getType())) {
                String timeValue = structure.getTime().getValue();
                // 提取 {3} 中的数字
                java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("\\{(\\d+)\\}");
                java.util.regex.Matcher numMatcher = numPattern.matcher(timeValue);
                if (numMatcher.find()) {
                    String number = numMatcher.group(1);
                    filled = filled.replace("{NUM}", number);
                }
            }
        }
        
        // 3. 填充地点
        if (structure.getLocation() != null) {
            filled = filled.replace("{LOCATION}", escapeSql(structure.getLocation()));
        }
        
        // 4. 填充金额
        if (structure.getAmount() != null) {
            filled = filled.replace("{AMOUNT_VALUE}", structure.getAmount().getValue());
        }
        
        // 5. 填充ID
        if (structure.getId() != null) {
            filled = filled.replace("{ID_VALUE}", escapeSql(structure.getId().getValue()));
        }
        
        log.debug("[SQLTemplateFiller] 填充完成: {} placeholders replaced", 
            countPlaceholders(template) - countPlaceholders(filled));
        
        return filled;
    }
    
    /**
     * 将时间表达式转换为SQL偏移量
     */
    private Integer convertTimeToOffset(QueryStructureExtractor.TimeExpression time) {
        if ("RELATIVE".equals(time.getType())) {
            switch (time.getValue()) {
                case "今天": return 0;
                case "昨天": return 1;
                case "前天": return 2;
                case "明天": return -1;
                case "后天": return -2;
                default: return null;
            }
        }
        return null;
    }
    
    /**
     * SQL转义（防止注入）
     */
    private String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
    
    /**
     * 统计占位符数量
     */
    private int countPlaceholders(String sql) {
        int count = 0;
        String[] placeholders = {"{PERSON_NAME}", "{OFFSET}", "{DATE_VALUE}", 
                                 "{LOCATION}", "{AMOUNT_VALUE}", "{ID_VALUE}"};
        for (String placeholder : placeholders) {
            int index = 0;
            while ((index = sql.indexOf(placeholder, index)) != -1) {
                count++;
                index += placeholder.length();
            }
        }
        return count;
    }
}
