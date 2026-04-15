package com.nl2sql.core.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TimeExpressionParser {
    
    /**
     * 解析查询中的时间表达式并转换为SQL
     */
    public String parseTimeExpressions(String query) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        
        String result = query;
        
        // 解析相对时间表达式
        result = parseRelativeTime(result);
        
        // 解析绝对时间表达式
        result = parseAbsoluteTime(result);
        
        if (!result.equals(query)) {
            log.info("时间表达式解析: {} -> {}", query, result);
        }
        
        return result;
    }
    
    /**
     * 解析相对时间
     */
    private String parseRelativeTime(String query) {
        String result = query;
        LocalDate now = LocalDate.now();
        
        // 今天/今日
        result = result.replaceAll("(?i)(今天|今日)", "DATE('" + now + "')");
        
        // 昨天/昨日
        result = result.replaceAll("(?i)(昨天|昨日)", "DATE('" + now.minusDays(1) + "')");
        
        // 明天/明日
        result = result.replaceAll("(?i)(明天|明日)", "DATE('" + now.plusDays(1) + "')");
        
        // 前天
        result = result.replaceAll("(?i)前天", "DATE('" + now.minusDays(2) + "')");
        
        // 本周/这周
        if (result.matches("(?i).*本[周|星期].*")) {
            LocalDate weekStart = now.minusDays(now.getDayOfWeek().getValue() - 1);
            LocalDate weekEnd = weekStart.plusDays(6);
            result = result.replaceAll("(?i)(本[周|星期])", 
                "BETWEEN '" + weekStart + "' AND '" + weekEnd + "'");
        }
        
        // 上周
        if (result.matches("(?i).*上[周|星期].*")) {
            LocalDate lastWeekStart = now.minusWeeks(1).minusDays(now.getDayOfWeek().getValue() - 1);
            LocalDate lastWeekEnd = lastWeekStart.plusDays(6);
            result = result.replaceAll("(?i)(上[周|星期])", 
                "BETWEEN '" + lastWeekStart + "' AND '" + lastWeekEnd + "'");
        }
        
        // 本月
        if (result.matches("(?i).*本月.*")) {
            LocalDate monthStart = now.withDayOfMonth(1);
            LocalDate monthEnd = now.withDayOfMonth(now.lengthOfMonth());
            result = result.replaceAll("(?i)本月", 
                "BETWEEN '" + monthStart + "' AND '" + monthEnd + "'");
        }
        
        // 上月/上个月
        if (result.matches("(?i).*上[个]?月.*")) {
            LocalDate lastMonth = now.minusMonths(1);
            LocalDate lastMonthStart = lastMonth.withDayOfMonth(1);
            LocalDate lastMonthEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
            result = result.replaceAll("(?i)(上[个]?月)", 
                "BETWEEN '" + lastMonthStart + "' AND '" + lastMonthEnd + "'");
        }
        
        // 最近N天
        Pattern recentDaysPattern = Pattern.compile("(?i)最近(\\d+)天");
        Matcher matcher = recentDaysPattern.matcher(result);
        if (matcher.find()) {
            int days = Integer.parseInt(matcher.group(1));
            LocalDate startDate = now.minusDays(days);
            result = matcher.replaceAll("BETWEEN '" + startDate + "' AND '" + now + "'");
        }
        
        // 最近N周
        Pattern recentWeeksPattern = Pattern.compile("(?i)最近(\\d+)周");
        matcher = recentWeeksPattern.matcher(result);
        if (matcher.find()) {
            int weeks = Integer.parseInt(matcher.group(1));
            LocalDate startDate = now.minusWeeks(weeks);
            result = matcher.replaceAll("BETWEEN '" + startDate + "' AND '" + now + "'");
        }
        
        // 最近N个月
        Pattern recentMonthsPattern = Pattern.compile("(?i)最近(\\d+)个月");
        matcher = recentMonthsPattern.matcher(result);
        if (matcher.find()) {
            int months = Integer.parseInt(matcher.group(1));
            LocalDate startDate = now.minusMonths(months);
            result = matcher.replaceAll("BETWEEN '" + startDate + "' AND '" + now + "'");
        }
        
        return result;
    }
    
    /**
     * 解析绝对时间
     */
    private String parseAbsoluteTime(String query) {
        String result = query;
        
        // YYYY-MM-DD格式
        Pattern datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
        Matcher matcher = datePattern.matcher(result);
        while (matcher.find()) {
            String dateStr = matcher.group();
            // 验证日期格式
            try {
                LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                // 已经是标准格式，无需转换
            } catch (Exception e) {
                log.warn("无效日期格式: {}", dateStr);
            }
        }
        
        // YYYY年MM月DD日
        Pattern chineseDatePattern = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
        matcher = chineseDatePattern.matcher(result);
        if (matcher.find()) {
            String year = matcher.group(1);
            String month = String.format("%02d", Integer.parseInt(matcher.group(2)));
            String day = String.format("%02d", Integer.parseInt(matcher.group(3)));
            String isoDate = year + "-" + month + "-" + day;
            result = matcher.replaceAll("DATE('" + isoDate + "')");
        }
        
        return result;
    }
}
