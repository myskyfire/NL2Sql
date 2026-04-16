package com.nl2sql.core.llm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行业概念词典服务
 * 
 * 提供跨行业的业务概念映射，增强NL2SQL的语义理解能力
 * 支持动态加载不同行业的专业术语，提高SQL生成的准确性
 */
@Slf4j
@Service
public class IndustryConceptDictionary {
    
    /**
     * 行业概念映射表
     * Key: 行业标识 (ecommerce/finance/medical/education/manufacturing...)
     * Value: 该行业的概念定义
     */
    private final Map<String, IndustryConcepts> industryMap = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    
    public IndustryConceptDictionary() {
        initializeDefaultIndustries();
    }
    
    /**
     * 初始化默认行业概念
     */
    private void initializeDefaultIndustries() {
        // 电商行业
        registerIndustry(buildEcommerceConcepts());
        
        // 金融行业
        registerIndustry(buildFinanceConcepts());
        
        // 医疗行业
        registerIndustry(buildMedicalConcepts());
        
        // 教育行业
        registerIndustry(buildEducationConcepts());
        
        // 制造业
        registerIndustry(buildManufacturingConcepts());
        
        log.info("[IndustryConceptDictionary] 已初始化 {} 个行业概念库", industryMap.size());
    }
    
    /**
     * 注册行业概念
     */
    public void registerIndustry(IndustryConcepts concepts) {
        industryMap.put(concepts.getIndustryCode(), concepts);
        log.info("[IndustryConceptDictionary] 注册行业: {} ({})", 
            concepts.getIndustryName(), concepts.getIndustryCode());
    }
    
    /**
     * 根据数据源获取对应的行业概念
     * 
     * @param datasourceId 数据源ID
     * @return 行业概念，如果未配置则返回通用概念
     */
    public IndustryConcepts getConceptsByDatasource(Long datasourceId) {
        if (datasourceId == null || jdbcTemplate == null) {
            return getGenericConcepts();
        }
        
        try {
            // 1. 从 datasource_industry_mapping 表查询行业代码
            String industryCode = jdbcTemplate.queryForObject(
                "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? ORDER BY priority LIMIT 1",
                String.class, datasourceId
            );
            
            if (industryCode == null) {
                // 2. fallback到 business_category 字段匹配
                String businessCategory = jdbcTemplate.queryForObject(
                    "SELECT business_category FROM datasource_config WHERE id = ?",
                    String.class, datasourceId
                );
                
                if (businessCategory != null && !businessCategory.isEmpty()) {
                    industryCode = matchIndustryCodeByCategory(businessCategory);
                }
            }
            
            // 3. 从数据库加载行业概念
            if (industryCode != null) {
                IndustryConcepts concepts = loadConceptsFromDatabase(industryCode);
                if (concepts != null) {
                    log.debug("[IndustryConceptDictionary] 数据源{}加载行业: {}", 
                        datasourceId, concepts.getIndustryName());
                    return concepts;
                }
            }
        } catch (Exception e) {
            log.warn("[IndustryConceptDictionary] 读取数据源行业配置失败: {}", e.getMessage());
        }
        
        // fallback到通用概念
        return getGenericConcepts();
    }
    
    /**
     * 从数据库加载行业概念
     */
    private IndustryConcepts loadConceptsFromDatabase(String industryCode) {
        try {
            // 查询行业基本信息
            Map<String, Object> industryInfo = jdbcTemplate.queryForMap(
                "SELECT industry_code, industry_name FROM industry_template WHERE industry_code = ? AND is_active = 1",
                industryCode
            );
            
            if (industryInfo == null) {
                return null;
            }
            
            IndustryConcepts concepts = new IndustryConcepts();
            concepts.setIndustryCode((String) industryInfo.get("industry_code"));
            concepts.setIndustryName((String) industryInfo.get("industry_name"));
            
            // 查询所有已审核的概念
            List<Map<String, Object>> conceptRows = jdbcTemplate.queryForList(
                "SELECT concept_type, concept_key, concept_aliases, description " +
                "FROM industry_concept " +
                "WHERE industry_code = ? AND status = 'approved' " +
                "ORDER BY usage_count DESC, confidence DESC",
                industryCode
            );
            
            for (Map<String, Object> row : conceptRows) {
                String type = (String) row.get("concept_type");
                String key = (String) row.get("concept_key");
                String aliasesJson = (String) row.get("concept_aliases");
                String description = (String) row.get("description");
                
                // 解析JSON别名数组
                List<String> aliases = parseAliasesJson(aliasesJson);
                String displayValue = aliases.isEmpty() ? key : String.join("/", aliases);
                
                switch (type) {
                    case "entity":
                        concepts.getBusinessEntities().put(key, displayValue);
                        break;
                    case "metric":
                        concepts.getMetrics().put(key, displayValue);
                        break;
                    case "dimension":
                        concepts.getDimensions().put(key, displayValue);
                        break;
                    case "table_role":
                        concepts.getTableRoles().put(key, description != null ? description : displayValue);
                        break;
                }
            }
            
            // ✅ 加载概念关系（用于同义词扩展）
            loadConceptRelations(concepts, industryCode);
            
            log.info("[IndustryConceptDictionary] 从数据库加载行业{}: {}个实体, {}个指标, {}个维度",
                industryCode,
                concepts.getBusinessEntities().size(),
                concepts.getMetrics().size(),
                concepts.getDimensions().size()
            );
            
            return concepts;
            
        } catch (Exception e) {
            log.error("[IndustryConceptDictionary] 从数据库加载行业概念失败: {}", industryCode, e);
            return null;
        }
    }
    
