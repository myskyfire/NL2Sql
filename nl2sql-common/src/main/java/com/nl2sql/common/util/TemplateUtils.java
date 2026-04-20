package com.nl2sql.common.util;

/**
 * 模板工具类
 */
public class TemplateUtils {
    
    /**
     * 生成AI回答模板
     */
    public static String generateAnswerTemplate(String question, String sql) {
        return String.format(
            "根据您的查询「%s」，系统生成了相应的 SQL 并成功执行。\n" +
            "您可以参考以下 SQL 语句进行类似的数据查询：\n\n" +
            "```sql\n%s\n```\n\n" +
            "如需进一步分析或可视化，请告知具体需求。",
            question, sql
        );
    }
}
