package com.nl2sql.core.agent;

import com.nl2sql.core.agent.engine.WorkflowEngine;
import com.nl2sql.core.agent.planner.PlannerAgent;
import com.nl2sql.core.agent.routing.SkillRouter;
import com.nl2sql.core.agent.tools.*;
import com.nl2sql.core.agent.worker.Worker;
import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.llm.LLMService;
import com.nl2sql.core.service.SessionContextManager;
import com.nl2sql.core.tracing.LangSmithTracingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 配置 - 采用 Plan-and-Execute + Multi-Agent 架构
 *
 * 替换原来的 ReActAgent，使用 SupervisorAgent 作为统一入口：
 * 1. 简单查询 → 直接执行（保留现有低延迟）
 * 2. 复杂查询 → Planner 生成 Plan → WorkflowEngine 编排 Worker 执行
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Autowired
    private LLMService llmService;

    @Autowired
    private SkillRouter skillRouter;

    @Autowired
    private PlannerAgent plannerAgent;

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired(required = false)
    private DatasourceClarificationTool datasourceClarificationTool;

    @Autowired
    private List<Worker> workers;

    // ==================== 保留的工具（供 Workers 使用）====================

    @Autowired(required = false)
    private RetrieveTableSchemaTool retrieveTableSchemaTool;

    @Autowired(required = false)
    private GenerateSQLTool generateSQLTool;

    @Autowired(required = false)
    private GenerateSQLFromSchemaTool generateSQLFromSchemaTool;

    @Autowired(required = false)
    private ExecuteSafeSQLTool executeSafeSQLTool;

    @Autowired(required = false)
    private ExecuteSQLTool executeSQLTool;

    @Autowired(required = false)
    private ValidateSQLTool validateSQLTool;

    @Autowired(required = false)
    private SQLExecutionTool sqlExecutionTool;

    @Autowired(required = false)
    private CorrectSqlTool correctSqlTool;

    @Autowired(required = false)
    private AssembleResultTool assembleResultTool;

    @Autowired(required = false)
    private LangSmithTracingService tracingService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired(required = false)
    private SessionContextManager sessionContextManager;

    @Autowired(required = false)
    private QueryCacheService queryCacheService;

    // ==================== SupervisorAgent Bean ====================

    @Bean
    public SupervisorAgent supervisorAgent() {
        log.info("初始化 NL2SQL Supervisor Agent（Plan-and-Execute + Multi-Agent）...");

        SupervisorAgent agent = new SupervisorAgent(
            llmService,
            skillRouter,
            plannerAgent,
            workflowEngine,
            datasourceClarificationTool,
            tracingService,
            toolRegistry,
            sessionContextManager,
            queryCacheService,
            workers
        );

        log.info("NL2SQL Supervisor Agent 初始化完成，已注册 Workers: {}", workers.size());
        return agent;
    }

    // ==================== 工具 Bean 声明（供其他模块引用）====================

    @Bean
    public CorrectSqlTool correctSqlToolBean() {
        if (correctSqlTool == null) {
            log.warn("[AgentConfig] CorrectSqlTool 未找到，将使用默认实现");
            return new CorrectSqlTool();
        }
        return correctSqlTool;
    }

    @Bean
    public AssembleResultTool assembleResultToolBean() {
        if (assembleResultTool == null) {
            log.warn("[AgentConfig] AssembleResultTool 未找到，将使用默认实现");
            return new AssembleResultTool();
        }
        return assembleResultTool;
    }
}
