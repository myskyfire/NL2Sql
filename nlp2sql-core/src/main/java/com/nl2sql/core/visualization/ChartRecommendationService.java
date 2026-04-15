package com.nl2sql.core.visualization;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChartRecommendationService {
    
    /**
     * 图表类型枚举
     */
    public enum ChartType {
        BAR("柱状图", "适合对比不同类别的数据"),
        LINE("折线图", "适合展示趋势变化"),
        PIE("饼图", "适合展示占比关系"),
        TABLE("表格", "适合展示详细数据"),
        SCATTER("散点图", "适合展示相关性");
        
        private final String name;
        private final String description;
        
        ChartType(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
    }
    
    /**
     * 图表配置
     */
    @Data
    public static class ChartConfig {
        private ChartType type;
        private String title;
        private List<String> xLabels;
        private List<Map<String, Object>> series;
        private Map<String, Object> options;
        
        public ChartConfig() {
            this.options = new HashMap<>();
        }
    }
    
    /**
     * 推荐的图表列表
     */
    @Data
    public static class ChartRecommendation {
        private List<ChartConfig> charts;
        private ChartType primaryType;
        private String reason;
        
        public ChartRecommendation() {
            this.charts = new ArrayList<>();
        }
    }
    
    /**
     * 根据查询结果推荐图表
     * 
     * @param query 原始查询问题
     * @param data 查询结果数据
     * @return 图表推荐
     */
    public ChartRecommendation recommendCharts(String query, List<Map<String, Object>> data) {
        ChartRecommendation recommendation = new ChartRecommendation();
        
        if (data == null || data.isEmpty()) {
            recommendation.setReason("数据为空，无法生成图表");
            return recommendation;
        }
        
        log.info("开始图表推荐: 数据行数={}", data.size());
        
        // 分析数据特征
        DataProfile profile = analyzeData(data);
        
        // 分析查询意图
        QueryIntent intent = analyzeQueryIntent(query);
        
        // 根据数据和意图推荐图表
        List<ChartConfig> charts = generateCharts(data, profile, intent);
        
        recommendation.setCharts(charts);
        recommendation.setPrimaryType(charts.isEmpty() ? ChartType.TABLE : charts.get(0).getType());
        recommendation.setReason(generateReason(profile, intent));
        
        log.info("图表推荐完成: 推荐{}个图表，主要类型={}", charts.size(), recommendation.getPrimaryType());
        
        return recommendation;
    }
    
    /**
     * 分析数据特征
     */
    private DataProfile analyzeData(List<Map<String, Object>> data) {
        DataProfile profile = new DataProfile();
        
        if (data.isEmpty()) {
            return profile;
        }
        
        Map<String, Object> firstRow = data.get(0);
        profile.setColumnCount(firstRow.size());
        profile.setRowCount(data.size());
        
        // 分析每列的类型
        for (String column : firstRow.keySet()) {
            ColumnInfo info = analyzeColumn(column, data);
            profile.getColumns().add(info);
            
            if (info.isNumeric()) {
                profile.setNumericColumnCount(profile.getNumericColumnCount() + 1);
            }
            if (info.isDateOrTime()) {
                profile.setDateColumnCount(profile.getDateColumnCount() + 1);
            }
            if (info.isCategorical()) {
                profile.setCategoryColumnCount(profile.getCategoryColumnCount() + 1);
            }
        }
        
        // 判断是否有时间序列
        profile.setHasTimeSeries(profile.getDateColumnCount() > 0);
        
        // 判断是否适合饼图（有分类和数值列）
        profile.setSuitableForPie(
            profile.getCategoryColumnCount() >= 1 && 
            profile.getNumericColumnCount() >= 1 &&
            data.size() <= 20 // 饼图不宜过多分类
        );
        
        // 判断是否适合折线图（有时间序列）
        profile.setSuitableForLine(profile.hasTimeSeries && profile.getNumericColumnCount() >= 1);
        
        // 判断是否适合柱状图（有分类和数值）
        profile.setSuitableForBar(
            profile.getCategoryColumnCount() >= 1 && 
            profile.getNumericColumnCount() >= 1
        );
        
        return profile;
    }
    
    /**
     * 分析单列信息
     */
    private ColumnInfo analyzeColumn(String columnName, List<Map<String, Object>> data) {
        ColumnInfo info = new ColumnInfo();
        info.setName(columnName);
        
        // 采样检查数据类型
        int sampleSize = Math.min(10, data.size());
        int numericCount = 0;
        int dateCount = 0;
        Set<String> uniqueValues = new HashSet<>();
        
        for (int i = 0; i < sampleSize; i++) {
            Object value = data.get(i).get(columnName);
            if (value == null) continue;
            
            if (value instanceof Number) {
                numericCount++;
            } else if (value instanceof Date || isDateString(value.toString())) {
                dateCount++;
            }
            
            uniqueValues.add(value.toString());
        }
        
        info.setNumeric(numericCount > sampleSize * 0.8);
        info.setDateOrTime(dateCount > sampleSize * 0.8);
        info.setCategorical(uniqueValues.size() <= 50 && !info.isNumeric());
        info.setUniqueValueCount(uniqueValues.size());
        
        return info;
    }
    
    /**
     * 分析查询意图
     */
    private QueryIntent analyzeQueryIntent(String query) {
        QueryIntent intent = new QueryIntent();
        
        if (query == null) {
            return intent;
        }
        
        String lowerQuery = query.toLowerCase();
        
        // 检测趋势相关词汇
        if (lowerQuery.contains("趋势") || lowerQuery.contains("变化") || 
            lowerQuery.contains("走势") || lowerQuery.contains("增长")) {
            intent.setTrendAnalysis(true);
        }
        
        // 检测对比相关词汇
        if (lowerQuery.contains("对比") || lowerQuery.contains("比较") || 
            lowerQuery.contains("排名") || lowerQuery.contains("top")) {
            intent.setComparison(true);
        }
        
        // 检测占比相关词汇
        if (lowerQuery.contains("占比") || lowerQuery.contains("比例") || 
            lowerQuery.contains("分布") || lowerQuery.contains("百分比")) {
            intent.setDistribution(true);
        }
        
        // 检测统计相关词汇
        if (lowerQuery.contains("统计") || lowerQuery.contains("汇总") || 
            lowerQuery.contains("总计") || lowerQuery.contains("平均")) {
            intent.setAggregation(true);
        }
        
        return intent;
    }
    
    /**
     * 生成图表配置
     */
    private List<ChartConfig> generateCharts(List<Map<String, Object>> data, 
                                             DataProfile profile, 
                                             QueryIntent intent) {
        List<ChartConfig> charts = new ArrayList<>();
        
        // 优先根据查询意图推荐
        if (intent.isTrendAnalysis() && profile.isSuitableForLine()) {
            charts.add(createLineChart(data, profile));
        }
        
        if (intent.isComparison() && profile.isSuitableForBar()) {
            charts.add(createBarChart(data, profile));
        }
        
        if (intent.isDistribution() && profile.isSuitableForPie()) {
            charts.add(createPieChart(data, profile));
        }
        
        // 如果没有明确意图，根据数据特征推荐
        if (charts.isEmpty()) {
            if (profile.isSuitableForLine()) {
                charts.add(createLineChart(data, profile));
            }
            if (profile.isSuitableForBar()) {
                charts.add(createBarChart(data, profile));
            }
            if (profile.isSuitableForPie()) {
                charts.add(createPieChart(data, profile));
            }
        }
        
        // 始终提供表格视图
        charts.add(createTableView(data, profile));
        
        return charts;
    }
    
    /**
     * 创建折线图配置
     */
    private ChartConfig createLineChart(List<Map<String, Object>> data, DataProfile profile) {
        ChartConfig config = new ChartConfig();
        config.setType(ChartType.LINE);
        config.setTitle("趋势分析");
        
        // 找到日期列作为X轴
        ColumnInfo dateColumn = profile.getColumns().stream()
            .filter(ColumnInfo::isDateOrTime)
            .findFirst()
            .orElse(profile.getColumns().get(0));
        
        // 找到数值列作为Y轴
        List<ColumnInfo> numericColumns = profile.getColumns().stream()
            .filter(ColumnInfo::isNumeric)
            .collect(Collectors.toList());
        
        config.setXLabels(data.stream()
            .map(row -> row.get(dateColumn.getName()).toString())
            .collect(Collectors.toList()));
        
        List<Map<String, Object>> series = new ArrayList<>();
        for (ColumnInfo col : numericColumns) {
            Map<String, Object> serie = new HashMap<>();
            serie.put("name", col.getName());
            serie.put("data", data.stream()
                .map(row -> row.get(col.getName()))
                .collect(Collectors.toList()));
            series.add(serie);
        }
        config.setSeries(series);
        
        return config;
    }
    
    /**
     * 创建柱状图配置
     */
    private ChartConfig createBarChart(List<Map<String, Object>> data, DataProfile profile) {
        ChartConfig config = new ChartConfig();
        config.setType(ChartType.BAR);
        config.setTitle("对比分析");
        
        // 找到分类列作为X轴
        ColumnInfo categoryColumn = profile.getColumns().stream()
            .filter(ColumnInfo::isCategorical)
            .findFirst()
            .orElse(profile.getColumns().get(0));
        
        // 找到数值列作为Y轴
        List<ColumnInfo> numericColumns = profile.getColumns().stream()
            .filter(ColumnInfo::isNumeric)
            .collect(Collectors.toList());
        
        config.setXLabels(data.stream()
            .map(row -> row.get(categoryColumn.getName()).toString())
            .collect(Collectors.toList()));
        
        List<Map<String, Object>> series = new ArrayList<>();
        for (ColumnInfo col : numericColumns) {
            Map<String, Object> serie = new HashMap<>();
            serie.put("name", col.getName());
            serie.put("data", data.stream()
                .map(row -> row.get(col.getName()))
                .collect(Collectors.toList()));
            series.add(serie);
        }
        config.setSeries(series);
        
        return config;
    }
    
    /**
     * 创建饼图配置
     */
    private ChartConfig createPieChart(List<Map<String, Object>> data, DataProfile profile) {
        ChartConfig config = new ChartConfig();
        config.setType(ChartType.PIE);
        config.setTitle("占比分析");
        
        // 找到分类列
        ColumnInfo categoryColumn = profile.getColumns().stream()
            .filter(ColumnInfo::isCategorical)
            .findFirst()
            .orElse(profile.getColumns().get(0));
        
        // 找到第一个数值列
        ColumnInfo numericColumn = profile.getColumns().stream()
            .filter(ColumnInfo::isNumeric)
            .findFirst()
            .orElse(null);
        
        if (numericColumn == null) {
            config.setType(ChartType.TABLE);
            return config;
        }
        
        List<Map<String, Object>> series = new ArrayList<>();
        Map<String, Object> pieData = new HashMap<>();
        pieData.put("name", numericColumn.getName());
        
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", row.get(categoryColumn.getName()));
            item.put("value", row.get(numericColumn.getName()));
            items.add(item);
        }
        pieData.put("data", items);
        series.add(pieData);
        config.setSeries(series);
        
        return config;
    }
    
    /**
     * 创建表格视图
     */
    private ChartConfig createTableView(List<Map<String, Object>> data, DataProfile profile) {
        ChartConfig config = new ChartConfig();
        config.setType(ChartType.TABLE);
        config.setTitle("详细数据");
        config.setSeries(new ArrayList<>(data));
        return config;
    }
    
    /**
     * 生成推荐理由
     */
    private String generateReason(DataProfile profile, QueryIntent intent) {
        StringBuilder reason = new StringBuilder();
        
        if (intent.isTrendAnalysis()) {
            reason.append("检测到趋势分析意图，");
        }
        if (intent.isComparison()) {
            reason.append("检测到对比分析意图，");
        }
        if (intent.isDistribution()) {
            reason.append("检测到占比分析意图，");
        }
        
        if (profile.isHasTimeSeries()) {
            reason.append("数据包含时间序列，");
        }
        if (profile.getCategoryColumnCount() > 0) {
            reason.append("包含").append(profile.getCategoryColumnCount()).append("个分类字段，");
        }
        if (profile.getNumericColumnCount() > 0) {
            reason.append("包含").append(profile.getNumericColumnCount()).append("个数值字段");
        }
        
        return reason.length() > 0 ? reason.toString() : "基于数据特征自动推荐";
    }
    
    /**
     * 判断是否为日期字符串
     */
    private boolean isDateString(String value) {
        return value.matches("\\d{4}-\\d{2}-\\d{2}") || 
               value.matches("\\d{4}/\\d{2}/\\d{2}") ||
               value.contains("-") && value.contains(":");
    }
    
    // 内部类
    
    @Data
    private static class DataProfile {
        private int rowCount;
        private int columnCount;
        private int numericColumnCount;
        private int dateColumnCount;
        private int categoryColumnCount;
        private boolean hasTimeSeries;
        private boolean suitableForPie;
        private boolean suitableForLine;
        private boolean suitableForBar;
        private List<ColumnInfo> columns = new ArrayList<>();
    }
    
    @Data
    private static class ColumnInfo {
        private String name;
        private boolean numeric;
        private boolean dateOrTime;
        private boolean categorical;
        private int uniqueValueCount;
    }
    
    @Data
    private static class QueryIntent {
        private boolean trendAnalysis;
        private boolean comparison;
        private boolean distribution;
        private boolean aggregation;
    }
}
