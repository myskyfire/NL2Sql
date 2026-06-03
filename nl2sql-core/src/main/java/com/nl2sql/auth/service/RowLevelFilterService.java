package com.nl2sql.auth.service;

import com.nl2sql.auth.mapper.RowLevelPolicyMapper;
import com.nl2sql.auth.mapper.UserMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 行级权限过滤服务
 * 核心功能：根据用户行级策略，自动改写SQL注入WHERE条件
 */
@Slf4j
@Service
public class RowLevelFilterService {

    @Autowired
    private RowLevelPolicyMapper rowLevelPolicyMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserMapper userMapper;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private static final String POLICY_CACHE_KEY = "row_policy:user:%d:ds:%s:table:%s";
    private static final long CACHE_TTL_MINUTES = 10;

    /**
     * 对SQL应用行级过滤
     *
     * @param sql          原始SQL
     * @param userId       用户ID
     * @param datasourceId 数据源ID
     * @return 改写后的SQL（如无需改写则返回原始SQL）
     */
    public String applyRowLevelFilter(String sql, Long userId, Long datasourceId) {
        if (userId == null || sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        // 管理员跳过行级过滤
        if (authService.isAdmin(userId)) {
            log.debug("[行级过滤] 用户{}是管理员，跳过行级过滤", userId);
            return sql;
        }

        try {
            // 1. 解析SQL提取表名
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                // 非SELECT语句不应用行级过滤
                return sql;
            }

            Select select = (Select) statement;
            Set<String> tableNames = extractTableNames(select);
            if (tableNames.isEmpty()) {
                return sql;
            }

            // 2. 获取用户角色
            String role = getUserRole(userId);

            // 3. 查询用户在这些表上的行级策略
            List<RowLevelPolicy> policies = getPolicies(userId, role, datasourceId, new ArrayList<>(tableNames));
            if (policies.isEmpty()) {
                return sql;
            }

            // 4. 按表名分组
            Map<String, List<RowLevelPolicy>> policiesByTable = policies.stream()
                    .collect(Collectors.groupingBy(p -> p.getTableName().toLowerCase()));

            // 5. 改写SQL
            String rewrittenSql = rewriteSql(sql, select, policiesByTable);

            log.info("[行级过滤] 用户{} SQL已改写，应用了{}条策略", userId, policies.size());
            return rewrittenSql;

        } catch (JSQLParserException e) {
            log.warn("[行级过滤] SQL解析失败，返回原始SQL: {}", e.getMessage());
            return sql;
        } catch (Exception e) {
            log.error("[行级过滤] 异常，返回原始SQL", e);
            return sql;
        }
    }

