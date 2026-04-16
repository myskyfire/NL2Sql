package com.nl2sql.core.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RAG 学习上下文（ThreadLocal）
 * 用于在工具调用链中传递用户问题等上下文信息
 */
@Slf4j
@Component
public class RagLearningContext {
    
    private static final ThreadLocal<String> currentUserQuestion = new ThreadLocal<>();
    private static final ThreadLocal<String> currentSql = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentDatasourceId = new ThreadLocal<>();
    
    @Autowired(required = false)
    private static JdbcTemplate jdbcTemplate;
    
    /**
     * 设置当前用户问题
     */
    public static void setCurrentQuestion(String question) {
        currentUserQuestion.set(question);
    }
    
    /**
     * 获取当前用户问题
     */
    public static String getCurrentQuestion() {
        return currentUserQuestion.get();
    }
    
    /**
     * 设置当前 SQL
     */
    public static void setCurrentSql(String sql) {
        currentSql.set(sql);
    }
    
    /**
     * 获取当前 SQL
     */
    public static String getCurrentSql() {
        return currentSql.get();
    }
    
    /**
     * 设置当前数据源ID
     */
    public static void setCurrentDatasourceId(Long datasourceId) {
        currentDatasourceId.set(datasourceId);
    }
    
    /**
     * 获取当前数据源ID
     */
    public static Long getCurrentDatasourceId() {
        return currentDatasourceId.get();
    }
    
    /**
     * 清理上下文（防止内存泄漏）
     */
    public static void clear() {
        currentUserQuestion.remove();
        currentSql.remove();
        currentDatasourceId.remove();
    }
    
    /**
     * ✅ 触发RAG学习：从成功SQL中提取新概念
     * 
     * @param success 是否执行成功
     * @param feedbackScore 用户反馈评分（可选）
     */
    public static void triggerLearning(boolean success, Double feedbackScore) {
        try {
            String question = getCurrentQuestion();
            String sql = getCurrentSql();
            Long datasourceId = getCurrentDatasourceId();
            
            if (question == null || sql == null || !success) {
                return; // 只学习成功的查询
            }
            
            // 如果用户评分高（>=4），自动学习
            if (feedbackScore != null && feedbackScore >= 4.0) {
                log.info("[RAG Learning] 检测到高评分查询，准备提取新概念: question={}", question);
                extractAndSaveConcepts(question, sql, datasourceId);
            }
            
        } catch (Exception e) {
            log.warn("[RAG Learning] 触发学习失败: {}", e.getMessage());
        }
    }
    
    /**
     * 从问题和SQL中提取概念并保存
     */
    private static void extractAndSaveConcepts(String question, String sql, Long datasourceId) {
        if (jdbcTemplate == null) {
            log.warn("[RAG Learning] JdbcTemplate未初始化，跳过学习");
            return;
        }
        
        try {
            // TODO: 实现NLP提取逻辑
            // 当前简化版：记录到日志，后续可扩展为：
            // 1. 使用LLM提取问题中的关键术语
            // 2. 分析SQL中的表/字段映射
            // 3. 插入concept_learning_log表（status=pending）
            
            log.info("[RAG Learning] 待学习样本 - Question: {}, SQL: {}, Datasource: {}",
                question, sql, datasourceId);
            
        } catch (Exception e) {
            log.error("[RAG Learning] 提取概念失败", e);
        }
    }
}
