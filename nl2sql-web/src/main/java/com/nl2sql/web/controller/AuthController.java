package com.nl2sql.web.controller;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.auth.service.RowLevelFilterService;
import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.NetworkUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {
    
    private final AuthService authService;

    private final RowLevelFilterService rowLevelFilterService;

    public AuthController(AuthService authService, RowLevelFilterService rowLevelFilterService) {
        this.authService = authService;
        this.rowLevelFilterService = rowLevelFilterService;
    }
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        log.info("用户登录请求: username={}", request.getUsername());
        
        String ipAddress = NetworkUtils.getClientIp(httpRequest);
        AuthService.LoginResult result = authService.login(
            request.getUsername(), 
            request.getPassword(), 
            ipAddress
        );
        
        if (result.isSuccess()) {
            return Result.success(result);
        } else {
            return Result.error(result.getMessage());
        }
    }
    
    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String token) {
        authService.logout(token);
        return Result.success();
    }
    
    /**
     * 验证Token
     */
    @GetMapping("/validate")
    public Result<Map<String, Object>> validateToken(@RequestHeader("Authorization") String token) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("Token无效或已过期");
        }
        
        // 检查是否在白名单
        boolean inWhitelist = authService.isInWhitelist(userInfo.getUserId());
        
        Map<String, Object> data = new HashMap<>();
        data.put("userInfo", userInfo);
        data.put("inWhitelist", inWhitelist);
        
        return Result.success(data);
    }
    
    /**
     * 添加用户到白名单(仅管理员)
     */
    @PostMapping("/whitelist/add")
    public Result<Boolean> addToWhitelist(
        @RequestHeader("Authorization") String token,
        @RequestBody WhitelistRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        boolean success = authService.addToWhitelist(
            userInfo.getUserId(),
            request.getUserId(),
            request.getReason(),
            request.getExpiresAt()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("添加失败");
        }
    }
    
    /**
     * 从白名单移除(仅管理员)
     */
    @PostMapping("/whitelist/remove")
    public Result<Boolean> removeFromWhitelist(
        @RequestHeader("Authorization") String token,
        @RequestParam Long userId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        boolean success = authService.removeFromWhitelist(userInfo.getUserId(), userId);
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("移除失败");
        }
    }
    
    /**
     * 获取白名单列表(仅管理员)
     */
    @GetMapping("/whitelist/list")
    public Result<List<AuthService.WhitelistEntry>> getWhitelistList(
        @RequestHeader("Authorization") String token
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        
        List<AuthService.WhitelistEntry> list = authService.getWhitelistList();
        return Result.success(list);
    }
    
    /**
     * 获取所有用户列表(仅管理员)
     */
    @GetMapping("/users")
    public Result<List<AuthService.UserInfo>> getAllUsers(
        @RequestHeader("Authorization") String token
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        
        List<AuthService.UserInfo> users = authService.getAllUsers();
        return Result.success(users);
    }
    
    /**
     * 授权用户访问指定表(仅管理员)
     */
    @PostMapping("/table-permission/grant")
    public Result<Boolean> grantTablePermission(
        @RequestHeader("Authorization") String token,
        @RequestBody TablePermissionRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        // 验证必填字段
        if (request.getDatabaseName() == null || request.getDatabaseName().trim().isEmpty()) {
            return Result.error("数据库名不能为空");
        }
        
        boolean success = authService.grantTablePermission(
            userInfo.getUserId(),
            request.getUserId(),
            request.getDatabaseName(),
            request.getTableName(),
            request.getDatasourceId()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("授权失败");
        }
    }
    
    /**
     * 撤销用户的表访问权限(仅管理员)
     */
    @PostMapping("/table-permission/revoke")
    public Result<Boolean> revokeTablePermission(
        @RequestHeader("Authorization") String token,
        @RequestBody TablePermissionRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        // 验证必填字段
        if (request.getDatabaseName() == null || request.getDatabaseName().trim().isEmpty()) {
            return Result.error("数据库名不能为空");
        }
        
        boolean success = authService.revokeTablePermission(
            userInfo.getUserId(),
            request.getUserId(),
            request.getDatabaseName(),
            request.getTableName(),
            request.getDatasourceId()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("撤销失败");
        }
    }
    
    /**
     * 获取用户的表授权列表(仅管理员)
     */
    @GetMapping("/table-permission/user/{userId}")
    public Result<List<AuthService.TablePermission>> getUserTablePermissions(
        @RequestHeader("Authorization") String token,
        @PathVariable Long userId,
        @RequestParam(required = false) Long datasourceId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        
        List<AuthService.TablePermission> permissions = authService.getUserTablePermissions(userId, datasourceId);
        return Result.success(permissions);
    }
    
    /**
     * 获取所有表的授权统计(仅管理员)
     */
    @GetMapping("/table-permission/all")
    public Result<List<Map<String, Object>>> getAllTablePermissions(
        @RequestHeader("Authorization") String token,
        @RequestParam(required = false) Long datasourceId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        
        List<Map<String, Object>> permissions = authService.getAllTablePermissions(datasourceId);
        return Result.success(permissions);
    }
    
    /**
     * 按表名查询已授权的用户列表(仅管理员)
     */
    @GetMapping("/table-permission/table/{tableName}")
    public Result<List<AuthService.TablePermission>> getPermissionsByTable(
        @RequestHeader("Authorization") String token,
        @PathVariable String tableName,
        @RequestParam(required = false) String databaseName,
        @RequestParam(required = false) Long datasourceId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        
        List<AuthService.TablePermission> permissions = authService.getPermissionsByTable(databaseName, tableName, datasourceId);
        return Result.success(permissions);
    }
    
    /**
     * 创建新用户(仅管理员)
     */
    @PostMapping("/user/create")
    public Result<Boolean> createUser(
        @RequestHeader("Authorization") String token,
        @RequestBody CreateUserRequest request
    ) {
        AuthService.UserInfo adminInfo = authService.validateToken(token);
        
        if (adminInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(adminInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        boolean success = authService.createUser(
            request.getUsername(),
            request.getPassword(),
            request.getRealName(),
            request.getRole()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("创建失败，用户名可能已存在");
        }
    }
    
    /**
     * 修改密码（管理员和普通用户都可使用）
     */
    @PostMapping("/password/change")
    public Result<Boolean> changePassword(
        @RequestHeader("Authorization") String token,
        @RequestBody ChangePasswordRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        boolean success = authService.changePassword(
            userInfo.getUserId(),
            request.getOldPassword(),
            request.getNewPassword()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("修改失败，请检查原密码是否正确");
        }
    }
    
    /**
     * 管理员重置用户密码
     */
    @PostMapping("/password/reset")
    public Result<Boolean> resetPassword(
        @RequestHeader("Authorization") String token,
        @RequestBody ResetPasswordRequest request
    ) {
        AuthService.UserInfo adminInfo = authService.validateToken(token);
        
        if (adminInfo == null) {
            return Result.error("未登录或Token无效");
        }
        
        if (!"admin".equals(adminInfo.getRole())) {
            return Result.error("权限不足，仅管理员可操作");
        }
        
        boolean success = authService.resetUserPassword(
            request.getUserId(),
            request.getNewPassword()
        );
        
        if (success) {
            return Result.success(true);
        } else {
            return Result.error("重置失败");
        }
    }
    
    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
    
    @Data
    public static class WhitelistRequest {
        private Long userId;
        private String reason;
        private java.time.LocalDateTime expiresAt;
    }
    
    @Data
    public static class TablePermissionRequest {
        private Long userId;
        private String databaseName;
        private String tableName;
        private Long datasourceId;
    }
    
    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String realName;
        private String role; // admin 或 user
    }
    
    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
    
    @Data
    public static class ResetPasswordRequest {
        private Long userId;
        private String newPassword;
    }

    // ==================== 行级权限策略管理 ====================

    /**
     * 查询所有行级策略(仅管理员)
     */
    @GetMapping("/row-level-policy/list")
    public Result<List<Map<String, Object>>> listRowLevelPolicies(
        @RequestHeader("Authorization") String token,
        @RequestParam(required = false) Long datasourceId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        List<Map<String, Object>> policies = rowLevelFilterService.listPolicies(datasourceId);
        return Result.success(policies);
    }

    /**
     * 查询行级策略详情(仅管理员)
     */
    @GetMapping("/row-level-policy/{id}")
    public Result<Map<String, Object>> getRowLevelPolicy(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }
        Map<String, Object> policy = rowLevelFilterService.getPolicyDetail(id);
        if (policy == null) {
            return Result.error("策略不存在");
        }
        return Result.success(policy);
    }

    /**
     * 创建行级策略(仅管理员)
     */
    @PostMapping("/row-level-policy/create")
    public Result<Long> createRowLevelPolicy(
        @RequestHeader("Authorization") String token,
        @RequestBody RowLevelPolicyRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }

        // 校验过滤类型
        if (!List.of("USER_ATTRIBUTE", "STATIC_VALUE", "EXPRESSION").contains(request.filterType)) {
            return Result.error("无效的过滤类型，支持: USER_ATTRIBUTE, STATIC_VALUE, EXPRESSION");
        }

        Long policyId = rowLevelFilterService.createPolicy(
            request.policyName, request.datasourceId, request.tableName,
            request.columnName, request.filterType, request.filterValue,
            request.priority, request.userIds, request.roleNames
        );

        return Result.success(policyId);
    }

    /**
     * 更新行级策略(仅管理员)
     */
    @PostMapping("/row-level-policy/update")
    public Result<Boolean> updateRowLevelPolicy(
        @RequestHeader("Authorization") String token,
        @RequestBody RowLevelPolicyUpdateRequest request
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }

        boolean success = rowLevelFilterService.updatePolicy(
            request.id, request.policyName, request.datasourceId, request.tableName,
            request.columnName, request.filterType, request.filterValue,
            request.priority, request.isActive, request.userIds, request.roleNames
        );

        if (success) {
            // 清除相关用户缓存
            rowLevelFilterService.clearUserPolicyCache(null);
        }

        return Result.success(success);
    }

    /**
     * 删除行级策略(仅管理员)
     */
    @PostMapping("/row-level-policy/delete/{id}")
    public Result<Boolean> deleteRowLevelPolicy(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }

        boolean success = rowLevelFilterService.deletePolicy(id);
        return Result.success(success);
    }

    /**
     * 停用行级策略(仅管理员)
     */
    @PostMapping("/row-level-policy/deactivate/{id}")
    public Result<Boolean> deactivateRowLevelPolicy(
        @RequestHeader("Authorization") String token,
        @PathVariable Long id
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }

        boolean success = rowLevelFilterService.deactivatePolicy(id);
        return Result.success(success);
    }

    /**
     * 查询用户在指定表上的行级策略(仅管理员)
     */
    @GetMapping("/row-level-policy/user/{userId}/table/{tableName}")
    public Result<List<Map<String, Object>>> getUserRowLevelPolicies(
        @RequestHeader("Authorization") String token,
        @PathVariable Long userId,
        @PathVariable String tableName,
        @RequestParam(required = false) Long datasourceId
    ) {
        AuthService.UserInfo userInfo = authService.validateToken(token);
        if (userInfo == null) {
            return Result.error("未登录或Token无效");
        }
        if (!"admin".equals(userInfo.getRole())) {
            return Result.error("权限不足");
        }

        List<Map<String, Object>> policies = rowLevelFilterService.getAppliedPolicies(userId, datasourceId, tableName);
        return Result.success(policies);
    }

    @Data
    public static class RowLevelPolicyRequest {
        private String policyName;
        private Long datasourceId;
        private String tableName;
        private String columnName;
        private String filterType;
        private String filterValue;
        private Integer priority;
        private List<Long> userIds;
        private List<String> roleNames;
    }

    @Data
    public static class RowLevelPolicyUpdateRequest {
        private Long id;
        private String policyName;
        private Long datasourceId;
        private String tableName;
        private String columnName;
        private String filterType;
        private String filterValue;
        private Integer priority;
        private Integer isActive;
        private List<Long> userIds;
        private List<String> roleNames;
    }
}
