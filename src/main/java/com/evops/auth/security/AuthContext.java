package com.evops.auth.security;

import java.util.Collections;
import java.util.Set;

/**
 * 已认证访问令牌对应的当前登录态（请求级 ThreadLocal）。
 *
 * <p>一人多岗：{@link #roles} 为当前启用角色 code 集合，{@link #permissions} 为这些角色
 * 并集出的权限码集合，均在每次请求令牌校验时按库内最新状态回填——停用角色/调整授权
 * 下一请求即时生效，无需重签令牌。{@link #role} 保留为主岗位（兼容脱敏等单角色口径）。
 */
public final class AuthContext {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static long requireUserId() {
        CurrentUser u = HOLDER.get();
        if (u == null) {
            throw new IllegalStateException("当前请求无登录态");
        }
        return u.getUserId();
    }

    public static class CurrentUser {
        private final long userId;
        private final String username;
        private final String role;
        private final Set<String> roles;
        private final Set<String> permissions;
        private final String jti;
        private final long accessExpEpochSecond;

        public CurrentUser(long userId, String username, String role, Set<String> roles,
                           Set<String> permissions, String jti, long accessExpEpochSecond) {
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
            this.permissions = permissions == null
                    ? Collections.emptySet() : Collections.unmodifiableSet(permissions);
            this.jti = jti;
            this.accessExpEpochSecond = accessExpEpochSecond;
        }

        public long getUserId() { return userId; }
        public String getUsername() { return username; }
        /** 主岗位（t_admin_user.role 单值，兼容脱敏白名单/留痕等历史口径）。 */
        public String getRole() { return role; }
        /** 当前启用角色 code 并集（多岗）。 */
        public Set<String> getRoles() { return roles; }
        /** 多角色并集出的权限码集合。 */
        public Set<String> getPermissions() { return permissions; }
        public String getJti() { return jti; }
        public long getAccessExpEpochSecond() { return accessExpEpochSecond; }

        public boolean hasRole(String code) {
            return roles.contains(code);
        }

        /** 权限并集命中任一即有权（多岗取并集，不是交集）。 */
        public boolean hasAnyPermission(String... required) {
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
}
