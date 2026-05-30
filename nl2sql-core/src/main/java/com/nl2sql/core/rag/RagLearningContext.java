package com.nl2sql.core.rag;

public class RagLearningContext {
    
    private static final ThreadLocal<String> currentUserQuestion = new ThreadLocal<>();
    private static final ThreadLocal<String> currentSql = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentDatasourceId = new ThreadLocal<>();
    
    public static void setCurrentQuestion(String question) {
        currentUserQuestion.set(question);
    }
    
    public static String getCurrentQuestion() {
        return currentUserQuestion.get();
    }
    
    public static void setCurrentSql(String sql) {
        currentSql.set(sql);
    }
    
    public static String getCurrentSql() {
        return currentSql.get();
    }
    
    public static void setCurrentDatasourceId(Long datasourceId) {
        currentDatasourceId.set(datasourceId);
    }
    
    public static Long getCurrentDatasourceId() {
        return currentDatasourceId.get();
    }
    
    public static void clear() {
        currentUserQuestion.remove();
        currentSql.remove();
        currentDatasourceId.remove();
    }
}
