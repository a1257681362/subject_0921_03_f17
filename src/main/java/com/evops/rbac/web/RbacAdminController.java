package com.evops.rbac.web;

import com.evops.common.ApiResponse;
import com.evops.auth.security.AuthContext;
import com.evops.auth.service.AuthService;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.rbac.dto.DenyLogVO;
import com.evops.rbac.dto.MenuNodeVO;
import com.evops.rbac.dto.MenuUpsertRequest;
import com.evops.rbac.dto.RoleMenuGrantRequest;
import com.evops.rbac.dto.RoleCreateRequest;
import com.evops.rbac.dto.RoleUpdateRequest;
import com.evops.rbac.dto.RoleVO;
import com.evops.rbac.dto.UserRolesUpdateRequest;
import com.evops.rbac.dto.UserWithRolesVO;
import com.evops.rbac.entity.RbacMenu;
import com.evops.rbac.service.MenuTreeAssembler;
import com.evops.rbac.service.RbacAdminService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RBAC 管理端（前缀 /api/admin/rbac，需访问令牌 + rbac:manage；种子仅 ADMIN 持有）。
 *
 * <ul>
 *   <li>角色：列表、新建、改名备注、启停（停用即时收权）、删除（内置/有人绑定禁删）；</li>
 *   <li>用户多岗：分页、查看岗位、覆盖式分配（空数组=下岗）；</li>
 *   <li>菜单树：全量维护树、节点 CRUD（父/引用校验、权限码唯一）；</li>
 *   <li>授权：查看/覆盖式重写角色的菜单与权限点；</li>
 *   <li>越权记录：分页只读查询。</li>
 * </ul>
 * 非 ADMIN 调用统一 403 并落 t_rbac_deny_log。
 */
@RestController
@RequestMapping("/api/admin/rbac")
@RequirePermission("rbac:manage")
public class RbacAdminController {

    private final RbacAdminService adminService;
    private final MenuTreeAssembler menuTreeAssembler;
    private final AuthService authService;

    public RbacAdminController(RbacAdminService adminService, MenuTreeAssembler menuTreeAssembler,
                               AuthService authService) {
        this.adminService = adminService;
        this.menuTreeAssembler = menuTreeAssembler;
        this.authService = authService;
    }

    // ==================== 角色 ====================

    @GetMapping("/roles")
    public ApiResponse<List<RoleVO>> listRoles(@RequestParam(required = false) String status) {
        return ApiResponse.ok(adminService.listRoles(status));
    }

    @PostMapping("/roles")
    public ApiResponse<RoleVO> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok(adminService.createRole(request));
    }

    @PutMapping("/roles/{id}")
    public ApiResponse<RoleVO> updateRole(@PathVariable Long id,
                                          @Valid @RequestBody RoleUpdateRequest request) {
        return ApiResponse.ok(adminService.updateRole(id, request));
    }

    @PostMapping("/roles/{id}/disable")
    public ApiResponse<Map<String, Object>> disableRole(@PathVariable Long id) {
        adminService.disableRole(id);
        return ApiResponse.ok(Collections.singletonMap("disabled", true));
    }

    @PostMapping("/roles/{id}/enable")
    public ApiResponse<Map<String, Object>> enableRole(@PathVariable Long id) {
        adminService.enableRole(id);
        return ApiResponse.ok(Collections.singletonMap("enabled", true));
    }

    @DeleteMapping("/roles/{id}")
    public ApiResponse<Map<String, Object>> deleteRole(@PathVariable Long id) {
        adminService.deleteRole(id);
        return ApiResponse.ok(Collections.singletonMap("deleted", true));
    }

    // ==================== 用户多岗 ====================

    @GetMapping("/users")
    public ApiResponse<PageVO<UserWithRolesVO>> pageUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(adminService.pageUsers(page, size, keyword));
    }

    @GetMapping("/users/{id}/roles")
    public ApiResponse<UserWithRolesVO> getUserRoles(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getUserWithRoles(id));
    }

    /** 覆盖式分配岗位；空 roleIds = 一键下岗，下一请求即收权。 */
    @PutMapping("/users/{id}/roles")
    public ApiResponse<UserWithRolesVO> assignUserRoles(@PathVariable Long id,
                                                        @Valid @RequestBody UserRolesUpdateRequest request) {
        return ApiResponse.ok(adminService.assignUserRoles(id, request.getRoleIds()));
    }

    /**
     * 停用账号：旧访问令牌下一请求即 401（status 拦截），全部刷新令牌立即撤销，
     * 且推进 token_version——即使日后启用，停用前的旧令牌也不复活。
     */
    @PostMapping("/users/{id}/disable")
    public ApiResponse<Map<String, Object>> disableUser(@PathVariable Long id) {
        if (id != null && id == AuthContext.requireUserId()) {
            throw new IllegalArgumentException("不能停用当前登录账号（防止自锁）");
        }
        authService.deactivateAccount(id);
        return ApiResponse.ok(Collections.singletonMap("disabled", true));
    }

    /** 重新启用账号：恢复可登录，但停用前的旧令牌仍作废，须用密码重新登录。 */
    @PostMapping("/users/{id}/enable")
    public ApiResponse<Map<String, Object>> enableUser(@PathVariable Long id) {
        authService.activateAccount(id);
        return ApiResponse.ok(Collections.singletonMap("enabled", true));
    }

    // ==================== 菜单树 ====================

    /** 全量维护树（含按钮/不可见/停用节点）。 */
    @GetMapping("/menus/tree")
    public ApiResponse<List<MenuNodeVO>> menuTree() {
        return ApiResponse.ok(menuTreeAssembler.assembleAllForAdmin());
    }

    @PostMapping("/menus")
    public ApiResponse<RbacMenu> createMenu(@Valid @RequestBody MenuUpsertRequest request) {
        return ApiResponse.ok(adminService.createMenu(request));
    }

    @PutMapping("/menus/{id}")
    public ApiResponse<RbacMenu> updateMenu(@PathVariable Long id,
                                            @Valid @RequestBody MenuUpsertRequest request) {
        return ApiResponse.ok(adminService.updateMenu(id, request));
    }

    @DeleteMapping("/menus/{id}")
    public ApiResponse<Map<String, Object>> deleteMenu(@PathVariable Long id) {
        adminService.deleteMenu(id);
        return ApiResponse.ok(Collections.singletonMap("deleted", true));
    }

    // ==================== 角色授权 ====================

    @GetMapping("/roles/{id}/menus")
    public ApiResponse<List<Long>> getRoleMenus(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getRoleMenuIds(id));
    }

    /** 覆盖式授权：提交后角色菜单/权限点 = menuIds 全集。 */
    @PutMapping("/roles/{id}/menus")
    public ApiResponse<List<Long>> grantRoleMenus(@PathVariable Long id,
                                                  @Valid @RequestBody RoleMenuGrantRequest request) {
        return ApiResponse.ok(adminService.grantRoleMenus(id, request.getMenuIds()));
    }

    // ==================== 越权记录 ====================

    @GetMapping("/deny-logs")
    public ApiResponse<PageVO<DenyLogVO>> pageDenyLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(adminService.pageDenyLogs(page, size, username, path, from, to));
    }
}
