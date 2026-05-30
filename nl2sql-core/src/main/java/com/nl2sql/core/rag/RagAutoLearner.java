package com.nl2sql.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RAG 自动学习器
 * 功能：将验证通过的 SQL 自动保存到知识库，实现持续优化
 */
@Slf4j
@Service
public class RagAutoLearner {
    
    @Autowired(required = false)
    private RagKnowledgeBaseService ragService;
    
    @Autowired(required = false)
    private ConceptAliasExtractor conceptAliasExtractor;
    
    /**
     * 自动学习：保存成功的 SQL 到知识库
     * 
     * @param question 用户问题
     * @param sql 生成的 SQL
     * @param qualityScore 质量评分（0.0-1.0）
     * @param category 分类标签
     */
    public void learnFromSuccess(String question, String sql, double qualityScore, String category) {
        if (ragService == null) {
            log.debug("[RagAutoLearner] RAG服务未启用，跳过学习");
            return;
        }
        
        try {
            // 过滤低质量 SQL
            if (qualityScore < 0.7) {
                log.debug("[RagAutoLearner] SQL质量过低，跳过学习: quality={}", qualityScore);
                return;
            }
            
            // 检查是否已存在相似问题（避免重复学习）
            var similarItems = ragService.searchSimilarQuestions(question, 1);
            if (!similarItems.isEmpty()) {
                double similarity = similarItems.get(0).getRelevance() != null ? 
                    similarItems.get(0).getRelevance() : 0.0;
                
                if (similarity > 0.95) {
                    log.info("[RagAutoLearner] 已存在高度相似问题，更新使用次数: similarity={}", similarity);
                    // 增加已有条目的使用次数和质量评分
                    ragService.recordUsage(similarItems.get(0).getId());
                    ragService.updateQualityScore(similarItems.get(0).getId(), 0.05f);
                    return;
                }
            }
            
            // 生成 AI 回答模板
            String answerTemplate = generateAnswerTemplate(question, sql);
            
            // 保存到知识库
            Long knowledgeId = ragService.saveQAPair(
                question, 
                answerTemplate, 
                sql, 
                category != null ? category : "auto_learned",
                (float) qualityScore
            );
            
            log.info("[RagAutoLearner] ✅ 自动学习成功: id={}, question={}, quality={}", 
                knowledgeId, question, qualityScore);
            
            if (conceptAliasExtractor != null && qualityScore >= 0.8) {
                try {
                    Long datasourceId = RagLearningContext.getCurrentDatasourceId();
                    conceptAliasExtractor.extractAndSave(question, sql, datasourceId, qualityScore);
                } catch (Exception e) {
                    log.warn("[RagAutoLearner] 概念别名提取失败: {}", e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("[RagAutoLearner] 自动学习失败: question={}", question, e);
        }
    }
    
    /**
     * 从执行结果中学习（SQL 执行成功后调用）
     * 
     * @param question 用户问题
     * @param sql 执行的 SQL
     * @param rowCount 返回行数
     * @param executionTime 执行时间（毫秒）
     */
    public void learnFromExecution(String question, String sql, int rowCount, long executionTime) {
        // ✅ 关键修复：入库前进行语义一致性验证
        if (!validateSemanticConsistency(question, sql)) {
            log.warn("[RagAutoLearner] 拒绝学习语义不一致的SQL: question={}, sql={}", question, sql);
            return;
        }
        
        // 计算质量评分
        double qualityScore = calculateQualityScore(rowCount, executionTime);
        
        // 推断分类
        String category = inferCategory(question, sql);
        
        // 触发学习
        learnFromSuccess(question, sql, qualityScore, category);
    }
    
    /**
     * 计算 SQL 质量评分
     * 
     * @param rowCount 返回行数
     * @param executionTime 执行时间（毫秒）
     * @return 质量评分（0.0-1.0）
     */
    private double calculateQualityScore(int rowCount, long executionTime) {
        double score = 0.8; // 基础分（能执行成功就有80分）
        
        // 根据返回行数调整
        if (rowCount > 0 && rowCount <= 1000) {
            score += 0.1; // 返回合理数量的数据
        } else if (rowCount > 1000) {
            score -= 0.05; // 返回过多数据，可能缺少 LIMIT
        }
        
        // 根据执行时间调整
        if (executionTime < 100) {
            score += 0.1; // 快速执行
        } else if (executionTime > 5000) {
            score -= 0.1; // 执行过慢
        }
        
        return Math.min(1.0, Math.max(0.0, score));
    }
    
    /**
     * 推断 SQL 分类
     */
    private String inferCategory(String question, String sql) {
        String lowerQuestion = question.toLowerCase();
        String upperSql = sql.toUpperCase();
        
        if (upperSql.contains("SUM") || upperSql.contains("COUNT") || upperSql.contains("AVG")) {
            return "统计查询";
        }
        if (upperSql.contains("JOIN")) {
            return "多表关联";
        }
        if (lowerQuestion.contains("趋势") || lowerQuestion.contains("最近")) {
            return "趋势分析";
        }
        if (lowerQuestion.contains("排名") || lowerQuestion.contains("top")) {
            return "排名查询";
        }
        
        return "普通查询";
    }
    
    /**
     * ✅ 关键：验证SQL与问题的语义一致性
     * 
     * @param question 用户问题
     * @param sql 生成的SQL
     * @return 是否一致
     */
    private boolean validateSemanticConsistency(String question, String sql) {
        String upperSql = sql.toUpperCase();
        boolean hasGroupBy = upperSql.contains("GROUP BY");
        boolean hasAggregation = upperSql.contains("SUM(") || 
                               upperSql.contains("COUNT(") ||
                               upperSql.contains("AVG(") ||
                               upperSql.contains("MAX(") ||
                               upperSql.contains("MIN(");
        
        boolean isStatQuestion = isStatisticalQuestion(question);
        
        if (hasGroupBy && !isStatQuestion) {
            log.warn("[RagAutoLearner] 语义不一致: SQL包含GROUP BY但问题非统计类: {}", question);
            return false;
        }
        
        if (isStatQuestion && !hasAggregation && !hasGroupBy) {
            log.warn("[RagAutoLearner] 语义不一致: 问题是统计类但SQL无聚合: {}", question);
            return false;
        }
        
        if (isStatQuestion && upperSql.contains("SELECT *")) {
            log.warn("[RagAutoLearner] 语义不一致: 统计问题不应使用SELECT *: {}", question);
            return false;
        }
        
        return true;
    }
    
    private boolean isStatisticalQuestion(String question) {
        String[] statKeywords = {
            "统计", "汇总", "平均", "合计", "每个", "各", "分组",
            "每天", "每月", "每年", "每周", "每日", "每月", "每年",
            "数量", "金额", "总额", "总量", "总计", "总数",
            "多少", "几", "占比", "比例", "百分比",
            "排名", "排行", "top", "前几",
            "趋势", "变化", "增长", "下降",
            "分布", "对比", "比较", "差异",
            "最大", "最小", "最高", "最低",
            "累计", "累计", "逐", "按"
        };
        
        for (String keyword : statKeywords) {
            if (question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 生成 AI 回答模板
     */
    private String generateAnswerTemplate(String question, String sql) {
        return String.format(
            "根据您的查询「%s」，系统生成了相应的 SQL 并成功执行。\n" +
            "您可以参考以下 SQL 语句进行类似的数据查询：\n\n" +
            "```sql\n%s\n```\n\n" +
            "如需进一步分析或可视化，请告知具体需求。",
            question, sql
        );
    }
}
