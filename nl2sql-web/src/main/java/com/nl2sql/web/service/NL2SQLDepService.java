package com.nl2sql.web.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NL2SQL核心业务服务 - Agent模式重构版
 * 
 * 注意：所有NL2SQL查询现在通过 NL2SQLAgent 处理
 * 此类仅保留一些遗留的辅助方法，未来将完全移除
 */
@Slf4j
@Service
@Deprecated  // 标记为废弃，所有新代码应使用 NL2SQLAgent
public class NL2SQLDepService {
    
    /**
     * @deprecated 请使用 AgentController.chat() 代替
     */
    @Deprecated
    public Object executeNL2SQLQuery(Object request, Object userInfo) {
        log.warn("[DEPRECATED] 调用了已废弃的 NL2SQLService.executeNL2SQLQuery()");
        log.warn("[DEPRECATED] 请使用 /api/agent/chat 端点");
        throw new UnsupportedOperationException(
            "此方法已废弃，请使用 AgentController.chat() 或访问 /api/agent/chat 端点"
        );
    }
}
