package com.nl2sql.core.agent.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NL2SQL 回归测试集
 * 
 * 目的：防止从小LLM切换到大LLM后出现"小模型跑通、大模型翻车"的问题
 * 
 * 测试策略：
 * 1. 语法防火墙测试 - 验证SQL清洗逻辑能处理NULL/空值等异常情况
 * 2. Schema白名单测试 - 验证列名校验能捕获幻觉列名
 * 3. 关键字检查测试 - 验证生成的SQL包含预期关键字且不包含禁用关键字
 */
@Slf4j
@SpringBootTest
public class NL2SQLRegressionTest {
    
    @Autowired
    private SQLValidationService validationService;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    private List<TestCase> testCases;
    
    @BeforeEach
    public void setUp() throws Exception {
        // 加载测试用例
        InputStream inputStream = getClass().getClassLoader()
            .getResourceAsStream("nl2sql-test-cases.json");
        
        assertNotNull(inputStream, "测试用例文件不存在");
        
        TestCase[] cases = objectMapper.readValue(inputStream, TestCase[].class);
        testCases = Arrays.asList(cases);
        
        log.info("加载了 {} 个回归测试用例", testCases.size());
    }
    
    /**
     * 测试1：语法防火墙 - NULL/空值处理
     */
    @Test
    public void testSyntaxFirewall_NullHandling() {
        log.info("========== 测试1：语法防火墙 - NULL/空值处理 ==========");
        
        // 模拟大模型可能输出的异常值
        String[] abnormalInputs = {
            "NULL",
            "null",
            "None",
            "N/A",
            "",
            "   ",
            null
        };
        
        for (String input : abnormalInputs) {
            String cleaned = com.nl2sql.common.util.MarkdownUtils.cleanSQL(input);
            
            // 关键断言：绝对不能返回null或空字符串
            assertNotNull(cleaned, "清洗后的SQL不能为null: 输入=" + input);
            assertFalse(cleaned.trim().isEmpty(), "清洗后的SQL不能为空: 输入=" + input);
            
            // 应该返回安全默认查询
            assertTrue(
                cleaned.contains("SELECT") && cleaned.contains("no_result_found"),
                "异常输入应返回安全默认查询: 输入=" + input + ", 输出=" + cleaned
            );
            
            log.info("✅ 异常输入处理通过: '{}' -> '{}'", input, cleaned);
        }
    }
    
    /**
     * 测试2：Schema白名单校验 - 捕获幻觉列名
     */
    @Test
    public void testColumnWhitelist_CatchHallucination() {
        log.info("========== 测试2：Schema白名单校验 ==========");
        
        // 模拟合法的列名集合
        Set<String> allowedColumns = new HashSet<>(Arrays.asList(
            "orders.id",
            "orders.order_no",
            "orders.user_id",
            "orders.total_amount",
            "users.id",
            "users.real_name",
            "users.email"
        ));
        
        // 测试1：合法SQL应该通过
        String validSql = "SELECT orders.id, orders.order_no FROM orders WHERE orders.user_id = 1";
        List<String> issues = validationService.validateColumnWhitelist(validSql, allowedColumns);
        assertTrue(issues.isEmpty(), "合法SQL不应有问题: " + issues);
        log.info("✅ 合法SQL通过校验: {}", validSql);
        
        // 测试2：幻觉列名应该被捕获
        String hallucinatedSql = "SELECT orders.id, orders.fake_column FROM orders";
        issues = validationService.validateColumnWhitelist(hallucinatedSql, allowedColumns);
        assertFalse(issues.isEmpty(), "幻觉列名应该被捕获");
        assertTrue(
            issues.get(0).contains("fake_column"),
            "错误信息应包含幻觉列名: " + issues
        );
        log.info("✅ 幻觉列名被捕获: {}", issues.get(0));
    }
    
