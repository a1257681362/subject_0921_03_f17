package com.evops.rbac.service;

import com.evops.rbac.entity.RbacMenu;
import com.evops.rbac.mapper.RbacMenuMapper;
import com.evops.rbac.mapper.RbacRoleMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * RBAC 只读查询：每请求实时装载「启用角色集 + 权限码并集 + 菜单并集」。
 *
 * <p>刻意不做缓存：角色停用、菜单停用、岗位/授权调整必须在下一次请求立即生效
 * （旧 JWT 不重签、用户不重登）。数据量仅角色/菜单/授权三张小表，单次请求两条 SQL。
 */
@Service
public class RbacQueryService {

    /** 代码层超管角色：命中即放行一切接口（菜单树另授全部节点保证完整）。 */
    public static final String ROLE_ADMIN = "ADMIN";

    private final RbacRoleMapper roleMapper;
    private final RbacMenuMapper menuMapper;

    public RbacQueryService(RbacRoleMapper roleMapper, RbacMenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
    }

    /** 当前启用角色码 + 权限码并集（按库内最新状态）。 */
    public RbacSnapshot loadSnapshot(long userId) {
        Set<String> roles = new TreeSet<>(roleMapper.selectActiveRoleCodes(userId));
        if (roles.isEmpty()) {
            return RbacSnapshot.EMPTY;
        }
        Set<String> permissions = new HashSet<>();
        for (RbacMenu menu : loadGrantedMenus(userId, roles)) {
            if (menu.getPermission() != null && !menu.getPermission().trim().isEmpty()) {
                permissions.add(menu.getPermission().trim());
            }
        }
        return new RbacSnapshot(roles, permissions);
    }

    /**
     * 用户并集内的启用菜单行（已去重）。ADMIN 直接取全部启用菜单，
     * 避免漏配某节点把管理员也挡在外面。
     */
    public List<RbacMenu> loadGrantedMenus(long userId, Set<String> activeRoles) {
        if (activeRoles.contains(ROLE_ADMIN)) {
            return menuMapper.selectAllEnabled();
        }
        return menuMapper.selectMenusForUser(userId);
    }
}
