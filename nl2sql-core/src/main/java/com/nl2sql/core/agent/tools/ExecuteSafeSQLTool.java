package com.nl2sql.core.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExecuteSafeSQLTool {

    @Deprecated
    @Tool("执行经过验证的安全SQL查询。@Deprecated - 建议使用 executeRawSQL 替代，由Workflow编排重试策略")
    public String executeSafeSQL(
        @P("SQL语句") String sql,
        @P("数据源ID") Long datasourceId,
        @P("用户ID") Long userId,
        @P("用户名") String username
    ) {
        log.info("[ExecuteSafeSQLTool] @Deprecated - 建议使用 executeRawSQL 替代");
        return ToolResponseBuilder.error("DEPRECATED", "此Tool已废弃，请使用 executeRawSQL 替代，由Workflow编排重试策略")
            .addMetadata("toolName", "execute_safe_sql")
            .addMetadata("deprecated", true)
            .addMetadata("replacement", "executeRawSQL")
            .build();
    }
}
