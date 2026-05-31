package com.nl2sql.core.agent.tools;

import com.nl2sql.core.rag.RagAutoLearner;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class RagLearnFromExecutionTool {

    @Autowired(required = false)
    private RagAutoLearner ragAutoLearner;

    @Tool("从SQL执行结果中触发RAG自动学习，将成功的问答对存入知识库供后续检索。输入问题、SQL、结果行数和执行时间")
    public String ragLearnFromExecution(
        @P("用户的原始问题") String question,
        @P("执行的SQL语句") String sql,
        @P("查询结果行数") Integer rowCount,
        @P("查询执行时间（毫秒）") Long executionTimeMs
    ) {
        try {
            log.info("[RagLearnFromExecutionTool] 触发RAG学习: question={}, rowCount={}", question, rowCount);

            if (ragAutoLearner == null) {
                return ToolResponseBuilder.error("RAG_UNAVAILABLE", "RAG自动学习器未配置")
                    .addMetadata("toolName", "rag_learn_from_execution")
                    .build();
            }

            if (question == null || question.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "用户问题不能为空")
                    .addMetadata("toolName", "rag_learn_from_execution")
                    .build();
            }

            if (sql == null || sql.trim().isEmpty()) {
                return ToolResponseBuilder.error("INVALID_INPUT", "SQL不能为空")
                    .addMetadata("toolName", "rag_learn_from_execution")
                    .build();
            }

            int rc = rowCount != null ? rowCount : 0;
            long execTime = executionTimeMs != null ? executionTimeMs : 0L;

            ragAutoLearner.learnFromExecution(question, sql, rc, execTime);

            Map<String, Object> data = new HashMap<>();
            data.put("learned", true);
            data.put("question", question);
            data.put("sql", sql);
            data.put("rowCount", rc);
            data.put("executionTimeMs", execTime);

            log.info("[RagLearnFromExecutionTool] RAG学习完成");

            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "rag_learn_from_execution")
                .build();

        } catch (Exception e) {
            log.warn("[RagLearnFromExecutionTool] RAG学习失败: {}", e.getMessage());
            return ToolResponseBuilder.error("LEARNING_ERROR", e.getMessage())
                .addMetadata("toolName", "rag_learn_from_execution")
                .build();
        }
    }
}
