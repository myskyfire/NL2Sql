package com.nl2sql.core.llm.extension.example;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 财务行业概念扩展示例
 * 
 * 【如何使用】
 * 1. 复制此文件到你的项目
 * 2. 修改包名为你的包名
 * 3. 根据实际业务调整逻辑
 * 4. Spring会自动扫描并加载（因为有@Component注解）
 * 
 * 【生效方式】
 * - 无需修改主程序代码
 * - 无需重新编译nl2sql-core
 * - 只需在你的二开项目中引入此实现类
 * 
 * ⚠️ 注意：此类仅作为示例，默认不激活
 * 如需启用，请添加 @Profile("finance") 或移除 @Profile 注解
 */
@Slf4j
@Component
@Profile("finance-example") // ← 默认不激活，需要时移除此行
public class FinanceConceptExtension implements IndustryConceptExtension {
    
    // ==================== Level 1: 术语理解增强 ====================
    
    @Override
    public List<Map<String, Object>> extractTerms(String question, Long datasourceId) {
        // 示例：从问题中提取财务术语
        List<Map<String, Object>> terms = new java.util.ArrayList<>();
        
        if (question.contains("收入") || question.contains("销售额")) {
            terms.add(Map.of(
                "term", "主营业务收入",
                "type", "metric",
                "confidence", 0.9
            ));
        }
        
        if (question.contains("利润") || question.contains("净利")) {
            terms.add(Map.of(
                "term", "净利润",
                "type", "metric",
                "confidence", 0.85
            ));
        }
        
        return terms;
    }
    
    @Override
    public List<String> suggestSynonyms(String conceptKey, String industryCode) {
        if (!"finance".equals(industryCode)) {
            return List.of();
        }
        
        // 财务术语同义词推荐
        return switch (conceptKey) {
            case "revenue" -> List.of("营收", "营业收入", "销售收入", "主营业务收入");
            case "net_profit" -> List.of("净利润", "税后利润", "纯利润");
            case "ar_balance" -> List.of("应收账款", "应收余额", "客户欠款");
            default -> List.of();
        };
    }
    
    // ==================== Level 2: SQL生成干预 ====================
    
    @Override
    public String enhancePromptBeforeGeneration(String originalPrompt, String question, Long datasourceId) {
        // 检测是否为财务查询
        if (!isFinancialQuery(question)) {
            return null; // 非财务查询，不干预
        }
        
        // 注入财务口径说明
        String financialHint = """
            
            ⚠️ **财务查询特别注意事项**：
            1. **收入确认原则**：使用权责发生制，而非收付实现制
            2. **红字冲销**：查询收入时必须剔除红字凭证（status != 'RED'）
            3. **含税vs不含税**：默认查询不含税金额，除非用户明确要求"含税"
            4. **科目层级**：查询费用时包含下级科目（使用LIKE或递归查询）
            
            示例：
            - "本月收入" → SELECT SUM(amount) FROM vouchers WHERE subject_code LIKE '6001%' AND status != 'RED' AND MONTH(voucher_date) = MONTH(CURDATE())
            - "应收账款余额" → SELECT SUM(debit_balance) FROM account_balances WHERE subject_code LIKE '1122%'
            """;
        
        return originalPrompt + financialHint;
    }
    
    @Override
    public String correctSqlAfterGeneration(String generatedSql, String question, Long datasourceId) {
        if (!isFinancialQuery(question)) {
            return null;
        }
        
        String correctedSql = generatedSql;
        
        // 规则1：自动剔除红字凭证
        if (correctedSql.toUpperCase().contains("FROM VOUCHERS") || 
            correctedSql.toUpperCase().contains("FROM voucher")) {
            if (!correctedSql.toUpperCase().contains("STATUS")) {
                correctedSql = correctedSql.replaceAll("(?i)WHERE", "WHERE status != 'RED' AND ");
                log.info("[财务扩展] 自动添加红字过滤: {}", correctedSql);
            }
        }
        
        // 规则2：收入查询默认不含税
        if ((question.contains("收入") || question.contains("销售额")) && 
            !question.contains("含税")) {
            if (correctedSql.toUpperCase().contains("TAX_AMOUNT")) {
                correctedSql = correctedSql.replaceAll("(?i)SUM\\(.*tax.*\\)", "SUM(amount_excluding_tax)");
                log.info("[财务扩展] 自动转换为不含税金额");
            }
        }
        
        return correctedSql;
    }
    
