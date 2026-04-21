package com.nl2sql.core.llm;

import com.nl2sql.core.llm.extension.IndustryConceptExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private IndustryConceptDictionary industryConceptDictionary;
    
    @Autowired(required = false)
    private List<IndustryConceptExtension> conceptExtensions;
    
    @PostConstruct
    public void init() {
        // ✅ 优化1：从数据库加载同义词（优先）
        loadSynonymsFromDatabase();
        
        // 如果数据库为空，加载默认硬编码同义词（兜底）
        if (synonymMap.isEmpty()) {
            log.warn("[SynonymService] 数据库无同义词数据，加载默认硬编码同义词");
            loadDefaultSynonyms();
        }
        
        // 加载业务术语
        loadBusinessTerms();
        
        log.info("[SynonymService] 同义词词典初始化完成: {}个同义词组, {}个业务术语", 
            synonymMap.size(), businessTermMap.size());
    }
    
    /**
     * ✅ 优化1：从数据库加载同义词（优先）
     */
    private void loadSynonymsFromDatabase() {
        if (jdbcTemplate == null) {
            log.warn("[SynonymService] JdbcTemplate未配置，跳过数据库加载");
            return;
        }
        
        try {
            // 从 industry_concept 表加载所有已审核的概念
            List<Map<String, Object>> concepts = jdbcTemplate.queryForList(
                "SELECT concept_key, concept_aliases FROM industry_concept WHERE status = 'approved'"
            );
            
            for (Map<String, Object> concept : concepts) {
                String conceptKey = (String) concept.get("concept_key");
                String aliasesJson = (String) concept.get("concept_aliases");
                
                if (conceptKey != null && aliasesJson != null) {
                    try {
                        // 解析JSON数组
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        List<String> aliases = mapper.readValue(aliasesJson, List.class);
                        
                        if (aliases != null && !aliases.isEmpty()) {
                            synonymMap.put(conceptKey, aliases);
                            log.debug("[SynonymService] 从数据库加载: {} -> {}", conceptKey, aliases);
                        }
                    } catch (Exception e) {
                        log.warn("[SynonymService] 解析别名JSON失败: {}", aliasesJson, e);
                    }
                }
            }
            
            log.info("[SynonymService] 从数据库加载 {} 个同义词组", synonymMap.size());
            
        } catch (Exception e) {
            log.error("[SynonymService] 从数据库加载同义词失败", e);
        }
    }
    
    /**
     * 加载默认同义词（兜底）
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
     * @param datasourceId 数据源ID（用于获取行业代码）
     * @return 扩展后的查询
     */
    public String expandSynonyms(String query, Long datasourceId) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        
        String expanded = query;
        
        // ✅ 步骤1：应用硬编码/数据库同义词
        for (Map.Entry<String, List<String>> entry : synonymMap.entrySet()) {
            String standardTerm = entry.getKey();
            List<String> synonyms = entry.getValue();
            
            for (String synonym : synonyms) {
                if (expanded.toLowerCase().contains(synonym.toLowerCase())) {
                    expanded = expanded.replaceAll(
                        "(?i)" + synonym, 
                        standardTerm
                    );
                    log.debug("[SynonymService] 同义词替换: {} -> {}", synonym, standardTerm);
                }
            }
        }
        
        // ✅ 优化3：调用行业扩展点获取额外同义词
        if (conceptExtensions != null && !conceptExtensions.isEmpty() && industryConceptDictionary != null && datasourceId != null) {
            try {
                // 获取当前数据源的行业代码
                String industryCode = getIndustryCodeByDatasource(datasourceId);
                
                if (industryCode != null) {
                    // 遍历所有概念，尝试匹配用户问题中的术语
                    for (Map.Entry<String, List<String>> entry : synonymMap.entrySet()) {
                        String conceptKey = entry.getKey();
                        
                        // 调用所有扩展点收集同义词
                        Set<String> extraSynonyms = new HashSet<>();
                        for (IndustryConceptExtension extension : conceptExtensions) {
                            try {
                                List<String> synonyms = extension.suggestSynonyms(conceptKey, industryCode);
                                if (synonyms != null && !synonyms.isEmpty()) {
                                    extraSynonyms.addAll(synonyms);
                                }
                            } catch (Exception e) {
                                log.warn("[SynonymService] 扩展点{}调用失败", extension.getClass().getSimpleName(), e);
                            }
                        }
                        
                        // 应用收集到的同义词
                        for (String extraSynonym : extraSynonyms) {
                            if (expanded.toLowerCase().contains(extraSynonym.toLowerCase())) {
                                expanded = expanded.replaceAll(
                                    "(?i)" + extraSynonym,
                                    conceptKey
                                );
                                log.debug("[SynonymService] 扩展点同义词: {} -> {}", extraSynonym, conceptKey);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[SynonymService] 行业扩展点调用失败", e);
            }
        }
        
        // ✅ 优化2：启用业务术语解析
        String resolved = resolveBusinessTerms(expanded);
        
        if (!resolved.equals(query)) {
            log.info("[SynonymService] 查询扩展完成: {} -> {}", query, resolved);
        }
        
        return resolved;
    }
    
    /**
     * 兼容旧版本API（无datasourceId）
     */
    public String expandSynonyms(String query) {
        return expandSynonyms(query, null);
    }
    
    /**
     * 根据数据源ID获取行业代码
     */
    private String getIndustryCodeByDatasource(Long datasourceId) {
        if (jdbcTemplate == null) {
            return null;
        }
        
        try {
            // 先查 datasource_industry_mapping 表
            String industryCode = jdbcTemplate.queryForObject(
                "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? ORDER BY priority ASC LIMIT 1",
                String.class, datasourceId
            );
            
            if (industryCode != null) {
                return industryCode;
            }
            
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 没有映射记录，继续尝试从 business_category 推断
            log.debug("[SynonymService] 数据源 {} 没有行业映射，尝试从业务分类推断", datasourceId);
        } catch (Exception e) {
            log.warn("[SynonymService] 查询行业映射失败: {}", e.getMessage());
        }
        
        // fallback：从 business_category 推断
        try {
            String businessCategory = jdbcTemplate.queryForObject(
                "SELECT business_category FROM datasource_config WHERE id = ?",
                String.class, datasourceId
            );
            
            if (businessCategory != null) {
                // 使用简单的关键词匹配（因为 IndustryConceptDictionary 没有公开该方法）
                return matchIndustryByKeyword(businessCategory);
            }
            
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 数据源不存在或没有业务分类
            log.debug("[SynonymService] 数据源 {} 没有业务分类", datasourceId);
        } catch (Exception e) {
            log.warn("[SynonymService] 查询业务分类失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 根据业务分类关键词匹配行业代码
     */
    private String matchIndustryByKeyword(String businessCategory) {
        if (businessCategory == null) {
            return null;
        }
        
        String category = businessCategory.toLowerCase();
        
        if (category.contains("电商") || category.contains("零售") || category.contains("ecommerce")) {
            return "ecommerce";
        } else if (category.contains("金融") || category.contains("银行") || category.contains("finance")) {
            return "finance";
        } else if (category.contains("医疗") || category.contains("医院") || category.contains("medical")) {
            return "medical";
        } else if (category.contains("教育") || category.contains("培训") || category.contains("education")) {
            return "education";
        } else if (category.contains("制造") || category.contains("生产") || category.contains("manufacturing")) {
            return "manufacturing";
        }
        
        return null; // 无法匹配
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