    /**
     * 检查SQL是否需要行级过滤改写
     */
    public boolean needsRowLevelFilter(String sql, Long userId, Long datasourceId) {
        if (userId == null || authService.isAdmin(userId)) {
            return false;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                return false;
            }
            Set<String> tableNames = extractTableNames((Select) statement);
            if (tableNames.isEmpty()) {
                return false;
            }
            String role = getUserRole(userId);
            List<RowLevelPolicy> policies = getPolicies(userId, role, datasourceId, new ArrayList<>(tableNames));
            return !policies.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取用户在指定表上的行级策略描述（用于前端展示）
     */
    public List<Map<String, Object>> getAppliedPolicies(Long userId, Long datasourceId, String tableName) {
        String role = getUserRole(userId);
        return rowLevelPolicyMapper.findActivePoliciesByUserAndTable(userId, role, datasourceId, tableName);
    }

    // ==================== SQL改写核心 ====================

    /**
     * 改写SQL，注入行级过滤条件
     */
    private String rewriteSql(String originalSql, Select select, Map<String, List<RowLevelPolicy>> policiesByTable) {
        SelectBody selectBody = select.getSelectBody();
        if (selectBody instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectBody;
            injectFilterToPlainSelect(plainSelect, policiesByTable);
        } else if (selectBody instanceof SetOperationList) {
            // UNION等集合操作，对每个子查询分别注入
            SetOperationList setOp = (SetOperationList) selectBody;
            for (SelectBody sb : setOp.getSelects()) {
                if (sb instanceof PlainSelect) {
                    injectFilterToPlainSelect((PlainSelect) sb, policiesByTable);
                }
            }
        }
        // 注：JSqlParser 4.6中WithItem不是SelectBody子类，CTE查询通过Select.getWithItemsList()处理
        // NL2SQL生成的SQL极少使用CTE，暂不处理

        return select.toString();
    }

    /**
     * 向PlainSelect注入WHERE条件
     */
    private void injectFilterToPlainSelect(PlainSelect plainSelect, Map<String, List<RowLevelPolicy>> policiesByTable) {
        Expression existingWhere = plainSelect.getWhere();

        // 获取当前查询的主表名
        String mainTableName = getMainTableName(plainSelect);
        if (mainTableName == null) {
            return;
        }

        List<RowLevelPolicy> policies = policiesByTable.get(mainTableName.toLowerCase());
        if (policies == null || policies.isEmpty()) {
            return;
        }

        // 构建过滤条件（多条策略用AND连接）
        Expression filterExpression = buildFilterExpression(policies, mainTableName);
        if (filterExpression == null) {
            return;
        }

        // 与现有WHERE条件合并
        if (existingWhere != null) {
            plainSelect.setWhere(new AndExpression(existingWhere, filterExpression));
        } else {
            plainSelect.setWhere(filterExpression);
        }
    }

    /**
     * 根据策略列表构建过滤表达式
     */
    private Expression buildFilterExpression(List<RowLevelPolicy> policies, String tableName) {
        Expression result = null;

        for (RowLevelPolicy policy : policies) {
            Expression condition = buildSingleCondition(policy, tableName);
            if (condition != null) {
                if (result == null) {
                    result = condition;
                } else {
                    result = new AndExpression(result, condition);
                }
            }
        }

        return result;
    }

    /**
     * 根据单条策略构建过滤条件
     */
    private Expression buildSingleCondition(RowLevelPolicy policy, String tableName) {
        String columnName = policy.getColumnName();
        String filterType = policy.getFilterType();
        String filterValue = policy.getFilterValue();

        Column column = new Column(columnName);

        switch (filterType) {
            case "USER_ATTRIBUTE":
                // filterValue格式: user.{attributeName}，如 user.region
                // 解析属性名，从用户信息中获取实际值
                String attributeValue = resolveUserAttribute(policy.getUserId(), filterValue);
                if (attributeValue == null) {
                    log.warn("[行级过滤] 无法解析用户属性: {}, 用户: {}", filterValue, policy.getUserId());
                    return null;
                }
                EqualsTo attrEquals = new EqualsTo();
                attrEquals.setLeftExpression(column);
                attrEquals.setRightExpression(new StringValue(attributeValue));
                return attrEquals;

            case "STATIC_VALUE":
                // filterValue格式: 单值如 "active" 或多值如 "华东,华南"
                if (filterValue.contains(",")) {
                    // IN表达式
                    InExpression inExpr = new InExpression();
                    inExpr.setLeftExpression(column);
                    String[] values = filterValue.split(",");
                    List<Expression> valueExprs = new ArrayList<>();
                    for (String v : values) {
                        valueExprs.add(new StringValue(v.trim()));
                    }
                    inExpr.setRightItemsList(new ExpressionList(valueExprs));
                    return inExpr;
                } else {
                    EqualsTo staticEquals = new EqualsTo();
                    staticEquals.setLeftExpression(column);
                    staticEquals.setRightExpression(new StringValue(filterValue));
                    return staticEquals;
                }

            case "EXPRESSION":
                // filterValue是完整的条件表达式，如 region IN ('华东','华南')
                try {
                    return CCJSqlParserUtil.parseCondExpression(filterValue);
                } catch (JSQLParserException e) {
                    log.warn("[行级过滤] 无法解析表达式: {}, error: {}", filterValue, e.getMessage());
                    return null;
                }

            default:
                log.warn("[行级过滤] 未知过滤类型: {}", filterType);
                return null;
        }
    }

    /**
     * 解析用户属性
     * filterValue格式: user.{attributeName}
     * 支持的属性: username, realName, role, email
     */
    private String resolveUserAttribute(Long userId, String filterValue) {
        if (filterValue == null || !filterValue.startsWith("user.")) {
            return null;
        }

        String attributeName = filterValue.substring(5).toLowerCase();
        AuthService.UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return null;
        }

        switch (attributeName) {
            case "username":
                return userInfo.getUsername();
            case "realname":
            case "real_name":
                return userInfo.getRealName();
            case "role":
                return userInfo.getRole();
            case "email":
                return userInfo.getEmail();
            default:
                log.warn("[行级过滤] 不支持的用户属性: {}", attributeName);
                return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从Select语句中提取表名
     */
    private Set<String> extractTableNames(Select select) {
        Set<String> tables = new HashSet<>();
        SelectBody selectBody = select.getSelectBody();
        extractTableNamesFromBody(selectBody, tables);
        return tables;
    }

    private void extractTableNamesFromBody(SelectBody selectBody, Set<String> tables) {
        if (selectBody instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectBody;
            if (plainSelect.getFromItem() != null) {
                String tableName = plainSelect.getFromItem().toString().split("\\s+")[0];
                // 去掉可能的别名
                tables.add(tableName.replace("`", ""));
            }
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() != null) {
                        String tableName = join.getRightItem().toString().split("\\s+")[0];
                        tables.add(tableName.replace("`", ""));
                    }
                }
            }
        } else if (selectBody instanceof SetOperationList) {
            SetOperationList setOp = (SetOperationList) selectBody;
            for (SelectBody sb : setOp.getSelects()) {
                extractTableNamesFromBody(sb, tables);
            }
        }
    }

    /**
     * 获取PlainSelect的主表名
     */
    private String getMainTableName(PlainSelect plainSelect) {
        if (plainSelect.getFromItem() != null) {
            String raw = plainSelect.getFromItem().toString();
            // 提取表名（去掉别名和反引号）
            String tableName = raw.split("\\s+")[0].replace("`", "");
            return tableName;
        }
        return null;
    }

    /**
     * 获取用户行级策略（带缓存）
     */
    private List<RowLevelPolicy> getPolicies(Long userId, String role, Long datasourceId, List<String> tableNames) {
        // 尝试从缓存获取
        if (redisTemplate != null) {
            try {
                String cacheKey = String.format(POLICY_CACHE_KEY, userId,
                        datasourceId != null ? datasourceId : "all",
                        String.join(",", tableNames));
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    @SuppressWarnings("unchecked")
                    List<RowLevelPolicy> result = (List<RowLevelPolicy>) cached;
                    return result;
                }
            } catch (Exception e) {
                log.debug("[行级过滤] 缓存读取失败: {}", e.getMessage());
            }
        }

        // 查数据库
        List<Map<String, Object>> rows = rowLevelPolicyMapper.findActivePoliciesByUserAndTables(
                userId, role, datasourceId, tableNames);

        List<RowLevelPolicy> policies = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RowLevelPolicy policy = new RowLevelPolicy();
            policy.setId(((Number) row.get("id")).longValue());
            policy.setPolicyName((String) row.get("policy_name"));
            Object dsId = row.get("datasource_id");
            policy.setDatasourceId(dsId != null ? ((Number) dsId).longValue() : null);
            policy.setTableName((String) row.get("table_name"));
            policy.setColumnName((String) row.get("column_name"));
            policy.setFilterType((String) row.get("filter_type"));
            policy.setFilterValue((String) row.get("filter_value"));
            policy.setPriority(row.get("priority") != null ? ((Number) row.get("priority")).intValue() : 0);
            policy.setUserId(userId);
            policies.add(policy);
        }

        // 写入缓存
        if (redisTemplate != null && !policies.isEmpty()) {
            try {
                String cacheKey = String.format(POLICY_CACHE_KEY, userId,
                        datasourceId != null ? datasourceId : "all",
                        String.join(",", tableNames));
                redisTemplate.opsForValue().set(cacheKey, policies, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.debug("[行级过滤] 缓存写入失败: {}", e.getMessage());
            }
        }

        return policies;
    }

    /**
     * 清除用户的行级策略缓存
     */
    public void clearUserPolicyCache(Long userId) {
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(String.format("row_policy:user:%d:*", userId));
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                log.debug("[行级过滤] 缓存清除失败: {}", e.getMessage());
            }
        }
    }

    private String getUserRole(Long userId) {
        try {
            AuthService.UserInfo userInfo = getUserInfo(userId);
            return userInfo != null ? userInfo.getRole() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AuthService.UserInfo getUserInfo(Long userId) {
        try {
            Map<String, Object> user = userMapper.findById(userId);
            if (user == null) return null;
            AuthService.UserInfo info = new AuthService.UserInfo();
            info.setUserId(userId);
            info.setUsername((String) user.get("username"));
            info.setRealName((String) user.get("real_name"));
            info.setRole((String) user.get("role"));
            info.setEmail((String) user.get("email"));
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 策略管理方法 ====================

    /**
     * 创建行级策略
     */
    public Long createPolicy(String policyName, Long datasourceId, String tableName,
                             String columnName, String filterType, String filterValue,
                             Integer priority, List<Long> userIds, List<String> roleNames) {
        rowLevelPolicyMapper.insertPolicy(policyName, datasourceId, tableName, columnName,
                filterType, filterValue, priority);

        // 获取自增ID（通过查询最新插入的记录）
        List<Map<String, Object>> allPolicies = rowLevelPolicyMapper.findAllPolicies(datasourceId);
        Long policyId = null;
        for (Map<String, Object> p : allPolicies) {
            if (policyName.equals(p.get("policy_name")) && tableName.equals(p.get("table_name"))) {
                policyId = ((Number) p.get("id")).longValue();
                break;
            }
        }

        if (policyId != null) {
            // 绑定用户
            if (userIds != null) {
                for (Long uid : userIds) {
                    rowLevelPolicyMapper.insertPolicyBinding(policyId, uid, null);
                }
            }
            // 绑定角色
            if (roleNames != null) {
                for (String rn : roleNames) {
                    rowLevelPolicyMapper.insertPolicyBinding(policyId, null, rn);
                }
            }
        }

        log.info("[行级策略] 创建策略: name={}, table={}, type={}", policyName, tableName, filterType);
        return policyId;
    }

    /**
     * 更新行级策略
     */
    public boolean updatePolicy(Long id, String policyName, Long datasourceId, String tableName,
                                String columnName, String filterType, String filterValue,
                                Integer priority, Integer isActive,
                                List<Long> userIds, List<String> roleNames) {
        int affected = rowLevelPolicyMapper.updatePolicy(id, policyName, datasourceId, tableName,
                columnName, filterType, filterValue, priority, isActive);
        if (affected > 0) {
            // 重新绑定
            rowLevelPolicyMapper.deletePolicyBindings(id);
            if (userIds != null) {
                for (Long uid : userIds) {
                    rowLevelPolicyMapper.insertPolicyBinding(id, uid, null);
                }
            }
            if (roleNames != null) {
                for (String rn : roleNames) {
                    rowLevelPolicyMapper.insertPolicyBinding(id, null, rn);
                }
            }
            log.info("[行级策略] 更新策略: id={}", id);
            return true;
        }
        return false;
    }

    /**
     * 删除行级策略
     */
    public boolean deletePolicy(Long id) {
        rowLevelPolicyMapper.deletePolicyBindings(id);
        int affected = rowLevelPolicyMapper.deletePolicy(id);
        log.info("[行级策略] 删除策略: id={}, affected={}", id, affected);
        return affected > 0;
    }

    /**
     * 停用策略
     */
    public boolean deactivatePolicy(Long id) {
        int affected = rowLevelPolicyMapper.deactivatePolicy(id);
        return affected > 0;
    }

    /**
     * 查询所有策略
     */
    public List<Map<String, Object>> listPolicies(Long datasourceId) {
        return rowLevelPolicyMapper.findAllPolicies(datasourceId);
    }

    /**
     * 查询策略详情（含绑定信息）
     */
    public Map<String, Object> getPolicyDetail(Long id) {
        Map<String, Object> policy = rowLevelPolicyMapper.findPolicyById(id);
        if (policy != null) {
            List<Map<String, Object>> bindings = rowLevelPolicyMapper.findPolicyBindings(id);
            policy.put("bindings", bindings);
        }
        return policy;
    }

    /**
     * 行级策略数据对象
     */
    @Data
    public static class RowLevelPolicy {
        private Long id;
        private String policyName;
        private Long datasourceId;
        private String tableName;
        private String columnName;
        private String filterType;
        private String filterValue;
        private Integer priority;
        private Long userId;
    }
}