    /**
     * 加载概念关系（同义词扩展）
     */
    private void loadConceptRelations(IndustryConcepts concepts, String industryCode) {
        try {
            List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                "SELECT source_concept_key, target_concept_key, relation_type " +
                "FROM concept_relation " +
                "WHERE industry_code = ?",
                industryCode
            );
            
            for (Map<String, Object> rel : relations) {
                String source = (String) rel.get("source_concept_key");
                String target = (String) rel.get("target_concept_key");
                String type = (String) rel.get("relation_type");
                
                // 同义词关系：将target添加到source的别名中
                if ("synonym".equals(type)) {
                    // 查找source在哪个map中
                    if (concepts.getMetrics().containsKey(source)) {
                        String existing = concepts.getMetrics().get(source);
                        if (!existing.contains(target)) {
                            concepts.getMetrics().put(source, existing + "/" + target);
                        }
                    } else if (concepts.getBusinessEntities().containsKey(source)) {
                        String existing = concepts.getBusinessEntities().get(source);
                        if (!existing.contains(target)) {
                            concepts.getBusinessEntities().put(source, existing + "/" + target);
                        }
                    } else if (concepts.getDimensions().containsKey(source)) {
                        String existing = concepts.getDimensions().get(source);
                        if (!existing.contains(target)) {
                            concepts.getDimensions().put(source, existing + "/" + target);
                        }
                    }
                }
            }
            
            log.debug("[IndustryConceptDictionary] 加载{}条概念关系", relations.size());
            
        } catch (Exception e) {
            log.warn("[IndustryConceptDictionary] 加载概念关系失败: {}", e.getMessage());
        }
    }
    
    /**
     * 解析JSON格式的别名数组
     */
    private List<String> parseAliasesJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("[IndustryConceptDictionary] 解析别名JSON失败: {}", json);
            return Collections.emptyList();
        }
    }
    
    /**
     * 根据业务类别字符串匹配行业代码
     * 
     * @param businessCategory 业务类别（如："订单,交易,trade,order"）
     * @return 行业代码，无匹配则返回null
     */
    private String matchIndustryCodeByCategory(String businessCategory) {
        String category = businessCategory.toLowerCase();
        
        // 电商/零售
        if (category.contains("order") || category.contains("交易") || 
            category.contains("订单") || category.contains("sales") || 
            category.contains("销售") || category.contains("trade")) {
            return "ecommerce";
        }
        
        // 金融
        if (category.contains("finance") || category.contains("财务") || 
            category.contains("accounting") || category.contains("会计") ||
            category.contains("bank") || category.contains("银行")) {
            return "finance";
        }
        
        // 医疗
        if (category.contains("medical") || category.contains("医疗") || 
            category.contains("hospital") || category.contains("医院") ||
            category.contains("health") || category.contains("健康")) {
            return "medical";
        }
        
        // 教育
        if (category.contains("education") || category.contains("教育") || 
            category.contains("school") || category.contains("学校") ||
            category.contains("student") || category.contains("学生")) {
            return "education";
        }
        
        // 制造
        if (category.contains("manufacturing") || category.contains("制造") || 
            category.contains("production") || category.contains("生产") ||
            category.contains("inventory") || category.contains("库存")) {
            return "manufacturing";
        }
        
        return null; // 无匹配
    }
    
    /**
     * 获取通用概念（无特定行业）
     */
    public IndustryConcepts getGenericConcepts() {
        IndustryConcepts generic = new IndustryConcepts();
        generic.setIndustryCode("generic");
        generic.setIndustryName("通用");
        
        // 通用指标类型
        generic.getMetricTypes().addAll(Arrays.asList(
            "数值型指标（如金额、数量、次数、比率等）",
            "统计指标（如总和、平均值、最大值、最小值）"
        ));
        
        // 通用维度类型
        generic.getDimensionTypes().addAll(Arrays.asList(
            "时间维度（年/月/日/季度）",
            "地理维度（省/市/区/国家）",
            "分类维度（类别/类型/等级）",
            "组织维度（部门/团队/区域）"
        ));
        
        // 通用表角色
        generic.getTableRoles().put("主表", "包含核心业务数据和主要指标的表");
        generic.getTableRoles().put("维度表", "提供分类、描述信息的辅助表");
        generic.getTableRoles().put("关联表", "连接多张表的中间表（多对多关系）");
        
        return generic;
    }
    
    /**
     * 构建电商行业概念
     */
    private IndustryConcepts buildEcommerceConcepts() {
        IndustryConcepts concepts = new IndustryConcepts();
        concepts.setIndustryCode("ecommerce");
        concepts.setIndustryName("电子商务");
        
        // 核心业务实体
        concepts.getBusinessEntities().put("order", "订单/交易记录");
        concepts.getBusinessEntities().put("product", "商品/SKU/产品");
        concepts.getBusinessEntities().put("customer", "客户/买家/用户");
        concepts.getBusinessEntities().put("category", "商品分类/类目");
        
        // 关键指标
        concepts.getMetrics().put("revenue", "销售额/收入/成交金额/GMV");
        concepts.getMetrics().put("quantity", "销量/数量/件数");
        concepts.getMetrics().put("order_count", "订单数/交易量");
        concepts.getMetrics().put("conversion_rate", "转化率/下单率");
        
        // 常见维度
        concepts.getDimensions().put("region", "地区/省份/城市/区域");
        concepts.getDimensions().put("time", "时间/日期/月份/季度");
        concepts.getDimensions().put("channel", "渠道/平台/来源");
        
        // 典型查询模式
        concepts.getQueryPatterns().add("统计{metric}按{dimension}分布");
        concepts.getQueryPatterns().add("查询{entity}的{metric}趋势");
        concepts.getQueryPatterns().add("对比不同{dimension}的{metric}");
        
        return concepts;
    }
    
    /**
     * 构建金融行业概念
     */
    private IndustryConcepts buildFinanceConcepts() {
        IndustryConcepts concepts = new IndustryConcepts();
        concepts.setIndustryCode("finance");
        concepts.setIndustryName("金融服务");
        
        concepts.getBusinessEntities().put("account", "账户/客户档案/账号");
        concepts.getBusinessEntities().put("transaction", "交易/流水/转账记录");
        concepts.getBusinessEntities().put("product", "金融产品/理财/基金");
        
        concepts.getMetrics().put("balance", "余额/资产/存款");
        concepts.getMetrics().put("amount", "交易金额/流水金额");
        concepts.getMetrics().put("interest", "利息/收益/利率");
        concepts.getMetrics().put("risk_score", "风险评分/信用评级");
        
        concepts.getDimensions().put("branch", "分支机构/网点/分行");
        concepts.getDimensions().put("product_type", "产品类型/业务类型");
        concepts.getDimensions().put("customer_segment", "客户分层/客群");
        
        return concepts;
    }
    
    /**
     * 构建医疗行业概念
     */
    private IndustryConcepts buildMedicalConcepts() {
        IndustryConcepts concepts = new IndustryConcepts();
        concepts.setIndustryCode("medical");
        concepts.setIndustryName("医疗健康");
        
        concepts.getBusinessEntities().put("patient", "患者/就诊人/病人");
        concepts.getBusinessEntities().put("visit", "就诊记录/门诊/住院");
        concepts.getBusinessEntities().put("diagnosis", "诊断/病历/病情");
        concepts.getBusinessEntities().put("prescription", "处方/用药/药品");
        
        concepts.getMetrics().put("visit_count", "就诊次数/门诊量");
        concepts.getMetrics().put("cost", "医疗费用/药费/检查费");
        concepts.getMetrics().put("bed_occupancy", "床位使用率/住院率");
        
        concepts.getDimensions().put("department", "科室/部门/专科");
        concepts.getDimensions().put("disease_type", "病种/疾病类型");
        concepts.getDimensions().put("doctor", "医生/医师/专家");
        
        return concepts;
    }
    
    /**
     * 构建教育行业概念
     */
    private IndustryConcepts buildEducationConcepts() {
        IndustryConcepts concepts = new IndustryConcepts();
        concepts.setIndustryCode("education");
        concepts.setIndustryName("教育培训");
        
        concepts.getBusinessEntities().put("student", "学生/学员/在校生");
        concepts.getBusinessEntities().put("course", "课程/培训班/科目");
        concepts.getBusinessEntities().put("enrollment", "报名/注册/入学");
        concepts.getBusinessEntities().put("grade", "成绩/分数/等级");
        
        concepts.getMetrics().put("enrollment_count", "报名人数/招生数");
        concepts.getMetrics().put("completion_rate", "完成率/结业率");
        concepts.getMetrics().put("average_score", "平均分/及格率");
        
        concepts.getDimensions().put("semester", "学期/学年/季度");
        concepts.getDimensions().put("major", "专业/学科/方向");
        concepts.getDimensions().put("campus", "校区/分院/教学点");
        
        return concepts;
    }
    
    /**
     * 构建制造业概念
     */
    private IndustryConcepts buildManufacturingConcepts() {
        IndustryConcepts concepts = new IndustryConcepts();
        concepts.setIndustryCode("manufacturing");
        concepts.setIndustryName("制造业");
        
        concepts.getBusinessEntities().put("production_order", "生产订单/工单");
        concepts.getBusinessEntities().put("material", "原材料/物料/零部件");
        concepts.getBusinessEntities().put("equipment", "设备/生产线/机器");
        concepts.getBusinessEntities().put("quality_check", "质检记录/检测报告");
        
        concepts.getMetrics().put("output", "产量/产出量/产能");
        concepts.getMetrics().put("defect_rate", "次品率/不良率/合格率");
        concepts.getMetrics().put("utilization", "设备利用率/稼动率");
        
        concepts.getDimensions().put("workshop", "车间/工厂/产线");
        concepts.getDimensions().put("shift", "班次/工作日/时间段");
        concepts.getDimensions().put("product_line", "产品线/产品系列");
        
        return concepts;
    }
    
    /**
     * 生成提示词片段：指标类型说明
     */
    public String generateMetricDescription(Long datasourceId) {
        IndustryConcepts concepts = getConceptsByDatasource(datasourceId);
        
        if (concepts.getMetrics().isEmpty()) {
            return "数值型指标（如金额、数量、次数、比率等）";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("数值型指标，例如：");
        concepts.getMetrics().values().stream()
            .limit(5)
            .forEach(v -> sb.append(v).append("、"));
        sb.delete(sb.length() - 1, sb.length()); // 删除最后一个顿号
        
        return sb.toString();
    }
    
    /**
     * 生成提示词片段：维度类型说明
     */
    public String generateDimensionDescription(Long datasourceId) {
        IndustryConcepts concepts = getConceptsByDatasource(datasourceId);
        
        if (concepts.getDimensions().isEmpty()) {
            return "分析维度（如时间、地区、分类等）";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("分析维度，例如：");
        concepts.getDimensions().values().stream()
            .limit(5)
            .forEach(v -> sb.append(v).append("、"));
        sb.delete(sb.length() - 1, sb.length());
        
        return sb.toString();
    }
    
    /**
     * 生成提示词片段：表角色说明
     */
    public String generateTableRoleDescription(Long datasourceId) {
        IndustryConcepts concepts = getConceptsByDatasource(datasourceId);
        
        if (concepts.getTableRoles().isEmpty()) {
            return "主表（包含核心业务数据）、维度表（提供分类信息）、关联表（连接多表）";
        }
        
        StringBuilder sb = new StringBuilder();
        concepts.getTableRoles().forEach((role, desc) -> {
            sb.append(role).append("（").append(desc).append("）、");
        });
        sb.delete(sb.length() - 2, sb.length()); // 删除最后的顿号和空格
        
        return sb.toString();
    }
    
    /**
     * 获取所有已注册的行业列表
     */
    public List<IndustryInfo> getAllIndustries() {
        List<IndustryInfo> industries = new ArrayList<>();
        industryMap.forEach((code, concepts) -> {
            IndustryInfo info = new IndustryInfo();
            info.setCode(code);
            info.setName(concepts.getIndustryName());
            info.setEntityCount(concepts.getBusinessEntities().size());
            info.setMetricCount(concepts.getMetrics().size());
            industries.add(info);
        });
        return industries;
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 行业概念定义
     */
    @Data
    public static class IndustryConcepts {
        private String industryCode;
        private String industryName;
        
        // 业务实体映射：英文key → 中文别名
        private Map<String, String> businessEntities = new HashMap<>();
        
        // 指标映射：英文key → 中文别名
        private Map<String, String> metrics = new HashMap<>();
        
        // 维度映射：英文key → 中文别名
        private Map<String, String> dimensions = new HashMap<>();
        
        // 表角色说明
        private Map<String, String> tableRoles = new HashMap<>();
        
        // 典型查询模式
        private List<String> queryPatterns = new ArrayList<>();
        
        // 通用字段（用于fallback）
        private List<String> metricTypes = new ArrayList<>();
        private List<String> dimensionTypes = new ArrayList<>();
    }
    
    /**
     * 行业简要信息
     */
    @Data
    public static class IndustryInfo {
        private String code;
        private String name;
        private int entityCount;
        private int metricCount;
    }
}
