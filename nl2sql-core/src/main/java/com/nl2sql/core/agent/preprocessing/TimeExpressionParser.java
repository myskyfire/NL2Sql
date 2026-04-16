package com.nl2sql.core.agent.preprocessing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间表达式解析器
 * 将自然语言时间表达转换为SQL条件
 */
@Slf4j
@Component
public class TimeExpressionParser {
    
    private static final Pattern RECENT_DAYS = Pattern.compile("最近(\\d+)天|近(\\d+)天|(\\d+)天内");
    private static final Pattern LAST_MONTH = Pattern.compile("上个月|上月");
    private static final Pattern THIS_MONTH = Pattern.compile("这个月|本月|当月");
    private static final Pattern LAST_QUARTER = Pattern.compile("上个季度|上季");
    private static final Pattern THIS_QUARTER = Pattern.compile("这个季度|本季度|当季");
    private static final Pattern LAST_YEAR = Pattern.compile("去年|上年度");
    private static final Pattern THIS_YEAR = Pattern.compile("今年|本年度|当年");
    private static final Pattern TODAY = Pattern.compile("今天|今日");
    private static final Pattern YESTERDAY = Pattern.compile("昨天|昨日");
    
    /**
     * 解析问题中的时间表达式，返回SQL WHERE条件
     * 
     * @param question 用户问题
     * @param dateColumn 日期字段名（默认created_at）
     * @return SQL条件片段，如 "AND created_at >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)"
     */
    public String parse(String question, String dateColumn) {
        if (question == null || question.isEmpty()) {
            return "";
        }
        
        String column = dateColumn != null ? dateColumn : "created_at";
        
        // 按优先级匹配
        if (TODAY.matcher(question).find()) {
            return String.format(" AND DATE(%s) = CURDATE()", column);
        }
        
        if (YESTERDAY.matcher(question).find()) {
            return String.format(" AND DATE(%s) = CURDATE() - INTERVAL 1 DAY", column);
        }
        
        Matcher recentDays = RECENT_DAYS.matcher(question);
        if (recentDays.find()) {
            int days = getMatchedNumber(recentDays);
            return String.format(" AND %s >= DATE_SUB(CURDATE(), INTERVAL %d DAY)", column, days);
        }
        
        if (LAST_MONTH.matcher(question).find()) {
            return String.format(" AND YEAR(%s) = YEAR(DATE_SUB(CURDATE(), INTERVAL 1 MONTH)) " +
                               "AND MONTH(%s) = MONTH(DATE_SUB(CURDATE(), INTERVAL 1 MONTH))", column, column);
        }
        
        if (THIS_MONTH.matcher(question).find()) {
            return String.format(" AND YEAR(%s) = YEAR(CURDATE()) AND MONTH(%s) = MONTH(CURDATE())", column, column);
        }
        
        if (LAST_QUARTER.matcher(question).find()) {
            return String.format(" AND QUARTER(%s) = QUARTER(DATE_SUB(CURDATE(), INTERVAL 3 MONTH)) " +
                               "AND YEAR(%s) = YEAR(DATE_SUB(CURDATE(), INTERVAL 3 MONTH))", column, column);
        }
        
        if (THIS_QUARTER.matcher(question).find()) {
            return String.format(" AND QUARTER(%s) = QUARTER(CURDATE()) AND YEAR(%s) = YEAR(CURDATE())", column, column);
        }
        
        if (LAST_YEAR.matcher(question).find()) {
            return String.format(" AND YEAR(%s) = YEAR(CURDATE()) - 1", column);
        }
        
        if (THIS_YEAR.matcher(question).find()) {
            return String.format(" AND YEAR(%s) = YEAR(CURDATE())", column);
        }
        
        return "";
    }
    
    /**
     * 增强问题：将时间表达式替换为明确的日期范围描述
     * 用于改善LLM理解
     */
    public String enhanceQuestion(String question) {
        if (question == null || question.isEmpty()) {
            return question;
        }
        
        LocalDate now = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        
        Matcher recentDays = RECENT_DAYS.matcher(question);
        if (recentDays.find()) {
            int days = getMatchedNumber(recentDays);
            LocalDate startDate = now.minusDays(days);
            String replacement = String.format("最近%d天（%s 至 %s）", days, 
                startDate.format(formatter), now.format(formatter));
            question = recentDays.replaceAll(replacement);
        }
        
        if (LAST_MONTH.matcher(question).find()) {
            LocalDate lastMonth = now.minusMonths(1);
            String monthStr = lastMonth.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
            question = LAST_MONTH.matcher(question).replaceAll(monthStr);
        }
        
        if (THIS_MONTH.matcher(question).find()) {
            String monthStr = now.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
            question = THIS_MONTH.matcher(question).replaceAll(monthStr + "（本月）");
        }
        
        if (LAST_YEAR.matcher(question).find()) {
            int lastYear = now.getYear() - 1;
            question = LAST_YEAR.matcher(question).replaceAll(lastYear + "年");
        }
        
        if (THIS_YEAR.matcher(question).find()) {
            int currentYear = now.getYear();
            question = THIS_YEAR.matcher(question).replaceAll(currentYear + "年（今年）");
        }
        
        return question;
    }
    
    private int getMatchedNumber(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (matcher.group(i) != null) {
                return Integer.parseInt(matcher.group(i));
            }
        }
        return 0;
    }
}
