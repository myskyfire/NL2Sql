package com.nl2sql.auth.service;

import com.nl2sql.auth.mapper.OperationLogMapper;
import com.nl2sql.auth.mapper.TablePermissionMapper;
import com.nl2sql.auth.mapper.UserMapper;
import com.nl2sql.auth.mapper.WhitelistMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AuthService {
    
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    
    // ✅ MyBatis Mappers
    @Autowired
    private WhitelistMapper whitelistMapper;
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private OperationLogMapper operationLogMapper;
    
    @Autowired
    private com.nl2sql.auth.mapper.AuthMapper authMapper;
    
    private static final String WHITELIST_CACHE_KEY = "auth:whitelist:user:%d";
    private static final String SESSION_CACHE_KEY = "auth:session:%s";
    private static final String TABLE_PERMS_CACHE_KEY = "auth:tableperms:user:%d";
    private static final long CACHE_TTL_MINUTES = 5;
    
    public AuthService(
        JdbcTemplate jdbcTemplate,
        RedisTemplate<String, Object> redisTemplate,
        UserMapper userMapper,
        WhitelistMapper whitelistMapper,
        TablePermissionMapper tablePermissionMapper,
        OperationLogMapper operationLogMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.userMapper = userMapper;
        this.whitelistMapper = whitelistMapper;
        this.tablePermissionMapper = tablePermissionMapper;
        this.operationLogMapper = operationLogMapper;
    }
    
    @PostConstruct
    public void init() {
        log.info("认证服务初始化完成");
    }
    
    /**
     * 用户登录
     */
    public LoginResult login(String username, String password, String ipAddress) {
        try {
            // ✅ 使用Mapper查询用户
            Map<String, Object> user = userMapper.findByUsername(username);
            
            if (user == null) {
                return LoginResult.error("用户名或密码错误");
            }
            
            // 检查是否激活（兼容Boolean和Integer类型）
            Object statusObj = user.get("status");
            boolean isActive = false;
            if (statusObj instanceof Integer) {
                isActive = ((Integer) statusObj) == 1;
            } else if (statusObj instanceof Boolean) {
                isActive = (Boolean) statusObj;
            }
            
            if (!isActive) {
                return LoginResult.error("账号已被禁用");
            }
            
            // 验证密码
            String storedPassword = (String) user.get("password");
            if (!passwordEncoder.matches(password, storedPassword)) {
                return LoginResult.error("用户名或密码错误");
            }
            
            Long userId = ((Number) user.get("id")).longValue();
            String role = (String) user.get("role");
            
            // ✅ 使用Mapper更新最后登录时间
            userMapper.updateLastLoginAt(userId);
            
            // 记录登录日志
            logOperation(userId, null, "LOGIN", "用户登录成功", ipAddress);
            
            // 生成会话Token
            String token = UUID.randomUUID().toString().replace("-", "");
            
            // 缓存会话信息
            Map<String, Object> sessionInfo = new HashMap<>();
            sessionInfo.put("userId", userId);
            sessionInfo.put("username", username);
            sessionInfo.put("realName", user.get("real_name"));
            sessionInfo.put("role", role);
            sessionInfo.put("loginTime", LocalDateTime.now().toString());
            
            redisTemplate.opsForValue().set(
                String.format(SESSION_CACHE_KEY, token),
                sessionInfo,
                24, TimeUnit.HOURS
            );
            
            // 检查是否在白名单中
            boolean inWhitelist = isInWhitelist(userId);
            
            return LoginResult.success(token, userId, username, role, inWhitelist);
            
        } catch (Exception e) {
            log.error("登录失败", e);
            return LoginResult.error("登录失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证Token并获取用户信息
     */
    public UserInfo validateToken(String token) {
        try {
            String key = String.format(SESSION_CACHE_KEY, token);
            
            // 使用opsForValue()读取（与登录时存储方式一致）
            Object sessionObj = redisTemplate.opsForValue().get(key);
            
            if (sessionObj == null) {
                return null;
            }
            
            // 转换Map类型
            Map<String, Object> sessionData;
            if (sessionObj instanceof Map) {
                sessionData = (Map<String, Object>) sessionObj;
            } else {
                log.warn("Session数据类型错误: {}", sessionObj.getClass().getName());
                return null;
            }
            
            UserInfo userInfo = new UserInfo();
            
            // 安全地获取userId，处理不同的数据类型
            Object userIdObj = sessionData.get("userId");
            log.info("[Token验证] userId原始类型: {}, 值: {}", userIdObj != null ? userIdObj.getClass().getName() : "null", userIdObj);
            
            if (userIdObj instanceof Number) {
                userInfo.setUserId(((Number) userIdObj).longValue());
            } else if (userIdObj instanceof String) {
                userInfo.setUserId(Long.parseLong((String) userIdObj));
            } else if (userIdObj instanceof List) {
                // 处理ArrayList情况，取第一个元素
                List<?> list = (List<?>) userIdObj;
                if (!list.isEmpty() && list.get(0) instanceof Number) {
                    userInfo.setUserId(((Number) list.get(0)).longValue());
                    log.warn("[Token验证] userId是List，取第一个元素: {}", userInfo.getUserId());
                } else {
                    log.error("[Token验证] userId List格式错误: {}", userIdObj);
                    return null;
                }
            } else {
                log.error("[Token验证] userId类型错误: {}", userIdObj != null ? userIdObj.getClass().getName() : "null");
                return null;
            }
            
            userInfo.setUsername((String) sessionData.get("username"));
            userInfo.setRealName((String) sessionData.get("realName"));
            userInfo.setRole((String) sessionData.get("role"));
            
            log.info("[Token验证] 成功: userId={}, username={}", userInfo.getUserId(), userInfo.getUsername());
            
            return userInfo;
        } catch (Exception e) {
            log.error("验证Token失败", e);
            return null;
        }
    }
    
    /**
     * 检查用户是否在白名单中
     */
    public boolean isInWhitelist(Long userId) {
        try {
            // 先查Redis缓存
            String cacheKey = String.format(WHITELIST_CACHE_KEY, userId);
            Boolean cached = (Boolean) redisTemplate.opsForValue().get(cacheKey);
            
            if (cached != null) {
                return cached;
            }
            
            // ✅ 使用Mapper查数据库
            int count = whitelistMapper.countActiveWhitelist(userId);
            boolean inWhitelist = count > 0;
            
            // 更新缓存
            redisTemplate.opsForValue().set(cacheKey, inWhitelist, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            
            return inWhitelist;
            
        } catch (Exception e) {
            log.error("检查白名单失败", e);
            return false;
        }
    }
    
    /**
     * 添加用户到白名单(仅管理员)
     */
    public boolean addToWhitelist(Long operatorId, Long targetUserId, String reason, LocalDateTime expiresAt) {
        try {
            // 检查操作员是否为管理员
            if (!isAdmin(operatorId)) {
                log.warn("非管理员尝试添加白名单: operatorId={}", operatorId);
                return false;
            }
            
            // 检查目标用户是否存在
            Integer count = authMapper.checkUserExists(targetUserId);
            
            if (count == null || count == 0) {
                log.warn("目标用户不存在: targetUserId={}", targetUserId);
                return false;
            }
            
            // 插入白名单
            authMapper.insertOrUpdateWhitelist(targetUserId, operatorId, reason, expiresAt.toString());
            
            // 清除缓存
            String cacheKey = String.format(WHITELIST_CACHE_KEY, targetUserId);
            redisTemplate.delete(cacheKey);
            
            // 记录日志
            logOperation(operatorId, targetUserId, "ADD_WHITELIST", 
                        String.format("添加白名单, 原因: %s", reason), null);
            
            log.info("添加白名单成功: operatorId={}, targetUserId={}", operatorId, targetUserId);
            return true;
            
        } catch (Exception e) {
            log.error("添加白名单失败", e);
            return false;
        }
    }
    
    /**
     * 检查用户是否有指定表的访问权限
     */
    public boolean hasTablePermission(Long userId, String tableName) {
        try {
            // 先查Redis缓存
            String cacheKey = String.format(TABLE_PERMS_CACHE_KEY, userId);
            Set<Object> cachedTables = redisTemplate.opsForSet().members(cacheKey);
            
            if (cachedTables != null && !cachedTables.isEmpty()) {
                return cachedTables.contains(tableName.toLowerCase());
            }
            
            // 查数据库
            String sql = "SELECT table_name FROM table_permissions WHERE user_id = ? AND is_active = 1";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
            Set<String> authorizedTables = new HashSet<>();
            
            for (Map<String, Object> row : rows) {
                authorizedTables.add(((String) row.get("table_name")).toLowerCase());
            }
            
            // 更新缓存
            if (!authorizedTables.isEmpty()) {
                redisTemplate.opsForSet().add(cacheKey, authorizedTables.toArray());
                redisTemplate.expire(cacheKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
            
            return authorizedTables.contains(tableName.toLowerCase());
            
        } catch (Exception e) {
            log.error("检查表权限失败", e);
            return false;
        }
    }
    
    /**
     * 获取用户有权限的所有表
     */
    public Set<String> getUserAuthorizedTables(Long userId) {
        try {
            String cacheKey = String.format(TABLE_PERMS_CACHE_KEY, userId);
            Set<Object> cached = redisTemplate.opsForSet().members(cacheKey);
            
            if (cached != null && !cached.isEmpty()) {
                Set<String> result = new HashSet<>();
                for (Object obj : cached) {
                    result.add(obj.toString());
                }
                return result;
            }
            
            String sql = "SELECT table_name FROM table_permissions WHERE user_id = ? AND is_active = 1";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
            Set<String> tables = new HashSet<>();
            
            for (Map<String, Object> row : rows) {
                tables.add(((String) row.get("table_name")).toLowerCase());
            }
            
            // 更新缓存
            if (!tables.isEmpty()) {
                redisTemplate.opsForSet().add(cacheKey, tables.toArray());
                redisTemplate.expire(cacheKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
            
            return tables;
            
        } catch (Exception e) {
            log.error("获取用户表权限失败", e);
            return Collections.emptySet();
        }
    }
    
    /**
     * 授权用户访问指定表(仅管理员)
     */
    public boolean grantTablePermission(Long operatorId, Long targetUserId, String tableName) {
        try {
            if (!isAdmin(operatorId)) {
                return false;
            }
            
            // 检查目标用户是否存在
            Integer count = authMapper.checkUserExists(targetUserId);
            
            if (count == null || count == 0) {
                return false;
            }
            
            // 插入或更新授权
            String insertSql = "INSERT INTO table_permissions (user_id, table_name, granted_by, is_active) " +
                              "VALUES (?, ?, ?, 1) " +
                              "ON DUPLICATE KEY UPDATE " +
                              "granted_by = VALUES(granted_by), " +
                              "is_active = 1";
            
            jdbcTemplate.update(insertSql, targetUserId, tableName.toLowerCase(), operatorId);
            
            // 清除缓存
            String cacheKey = String.format(TABLE_PERMS_CACHE_KEY, targetUserId);
            redisTemplate.delete(cacheKey);
            
            // 记录日志
            logOperation(operatorId, targetUserId, "GRANT_TABLE", 
                        String.format("授权访问表: %s", tableName), null);
            
            log.info("表授权成功: operatorId={}, targetUserId={}, table={}", operatorId, targetUserId, tableName);
            return true;
            
        } catch (Exception e) {
            log.error("表授权失败", e);
            return false;
        }
    }
    
    /**
     * 撤销用户的表访问权限(仅管理员)
     */
    public boolean revokeTablePermission(Long operatorId, Long targetUserId, String tableName) {
        try {
            if (!isAdmin(operatorId)) {
                return false;
            }
            
            int affected = authMapper.revokeTablePermission(targetUserId, tableName.toLowerCase());
            
            if (affected > 0) {
                // 清除缓存
                String cacheKey = String.format(TABLE_PERMS_CACHE_KEY, targetUserId);
                redisTemplate.delete(cacheKey);
                
                logOperation(operatorId, targetUserId, "REVOKE_TABLE", 
                            String.format("撤销表权限: %s", tableName), null);
                
                log.info("撤销表权限成功: targetUserId={}, table={}", targetUserId, tableName);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("撤销表权限失败", e);
            return false;
        }
    }
    
    /**
     * 获取用户的表授权列表
     */
    public List<TablePermission> getUserTablePermissions(Long userId) {
        try {
            String sql = "SELECT tp.id, tp.user_id, u.username, u.real_name, " +
                        "tp.table_name, tp.granted_by, tp.granted_at, tp.is_active " +
                        "FROM table_permissions tp " +
                        "JOIN users u ON tp.user_id = u.id " +
                        "WHERE tp.user_id = ? " +
                        "ORDER BY tp.granted_at DESC";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
            List<TablePermission> result = new ArrayList<>();
            
            for (Map<String, Object> row : rows) {
                TablePermission perm = new TablePermission();
                perm.setId(((Number) row.get("id")).longValue());
                perm.setUserId(((Number) row.get("user_id")).longValue());
                perm.setUsername((String) row.get("username"));
                perm.setRealName((String) row.get("real_name"));
                perm.setTableName((String) row.get("table_name"));
                perm.setGrantedById(((Number) row.get("granted_by")).longValue());
                perm.setGrantedAt(row.get("granted_at").toString());
                
                perm.setIsActive(((Number) row.get("is_active")).intValue() == 1);
                
                result.add(perm);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("获取表授权列表失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取所有表的授权统计
     */
    public List<Map<String, Object>> getAllTablePermissions() {
        try {
            // ✅ 使用Mapper查询
            return tablePermissionMapper.findAllTablePermissions();
        } catch (Exception e) {
            log.error("获取所有表授权统计失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 按表名查询已授权的用户列表
     */
    public List<TablePermission> getPermissionsByTable(String tableName) {
        try {
            // ✅ 使用Mapper查询
            List<Map<String, Object>> rows = tablePermissionMapper.findByTableName(tableName.toLowerCase());
            List<TablePermission> result = new ArrayList<>();
            
            for (Map<String, Object> row : rows) {
                TablePermission perm = new TablePermission();
                perm.setId(((Number) row.get("id")).longValue());
                perm.setUserId(((Number) row.get("user_id")).longValue());
                perm.setUsername((String) row.get("username"));
                perm.setRealName((String) row.get("real_name"));
                perm.setEmail((String) row.get("email"));
                perm.setRole((String) row.get("role"));
                perm.setTableName((String) row.get("table_name"));
                perm.setGrantedById(((Number) row.get("granted_by")).longValue());
                
                Object grantedByUsername = row.get("granted_by_username");
                if (grantedByUsername != null) {
                    perm.setGrantedByUsername(grantedByUsername.toString());
                }
                
                perm.setGrantedAt(row.get("granted_at").toString());
                
                perm.setIsActive(((Number) row.get("is_active")).intValue() == 1);
                
                result.add(perm);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("按表查询权限失败: table={}", tableName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 从白名单移除(仅管理员)
     */
    public boolean removeFromWhitelist(Long operatorId, Long targetUserId) {
        try {
            if (!isAdmin(operatorId)) {
                return false;
            }
            
            int affected = authMapper.deactivateWhitelist(targetUserId);
            
            if (affected > 0) {
                // 清除缓存
                String cacheKey = String.format(WHITELIST_CACHE_KEY, targetUserId);
                redisTemplate.delete(cacheKey);
                
                logOperation(operatorId, targetUserId, "REMOVE_WHITELIST", "从白名单移除", null);
                log.info("从白名单移除成功: targetUserId={}", targetUserId);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("从白名单移除失败", e);
            return false;
        }
    }
    
    /**
     * 获取白名单列表
     */
    public List<WhitelistEntry> getWhitelistList() {
        try {
            // ✅ 使用Mapper查询
            List<Map<String, Object>> rows = whitelistMapper.findWhitelistList();
            List<WhitelistEntry> result = new ArrayList<>();
            
            for (Map<String, Object> row : rows) {
                WhitelistEntry entry = new WhitelistEntry();
                entry.setId(((Number) row.get("id")).longValue());
                entry.setUserId(((Number) row.get("user_id")).longValue());
                entry.setUsername((String) row.get("username"));
                entry.setRealName((String) row.get("real_name"));
                entry.setAddedById(((Number) row.get("added_by")).longValue());
                entry.setReason((String) row.get("reason"));
                
                Object expiresAt = row.get("expires_at");
                if (expiresAt != null) {
                    entry.setExpiresAt(expiresAt.toString());
                }
                
                entry.setIsActive(((Number) row.get("is_active")).intValue() == 1);
                entry.setCreatedAt(row.get("created_at").toString());
                
                result.add(entry);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("获取白名单列表失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取所有用户列表
     */
    public List<UserInfo> getAllUsers() {
        try {
            // ✅ 使用Mapper查询
            List<Map<String, Object>> rows = userMapper.findAllUsers();
            List<UserInfo> result = new ArrayList<>();
            
            for (Map<String, Object> row : rows) {
                UserInfo user = new UserInfo();
                user.setUserId(((Number) row.get("id")).longValue());
                user.setUsername((String) row.get("username"));
                user.setRealName((String) row.get("real_name"));
                user.setEmail((String) row.get("email"));
                user.setRole((String) row.get("role"));
                user.setIsActive(((Number) row.get("status")).intValue() == 1);
                user.setCreatedAt(row.get("created_at").toString());
                
                Object lastLogin = row.get("last_login_at");
                if (lastLogin != null) {
                    user.setLastLoginAt(lastLogin.toString());
                }
                
                result.add(user);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 登出
     */
    public void logout(String token) {
        try {
            String key = String.format(SESSION_CACHE_KEY, token);
            redisTemplate.delete(key);
            log.info("用户登出: token={}", token.substring(0, 8) + "...");
        } catch (Exception e) {
            log.error("登出失败", e);
        }
    }
    
    /**
     * 检查是否为管理员
     */
    public boolean isAdmin(Long userId) {
        try {
            // ✅ 使用Mapper查询角色
            String role = userMapper.findRoleById(userId);
            return "admin".equals(role);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 记录操作日志
     */
    private void logOperation(Long operatorId, Long targetUserId, String operationType, 
                             String description, String ipAddress) {
        try {
            // 获取用户名
            String username = "unknown";
            try {
                Map<String, Object> user = userMapper.findByUsername(
                    authMapper.getUsernameById(operatorId)
                );
                if (user != null) {
                    username = (String) user.get("username");
                }
            } catch (Exception e) {
                log.warn("获取用户名失败: {}", e.getMessage());
            }
            
            // 构建details JSON
            String details = String.format(
                "{\"operation_type\":\"%s\",\"target_user_id\":%s,\"description\":\"%s\"}",
                operationType,
                targetUserId != null ? targetUserId : "null",
                description != null ? description.replace("\"", "\\\"") : ""
            );
            
            // ✅ 使用Mapper记录日志
            operationLogMapper.insertOperationLog(operatorId, username, operationType, details, ipAddress);
        } catch (Exception e) {
            log.error("记录操作日志失败: {}", e.getMessage());
        }
    }
    
    // ==================== 数据类 ====================
    
    @Data
    public static class LoginResult {
        private boolean success;
        private String message;
        private String token;
        private Long userId;
        private String username;
        private String role;
        private Boolean inWhitelist;
        
        public static LoginResult success(String token, Long userId, String username, String role, Boolean inWhitelist) {
            LoginResult result = new LoginResult();
            result.success = true;
            result.message = "登录成功";
            result.token = token;
            result.userId = userId;
            result.username = username;
            result.role = role;
            result.inWhitelist = inWhitelist;
            return result;
        }
        
        public static LoginResult error(String message) {
            LoginResult result = new LoginResult();
            result.success = false;
            result.message = message;
            return result;
        }
    }
    
    @Data
    public static class UserInfo {
        private Long userId;
        private String username;
        private String realName;
        private String email;
        private String role;
        private Boolean isActive;
        private String createdAt;
        private String lastLoginAt;
    }
    
    @Data
    public static class WhitelistEntry {
        private Long id;
        private Long userId;
        private String username;
        private String realName;
        private Long addedById;
        private String reason;
        private String expiresAt;
        private Boolean isActive;
        private String createdAt;
    }
    
    @Data
    public static class TablePermission {
        private Long id;
        private Long userId;
        private String username;
        private String realName;
        private String email;
        private String role;
        private String tableName;
        private Long grantedById;
        private String grantedByUsername;
        private String grantedAt;
        private String expiresAt;
        private Boolean isActive;
    }
    
    /**
     * 创建新用户(仅管理员)
     */
    public boolean createUser(String username, String password, String realName, String role) {
        try {
            // ✅ 使用Mapper检查用户名是否已存在
            Map<String, Object> existingUser = userMapper.findByUsername(username);
            if (existingUser != null) {
                log.warn("用户名已存在: {}", username);
                return false;
            }
            
            // 加密密码
            String encodedPassword = passwordEncoder.encode(password);
            
            // ✅ 使用Mapper插入用户
            userMapper.insertUser(username, encodedPassword, realName, role);
            
            log.info("创建用户成功: username={}, role={}", username, role);
            return true;
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return false;
        }
    }
    
    /**
     * 修改密码（验证原密码）
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        try {
            // ✅ 使用Mapper查询用户当前密码
            Map<String, Object> user = userMapper.findByUsername(
                authMapper.getUsernameById(userId)
            );
            
            if (user == null) {
                return false;
            }
            
            String storedPassword = (String) user.get("password");
            
            // 验证原密码
            if (!passwordEncoder.matches(oldPassword, storedPassword)) {
                log.warn("原密码错误: userId={}", userId);
                return false;
            }
            
            // 更新新密码
            String newEncodedPassword = passwordEncoder.encode(newPassword);
            userMapper.updatePassword(userId, newEncodedPassword);
            
            log.info("用户修改密码成功: userId={}", userId);
            return true;
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return false;
        }
    }
    
    /**
     * 管理员重置用户密码（不需要原密码）
     */
    public boolean resetUserPassword(Long userId, String newPassword) {
        try {
            // ✅ 使用Mapper检查用户是否存在
            String username = authMapper.getUsernameById(userId);
            if (username == null) {
                return false;
            }
            
            // 直接更新密码
            String encodedPassword = passwordEncoder.encode(newPassword);
            userMapper.updatePassword(userId, encodedPassword);
            
            log.info("管理员重置用户密码成功: userId={}", userId);
            return true;
        } catch (Exception e) {
            log.error("重置密码失败", e);
            return false;
        }
    }
}
