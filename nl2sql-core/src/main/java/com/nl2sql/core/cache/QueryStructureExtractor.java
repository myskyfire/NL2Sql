package com.nl2sql.core.cache;

import com.nl2sql.common.util.EntityExtractor;
import com.nl2sql.metadata.mapper.IndustryConceptAdminMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询结构提取器 - 规则引擎
 * 将自然语言查询解析为结构化JSON，用于L2缓存归一化
 * 覆盖QueryNormalizer所有场景并增强
 */
@Slf4j
public class QueryStructureExtractor {
    
    // 绝对日期正则：2025-09-08, 2025/09/08, 2025年9月8日
    private static final Pattern ABSOLUTE_DATE_PATTERN = 
        Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日]?");
    
    // ✅ 数据库连接（用于查询行业表）
    private JdbcTemplate jdbcTemplate;
    
    // ✅ 数据源ID（用于确定行业）
    private Long datasourceId;
    
    // ✅ IndustryConceptAdminMapper（用于查询行业代码）
    private IndustryConceptAdminMapper adminMapper;
    
    // 行业目标提取器（可选，仅用于行业特有逻辑）
    private IndustryTargetExtractor industryTargetExtractor;
    
    /**
     * 设置行业目标提取器（由行业扩展点注入，仅用于特殊逻辑）
     */
    public void setIndustryTargetExtractor(IndustryTargetExtractor extractor) {
        this.industryTargetExtractor = extractor;
    }
    
    /**
     * ✅ 设置数据库连接和数据源ID（用于查询行业表）
     */
    public void setDataSource(JdbcTemplate jdbcTemplate, Long datasourceId) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceId = datasourceId;
    }
    
    /**
     * ✅ 设置IndustryConceptAdminMapper（用于查询行业代码）
     */
    public void setAdminMapper(IndustryConceptAdminMapper adminMapper) {
        this.adminMapper = adminMapper;
    }
    
    /**
     * 提取查询结构
     */
    public QueryStructure extract(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        
        QueryStructure structure = new QueryStructure();
        
        // 1. 提取人名（HanLP NER）
        structure.setPerson(extractPerson(query));
        
        // 2. 提取时间（区分相对/绝对）
        structure.setTime(extractTime(query));
        
        // 3. 提取地点（HanLP NER）
        structure.setLocation(extractLocation(query));
        
        // 4. 提取意图
        structure.setIntent(detectIntent(query));
        
        // ✅ 5. 提取时间粒度（每天/每周/每月）
        structure.setTimeGranularity(extractTimeGranularity(query));
        
        // 6. 提取目标关键词（支持行业扩展）
        structure.setTarget(extractTarget(query));
        
        // 7. 提取金额
        structure.setAmount(extractAmount(query));
        
        // 8. 提取ID/编号
        structure.setId(extractId(query));
        
        log.debug("[QueryStructure] 提取完成: query={}, structure={}", query, structure);
        return structure;
    }
    
    /**
     * 转换为归一化JSON（用于缓存Key）
     */
    public String toNormalizedJson(QueryStructure structure) {
        if (structure == null) {
            return null;
        }
        
        Map<String, Object> normalized = new HashMap<>();
        
        // 意图
        normalized.put("intent", structure.getIntent());
        
        // ✅ 新增：时间粒度（区分"每天"vs"总计"）
        if (structure.getTimeGranularity() != null) {
            normalized.put("time_granularity", structure.getTimeGranularity());
        }
        
        // 实体（归一化）
        Map<String, Object> entities = new HashMap<>();
        if (structure.getPerson() != null) {
            entities.put("person", "{PERSON}");
        }
        if (structure.getTime() != null) {
            entities.put("time_type", structure.getTime().getType());
            entities.put("time_value", normalizeTimeValue(structure.getTime()));
        }
        if (structure.getLocation() != null) {
            entities.put("location", "{LOCATION}");
        }
        normalized.put("entities", entities);
        
        // 金额（归一化）
        if (structure.getAmount() != null) {
            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("type", structure.getAmount().getType());
            amountMap.put("value", "{AMOUNT}");
            normalized.put("amount", amountMap);
        }
        
        // ID/编号（归一化）
        if (structure.getId() != null) {
            Map<String, Object> idMap = new HashMap<>();
            idMap.put("type", structure.getId().getType());
            idMap.put("value", "{ID}");
            normalized.put("id", idMap);
        }
        
        // 目标（可选，由行业扩展点注入）
        if (structure.getTarget() != null) {
            normalized.put("target", structure.getTarget());
        }
        
        // 序列化为紧凑JSON（作为缓存Key）
        return serializeToJson(normalized);
    }
    
    // ==================== 私有方法 ====================
    
    private String extractPerson(String query) {
        List<String> persons = EntityExtractor.extractPersons(query);
        return persons.isEmpty() ? null : persons.get(0);
    }
    
    private TimeExpression extractTime(String query) {
        // 优先匹配绝对日期 - 完整格式
        Matcher absoluteMatcher = ABSOLUTE_DATE_PATTERN.matcher(query);
        if (absoluteMatcher.find()) {
            String dateStr = absoluteMatcher.group();
            String normalized = normalizeDate(dateStr);
            return new TimeExpression("ABSOLUTE", normalized);
        }
        
        // 匹配绝对日期 - yyyy年MM月
        Pattern yearMonthPattern = Pattern.compile("\\d{4}年\\d{1,2}月?");
        Matcher ymMatcher = yearMonthPattern.matcher(query);
        if (ymMatcher.find()) {
            return new TimeExpression("ABSOLUTE_MONTH", ymMatcher.group());
        }
        
        // 匹配绝对日期 - MM月DD日/号
        Pattern monthDayPattern = Pattern.compile("\\d{1,2}月\\d{1,2}[日号]");
        Matcher mdMatcher = monthDayPattern.matcher(query);
        if (mdMatcher.find()) {
            return new TimeExpression("ABSOLUTE_DAY", mdMatcher.group());
        }
        
        // 匹配相对时间 - 天级别
        if (query.contains("昨天")) return new TimeExpression("RELATIVE", "昨天");
        if (query.contains("今天")) return new TimeExpression("RELATIVE", "今天");
        if (query.contains("前天")) return new TimeExpression("RELATIVE", "前天");
        if (query.contains("明天")) return new TimeExpression("RELATIVE", "明天");
        if (query.contains("后天")) return new TimeExpression("RELATIVE", "后天");
        
        // 周级别
        if (query.contains("上周")) return new TimeExpression("RELATIVE_WEEK", "上周");
        if (query.contains("本周")) return new TimeExpression("RELATIVE_WEEK", "本周");
        if (query.contains("下周")) return new TimeExpression("RELATIVE_WEEK", "下周");
        
        // 月级别
        if (query.contains("上月") || query.contains("上个月")) return new TimeExpression("RELATIVE_MONTH", "上月");
        if (query.contains("本月") || query.contains("这个月")) return new TimeExpression("RELATIVE_MONTH", "本月");
        if (query.contains("下月") || query.contains("下个月")) return new TimeExpression("RELATIVE_MONTH", "下月");
        
        // 年级别
        if (query.contains("去年")) return new TimeExpression("RELATIVE_YEAR", "去年");
        if (query.contains("今年") || query.contains("本年")) return new TimeExpression("RELATIVE_YEAR", "今年");
        if (query.contains("明年")) return new TimeExpression("RELATIVE_YEAR", "明年");
        if (query.contains("前年")) return new TimeExpression("RELATIVE_YEAR", "前年");
        
        // 最近N天/周/月/年（支持多种表达）
        Pattern recentPattern = Pattern.compile("(最近|过去|近)(\\d+)(天|周|月|年)");
        Matcher recentMatcher = recentPattern.matcher(query);
        if (recentMatcher.find()) {
            String number = recentMatcher.group(2);  // 提取数字
            String unit = recentMatcher.group(3);
            return new TimeExpression("RELATIVE_RANGE", "最近{" + number + "}" + unit);
        }
        
        return null;
    }
    
    private String extractLocation(String query) {
        List<String> locations = EntityExtractor.extractLocations(query);
        return locations.isEmpty() ? null : locations.get(0);
    }
    
    private String detectIntent(String query) {
        // ✅ 增强：检测聚合关键词（包括"XX数/XX量/XX额"等隐含统计）
        if (query.contains("统计") || query.contains("汇总") || query.contains("平均") || 
            query.contains("合计") || query.contains("总数") ||
            query.contains("订单数") || query.contains("用户数") || query.contains("商品数") ||
            query.contains("数量") || query.contains("次数") || query.contains("频次")) {
            return "aggregate";
        }
        // ✅ 修复：排除"最近"等时间词，只匹配真正的排序意图
        if ((query.contains("排序") || query.contains("排名")) && !query.contains("最近")) {
            return "sort";
        }
        return "query";
    }
    
    /**
     * 提取目标关键词（✅ 重构：优先查行业表，扩展点仅用于特殊逻辑）
     */
    private String extractTarget(String query) {
        // ✅ 1. 优先从行业表查询（通用逻辑）
        if (jdbcTemplate != null && datasourceId != null) {
            String industryCode = getIndustryCode(datasourceId);
            if (industryCode != null) {
                String targetFromConfig = IndustryTargetExtractor.extractFromConfig(
                    query, jdbcTemplate, industryCode
                );
                if (targetFromConfig != null) {
                    log.debug("[QueryStructure] 从行业表提取目标: {}", targetFromConfig);
                    return targetFromConfig;
                }
            }
        }
        
        // ✅ 2. 行业特有逻辑（扩展点）
        if (industryTargetExtractor != null) {
            String industryTarget = industryTargetExtractor.extract(query);
            if (industryTarget != null) {
                log.debug("[QueryStructure] 行业扩展点提取目标: {}", industryTarget);
                return industryTarget;
            }
        }
        
        // ✅ 3. 降级：通用目标提取（保持基础能力）
        return detectGenericTarget(query);
    }
    
    /**
     * 获取数据源的行业代码
     */
    private String getIndustryCode(Long datasourceId) {
        try {
            if (adminMapper != null) {
                return adminMapper.selectIndustryCodeByDatasourceId(datasourceId);
            }
            // 降级：使用jdbcTemplate
            return jdbcTemplate.queryForObject(
                "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? LIMIT 1",
                String.class, datasourceId
            );
        } catch (Exception e) {
            log.warn("[QueryStructure] 查询行业代码失败: datasourceId={}", datasourceId, e);
            return null;
        }
    }
    
    /**
     * 通用目标提取（基础兜底）
     */
    private String detectGenericTarget(String query) {
        // 业务对象类
        if (query.contains("订单")) return "订单";
        if (query.contains("库存")) return "库存";
        if (query.contains("商品") || query.contains("产品")) return "商品";
        if (query.contains("用户") || query.contains("客户") || query.contains("会员")) return "用户";
        
        // 财务类
        if (query.contains("余额") || query.contains("金额") || query.contains("GMV") || 
            query.contains("营收") || query.contains("收入") || query.contains("利润")) return "金额";
        
        return null;
    }
    
    /**
     * ✅ 提取时间粒度（每天/每周/每月）
     */
    private String extractTimeGranularity(String query) {
        if (query.contains("每天") || query.contains("每日") || query.contains("按天")) {
            return "day";
        }
        if (query.contains("每周") || query.contains("按周")) {
            return "week";
        }
        if (query.contains("每月") || query.contains("按月")) {
            return "month";
        }
        return null;
    }
    
    private AmountExpression extractAmount(String query) {
        // ✅ 修复：排除时间表达式中的数字（如"7天"、"30日"）
        // 先移除时间相关模式
        String cleanedQuery = query.replaceAll("(最近|过去|近)\\d+(天|周|月|年)", "")
                                    .replaceAll("\\d+[天周月年]", "");
        
        Pattern amountPattern = Pattern.compile("(\\d+[万千元亿]?元?|[￥$€£]\\d+([万千元亿])?)");
        Matcher matcher = amountPattern.matcher(cleanedQuery);
        if (matcher.find()) {
            String value = matcher.group();
            if (value.contains("万") || value.contains("千") || value.contains("亿")) {
                return new AmountExpression("RANGE", value);
            }
            return new AmountExpression("EXACT", value);
        }
        return null;
    }
    
    private IdExpression extractId(String query) {
        Pattern idPattern = Pattern.compile("(ID|编号|订单号|账号)[为是]?(\\w+)");
        Matcher matcher = idPattern.matcher(query);
        if (matcher.find()) {
            String type = matcher.group(1);
            String value = matcher.group(2);
            return new IdExpression(type, value);
        }
        return null;
    }
    
    private String normalizeDate(String dateStr) {
        String normalized = dateStr.replaceAll("[年月]", "-").replace("日", "");
        normalized = normalized.replaceAll("/", "-");
        
        String[] parts = normalized.split("-");
        if (parts.length == 3) {
            parts[1] = String.format("%02d", Integer.parseInt(parts[1]));
            parts[2] = String.format("%02d", Integer.parseInt(parts[2]));
            return parts[0] + "-" + parts[1] + "-" + parts[2];
        }
        
        return normalized;
    }
    
    private String normalizeTimeValue(TimeExpression time) {
        if ("ABSOLUTE".equals(time.getType())) {
            return "{DATE_ABSOLUTE}";
        } else if ("RELATIVE".equals(time.getType())) {
            return "{DATE_RELATIVE}";
        } else if ("RELATIVE_RANGE".equals(time.getType())) {
            return "{DATE_RANGE}";
        }
        return time.getValue();
    }
    
    private String serializeToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value instanceof Map) {
                sb.append(serializeToJson((Map<String, Object>) value));
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class QueryStructure {
        private String person;              // 人名
        private TimeExpression time;        // 时间表达式
        private String location;            // 地点
        private String intent;              // 意图：query/aggregate/sort
        private String timeGranularity;     // ✅ 时间粒度：day/week/month（区分"每天"vs"总计"）
        private String target;              // 目标关键词：订单/余额/库存等
        private AmountExpression amount;    // 金额表达式
        private IdExpression id;            // ID/编号表达式
    }
    
    @Data
    public static class TimeExpression {
        private String type;    // ABSOLUTE / RELATIVE / RELATIVE_RANGE
        private String value;   // "2025-09-08" 或 "昨天" 或 "最近7天"
        
        public TimeExpression(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }
    
    @Data
    public static class AmountExpression {
        private String type;    // EXACT / RANGE
        private String value;   // "100元" 或 "5万元"
        
        public AmountExpression(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }
    
    @Data
    public static class IdExpression {
        private String type;    // ID / 编号 / 订单号 / 账号
        private String value;   // 具体值
        
        public IdExpression(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }
}