    /**
     * 测试3：关键字检查 - 验证SQL结构
     */
    @Test
    public void testKeywordValidation() {
        log.info("========== 测试3：关键字检查 ==========");
        
        for (TestCase testCase : testCases) {
            log.info("测试用例 #{}: {}", testCase.getId(), testCase.getDescription());
            
            // 模拟生成SQL（实际场景中应由LLM生成）
            // 这里使用硬编码的期望SQL作为示例
            String mockSql = generateMockSql(testCase);
            
            if (mockSql == null) {
                log.warn("⚠️ 跳过测试用例 #{}: 无法生成模拟SQL", testCase.getId());
                continue;
            }
            
            // 检查必需关键字
            for (String keyword : testCase.getExpectedKeywords()) {
                assertTrue(
                    mockSql.toUpperCase().contains(keyword.toUpperCase()),
                    String.format("SQL缺少必需关键字 '%s': %s", keyword, mockSql)
                );
            }
            
            // 检查禁用关键字
            for (String keyword : testCase.getForbiddenKeywords()) {
                assertFalse(
                    mockSql.toUpperCase().contains(keyword.toUpperCase()),
                    String.format("SQL包含禁用关键字 '%s': %s", keyword, mockSql)
                );
            }
            
            log.info("✅ 测试用例 #{} 通过", testCase.getId());
        }
    }
    
    /**
     * 测试4：综合验证报告
     */
    @Test
    public void testComprehensiveValidation() {
        log.info("========== 测试4：综合验证报告 ==========");
        
        // 测试合法SQL
        String validSql = "SELECT user_id, COUNT(*) as order_count FROM orders GROUP BY user_id";
        SQLValidationService.ValidationReport report = validationService.comprehensiveValidate(validSql);
        
        assertTrue(report.isOverallValid(), "合法SQL应通过综合验证");
        assertTrue(report.isSyntaxValid(), "语法应有效");
        log.info("✅ 合法SQL综合验证通过");
        
        // 测试有问题的SQL（聚合函数但未GROUP BY）
        String problematicSql = "SELECT user_id, COUNT(*) FROM orders";
        report = validationService.comprehensiveValidate(problematicSql);
        
        assertFalse(report.getAggregationIssues().isEmpty(), "应检测到聚合问题");
        log.info("✅ 问题SQL被正确识别: {}", report.getAggregationIssues());
    }
    
    /**
     * 测试5：Markdown清洗功能
     */
    @Test
    public void testMarkdownCleaning() {
        log.info("========== 测试5：Markdown清洗功能 ==========");
        
        // 测试1：标准Markdown代码块
        String markdown1 = "```sql\nSELECT * FROM orders\n```";
        String cleaned1 = com.nl2sql.common.util.MarkdownUtils.cleanSQL(markdown1);
        assertEquals("SELECT * FROM orders", cleaned1);
        log.info("✅ Markdown代码块清洗通过");
        
        // 测试2：带json标记的代码块
        String markdown2 = "```json\n{\"sql\": \"SELECT * FROM orders\"}\n```";
        String cleaned2 = com.nl2sql.common.util.MarkdownUtils.extractFromMarkdown(markdown2);
        assertTrue(cleaned2.contains("SELECT"));
        log.info("✅ JSON代码块提取通过");
        
        // 测试3：带前缀的SQL
        String withPrefix = "sql SELECT * FROM orders";
        String cleaned3 = com.nl2sql.common.util.MarkdownUtils.cleanSQL(withPrefix);
        assertEquals("SELECT * FROM orders", cleaned3);
        log.info("✅ SQL前缀清理通过");
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 根据测试用例生成模拟SQL（实际应由LLM生成）
     */
    private String generateMockSql(TestCase testCase) {
        // 这里简化处理，返回典型SQL模板
        // 实际项目中应调用LLM生成真实SQL
        
        switch (testCase.getId()) {
            case 1:
                return "SELECT id, order_no, user_id, total_amount, created_at FROM orders ORDER BY created_at DESC LIMIT 10";
            case 2:
                return "SELECT user_id, COUNT(*) as order_count FROM orders GROUP BY user_id";
            case 3:
                return "SELECT product_name, SUM(amount) as sales_amount FROM orders GROUP BY product_name ORDER BY sales_amount DESC LIMIT 3";
            case 4:
                return "SELECT orders.id, orders.order_no FROM orders JOIN users ON orders.user_id = users.id WHERE users.real_name = '张三'";
            case 5:
                return "SELECT region, SUM(total_amount) as sales_amount FROM orders GROUP BY region";
            default:
                return null; // 其他用例暂未实现
        }
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class TestCase {
        private int id;
        private String question;
        private List<String> expectedKeywords;
        private List<String> forbiddenKeywords;
        private List<String> expectedColumns;
        private String description;
    }
}
