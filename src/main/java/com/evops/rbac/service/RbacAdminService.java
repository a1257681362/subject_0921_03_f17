package com.evops.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.auth.entity.AdminUser;
import com.evops.auth.mapper.AdminUserMapper;
import com.evops.common.web.PageVO;
import com.evops.rbac.dto.DenyLogVO;
import com.evops.rbac.dto.MenuUpsertRequest;
import com.evops.rbac.dto.RoleCreateRequest;
import com.evops.rbac.dto.RoleUpdateRequest;
import com.evops.rbac.dto.RoleVO;
import com.evops.rbac.dto.UserWithRolesVO;
import com.evops.rbac.entity.AdminUserRole;
import com.evops.rbac.entity.RbacDenyLog;
import com.evops.rbac.entity.RbacMenu;
import com.evops.rbac.entity.RbacRole;
import com.evops.rbac.entity.RbacRoleMenu;
import com.evops.rbac.mapper.AdminUserRoleMapper;
import com.evops.rbac.mapper.RbacDenyLogMapper;
import com.evops.rbac.mapper.RbacMenuMapper;
import com.evops.rbac.mapper.RbacRoleMapper;
import com.evops.rbac.mapper.RbacRoleMenuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 管理端写操作：角色维护、用户多岗分配（覆盖式）、菜单树维护、角色授权（覆盖式）、
 * 越权记录只读查询。所有变更下一请求即生效（授权不缓存）。
 *
 * <p>校验失败统一抛 {@link IllegalArgumentException}（全局处理器回 200+success=false）；
 * 重复键由调用方预判，不依赖数据库报错文案。
 */
@Service
public class RbacAdminService {

    private final RbacRoleMapper roleMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final RbacMenuMapper menuMapper;
    private final RbacRoleMenuMapper roleMenuMapper;
    private final RbacDenyLogMapper denyLogMapper;
    private final AdminUserMapper adminUserMapper;

