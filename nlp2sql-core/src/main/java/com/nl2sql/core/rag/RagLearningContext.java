package com.nl2sql.core.rag;

/**
 * RAG 学习上下文（ThreadLocal）
 * 用于在工具调用链中传递用户问题等上下文信息
 */
public class RagLearningContext {
    
    private static final ThreadLocal<String> currentUserQuestion = new ThreadLocal<>();
    private static final ThreadLocal<String> currentSql = new ThreadLocal<>();
    
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
     * 清理上下文（防止内存泄漏）
     */
    public static void clear() {
        currentUserQuestion.remove();
        currentSql.remove();
    }
}
