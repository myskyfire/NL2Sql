package com.nl2sql.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.IndustryConceptDictionary;
import com.nl2sql.metadata.mapper.IndustryConceptAdminMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ConceptAliasExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern FROM_PATTERN = Pattern.compile(
        "FROM\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGG_PATTERN = Pattern.compile(
        "(SUM|COUNT|AVG|MAX|MIN)\\s*\\(\\s*([\\w.*]+)\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY_PATTERN = Pattern.compile(
        "GROUP\\s+BY\\s+([\\w\\s,.`]+?)(?:ORDER|HAVING|LIMIT|WHERE|$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> GENERIC_PHRASES = Set.of(
        "查询", "查看", "帮我", "显示", "列出", "统计", "分析", "数据", "信息", "详情",
        "所有", "全部", "一下", "看看", "什么", "怎么", "如何", "多少", "几个"
    );

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private IndustryConceptAdminMapper adminMapper;

    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;

    @Autowired(required = false)
    private IndustryConceptDictionary dictionary;

    public void extractAndSave(String question, String sql, Long datasourceId, double qualityScore) {
        if (question == null || question.length() < 3 || isGenericQuestion(question)) {
            return;
        }
        if (jdbcTemplate == null) {
            return;
        }

        try {
            String industryCode = resolveIndustryCode(datasourceId);
            if (industryCode == null) {
                return;
            }

            SqlStructure structure = parseSqlStructure(sql);
            if (structure == null) {
                return;
            }

            MatchResult match = matchExistingConcept(question, structure, industryCode);

            if (match.isMatched()) {
                saveAliasToLearning(match.getConceptKey(), question, sql,
                    industryCode, match.getMatchMethod(), match.getMatchScore(), qualityScore);
            }
        } catch (Exception e) {
            log.warn("[ConceptAliasExtractor] extractAndSave failed: {}", e.getMessage());
        }
    }

    private boolean isGenericQuestion(String question) {
        String trimmed = question.trim();
        if (trimmed.length() <= 2) {
            return true;
        }
        for (String phrase : GENERIC_PHRASES) {
            if (trimmed.equals(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String resolveIndustryCode(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        try {
            if (adminMapper != null) {
                String code = adminMapper.selectIndustryCodeByDatasourceId(datasourceId);
                if (code != null) {
                    return code;
                }
                String category = adminMapper.selectBusinessCategory(datasourceId);
                if (category != null) {
                    return matchIndustryCodeByCategory(category);
                }
            } else {
                String code = jdbcTemplate.queryForObject(
                    "SELECT industry_code FROM datasource_industry_mapping WHERE datasource_id = ? ORDER BY priority LIMIT 1",
                    String.class, datasourceId);
                if (code != null) {
                    return code;
                }
            }
        } catch (Exception e) {
            log.debug("[ConceptAliasExtractor] resolveIndustryCode failed: {}", e.getMessage());
        }
        return "ecommerce";
    }

    private String matchIndustryCodeByCategory(String businessCategory) {
        if (businessCategory == null) {
            return null;
        }
        String cat = businessCategory.toLowerCase();
        if (cat.contains("order") || cat.contains("交易") || cat.contains("订单") ||
            cat.contains("sales") || cat.contains("销售")) {
            return "ecommerce";
        }
        if (cat.contains("finance") || cat.contains("财务") || cat.contains("银行")) {
            return "finance";
        }
        if (cat.contains("medical") || cat.contains("医疗") || cat.contains("医院")) {
            return "medical";
        }
        if (cat.contains("education") || cat.contains("教育")) {
            return "education";
        }
        if (cat.contains("manufacturing") || cat.contains("制造") || cat.contains("生产")) {
            return "manufacturing";
        }
        return null;
    }

    SqlStructure parseSqlStructure(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }

        SqlStructure s = new SqlStructure();

        Matcher fromMatcher = FROM_PATTERN.matcher(sql);
        if (fromMatcher.find()) {
            String table = fromMatcher.group(1);
            if (table.contains(".")) {
                table = table.substring(table.lastIndexOf('.') + 1);
            }
            s.setMainTable(table);
        }

        Matcher aggMatcher = AGG_PATTERN.matcher(sql);
        while (aggMatcher.find()) {
            String func = aggMatcher.group(1).toUpperCase();
            String field = aggMatcher.group(2);
            s.getAggregates().add(func + "(" + field + ")");
        }

        Matcher gbMatcher = GROUP_BY_PATTERN.matcher(sql);
        if (gbMatcher.find()) {
            String gbClause = gbMatcher.group(1).trim();
            s.setGroupByFields(Arrays.asList(gbClause.split("\\s*,\\s*")));
        }

        s.setHasJoin(sql.toUpperCase().contains("JOIN"));

        return s;
    }

    private MatchResult matchExistingConcept(String question, SqlStructure structure, String industryCode) {
        MatchResult sqlMatch = matchBySqlStructure(structure, industryCode);
        if (sqlMatch.isMatched()) {
            return sqlMatch;
        }

        MatchResult vectorMatch = matchByVectorSimilarity(question, industryCode);
        if (vectorMatch.isMatched()) {
            return vectorMatch;
        }

        return MatchResult.notMatched();
    }

    private MatchResult matchBySqlStructure(SqlStructure structure, String industryCode) {
        List<Map<String, Object>> concepts;
        try {
            if (adminMapper != null) {
                concepts = adminMapper.selectApprovedConceptsByType(industryCode);
            } else {
                concepts = jdbcTemplate.queryForList(
                    "SELECT concept_key, concept_aliases, description, concept_type " +
                    "FROM industry_concept " +
                    "WHERE industry_code = ? AND status = 'approved' AND concept_type IN ('metric','entity')",
                    industryCode);
            }
        } catch (Exception e) {
            log.warn("[ConceptAliasExtractor] query concepts failed: {}", e.getMessage());
            return MatchResult.notMatched();
        }

        for (Map<String, Object> concept : concepts) {
            String conceptKey = (String) concept.get("concept_key");
            String description = (String) concept.get("description");
            String aliasesJson = (String) concept.get("concept_aliases");

            if (description != null && structure.getAggregates() != null) {
                for (String agg : structure.getAggregates()) {
                    String normalizedAgg = agg.toLowerCase().replace(" ", "");
                    String normalizedDesc = description.toLowerCase().replace(" ", "");

                    if (normalizedDesc.contains(normalizedAgg) ||
                        matchesAggregationToDescription(agg, description, structure.getMainTable())) {
                        return MatchResult.matched(conceptKey, "sql_structure", 0.9);
                    }
                }
            }

            if (aliasesJson != null && structure.getMainTable() != null) {
                List<String> aliases = parseAliasesJson(aliasesJson);
                for (String alias : aliases) {
                    if (alias.equalsIgnoreCase(structure.getMainTable())) {
                        return MatchResult.matched(conceptKey, "sql_structure", 0.85);
                    }
                }
            }
        }

        return MatchResult.notMatched();
    }

    private boolean matchesAggregationToDescription(String aggregate, String description, String mainTable) {
        String aggLower = aggregate.toLowerCase();
        String descLower = description.toLowerCase();

        if (aggLower.startsWith("sum(") && (descLower.contains("总和") || descLower.contains("总额") ||
            descLower.contains("金额") || descLower.contains("总量") || descLower.contains("合计"))) {
            return true;
        }
        if (aggLower.startsWith("count(") && (descLower.contains("数量") || descLower.contains("总数") ||
            descLower.contains("计数") || descLower.contains("个数"))) {
            return true;
        }
        if (aggLower.startsWith("avg(") && (descLower.contains("平均") || descLower.contains("均值"))) {
            return true;
        }
        if (aggLower.startsWith("max(") && (descLower.contains("最大") || descLower.contains("最高") ||
            descLower.contains("峰值"))) {
            return true;
        }
        if (aggLower.startsWith("min(") && (descLower.contains("最小") || descLower.contains("最低"))) {
            return true;
        }

        if (mainTable != null && descLower.contains(mainTable.toLowerCase())) {
            return true;
        }

        return false;
    }

    private MatchResult matchByVectorSimilarity(String question, String industryCode) {
        if (ragService == null) {
            return MatchResult.notMatched();
        }

        try {
            var similarItems = ragService.searchSimilarQuestions(question, 3);
            if (similarItems == null || similarItems.isEmpty()) {
                return MatchResult.notMatched();
            }

            for (var item : similarItems) {
                double score = item.getRelevance() != null ? item.getRelevance() : 0.0;
                if (score >= 0.85) {
                    String matchedQuestion = item.getQuestion();
                    String conceptKey = findConceptKeyByAlias(matchedQuestion, industryCode);
                    if (conceptKey != null) {
                        return MatchResult.matched(conceptKey, "vector_similarity", score);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ConceptAliasExtractor] vector similarity match failed: {}", e.getMessage());
        }

        return MatchResult.notMatched();
    }

    private String findConceptKeyByAlias(String text, String industryCode) {
        try {
            List<Map<String, Object>> concepts;
            if (adminMapper != null) {
                concepts = adminMapper.selectApprovedConcepts(industryCode);
            } else {
                concepts = jdbcTemplate.queryForList(
                    "SELECT concept_key, concept_aliases FROM industry_concept " +
                    "WHERE industry_code = ? AND status = 'approved'",
                    industryCode);
            }

            for (Map<String, Object> concept : concepts) {
                String conceptKey = (String) concept.get("concept_key");
                String aliasesJson = (String) concept.get("concept_aliases");
                List<String> aliases = parseAliasesJson(aliasesJson);

                if (conceptKey.equals(text)) {
                    return conceptKey;
                }
                for (String alias : aliases) {
                    if (alias.equals(text) || text.contains(alias) || alias.contains(text)) {
                        return conceptKey;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ConceptAliasExtractor] findConceptKeyByAlias failed: {}", e.getMessage());
        }
        return null;
    }

    private void saveAliasToLearning(String conceptKey, String newAlias, String sql,
                                      String industryCode, String matchMethod,
                                      double matchScore, double qualityScore) {
        try {
            List<String> existingAliases = getExistingAliases(industryCode, conceptKey);
            if (existingAliases.contains(newAlias)) {
                log.debug("[ConceptAliasExtractor] alias already exists: {}", newAlias);
                return;
            }

            boolean alreadyPending = checkPendingAlias(industryCode, conceptKey, newAlias);
            if (alreadyPending) {
                log.debug("[ConceptAliasExtractor] alias already pending: {}", newAlias);
                return;
            }

            jdbcTemplate.update(
                "INSERT INTO concept_alias_learning " +
                "(industry_code, concept_key, new_alias, source_sql, source_question, " +
                "quality_score, match_method, match_score, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending') " +
                "ON DUPLICATE KEY UPDATE source_sql = VALUES(source_sql), " +
                "quality_score = VALUES(quality_score), updated_at = NOW()",
                industryCode, conceptKey, newAlias, sql, null,
                qualityScore, matchMethod, matchScore
            );

            log.info("[ConceptAliasExtractor] new alias pending: conceptKey={}, alias={}, method={}, score={}",
                conceptKey, newAlias, matchMethod, matchScore);

        } catch (Exception e) {
            log.warn("[ConceptAliasExtractor] saveAliasToLearning failed: {}", e.getMessage());
        }
    }

    private List<String> getExistingAliases(String industryCode, String conceptKey) {
        try {
            Map<String, Object> concept = null;
            if (adminMapper != null) {
                concept = adminMapper.selectConceptByIndustryAndKey(industryCode, conceptKey);
            } else {
                List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    "SELECT concept_aliases FROM industry_concept " +
                    "WHERE industry_code = ? AND concept_key = ? AND status = 'approved'",
                    industryCode, conceptKey);
                if (!results.isEmpty()) {
                    concept = results.get(0);
                }
            }

            if (concept != null) {
                return parseAliasesJson((String) concept.get("concept_aliases"));
            }
        } catch (Exception e) {
            log.debug("[ConceptAliasExtractor] getExistingAliases failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private boolean checkPendingAlias(String industryCode, String conceptKey, String newAlias) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM concept_alias_learning " +
                "WHERE industry_code = ? AND concept_key = ? AND new_alias = ? AND status = 'pending'",
                Integer.class, industryCode, conceptKey, newAlias);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> parseAliasesJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Data
    static class SqlStructure {
        private String mainTable;
        private List<String> fields = new ArrayList<>();
        private List<String> aggregates = new ArrayList<>();
        private List<String> groupByFields = new ArrayList<>();
        private boolean hasJoin;
    }

    @Data
    static class MatchResult {
        private boolean matched;
        private String conceptKey;
        private String matchMethod;
        private double matchScore;

        static MatchResult matched(String conceptKey, String matchMethod, double matchScore) {
            MatchResult r = new MatchResult();
            r.setMatched(true);
            r.setConceptKey(conceptKey);
            r.setMatchMethod(matchMethod);
            r.setMatchScore(matchScore);
            return r;
        }

        static MatchResult notMatched() {
            MatchResult r = new MatchResult();
            r.setMatched(false);
            return r;
        }
    }
}
