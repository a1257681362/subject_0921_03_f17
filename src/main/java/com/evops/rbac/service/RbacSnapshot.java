package com.evops.rbac.service;

import java.util.Collections;
import java.util.Set;

/**
 * 某用户在本次请求时刻的授权快照：启用角色 code 集合 + 这些角色并集出的权限码集合。
 * 每次请求实时从库加载（不缓存），因此停用角色/解绑授权下一请求立即收缩。
 */
public final class RbacSnapshot {

    public static final RbacSnapshot EMPTY =
            new RbacSnapshot(Collections.emptySet(), Collections.emptySet());

    private final Set<String> roles;
    private final Set<String> permissions;

    public RbacSnapshot(Set<String> roles, Set<String> permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public boolean hasRole(String code) {
        return roles.contains(code);
    }

    /** 权限并集命中任一即放行（多岗取并集，不是交集）。 */
    public boolean hasAnyPermission(String[] required) {
        if (required == null || required.length == 0) {
            return true;
        }
        for (String p : required) {
            if (permissions.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
