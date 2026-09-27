package com.evops.ticket.mask;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 某一版脱敏规则的不可变快照：版本号、字段→打码类型映射、可见明文的白名单角色。
 * 序列化期间按这一份快照判定，规则即使在请求处理中发布了新版本，本请求口径也保持一致。
 */
public final class EffectiveRule {

    /** 取不到任何规则时的兜底：白名单为空（全员打码），字段映射走注解默认——安全失败关闭。 */
    public static final EffectiveRule FAIL_CLOSED =
            new EffectiveRule(0, Collections.emptyMap(), Collections.emptySet());

    private final int ruleVersion;
    private final Map<String, MaskType> fieldRules;
    private final Set<String> whitelistRoles;

    public EffectiveRule(int ruleVersion, Map<String, MaskType> fieldRules, Set<String> whitelistRoles) {
        this.ruleVersion = ruleVersion;
        this.fieldRules = fieldRules;
        this.whitelistRoles = whitelistRoles;
    }

    public int getRuleVersion() {
        return ruleVersion;
    }

    /** 字段在规则版本里配置的打码类型；未收录返回 null（调用方回退注解默认）。 */
    public MaskType fieldType(String fieldName) {
        return fieldRules.get(fieldName);
    }

    /** 规则收录的全部敏感字段名（Map 回放路径据此识别敏感键）。 */
    public Set<String> fieldNames() {
        return fieldRules.keySet();
    }

    /** 白名单角色可见完整值；无登录态/角色未知一律不放行。 */
    public boolean allowsPlain(String roleCode) {
        return roleCode != null && whitelistRoles.contains(roleCode);
    }

    /**
     * 一人多岗：当前启用岗位并集与白名单有交集即放行（并集语义，不是交集收紧）。
     *
     * @return 实际命中白名单的角色码（用于留痕「凭哪一岗看明文」）；无命中返回 null。
     *         多个命中时优先返回业务岗（排除 ADMIN 后按字典序取首），只剩 ADMIN 才记 ADMIN。
     */
    public String firstAllowedRole(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return null;
        }
        java.util.TreeSet<String> matched = new java.util.TreeSet<>();
        for (String code : roleCodes) {
            if (code != null && whitelistRoles.contains(code)) {
                matched.add(code);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            matched.remove("ADMIN");
        }
        return matched.first();
    }
}