    public RbacAdminService(RbacRoleMapper roleMapper, AdminUserRoleMapper userRoleMapper,
                            RbacMenuMapper menuMapper, RbacRoleMenuMapper roleMenuMapper,
                            RbacDenyLogMapper denyLogMapper, AdminUserMapper adminUserMapper) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.denyLogMapper = denyLogMapper;
        this.adminUserMapper = adminUserMapper;
    }

    // ==================== 角色 ====================

    public List<RoleVO> listRoles(String status) {
        LambdaQueryWrapper<RbacRole> w = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            w.eq(RbacRole::getStatus, status.trim());
        }
        w.orderByAsc(RbacRole::getId);
        List<RoleVO> result = new ArrayList<>();
        for (RbacRole role : roleMapper.selectList(w)) {
            result.add(toRoleVO(role));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleCreateRequest request) {
        String code = request.getCode().trim();
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<RbacRole>()
                .eq(RbacRole::getCode, code));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("角色码已存在：" + code);
        }
        RbacRole role = new RbacRole();
        role.setCode(code);
        role.setName(request.getName().trim());
        role.setRemark(trimToNull(request.getRemark()));
        role.setStatus("ACTIVE");
        role.setBuiltin(0);
        role.setCreateTime(LocalDateTime.now());
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.insert(role);
        return toRoleVO(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateRole(long roleId, RoleUpdateRequest request) {
        RbacRole role = requireRole(roleId);
        role.setName(request.getName().trim());
        role.setRemark(trimToNull(request.getRemark()));
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
        return toRoleVO(role);
    }

    /** 停用角色：所有绑定者下一请求即时失去该岗权限（不影响其其它岗位）。 */
    @Transactional(rollbackFor = Exception.class)
    public void disableRole(long roleId) {
        RbacRole role = requireRole(roleId);
        if (isBuiltin(role) && RbacQueryService.ROLE_ADMIN.equals(role.getCode())) {
            throw new IllegalArgumentException("内置管理员角色不可停用（防止自锁）");
        }
        role.setStatus("DISABLED");
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void enableRole(long roleId) {
        RbacRole role = requireRole(roleId);
        role.setStatus("ACTIVE");
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(long roleId) {
        RbacRole role = requireRole(roleId);
        if (isBuiltin(role)) {
            throw new IllegalArgumentException("内置角色不可删除：" + role.getCode());
        }
        Long users = userRoleMapper.selectCount(new LambdaQueryWrapper<AdminUserRole>()
                .eq(AdminUserRole::getRoleId, roleId));
        if (users != null && users > 0) {
            throw new IllegalArgumentException("仍有 " + users + " 个用户绑定该角色，请先解绑再删除");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RbacRoleMenu>()
                .eq(RbacRoleMenu::getRoleId, roleId));
        roleMapper.deleteById(roleId);
    }

    // ==================== 用户多岗 ====================

    public PageVO<UserWithRolesVO> pageUsers(long page, long size, String keyword) {
        LambdaQueryWrapper<AdminUser> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            w.like(AdminUser::getUsername, kw).or().like(AdminUser::getDisplayName, kw);
        }
        w.orderByAsc(AdminUser::getId);
        Page<AdminUser> p = adminUserMapper.selectPage(new Page<>(page, size), w);
        List<UserWithRolesVO> list = p.getRecords().stream()
                .map(this::toUserWithRolesVO)
                .collect(Collectors.toList());
        return new PageVO<>(p.getCurrent(), p.getSize(), p.getTotal(), list);
    }

    public UserWithRolesVO getUserWithRoles(long userId) {
        return toUserWithRolesVO(requireAdminUser(userId));
    }

    /**
     * 覆盖式分配岗位：分配后用户岗位 = roleIds 全集（空数组=下岗）。
     * 同步把 t_admin_user.role 置为首个启用岗位 code（无岗位回退 STAFF），保持主岗位列有意义。
     * 下一请求即按新并集鉴权与装配菜单。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserWithRolesVO assignUserRoles(long userId, List<Long> roleIds) {
        AdminUser user = requireAdminUser(userId);
        Set<Long> distinctIds = new LinkedHashSet<>();
        if (roleIds != null) {
            distinctIds.addAll(roleIds);
        }

        List<RbacRole> roles = new ArrayList<>();
        for (Long rid : distinctIds) {
            roles.add(requireRole(rid));
        }

        userRoleMapper.delete(new LambdaQueryWrapper<AdminUserRole>()
                .eq(AdminUserRole::getUserId, userId));
        for (Long rid : distinctIds) {
            AdminUserRole link = new AdminUserRole();
            link.setUserId(userId);
            link.setRoleId(rid);
            userRoleMapper.insert(link);
        }

        String primary = roles.stream()
                .filter(r -> "ACTIVE".equals(r.getStatus()))
                .map(RbacRole::getCode)
                .findFirst()
                .orElse("STAFF");
        user.setRole(primary);
        user.setUpdateTime(LocalDateTime.now());
        adminUserMapper.updateById(user);

        return toUserWithRolesVO(requireAdminUser(userId));
    }

    // ==================== 角色授权（菜单/权限点） ====================

    public List<Long> getRoleMenuIds(long roleId) {
        requireRole(roleId);
        return roleMenuMapper.selectList(new LambdaQueryWrapper<RbacRoleMenu>()
                        .eq(RbacRoleMenu::getRoleId, roleId))
                .stream().map(RbacRoleMenu::getMenuId).collect(Collectors.toList());
    }

    /** 覆盖式授权：单事务删除旧关联再插入（去重、校验菜单存在）。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> grantRoleMenus(long roleId, List<Long> menuIds) {
        requireRole(roleId);
        Set<Long> distinct = new LinkedHashSet<>();
        if (menuIds != null) {
            distinct.addAll(menuIds);
        }
        for (Long mid : distinct) {
            if (menuMapper.selectById(mid) == null) {
                throw new IllegalArgumentException("菜单/权限点不存在：id=" + mid);
            }
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RbacRoleMenu>()
                .eq(RbacRoleMenu::getRoleId, roleId));
        for (Long mid : distinct) {
            RbacRoleMenu link = new RbacRoleMenu();
            link.setRoleId(roleId);
            link.setMenuId(mid);
            roleMenuMapper.insert(link);
        }
        return new ArrayList<>(distinct);
    }

    // ==================== 菜单树维护 ====================

    @Transactional(rollbackFor = Exception.class)
    public RbacMenu createMenu(MenuUpsertRequest request) {
        RbacMenu menu = new RbacMenu();
        applyMenuFields(menu, request);
        menu.setStatus(request.getStatus() == null ? "ENABLED" : request.getStatus());
        validateMenu(menu, null);
        menu.setCreateTime(LocalDateTime.now());
        menu.setUpdateTime(LocalDateTime.now());
        menuMapper.insert(menu);
        return menu;
    }

    @Transactional(rollbackFor = Exception.class)
    public RbacMenu updateMenu(long menuId, MenuUpsertRequest request) {
        RbacMenu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw new IllegalArgumentException("菜单/权限点不存在：id=" + menuId);
        }
        applyMenuFields(menu, request);
        if (request.getStatus() != null) {
            menu.setStatus(request.getStatus());
        }
        validateMenu(menu, menuId);
        menu.setUpdateTime(LocalDateTime.now());
        menuMapper.updateById(menu);
        return menu;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(long menuId) {
        if (menuMapper.selectById(menuId) == null) {
            throw new IllegalArgumentException("菜单/权限点不存在：id=" + menuId);
        }
        Long children = menuMapper.selectCount(new LambdaQueryWrapper<RbacMenu>()
                .eq(RbacMenu::getParentId, menuId));
        if (children != null && children > 0) {
            throw new IllegalArgumentException("存在子节点，请先删除子节点");
        }
        Long refs = roleMenuMapper.selectCount(new LambdaQueryWrapper<RbacRoleMenu>()
                .eq(RbacRoleMenu::getMenuId, menuId));
        if (refs != null && refs > 0) {
            throw new IllegalArgumentException("该节点已授权给角色，请先在角色授权中取消勾选再删除");
        }
        menuMapper.deleteById(menuId);
    }

    // ==================== 越权记录（只读） ====================

    public PageVO<DenyLogVO> pageDenyLogs(long page, long size, String username, String path,
                                          LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<RbacDenyLog> w = new LambdaQueryWrapper<>();
        if (username != null && !username.trim().isEmpty()) {
            w.eq(RbacDenyLog::getUsername, username.trim());
        }
        if (path != null && !path.trim().isEmpty()) {
            w.like(RbacDenyLog::getRequestPath, path.trim());
        }
        if (from != null) {
            w.ge(RbacDenyLog::getDenyTime, from);
        }
        if (to != null) {
            w.le(RbacDenyLog::getDenyTime, to);
        }
        w.orderByDesc(RbacDenyLog::getId);
        Page<RbacDenyLog> p = denyLogMapper.selectPage(new Page<>(page, size), w);
        List<DenyLogVO> list = p.getRecords().stream().map(this::toDenyLogVO).collect(Collectors.toList());
        return new PageVO<>(p.getCurrent(), p.getSize(), p.getTotal(), list);
    }

    // ==================== 内部 ====================

    private void applyMenuFields(RbacMenu menu, MenuUpsertRequest request) {
        menu.setParentId(request.getParentId());
        menu.setType(request.getType().trim());
        menu.setName(request.getName().trim());
        menu.setPermission(trimToNull(request.getPermission()));
        menu.setPath(trimToNull(request.getPath()));
        menu.setIcon(trimToNull(request.getIcon()));
        menu.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        menu.setVisible(request.getVisible() == null ? 1 : request.getVisible());
    }

    private void validateMenu(RbacMenu menu, Long excludeId) {
        if (!"DIR".equals(menu.getType()) && !"MENU".equals(menu.getType())
                && !"BUTTON".equals(menu.getType())) {
            throw new IllegalArgumentException("节点类型仅支持 DIR/MENU/BUTTON");
        }
        if (menu.getVisible() == null || (menu.getVisible() != 0 && menu.getVisible() != 1)) {
            throw new IllegalArgumentException("visible 仅支持 0/1");
        }
        if (menu.getStatus() != null && !"ENABLED".equals(menu.getStatus())
                && !"DISABLED".equals(menu.getStatus())) {
            throw new IllegalArgumentException("status 仅支持 ENABLED/DISABLED");
        }
        if (menu.getParentId() != 0L) {
            RbacMenu parent = menuMapper.selectById(menu.getParentId());
            if (parent == null) {
                throw new IllegalArgumentException("父节点不存在：id=" + menu.getParentId());
            }
            if ("BUTTON".equals(parent.getType())) {
                throw new IllegalArgumentException("按钮节点下不能再挂子节点");
            }
        }
        if (menu.getPermission() != null) {
            LambdaQueryWrapper<RbacMenu> w = new LambdaQueryWrapper<RbacMenu>()
                    .eq(RbacMenu::getPermission, menu.getPermission());
            if (excludeId != null) {
                w.ne(RbacMenu::getId, excludeId);
            }
            Long dup = menuMapper.selectCount(w);
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("权限码已存在：" + menu.getPermission());
            }
        }
    }

    private RbacRole requireRole(long roleId) {
        RbacRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在：id=" + roleId);
        }
        return role;
    }

    private AdminUser requireAdminUser(long userId) {
        AdminUser user = adminUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("账号不存在：id=" + userId);
        }
        return user;
    }

    private boolean isBuiltin(RbacRole role) {
        return role.getBuiltin() != null && role.getBuiltin() == 1;
    }

    private RoleVO toRoleVO(RbacRole role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setCode(role.getCode());
        vo.setName(role.getName());
        vo.setStatus(role.getStatus());
        vo.setBuiltin(role.getBuiltin());
        vo.setRemark(role.getRemark());
        vo.setCreateTime(role.getCreateTime());
        vo.setUserCount(userRoleMapper.selectCount(new LambdaQueryWrapper<AdminUserRole>()
                .eq(AdminUserRole::getRoleId, role.getId())));
        return vo;
    }

    private UserWithRolesVO toUserWithRolesVO(AdminUser user) {
        UserWithRolesVO vo = new UserWithRolesVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setVenue(user.getVenue());
        vo.setPrimaryRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setRoles(roleMapper.selectRolesByUserId(user.getId()).stream()
                .map(this::toRoleVO).collect(Collectors.toList()));
        return vo;
    }

    private DenyLogVO toDenyLogVO(RbacDenyLog row) {
        DenyLogVO vo = new DenyLogVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setUsername(row.getUsername());
        vo.setRolesCsv(row.getRolesCsv());
        vo.setRequestMethod(row.getRequestMethod());
        vo.setRequestPath(row.getRequestPath());
        vo.setRequiredPermission(row.getRequiredPermission());
        vo.setClientIp(row.getClientIp());
        vo.setRequestId(row.getRequestId());
        vo.setDenyTime(row.getDenyTime());
        return vo;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
