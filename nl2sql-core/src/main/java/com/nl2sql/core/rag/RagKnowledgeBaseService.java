package com.nl2sql.core.rag;

import com.nl2sql.core.rag.dto.BatchImportResult;
import com.nl2sql.core.rag.dto.QAImportRequest;
import com.nl2sql.core.rag.dto.RagQAPairDTO;
import com.nl2sql.core.rag.mapper.RagKnowledgeBaseServiceMapper;
import com.nl2sql.core.rag.provider.VectorSearchResult;
import com.nl2sql.core.rag.provider.VectorStoreManager;
import com.nl2sql.core.rag.provider.VectorStoreProvider;
import com.nl2sql.core.rerank.Reranker;
import com.nl2sql.core.tracing.TraceSpan;
import com.nl2sql.core.tracing.TracingService;
import com.nl2sql.core.tracing.TracingContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagKnowledgeBaseService {
    
    private final JdbcTemplate jdbcTemplate;
    private final VectorStoreManager vectorStoreManager;
    
    @Autowired
    private RagKnowledgeBaseServiceMapper ragMapper;
    
    @Autowired(required = false)
    private Reranker reranker;  // Reranker 重排序服务
    
    @Autowired(required = false)
    private TracingService tracingService;
    
    private static final double SIMILARITY_THRESHOLD = 0.9;
    private static final int MAX_EXAMPLES = 5;  // ✅ 电商场景推荐 5 个示例
    private static final int RERANK_CANDIDATE_COUNT = 20;  // ✅ Rerank 候选数量（top-k * 4）
    
    public RagKnowledgeBaseService(JdbcTemplate jdbcTemplate,
                                   VectorStoreManager vectorStoreManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreManager = vectorStoreManager;
    }
    
    /**
     * 保存问答对到知识库（多提供者架构）
     */
    public Long saveQAPair(String question, String answer, String sqlExample, 
                          String category, float qualityScore) {
        // 1. 保存到MySQL（持久化）- 使用 MyBatis
        RagQAPairDTO qaPair = new RagQAPairDTO();
        qaPair.setQuestion(question);
        qaPair.setAnswer(answer);
        qaPair.setSqlExample(sqlExample);
        qaPair.setCategory(category);
        qaPair.setQualityScore(qualityScore);
        
        ragMapper.insertQAPair(qaPair);
        
        // MyBatis useGeneratedKeys 会自动填充 ID 到对象
        Long id = qaPair.getId();
        
        // 2. 同步到向量数据库（使用活跃提供者）
        VectorStoreProvider activeProvider = vectorStoreManager.getActiveProvider();
        if (activeProvider != null) {
            try {
                String docId = activeProvider.addKnowledge(question, answer, sqlExample, category, qualityScore);
                log.info("RAG知识已同步到{}: id={}, docId={}", activeProvider.getName(), id, docId);
            } catch (Exception e) {
                log.warn("向量数据库同步失败({}): {}", activeProvider.getName(), e.getMessage());
            }
        } else {
            log.warn("没有可用的向量数据库提供者，仅保存到MySQL");
        }
        
        log.info("保存RAG问答对: id={}, question={}, quality={}", id, question, qualityScore);
        return id;
    }
    
    /**
     * 检索相似的问答对（多提供者架构，自动降级 + Reranker 精排）
     */
    public List<KnowledgeItem> searchSimilarQuestions(String question, int maxResults) {
        TraceSpan run = startRetrieverTrace(question, maxResults);
        try {
            List<KnowledgeItem> results = doSearchSimilarQuestions(question, maxResults);
            endRetrieverTrace(run, results, null);
            return results;
        } catch (Exception e) {
            endRetrieverTrace(run, null, e.getMessage());
            throw e;
        }
    }

    private List<KnowledgeItem> doSearchSimilarQuestions(String question, int maxResults) {
        VectorStoreProvider activeProvider = vectorStoreManager.getActiveProvider();
        
        if (activeProvider != null) {
            try {
                int candidateCount = (reranker != null && reranker.isAvailable()) 
                    ? RERANK_CANDIDATE_COUNT 
                    : maxResults;
                
                List<VectorSearchResult> candidates = 
                    activeProvider.searchSimilar(question, candidateCount, 0.7);
                
                if (candidates.isEmpty()) {
                    log.debug("{}向量搜索无结果，降级到MySQL", activeProvider.getName());
                    return searchByMySQL(question, maxResults);
                }
                
                if (reranker != null && reranker.isAvailable()) {
                    log.info("[RAG-Rerank] 启动重排序: provider={}, candidates={}", 
                        reranker.getName(), candidates.size());
                    
                    List<String> candidateDocs = candidates.stream()
                        .map(VectorSearchResult::getQuestion)
                        .collect(java.util.stream.Collectors.toList());
                    
                    List<Reranker.RerankedDocument> reranked = reranker.rerank(question, candidateDocs);
                    
                    if (!reranked.isEmpty()) {
                        log.info("[RAG-Rerank] 重排序完成: top_score={}", 
                            String.format("%.3f", reranked.get(0).getRelevanceScore()));
                        
                        int topK = Math.min(maxResults, reranked.size());
                        List<VectorSearchResult> finalResults = new ArrayList<>();
                        
                        for (int i = 0; i < topK; i++) {
                            Reranker.RerankedDocument doc = reranked.get(i);
                            VectorSearchResult original = candidates.stream()
                                .filter(c -> c.getQuestion().equals(doc.getContent()))
                                .findFirst()
                                .orElse(null);
                            
                            if (original != null) {
                                original.setScore(doc.getRelevanceScore());
                                finalResults.add(original);
                            }
                        }
                        
                        log.info("[RAG-Rerank] 最终返回 {} 条结果", finalResults.size());
                        return convertFromProviderResults(finalResults);
                    } else {
                        log.warn("[RAG-Rerank] 重排序失败，使用原始向量排序");
                    }
                }
                
                log.info("RAG检索成功({}): question={}, found={} items", 
                    activeProvider.getName(), question, candidates.size());
                return convertFromProviderResults(candidates.subList(0, Math.min(maxResults, candidates.size())));
                
            } catch (Exception e) {
                log.warn("{}向量搜索失败，降级到MySQL全文检索: {}", 
                    activeProvider.getName(), e.getMessage());
            }
        } else {
            log.debug("没有可用的向量数据库提供者，直接使用MySQL全文检索");
        }
        
        return searchByMySQL(question, maxResults);
    }

    private TraceSpan startRetrieverTrace(String question, int maxResults) {
        if (tracingService == null || !tracingService.isEnabled()) return null;
        try {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("question", question);
            inputs.put("maxResults", maxResults);
            return tracingService.traceRetriever("RAG检索", inputs, TracingContext.currentRunId());
        } catch (Exception e) {
            log.debug("[LangSmith] startRetrieverTrace 失败: {}", e.getMessage());
            return null;
        }
    }

    private void endRetrieverTrace(TraceSpan run, List<KnowledgeItem> results, String error) {
        if (tracingService == null || run == null) return;
        try {
            Map<String, Object> outputs = new HashMap<>();
            outputs.put("resultCount", results != null ? results.size() : 0);
            if (results != null && !results.isEmpty()) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 0; i < Math.min(3, results.size()); i++) {
                    KnowledgeItem item = results.get(i);
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("question", item.getQuestion());
                    itemMap.put("score", item.getRelevance());
                    items.add(itemMap);
                }
                outputs.put("topResults", items);
            }
            tracingService.endRun(run, outputs, error);
        } catch (Exception e) {
            log.debug("[LangSmith] endRetrieverTrace 失败: {}", e.getMessage());
        }
    }
    
    /**
     * 构建RAG增强文本（供Tool调用）
     * 
     * @param query 用户问题
     * @return RAG增强文本，如果没有相似示例则返回空字符串
     */
    public String buildRAGEnhancement(String query) {
        try {
            List<KnowledgeItem> similarItems = searchSimilarQuestions(query, 1);
            
            if (similarItems.isEmpty()) {
                log.debug("[RAG] 未找到相似示例");
                return "";
            }
            
            StringBuilder ragBuilder = new StringBuilder();
            ragBuilder.append("\n\n参考示例（历史成功案例，请借鉴其JOIN方式和字段选择）:\n");
            
            int validCount = 0;
            for (KnowledgeItem item : similarItems) {
                // 过滤包含GROUP BY但非统计类问题的示例
                if (item.getSqlExample() != null && !item.getSqlExample().isEmpty()) {
                    String sql = item.getSqlExample().toUpperCase();
                    boolean hasGroupBy = sql.contains("GROUP BY");
                    boolean isStatQuestion = item.getQuestion().contains("统计") || 
                                           item.getQuestion().contains("汇总") ||
                                           item.getQuestion().contains("平均") ||
                                           item.getQuestion().contains("合计");
                    
                    if (hasGroupBy && !isStatQuestion) {
                        log.warn("[RAG] 跳过错误的示例: question={}, reason=非统计问题但包含GROUP BY", 
                            item.getQuestion());
                        continue;
                    }
                }
                
                ragBuilder.append(String.format("\n示例%d:\n", ++validCount));
                ragBuilder.append("问题: ").append(item.getQuestion()).append("\n");
                if (item.getSqlExample() != null && !item.getSqlExample().isEmpty()) {
                    ragBuilder.append("SQL: ").append(item.getSqlExample()).append("\n");
                }
            }
            
            if (validCount > 0) {
                log.info("[RAG] 有效示例数量: {}", validCount);
                return ragBuilder.toString();
            }
            
            return "";
            
        } catch (Exception e) {
            log.warn("[RAG] 构建增强文本失败: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * MySQL全文检索（降级方案）
     */
    private List<KnowledgeItem> searchByMySQL(String question, int maxResults) {
        // ✅ 关键优化：结合用户反馈评分调整排序权重
        // 使用 MyBatis Mapper
        List<KnowledgeItem> results = ragMapper.searchByFullText(question, 0.8f, maxResults);
        
        if (!results.isEmpty()) {
            log.info("RAG检索成功(MySQL+Feedback): question={}, found={} items", question, results.size());
        } else {
            log.debug("RAG未找到相似问题: question={}", question);
        }
        
        return results;
    }
    
    /**
     * 转换Provider结果为KnowledgeItem（过滤低质量条目）
     */
    private List<KnowledgeItem> convertFromProviderResults(List<VectorSearchResult> providerResults) {
        List<KnowledgeItem> items = new ArrayList<>();
        
        for (VectorSearchResult result : providerResults) {
            // ✅ 关键修复：过滤低质量示例（quality_score < 0.8）
            // 原因：1星反馈会导致quality_score降至0.7以下，但仍可能被检索到
            if (result.getQualityScore() != null && result.getQualityScore() < 0.8f) {
                log.warn("[RAG-Chroma] 过滤低质量示例: question={}, qualityScore={}", 
                    result.getQuestion(), result.getQualityScore());
                continue;
            }
            
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion(result.getQuestion());
            item.setAnswer(result.getAnswer());
            item.setSqlExample(result.getSqlExample());
            item.setCategory(result.getCategory());
            item.setRelevance(result.getScore());
            item.setQualityScore(result.getQualityScore() != null ? result.getQualityScore() : 0.9f);
            item.setUsageCount(0);
            items.add(item);
        }
        
        return items;
    }
    
    /**
     * 记录使用（增加使用次数）
     */
    public void recordUsage(Long knowledgeId) {
        ragMapper.incrementUsageCount(knowledgeId);
    }
    
    /**
     * 更新质量评分（基于用户反馈）
     */
    public void updateQualityScore(Long knowledgeId, float scoreChange) {
        // 获取当前分数
        KnowledgeItem item = ragMapper.getHighQualitySamples(null, 1).stream()
            .filter(i -> i.getId().equals(knowledgeId))
            .findFirst()
            .orElse(null);
        
        if (item != null) {
            float newScore = Math.min(1.0f, Math.max(0.0f, item.getQualityScore() + scoreChange));
            ragMapper.updateQualityScore(knowledgeId, newScore);
        }
        
        log.debug("更新RAG质量评分: id={}, change={}", knowledgeId, scoreChange);
    }
    
    /**
     * 获取高质量样本（用于few-shot学习）
     */
    public List<KnowledgeItem> getHighQualitySamples(String category, int limit) {
        return ragMapper.getHighQualitySamples(category, limit);
    }
    
    /**
     * 删除低质量样本
     */
    public void removeLowQualitySamples(float threshold) {
        // 先查询低质量条目
        List<KnowledgeItem> lowQualityItems = ragMapper.getLowQualityItems(threshold);
        
        if (!lowQualityItems.isEmpty()) {
            List<Long> ids = lowQualityItems.stream()
                .map(KnowledgeItem::getId)
                .collect(java.util.stream.Collectors.toList());
            
            int deleted = ragMapper.batchDelete(ids);
            log.info("清理低质量RAG样本: threshold={}, deleted={}", threshold, deleted);
        }
    }
    
    /**
     * 清空知识库（危险操作）
     * @return 删除的记录数
     */
    public int clearAllKnowledge() {
        try {
            // 1. 获取总数
            Long totalCount = ragMapper.getTotalCount();
            
            // 2. 清空MySQL表 - 需要添加 truncate 方法到 Mapper
            String sql = "DELETE FROM rag_knowledge_base";
            int deleted = jdbcTemplate.update(sql);
            
            // 3. 清空向量数据库（如果有活跃提供者）
            VectorStoreProvider activeProvider = vectorStoreManager.getActiveProvider();
            if (activeProvider != null) {
                try {
                    activeProvider.clearAll();
                    log.info("已清空{}向量数据库", activeProvider.getName());
                } catch (Exception e) {
                    log.warn("清空向量数据库失败({}): {}", activeProvider.getName(), e.getMessage());
                }
            }
            
            log.warn("⚠️ RAG知识库已全部清空: deleted={}条", deleted);
            return deleted;
        } catch (Exception e) {
            log.error("清空RAG知 识库失败", e);
            throw new RuntimeException("清空失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 批量导入QA对（用于手动录入生产环境SQL）
     */
    public BatchImportResult batchImportQAPairs(List<QAImportRequest> requests) {
        BatchImportResult result = new BatchImportResult();
        
        if (requests == null || requests.isEmpty()) {
            log.warn("批量导入请求为空");
            return result;
        }
        
        log.info("开始批量导入QA对: count={}", requests.size());
        
        for (int i = 0; i < requests.size(); i++) {
            QAImportRequest request = requests.get(i);
            try {
                // 参数校验
                if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                    result.addFailure("第" + (i + 1) + "条：问题不能为空");
                    continue;
                }
                if (request.getSql() == null || request.getSql().trim().isEmpty()) {
                    result.addFailure("第" + (i + 1) + "条：SQL不能为空");
                    continue;
                }
                
                // 生成AI回答（如果未提供）
                String answer = request.getAnswer();
                if (answer == null || answer.trim().isEmpty()) {
                    answer = generateAnswerTemplate(request.getQuestion(), request.getSql());
                }
                
                // 设置默认值
                String category = request.getCategory() != null ? request.getCategory() : "manual_imported";
                float qualityScore = request.getQualityScore() != null ? request.getQualityScore() : 0.9f;
                
                // 保存QA对
                Long id = saveQAPair(
                    request.getQuestion(),
                    answer,
                    request.getSql(),
                    category,
                    qualityScore
                );
                
                result.addSuccess();
                log.debug("导入成功 [{}/{}]: id={}, question={}", 
                    i + 1, requests.size(), id, request.getQuestion());
                
            } catch (Exception e) {
                String errorMsg = String.format("第%d条导入失败: %s", i + 1, e.getMessage());
                result.addFailure(errorMsg);
                log.error(errorMsg, e);
            }
        }
        
        log.info("批量导入完成: success={}, failed={}", 
            result.getSuccessCount(), result.getFailedCount());
        
        return result;
    }
    
    /**
     * 生成AI回答模板
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
    
    /**
     * 获取知识库统计信息
     */
    public java.util.Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // 总知识条目数
            Long totalCount = ragMapper.getTotalCount();
            stats.put("totalCount", totalCount != null ? totalCount : 0);
            
            // 平均质量评分 - 保留 jdbcTemplate（复杂聚合查询）
            Double avgQuality = jdbcTemplate.queryForObject(
                "SELECT AVG(quality_score) FROM rag_knowledge_base", Double.class
            );
            stats.put("avgQuality", avgQuality != null ? String.format("%.2f", avgQuality) : "0.00");
            
            // 本月新增数量 - 保留 jdbcTemplate（日期函数）
            Integer monthlyAdded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_knowledge_base WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 MONTH)",
                Integer.class
            );
            stats.put("monthlyAdded", monthlyAdded != null ? monthlyAdded : 0);
            
            // 用户反馈总数 - 保留 jdbcTemplate（跨表查询）
            Long feedbackCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_feedback", Long.class
            );
            stats.put("feedbackCount", feedbackCount != null ? feedbackCount : 0);
            
            log.info("RAG统计信息查询成功: {}", stats);
            
        } catch (Exception e) {
            log.error("获取RAG统计信息失败", e);
            stats.put("totalCount", 0);
            stats.put("avgQuality", "0.00");
            stats.put("monthlyAdded", 0);
            stats.put("feedbackCount", 0);
        }
        
        return stats;
    }
    
    /**
     * 查询知识库列表（支持搜索、分页）
     */
    public java.util.List<KnowledgeItem> queryKnowledgeList(String keyword, int offset, int limit) {
        StringBuilder sqlBuilder = new StringBuilder(
            "SELECT id, question, answer, sql_example, category, quality_score, usage_count, created_at " +
            "FROM rag_knowledge_base"
        );
        
        List<Object> params = new ArrayList<>();
        
        // 关键词搜索
        if (keyword != null && !keyword.trim().isEmpty()) {
            sqlBuilder.append(" WHERE question LIKE ? OR sql_example LIKE ?");
            String searchPattern = "%" + keyword.trim() + "%";
            params.add(searchPattern);
            params.add(searchPattern);
        }
        
        // 排序和分页
        sqlBuilder.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        
        try {
            java.util.List<KnowledgeItem> results = jdbcTemplate.query(
                sqlBuilder.toString(),
                new KnowledgeListRowMapper(),
                params.toArray()
            );
            
            log.debug("RAG知识库列表查询: keyword={}, total={}", keyword, results.size());
            return results;
            
        } catch (Exception e) {
            log.error("查询RAG知识库列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取知识库总数（用于分页）
     */
    public long getKnowledgeCount(String keyword) {
        StringBuilder sqlBuilder = new StringBuilder("SELECT COUNT(*) FROM rag_knowledge_base");
        List<Object> params = new ArrayList<>();
        
        if (keyword != null && !keyword.trim().isEmpty()) {
            sqlBuilder.append(" WHERE question LIKE ? OR sql_example LIKE ?");
            String searchPattern = "%" + keyword.trim() + "%";
            params.add(searchPattern);
            params.add(searchPattern);
        }
        
        try {
            Long count = jdbcTemplate.queryForObject(sqlBuilder.toString(), Long.class, params.toArray());
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("获取RAG知识库总数失败", e);
            return 0;
        }
    }
    
    /**
     * 删除知识库条目
     */
    public boolean deleteKnowledge(Long id) {
        try {
            int deleted = ragMapper.deleteById(id);
            if (deleted > 0) {
                log.info("删除RAG知识库条目: id={}", id);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("删除RAG知识库条目失败: id={}", id, e);
            return false;
        }
    }
    
    // ==================== 数据模型 ====================
    
    @Data
    public static class KnowledgeItem {
        private Long id;
        private String question;
        private String answer;
        private String sqlExample;
        private String category;
        private Float qualityScore;
        private Integer usageCount;
        private Double relevance; // 相关度分数
        private LocalDateTime createdAt; // 创建时间
    }
    
    // ==================== RowMapper ====================
    
    private static class KnowledgeRowMapper implements RowMapper<KnowledgeItem> {
        @Override
        public KnowledgeItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeItem item = new KnowledgeItem();
            item.setId(rs.getLong("id"));
            item.setQuestion(rs.getString("question"));
            item.setAnswer(rs.getString("answer"));
            item.setSqlExample(rs.getString("sql_example"));
            item.setCategory(rs.getString("category"));
            item.setQualityScore(rs.getFloat("quality_score"));
            item.setUsageCount(rs.getInt("usage_count"));
            
            // relevance可能不存在（getHighQualitySamples查询）
            try {
                item.setRelevance(rs.getDouble("relevance"));
            } catch (SQLException e) {
                item.setRelevance(null);
            }
            
            return item;
        }
    }
}
