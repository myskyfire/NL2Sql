package com.nl2sql.core.rag;

import com.nl2sql.core.rag.mapper.RagFeedbackMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 低分SQL示例服务
 * 用于在生成SQL前提供负面示例，避免重复错误
 */
@Slf4j
@Service
public class LowRatingExampleService {
    
    @Autowired
    private RagFeedbackMapper ragFeedbackMapper;
    
    /**
     * 查找与当前问题相似的低分示例
     * 
     * @param question 当前问题
     * @param similarityThreshold 相似度阈值（0.0-1.0）
     * @param limit 返回数量
     * @return 低分示例列表
     */
    public List<LowRatingExample> findSimilarLowRatingExamples(
            String question, double similarityThreshold, int limit) {
        
        try {
            // ✅ 使用MyBatis Mapper查询
            List<LowRatingExample> examples = ragFeedbackMapper.findSimilarLowRatingExamples(question, limit);
            
            // 过滤掉相似度低于阈值的
            return examples.stream()
                .filter(ex -> ex.getRelevance() >= similarityThreshold)
                .toList();
                
        } catch (Exception e) {
            log.error("[低分示例] 查询失败: question={}", question, e);
            return List.of();
        }
    }
    
    /**
     * 检查生成的SQL是否与历史低分SQL高度相似
     * 
     * @param question 当前问题
     * @param generatedSql 生成的SQL
     * @return 如果找到高度相似的低分示例，返回该示例；否则返回null
     */
    public LowRatingExample checkIfSimilarToLowRating(String question, String generatedSql) {
        try {
            // ✅ 使用MyBatis Mapper查询（同时匹配 question + SQL）
            return ragFeedbackMapper.checkExactMatchLowRating(question, generatedSql);
            
        } catch (Exception e) {
            log.error("[低分检查] 查询失败: question={}", question, e);
            return null;
        }
    }
    
    /**
     * 获取负面示例文本（供Tool调用）
     * 
     * @param query 用户问题
     * @return 格式化的负面示例文本，如果没有则返回空字符串
     */
    public String getNegativeExamples(String query) {
        try {
            List<LowRatingExample> badExamples = findSimilarLowRatingExamples(query, 0.85, 2);
            
            if (badExamples.isEmpty()) {
                return "";
            }
            
            log.info("[低分示例] 找到 {} 个负面示例", badExamples.size());
            StringBuilder negBuilder = new StringBuilder();
            negBuilder.append("\n⚠️ **以下SQL曾被用户评为低分，请避免类似错误：**\n\n");
            
            for (int i = 0; i < badExamples.size(); i++) {
                LowRatingExample ex = badExamples.get(i);
                negBuilder.append(String.format(
                    "**反例 %d:**\n" +
                    "问题: %s\n" +
                    "错误SQL: %s\n" +
                    "用户反馈: %s\n" +
                    "评分: %d星\n\n",
                    i + 1,
                    ex.getQuestion(),
                    ex.getGeneratedSql(),
                    ex.getFeedbackText() != null ? ex.getFeedbackText() : "未提供原因",
                    ex.getRating()
                ));
            }
            
            negBuilder.append("**请确保生成的SQL与上述错误示例完全不同！**\n\n");
            return negBuilder.toString();
            
        } catch (Exception e) {
            log.warn("[低分示例] 获取失败: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * 低分示例数据类
     */
    @Data
    public static class LowRatingExample {
        private String question;        // 原始问题
        private String generatedSql;    // 生成的SQL
        private String feedbackText;    // 用户反馈原因
        private Integer rating;         // 评分（1-2星）
        private Double relevance;       // 相似度/相关性
    }
}
