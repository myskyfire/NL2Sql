package com.nl2sql.core.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SynonymService {
    
    // 同义词映射表
    private final Map<String, List<String>> synonymMap = new ConcurrentHashMap<>();
    
    // 业务术语映射表
    private final Map<String, String> businessTermMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        loadDefaultSynonyms();
        loadBusinessTerms();
        log.info("同义词词典初始化完成: {}个同义词组, {}个业务术语", 
            synonymMap.size(), businessTermMap.size());
    }
    
    /**
     * 加载默认同义词
     */
    private void loadDefaultSynonyms() {
        // 订单相关
        synonymMap.put("订单", Arrays.asList("定单", "ordr", "order"));
        synonymMap.put("用户", Arrays.asList("客户", "会员", "user", "customer"));
        synonymMap.put("商品", Arrays.asList("产品", "物品", "product", "goods"));
        
        // 金额相关
        synonymMap.put("金额", Arrays.asList("钱", "费用", "价格", "amount", "price"));
        synonymMap.put("收入", Arrays.asList("营收", "销售额", "营业额", "revenue"));
        synonymMap.put("利润", Arrays.asList("盈利", "收益", "profit"));
        
        // 地区相关
        synonymMap.put("地区", Arrays.asList("省份", "城市", "区域", "地域", "province", "city"));
        synonymMap.put("省份", Arrays.asList("省", "province"));
        synonymMap.put("城市", Arrays.asList("市", "city"));
        
        // 时间相关
        synonymMap.put("今天", Arrays.asList("今日", "当天", "today"));
        synonymMap.put("昨天", Arrays.asList("昨日", "yesterday"));
        synonymMap.put("明天", Arrays.asList("明日", "tomorrow"));
        synonymMap.put("本周", Arrays.asList("这周", "this week"));
        synonymMap.put("上周", Arrays.asList("last week"));
        synonymMap.put("本月", Arrays.asList("这个月", "this month"));
        synonymMap.put("上月", Arrays.asList("上个月", "last month"));
        
        // 数量相关
        synonymMap.put("数量", Arrays.asList("个数", "总数", "count", "total"));
        synonymMap.put("平均", Arrays.asList("均值", "avg", "average"));
        synonymMap.put("最大", Arrays.asList("最高", "max", "maximum"));
        synonymMap.put("最小", Arrays.asList("最低", "min", "minimum"));
        
        // 状态相关
        synonymMap.put("成功", Arrays.asList("已完成", "completed", "success"));
        synonymMap.put("失败", Arrays.asList("未完成", "failed", "error"));
        synonymMap.put("进行中", Arrays.asList("处理中", "pending", "processing"));
    }
    
    /**
     * 加载业务术语
     */
    private void loadBusinessTerms() {
        // DAU - 日活跃用户
        businessTermMap.put("DAU", "COUNT(DISTINCT user_id) WHERE DATE(login_time) = CURDATE()");
        businessTermMap.put("日活", "COUNT(DISTINCT user_id) WHERE DATE(login_time) = CURDATE()");
        businessTermMap.put("活跃用户", "COUNT(DISTINCT user_id) WHERE last_login >= DATE_SUB(NOW(), INTERVAL 7 DAY)");
        
        // GMV - 商品交易总额
        businessTermMap.put("GMV", "SUM(order_amount)");
        businessTermMap.put("成交金额", "SUM(order_amount)");
        businessTermMap.put("交易总额", "SUM(order_amount)");
        
        // 转化率
        businessTermMap.put("转化率", "COUNT(CASE WHEN status = 'completed' THEN 1 END) * 100.0 / COUNT(*)");
        businessTermMap.put("完成率", "COUNT(CASE WHEN status = 'completed' THEN 1 END) * 100.0 / COUNT(*)");
        
        // 客单价
        businessTermMap.put("客单价", "AVG(order_amount)");
        businessTermMap.put("平均订单金额", "AVG(order_amount)");
        
        // 复购率
        businessTermMap.put("复购率", "COUNT(DISTINCT user_id HAVING COUNT(order_id) > 1) * 100.0 / COUNT(DISTINCT user_id)");
    }
    
    /**
     * 扩展查询中的同义词
     * 
     * @param query 原始查询
     * @return 扩展后的查询
     */
    public String expandSynonyms(String query) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        
        String expanded = query;
        
        // 遍历同义词映射
        for (Map.Entry<String, List<String>> entry : synonymMap.entrySet()) {
            String standardTerm = entry.getKey();
            List<String> synonyms = entry.getValue();
            
            // 检查是否包含同义词
            for (String synonym : synonyms) {
                if (expanded.toLowerCase().contains(synonym.toLowerCase())) {
                    // 替换为标准化术语
                    expanded = expanded.replaceAll(
                        "(?i)" + synonym, 
                        standardTerm
                    );
                    log.debug("同义词替换: {} -> {}", synonym, standardTerm);
                }
            }
        }
        
        if (!expanded.equals(query)) {
            log.info("同义词扩展: {} -> {}", query, expanded);
        }
        
        return expanded;
    }
    
    /**
     * 解析业务术语
     * 
     * @param query 包含业务术语的查询
     * @return 解析后的查询
     */
    public String resolveBusinessTerms(String query) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        
        String resolved = query;
        
        // 遍历业务术语映射
        for (Map.Entry<String, String> entry : businessTermMap.entrySet()) {
            String term = entry.getKey();
            String sqlExpression = entry.getValue();
            
            if (resolved.contains(term)) {
                resolved = resolved.replace(term, sqlExpression);
                log.debug("业务术语解析: {} -> {}", term, sqlExpression);
            }
        }
        
        if (!resolved.equals(query)) {
            log.info("业务术语解析: {} -> {}", query, resolved);
        }
        
        return resolved;
    }
    
    /**
     * 添加自定义同义词
     */
    public void addSynonym(String standardTerm, String... synonyms) {
        synonymMap.put(standardTerm, Arrays.asList(synonyms));
        log.info("添加同义词: {} -> {}", standardTerm, Arrays.toString(synonyms));
    }
    
    /**
     * 添加自定义业务术语
     */
    public void addBusinessTerm(String term, String sqlExpression) {
        businessTermMap.put(term, sqlExpression);
        log.info("添加业务术语: {} -> {}", term, sqlExpression);
    }
    
    /**
     * 获取所有同义词
     */
    public Map<String, List<String>> getAllSynonyms() {
        return new HashMap<>(synonymMap);
    }
    
    /**
     * 获取所有业务术语
     */
    public Map<String, String> getAllBusinessTerms() {
        return new HashMap<>(businessTermMap);
    }
}
