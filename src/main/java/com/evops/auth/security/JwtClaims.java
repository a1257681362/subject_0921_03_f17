package com.evops.auth.security;

import java.util.Collections;
import java.util.Set;

/**
 * 访问令牌校验后的声明快照。
 */
public class JwtClaims {
    private long userId;
    private String username;
    /** 签发时的密码（令牌）版本；与库内不一致即判失效 */
    private int tokenVersion;
    /** 令牌唯一标识，登出/改密按此拉黑 */
    private String jti;
    /** 主岗位：校验时按库内最新值回填（调整无需重新签发即生效），用于脱敏白名单主角色判定 */
    private String role;
    /**
     * 当前启用岗位 code 并集。<b>不是 JWT 内容</b>：每次 authenticate 按库内最新状态回填，
     * 停用角色/调整岗位下一请求立即收缩，无需重签令牌。
     */
    private Set<String> roles = Collections.emptySet();
    /** 多岗位并集出的权限码集合，同样为每请求实时回填的瞬态值。 */
    private Set<String> permissions = Collections.emptySet();
    private long exp;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? Collections.emptySet() : roles;
    }
    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions == null ? Collections.emptySet() : permissions;
    }
    public long getExp() { return exp; }
    public void setExp(long exp) { this.exp = exp; }
}
