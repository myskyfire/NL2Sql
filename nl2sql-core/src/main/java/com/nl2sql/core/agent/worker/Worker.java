package com.nl2sql.core.agent.worker;

import com.nl2sql.core.agent.planner.QueryPlan;
import lombok.Data;

import java.util.*;

/**
 * Worker 统一接口
 *
 * 每个 Worker 负责一个独立的执行环节（SQL/Chart/Summary）
 */
public interface Worker {

    /**
     * 执行 Worker 逻辑
     */
    WorkerResult execute(WorkerContext context);

    /**
     * Worker 类型标识
     */
    String getWorkerType();

    /**
     * Worker 上下文
     */
    @Data
    class WorkerContext {
        private QueryPlan plan;
        private Map<String, WorkerResult> previousResults = new HashMap<>();
        private Long datasourceId;
        private Long userId;
        private String username;
        private String userMessage;

        /**
         * 获取指定 Worker 的结果
         */
        @SuppressWarnings("unchecked")
        public <T> T getPreviousResultData(String workerType, String key) {
            WorkerResult result = previousResults.get(workerType);
            if (result == null || result.getData() == null) {
                return null;
            }
            return (T) result.getData().get(key);
        }
    }

    /**
     * Worker 执行结果
     */
    @Data
    class WorkerResult {
        private boolean success;
        private boolean skipped;
        private boolean waitingForApproval;  // ✅ 新增：等待人工确认
        private String workerType;
        private Map<String, Object> data = new HashMap<>();
        private String rawOutput;
        private String errorMessage;
        private List<String> warnings = new ArrayList<>();

        public static WorkerResult success(String type, Map<String, Object> data) {
            WorkerResult r = new WorkerResult();
            r.success = true;
            r.workerType = type;
            r.data = data != null ? data : new HashMap<>();
            return r;
        }

        public static WorkerResult failure(String type, String errorMessage) {
            WorkerResult r = new WorkerResult();
            r.success = false;
            r.workerType = type;
            r.errorMessage = errorMessage;
            return r;
        }

        public static WorkerResult skip(String type) {
            WorkerResult r = new WorkerResult();
            r.success = true;
            r.skipped = true;
            r.workerType = type;
            return r;
        }
        
        /**
         * ✅ 人机协同：创建等待人工确认的结果
         */
        public static WorkerResult waitingForApproval(String type, String approvalRequestJson, String sql) {
            WorkerResult r = new WorkerResult();
            r.success = false;  // 不视为成功，因为未执行
            r.waitingForApproval = true;
            r.workerType = type;
            r.rawOutput = approvalRequestJson;
            r.data.put("sql", sql);
            return r;
        }
    }
}