    // ==================== Level 3: SQL执行校验 ====================
    
    @Override
    public Map<String, Object> validateBeforeExecution(String sql, String question, Long datasourceId, String userId) {
        // 安全检查1：禁止查询凭证明细（只允许汇总）
        if (sql.toUpperCase().contains("SELECT * FROM VOUCHERS") ||
            sql.toUpperCase().contains("SELECT VOUCHER_NO FROM VOUCHERS")) {
            return Map.of(
                "success", false,
                "message", "出于数据安全考虑，不允许查询凭证明细，请使用汇总查询（如：SUM、COUNT、GROUP BY）"
            );
        }
        
        // 安全检查2：禁止跨年查询（需要特殊权限）
        if (sql.toUpperCase().contains("YEAR(") && 
            sql.toUpperCase().contains("BETWEEN") &&
            !hasCrossYearPermission(userId)) {
            return Map.of(
                "success", false,
                "message", "跨年查询需要特殊权限，请联系管理员"
            );
        }
        
        // 安全检查3：限制数据范围（只能查本部门）
        if (sql.toUpperCase().contains("FROM EXPENSE_CLAIMS") &&
            !sql.toUpperCase().contains("DEPARTMENT_ID")) {
            String departmentId = getUserDepartment(userId);
            if (departmentId != null) {
                sql = sql + " AND department_id = " + departmentId;
                log.info("[财务扩展] 自动添加部门过滤: department_id={}", departmentId);
            }
        }
        
        return Map.of(
            "success", true,
            "message", "允许执行"
        );
    }
    
    // ==================== Level 4: 学习与优化 ====================
    
    @Override
    public String getMetricTemplate(String metricName, Long datasourceId) {
        // 财务指标计算模板
        return switch (metricName) {
            case "应收账款余额" -> """
                SELECT 
                    SUM(debit_balance) - SUM(credit_balance) AS ar_balance
                FROM account_balances ab
                JOIN subjects s ON ab.subject_id = s.id
                WHERE s.subject_code LIKE '1122%'  -- 应收账款科目
                  AND ab.period = DATE_FORMAT(CURDATE(), '%Y-%m')
                """;
                
            case "月度预算执行率" -> """
                SELECT 
                    b.department_name,
                    b.budget_amount,
                    COALESCE(SUM(e.actual_amount), 0) AS actual_amount,
                    CASE 
                        WHEN b.budget_amount > 0 
                        THEN COALESCE(SUM(e.actual_amount), 0) / b.budget_amount * 100 
                        ELSE 0 
                    END AS execution_rate
                FROM budgets b
                LEFT JOIN expense_actuals e ON b.id = e.budget_id
                WHERE b.period = DATE_FORMAT(CURDATE(), '%Y-%m')
                GROUP BY b.department_name, b.budget_amount
                """;
                
            case "毛利率" -> """
                SELECT 
                    SUM(revenue.amount) AS total_revenue,
                    SUM(cost.amount) AS total_cost,
                    CASE 
                        WHEN SUM(revenue.amount) > 0 
                        THEN (SUM(revenue.amount) - SUM(cost.amount)) / SUM(revenue.amount) * 100 
                        ELSE 0 
                    END AS gross_margin_rate
                FROM vouchers revenue
                JOIN vouchers cost ON revenue.department_id = cost.department_id
                WHERE revenue.subject_code LIKE '6001%'  -- 主营业务收入
                  AND cost.subject_code LIKE '6401%'      -- 主营业务成本
                  AND revenue.status != 'RED'
                  AND cost.status != 'RED'
                  AND MONTH(revenue.voucher_date) = MONTH(CURDATE())
                """;
                
            default -> null; // 无模板
        };
    }
    
    // ==================== 辅助方法 ====================
    
    private boolean isFinancialQuery(String question) {
        String[] financialKeywords = {"收入", "利润", "成本", "费用", "资产", "负债", "应收", "应付", "凭证", "科目"};
        for (String keyword : financialKeywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean hasCrossYearPermission(String userId) {
        // TODO: 查询用户权限表
        return false; // 默认不允许
    }
    
    private String getUserDepartment(String userId) {
        // TODO: 查询用户所属部门
        return null; // 示例中返回null，实际应从数据库查询
    }
}
